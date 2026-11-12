package com.popogonry.hackforge.labsession.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;

import com.popogonry.hackforge.TestcontainersConfiguration;
import com.popogonry.hackforge.labsession.cluster.FakeLabCluster;
import com.popogonry.hackforge.labsession.cluster.LabCluster;
import com.popogonry.hackforge.labsession.cluster.LabPodStatus;

/** reconciler 상태 전이(ADR 012). 클러스터는 {@link FakeLabCluster}, 시간은 {@link MutableClock}으로 바꾼다. start-timeout 5m, idle-timeout 30m */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class LabReconcilerTests {

	private static final Instant T0 = Instant.parse("2026-11-12T00:00:00Z");

	@TestBean
	LabCluster labCluster;

	@TestBean
	Clock clock;

	static LabCluster labCluster() {
		return new FakeLabCluster();
	}

	static Clock clock() {
		return new MutableClock();
	}

	@Autowired
	LabReconciler labReconciler;

	@Autowired
	JdbcTemplate jdbcTemplate;

	FakeLabCluster cluster;

	MutableClock now;

	long userId;

	long challengeId;

	@BeforeEach
	void setUp() {
		cluster = (FakeLabCluster) labCluster;
		cluster.reset();
		now = (MutableClock) clock;
		now.set(T0);
		jdbcTemplate.update("delete from lab_sessions");
		jdbcTemplate.update("delete from challenges");
		jdbcTemplate.update("delete from spring_session");
		jdbcTemplate.update("delete from users");
		challengeId = jdbcTemplate.queryForObject("""
				insert into challenges (slug, title, image) values ('test-web', '테스트', 'test-web:0.1.0') returning id
				""", Long.class);
		userId = newUser("kim");
	}

	long newUser(String nickname) {
		return jdbcTemplate.queryForObject("""
				insert into users (email, password_hash, name, nickname, affiliation, role, status, privacy_agreed_at, created_at)
				values (?, 'x', '김', ?, '학교', 'LEARNER', 'APPROVED', now(), now()) returning id
				""", Long.class, nickname + "@example.com", nickname);
	}

	/** 시작 API가 넣는 것과 같은 CREATING 세션 */
	long newSession(long user) {
		return jdbcTemplate.queryForObject("""
				insert into lab_sessions (user_id, challenge_id, image, status, status_changed_at, created_at)
				values (?, ?, 'test-web:0.1.0', 'CREATING', ?, ?) returning id
				""", Long.class, user, challengeId, Timestamp.from(now.instant()), Timestamp.from(now.instant()));
	}

	Map<String, Object> row(long id) {
		return jdbcTemplate.queryForMap("select * from lab_sessions where id = ?", id);
	}

	String statusOf(long id) {
		return (String) row(id).get("status");
	}

	int attemptsOf(long id) {
		return ((Number) row(id).get("start_attempts")).intValue();
	}

	/** 다른 요청이 세션을 바꾼 것처럼 JPA와 같은 방식으로 version을 올린다 */
	void changeFromOutside(long id, String status, String endReason) {
		jdbcTemplate.update("update lab_sessions set status = ?, end_reason = ?, version = version + 1 where id = ?",
				status, endReason, id);
	}

	@Test
	void creatingCreatesNamespaceAndPodWithSessionImage() {
		long id = newSession(userId);

		labReconciler.reconcileAll();

		assertThat(statusOf(id)).isEqualTo("INITIALIZING");
		assertThat(cluster.namespaceExists(id)).isTrue();
		assertThat(cluster.getPodStatus(id)).isEqualTo(LabPodStatus.STARTING);
		assertThat(cluster.podImage(id)).isEqualTo("test-web:0.1.0");
	}

	@Test
	void readyPodMakesSessionReadyAndStartsIdleClock() {
		long id = newSession(userId);
		labReconciler.reconcileAll();
		now.advance(Duration.ofSeconds(20));
		cluster.setPodStatus(id, LabPodStatus.READY);

		labReconciler.reconcileAll();

		Map<String, Object> row = row(id);
		assertThat(row.get("status")).isEqualTo("READY");
		assertThat(((Timestamp) row.get("ready_at")).toInstant()).isEqualTo(T0.plusSeconds(20));
		assertThat(((Timestamp) row.get("last_activity_at")).toInstant()).isEqualTo(T0.plusSeconds(20));
	}

	@Test
	void startingPodWaitsUntilStartTimeoutThenRetries() {
		long id = newSession(userId);
		labReconciler.reconcileAll();

		now.advance(Duration.ofMinutes(5));
		labReconciler.reconcileAll();
		assertThat(statusOf(id)).isEqualTo("INITIALIZING");

		now.advance(Duration.ofSeconds(1));
		labReconciler.reconcileAll();
		assertThat(statusOf(id)).isEqualTo("CREATING");
		assertThat(attemptsOf(id)).isEqualTo(1);
		assertThat(cluster.getPodStatus(id)).isEqualTo(LabPodStatus.ABSENT);
	}

	/** 문제 이미지가 바뀌어도 재시도 Pod는 시작할 때 세션에 저장한 이미지로 만든다 */
	@Test
	void retryPodUsesSessionImage() {
		long id = newSession(userId);
		labReconciler.reconcileAll();
		jdbcTemplate.update("update challenges set image = 'test-web:0.2.0'");
		cluster.setPodStatus(id, LabPodStatus.FAILED);

		labReconciler.reconcileAll();
		labReconciler.reconcileAll();

		assertThat(statusOf(id)).isEqualTo("INITIALIZING");
		assertThat(cluster.podImage(id)).isEqualTo("test-web:0.1.0");
	}

	@Test
	void secondFailureDeletesNamespaceAndEndsAsFailed() {
		long id = newSession(userId);
		labReconciler.reconcileAll();
		cluster.setPodStatus(id, LabPodStatus.FAILED);
		labReconciler.reconcileAll();
		labReconciler.reconcileAll();
		assertThat(statusOf(id)).isEqualTo("INITIALIZING");

		cluster.setPodStatus(id, LabPodStatus.FAILED);
		labReconciler.reconcileAll();
		assertThat(statusOf(id)).isEqualTo("TERMINATING");
		assertThat(row(id).get("end_reason")).isNull();

		now.advance(Duration.ofSeconds(3));
		labReconciler.reconcileAll();
		Map<String, Object> row = row(id);
		assertThat(row.get("status")).isEqualTo("FAILED");
		assertThat(((Timestamp) row.get("ended_at")).toInstant()).isEqualTo(T0.plusSeconds(3));
		assertThat(cluster.namespaceExists(id)).isFalse();
	}

	@Test
	void missingPodWhileInitializingCountsAsFailure() {
		long id = newSession(userId);
		labReconciler.reconcileAll();
		cluster.deletePod(id);

		labReconciler.reconcileAll();

		assertThat(statusOf(id)).isEqualTo("CREATING");
		assertThat(attemptsOf(id)).isEqualTo(1);
	}

	@Test
	void creatingWaitsWhilePreviousPodIsDeleting() {
		long id = newSession(userId);
		cluster.createNamespace(id);
		cluster.setPodStatus(id, LabPodStatus.DELETING);

		labReconciler.reconcileAll();

		assertThat(statusOf(id)).isEqualTo("CREATING");
		assertThat(cluster.podImage(id)).isNull();
	}

	@Test
	void creatingPastStartTimeoutWithoutPodRetries() {
		long id = newSession(userId);
		cluster.createNamespace(id);
		cluster.setPodStatus(id, LabPodStatus.DELETING);
		now.advance(Duration.ofMinutes(6));

		labReconciler.reconcileAll();

		assertThat(statusOf(id)).isEqualTo("CREATING");
		assertThat(attemptsOf(id)).isEqualTo(1);
	}

	@Test
	void lostPodWhileReadyEndsAsPodLost() {
		long id = newSession(userId);
		labReconciler.reconcileAll();
		cluster.setPodStatus(id, LabPodStatus.READY);
		labReconciler.reconcileAll();

		cluster.setPodStatus(id, LabPodStatus.FAILED);
		labReconciler.reconcileAll();
		assertThat(statusOf(id)).isEqualTo("TERMINATING");
		assertThat(row(id).get("end_reason")).isEqualTo("POD_LOST");

		labReconciler.reconcileAll();
		assertThat(statusOf(id)).isEqualTo("DELETED");
	}

	@Test
	void terminatingByUserEndsAsDeleted() {
		long id = newSession(userId);
		labReconciler.reconcileAll();
		changeFromOutside(id, "TERMINATING", "USER");

		labReconciler.reconcileAll();

		assertThat(statusOf(id)).isEqualTo("DELETED");
		assertThat(cluster.namespaceExists(id)).isFalse();
	}

	@Test
	void failureOfOneSessionDoesNotBlockOthers() {
		long broken = newSession(userId);
		long fine = newSession(newUser("lee"));
		cluster.failWith(broken, new IllegalStateException("API server 연결 실패"));

		labReconciler.reconcileAll();

		assertThat(statusOf(broken)).isEqualTo("CREATING");
		assertThat(statusOf(fine)).isEqualTo("INITIALIZING");

		cluster.failWith(broken, null);
		labReconciler.reconcileAll();
		assertThat(statusOf(broken)).isEqualTo("INITIALIZING");
	}

	/** reconciler가 읽은 뒤 종료 요청이 먼저 저장되면 reconciler 쪽 저장이 실패하고 종료가 남는다(낙관적 잠금) */
	@Test
	void changeSavedByOthersMeanwhileIsKept() {
		long id = newSession(userId);
		// 다른 connection에서 바로 commit되도록 다른 thread에서 실행
		cluster.onCreatePod(() -> CompletableFuture.runAsync(() -> changeFromOutside(id, "TERMINATING", "USER")).join());

		labReconciler.reconcileAll();

		assertThat(statusOf(id)).isEqualTo("TERMINATING");
		assertThat(row(id).get("end_reason")).isEqualTo("USER");
	}

	/** 지금 시각에 READY가 된 세션 */
	long readySession() {
		long id = newSession(userId);
		labReconciler.reconcileAll();
		cluster.setPodStatus(id, LabPodStatus.READY);
		labReconciler.reconcileAll();
		assertThat(statusOf(id)).isEqualTo("READY");
		return id;
	}

	@Test
	void readySessionWithoutActivityForIdleTimeoutEndsAsIdle() {
		long id = readySession();

		now.advance(Duration.ofMinutes(30));
		labReconciler.reconcileAll();
		assertThat(statusOf(id)).isEqualTo("READY");

		now.advance(Duration.ofSeconds(1));
		labReconciler.reconcileAll();
		assertThat(statusOf(id)).isEqualTo("TERMINATING");
		assertThat(row(id).get("end_reason")).isEqualTo("IDLE");

		labReconciler.reconcileAll();
		assertThat(statusOf(id)).isEqualTo("DELETED");
	}

	@Test
	void activityPushesIdleTimeoutBack() {
		long id = readySession();
		now.advance(Duration.ofMinutes(20));
		jdbcTemplate.update("update lab_sessions set last_activity_at = ? where id = ?", Timestamp.from(now.instant()), id);

		now.advance(Duration.ofMinutes(20));
		labReconciler.reconcileAll();

		assertThat(statusOf(id)).isEqualTo("READY");
	}

	/** idle 시간은 READY부터 센다. 생성에 걸린 시간은 들어가지 않는다 */
	@Test
	void creationTimeIsNotCountedAsIdle() {
		long id = newSession(userId);
		labReconciler.reconcileAll();
		now.advance(Duration.ofMinutes(4));
		cluster.setPodStatus(id, LabPodStatus.READY);
		labReconciler.reconcileAll();

		now.advance(Duration.ofMinutes(29));
		labReconciler.reconcileAll();

		assertThat(statusOf(id)).isEqualTo("READY");
	}

	@Test
	void terminatingWaitsUntilNamespaceIsGone() {
		long id = newSession(userId);
		labReconciler.reconcileAll();
		changeFromOutside(id, "TERMINATING", "USER");
		cluster.holdNamespaceDeletion(true);

		labReconciler.reconcileAll();
		assertThat(statusOf(id)).isEqualTo("TERMINATING");
		assertThat(row(id).get("ended_at")).isNull();

		cluster.holdNamespaceDeletion(false);
		labReconciler.reconcileAll();
		assertThat(statusOf(id)).isEqualTo("DELETED");
	}

	/** 진행 중 세션이 없는 lab namespace를 지운다. 끝난 세션의 namespace가 남은 경우와 세션 행이 없는 경우(탈퇴 등) */
	@Test
	void orphanNamespacesAreDeleted() {
		long active = newSession(userId);
		long ended = newSession(newUser("lee"));
		jdbcTemplate.update("update lab_sessions set status = 'DELETED', end_reason = 'USER' where id = ?", ended);
		long missing = ended + 100;
		for (long id : new long[] { active, ended, missing }) {
			cluster.createNamespace(id);
		}

		labReconciler.deleteOrphans();

		assertThat(cluster.listSessionIds()).isEqualTo(Set.of(active));
	}

	/** 종료 중 세션의 namespace는 reconciler가 지우는 중이므로 orphan이 아니다 */
	@Test
	void terminatingSessionIsNotOrphan() {
		long id = newSession(userId);
		labReconciler.reconcileAll();
		changeFromOutside(id, "TERMINATING", "USER");
		cluster.holdNamespaceDeletion(true);

		labReconciler.deleteOrphans();

		assertThat(cluster.listSessionIds()).isEqualTo(Set.of(id));
		assertThat(statusOf(id)).isEqualTo("TERMINATING");
	}

	static class MutableClock extends Clock {

		private Instant instant = T0;

		void set(Instant instant) {
			this.instant = instant;
		}

		void advance(Duration duration) {
			instant = instant.plus(duration);
		}

		@Override
		public Instant instant() {
			return instant;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

	}

}
