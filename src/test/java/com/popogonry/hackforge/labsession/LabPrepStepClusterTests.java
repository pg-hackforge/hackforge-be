package com.popogonry.hackforge.labsession;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.popogonry.hackforge.TestcontainersConfiguration;
import com.popogonry.hackforge.challenge.TestChallenges;
import com.popogonry.hackforge.labsession.cluster.LabCluster;
import com.popogonry.hackforge.labsession.domain.LabPrepStep;
import com.popogonry.hackforge.labsession.service.LabReconciler;

/**
 * ADR 021. 개발 클러스터의 실제 Pod로 reconciler를 돌려 준비 단계가 바뀌는 순서를 본다. {@code ./gradlew clusterTest}로만 실행한다.
 * 필요한 것은 {@code KubernetesLabClusterTests}와 같다. 예약 실행과 고아 namespace 삭제는 끄고 reconciler를 1초마다 직접 부른다.
 */
@Tag("cluster")
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "hackforge.lab.reconciler.enabled=false")
class LabPrepStepClusterTests {

	private static final String TEST_IMAGE = "hackforge/lab-test-challenge:0.1.0";

	/** 개발 DB 세션 id, KubernetesLabClusterTests와 겹치지 않게 큰 값부터 */
	private static final long FIRST_ID = 900_000_101L;

	@Autowired
	LabReconciler labReconciler;

	@Autowired
	LabCluster labCluster;

	@Autowired
	JdbcTemplate jdbcTemplate;

	long id;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("delete from lab_sessions");
		jdbcTemplate.update("delete from challenges");
		jdbcTemplate.update("delete from users");
		jdbcTemplate.execute("alter table lab_sessions alter column id restart with " + FIRST_ID);
		long userId = jdbcTemplate.queryForObject("""
				insert into users (email, password_hash, name, nickname, affiliation, role, status, privacy_agreed_at, created_at)
				values ('kim@example.com', 'x', '김', 'kim', '학교', 'LEARNER', 'APPROVED', now(), now()) returning id
				""", Long.class);
		long challengeId = TestChallenges.insert(jdbcTemplate, "test-web", "테스트", TEST_IMAGE);
		// 시작 API가 넣는 것과 같은 행
		id = jdbcTemplate.queryForObject("""
				insert into lab_sessions (user_id, challenge_id, image, status, prep_step, status_changed_at, created_at)
				values (?, ?, ?, 'CREATING', 'SPACE', ?, ?) returning id
				""", Long.class, userId, challengeId, TEST_IMAGE, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
	}

	@AfterEach
	void tearDown() throws InterruptedException {
		labCluster.deleteNamespace(id);
		for (int i = 0; i < 120 && labCluster.namespaceExists(id); i++) {
			Thread.sleep(1000);
		}
	}

	@Test
	void prepStepAdvancesInOrderOnRealPodAndClearsWhenReady() throws InterruptedException {
		Instant start = Instant.now();
		List<String> seen = new ArrayList<>();
		List<String> timeline = new ArrayList<>();
		String status = "CREATING";
		while (!status.equals("READY") && Duration.between(start, Instant.now()).compareTo(Duration.ofMinutes(3)) < 0) {
			Map<String, Object> row = jdbcTemplate.queryForMap("select status, prep_step from lab_sessions where id = ?", id);
			status = (String) row.get("status");
			String step = (String) row.get("prep_step");
			if (seen.isEmpty() || !Objects.equals(seen.getLast(), step)) {
				seen.add(step);
				timeline.add("%ds %s %s".formatted(Duration.between(start, Instant.now()).toSeconds(), status, step));
			}
			labReconciler.reconcileAll();
			Thread.sleep(1000);
		}
		System.out.println("준비 단계: " + String.join(" → ", timeline));

		assertThat(status).as("3분 안에 READY").isEqualTo("READY");
		assertThat(seen.getFirst()).isEqualTo("SPACE");
		assertThat(seen.getLast()).isNull();
		List<String> steps = seen.subList(0, seen.size() - 1);
		// 1초 간격이라 짧은 단계(LOADING)는 지나칠 수 있다. 본 단계는 진행 순서대로이고 되돌아가지 않는다
		assertThat(steps).contains("CONTAINER", "STARTING");
		assertThat(steps).isSortedAccordingTo((a, b) -> LabPrepStep.valueOf(a).compareTo(LabPrepStep.valueOf(b)));
		assertThat(steps).doesNotHaveDuplicates();
		assertThat(Arrays.stream(LabPrepStep.values()).map(Enum::name).toList()).containsAll(steps);
	}

}
