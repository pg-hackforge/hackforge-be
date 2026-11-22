package com.popogonry.hackforge.labsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

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
import com.popogonry.hackforge.labsession.cluster.PodFileRead;
import com.popogonry.hackforge.labsession.cluster.ScriptResult;
import com.popogonry.hackforge.labsession.domain.Redeploy;
import com.popogonry.hackforge.labsession.domain.RedeployStatus;
import com.popogonry.hackforge.labsession.domain.ResetScope;
import com.popogonry.hackforge.labsession.domain.Verification;
import com.popogonry.hackforge.labsession.domain.VerificationStatus;
import com.popogonry.hackforge.labsession.dto.LabFileEntry;
import com.popogonry.hackforge.labsession.dto.LabFileResponse;
import com.popogonry.hackforge.labsession.service.LabFileService;
import com.popogonry.hackforge.labsession.service.LabReconciler;
import com.popogonry.hackforge.labsession.service.RedeployService;
import com.popogonry.hackforge.labsession.service.VerificationService;
import com.popogonry.hackforge.web.ApiException;
import com.popogonry.hackforge.web.ErrorCode;

/**
 * ADR 024. 개발 클러스터의 실제 Pod에서 파일 수정, 재배포, 서비스 초기화, 실습 환경 다시 만들기를 본다.
 * {@code ./gradlew clusterTest}로만 실행하고 필요한 것은 {@code KubernetesLabClusterTests}와 같다.
 * 테스트 문제 이미지에는 원본 사본({@code /hackforge/original})이 있다. 방어 검증(ADR 025)은 실제 문제의 공격 문자열을
 * 공개 사본이 있는 이 저장소에 넣지 않도록 테스트용 스크립트(표시 파일이 있으면 공격이 통함)로 본다
 */
@Tag("cluster")
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "hackforge.lab.reconciler.enabled=false")
class DefenseClusterTests {

	private static final String TEST_IMAGE = "hackforge/lab-test-challenge:0.1.0";

	/** 개발 DB 세션 id, 다른 클러스터 테스트와 겹치지 않게 */
	private static final long FIRST_ID = 900_000_201L;

	private static final Duration SCRIPT_TIMEOUT = Duration.ofSeconds(60);

	@Autowired
	LabReconciler labReconciler;

	@Autowired
	LabCluster labCluster;

	@Autowired
	LabFileService labFileService;

	@Autowired
	RedeployService redeployService;

	@Autowired
	VerificationService verificationService;

	@Autowired
	JdbcTemplate jdbcTemplate;

	long id;

	long userId;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("delete from lab_sessions");
		jdbcTemplate.update("delete from challenges");
		jdbcTemplate.update("delete from users");
		jdbcTemplate.execute("alter table lab_sessions alter column id restart with " + FIRST_ID);
		userId = jdbcTemplate.queryForObject("""
				insert into users (email, password_hash, name, nickname, affiliation, role, status, privacy_agreed_at, created_at)
				values ('kim@example.com', 'x', '김', 'kim', '학교', 'LEARNER', 'APPROVED', now(), now()) returning id
				""", Long.class);
		long challengeId = TestChallenges.insert(jdbcTemplate, "test-web", "테스트", TEST_IMAGE);
		id = jdbcTemplate.queryForObject("""
				insert into lab_sessions (user_id, challenge_id, image, status, prep_step, stage, status_changed_at, created_at)
				values (?, ?, ?, 'CREATING', 'SPACE', 'DEFENSE', ?, ?) returning id
				""", Long.class, userId, challengeId, TEST_IMAGE, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
	}

	@AfterEach
	void tearDown() throws InterruptedException {
		labCluster.closeTarget(id);
		labCluster.deleteNamespace(id);
		for (int i = 0; i < 120 && labCluster.namespaceExists(id); i++) {
			Thread.sleep(1000);
		}
	}

	@Test
	void editRedeployAndResetOnRealPod() throws Exception {
		waitUntilReady();
		assertThat(script("diff -r /app /hackforge/original").exitCode()).as("이미지의 원본 사본").isZero();

		// 파일 목록과 기본 수정 불가(compose.yaml)
		assertThat(labFileService.listLabFiles(id, userId))
			.contains(new LabFileEntry("compose.yaml", compose().getBytes(StandardCharsets.UTF_8).length, true, true));
		assertThatThrownBy(() -> labFileService.saveLabFile(id, "compose.yaml", "x", null, userId))
			.isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FILE_READ_ONLY));

		// 새 파일 저장과 해시 충돌
		LabFileResponse saved = labFileService.saveLabFile(id, "web/index.html", "hf-redeployed\n", null, userId);
		assertThat(labFileService.getLabFile(id, "web/index.html", userId)).isEqualTo(saved);
		assertThat(labFileService.saveLabFile(id, "web/index.html", "hf-redeployed\n", saved.sha256(), userId).sha256())
			.isEqualTo(saved.sha256());
		assertThatThrownBy(() -> labFileService.saveLabFile(id, "web/index.html", "y", "0".repeat(64), userId))
			.isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FILE_CHANGED));

		// 경로는 스크립트에 글자 그대로 넣지 않는다. 한글, 빈칸, 따옴표, 셸 특수 문자, -로 시작하는 이름
		for (String path : java.util.List.of("web/새 파일 (1).html", "web/it's $(touch hf-pwned) `x`;.txt", "-n.txt")) {
			LabFileResponse odd = labFileService.saveLabFile(id, path, "한글 내용\n", null, userId);
			assertThat(labFileService.getLabFile(id, path, userId)).isEqualTo(odd);
			assertThat(odd.content()).isEqualTo("한글 내용\n");
		}
		assertThat(labFileService.listLabFiles(id, userId)).extracting(LabFileEntry::path)
			.contains("web/새 파일 (1).html", "web/it's $(touch hf-pwned) `x`;.txt", "-n.txt")
			.doesNotContain("hf-pwned", "web/hf-pwned");
		assertThat(script("rm -- 'web/새 파일 (1).html' web/it*.txt ./-n.txt").exitCode()).isZero();

		// 바이너리와 큰 파일
		assertThat(script("printf 'a\\000b' > bin.dat && head -c 1100000 /dev/zero | tr '\\000' a > big.txt").exitCode()).isZero();
		assertThat(labFileService.listLabFiles(id, userId)).anySatisfy(f -> {
			assertThat(f.path()).isEqualTo("bin.dat");
			assertThat(f.openable()).isFalse();
		}).anySatisfy(f -> {
			assertThat(f.path()).isEqualTo("big.txt");
			assertThat(f.openable()).isFalse();
		});
		assertThatThrownBy(() -> labFileService.getLabFile(id, "big.txt", userId))
			.isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FILE_NOT_EDITABLE));

		// compose.yaml은 학습자가 터미널로 고칠 수 있다. 웹 서비스가 web/을 보여 주게 바꾸고 재배포한다
		String original = compose();
		String changed = original.replace("ports: [\"8080:80\"]",
				"ports: [\"8080:80\"]\n    volumes: [\"./web:/usr/share/nginx/html:ro\"]");
		assertThat(changed).isNotEqualTo(original);
		labCluster.writeFile(id, "compose.yaml", changed.getBytes(StandardCharsets.UTF_8), null);
		// 서비스 DB에 표시를 남긴다. 서비스 초기화는 volume까지 지운다
		assertThat(script("docker compose exec -T db psql -U postgres -c 'create table hf_marker (x int)'").exitCode())
			.isZero();

		Redeploy redeploy = waitUntilDone(redeployService.requestRedeploy(id, userId).getId());
		assertThat(redeploy.getStatus()).as(redeploy.getLog()).isEqualTo(RedeployStatus.SUCCESS);
		assertThat(redeploy.getLog()).isNotBlank();
		assertThat(get("/")).contains("hf-redeployed");

		// 파일 하나 되돌리기. 원본에 없는 파일은 404
		assertThat(new String(labCluster.revertFile(id, "compose.yaml").content(), StandardCharsets.UTF_8))
			.isEqualTo(original);
		assertThatThrownBy(() -> labFileService.revertLabFile(id, "web/index.html", userId))
			.isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.FILE_NOT_FOUND));

		// 서비스 초기화: 원본 코드와 빈 DB로 다시 기동
		RedeployService.ResetResult reset = redeployService.resetLab(id, userId, ResetScope.SERVICES, true);
		Redeploy resetDone = waitUntilDone(reset.redeploy().getId());
		assertThat(resetDone.getStatus()).as(resetDone.getLog()).isEqualTo(RedeployStatus.SUCCESS);
		assertThat(labFileService.listLabFiles(id, userId)).extracting(LabFileEntry::path).containsExactly("compose.yaml");
		assertThat(get("/")).doesNotContain("hf-redeployed").contains("nginx");
		assertThat(script("docker compose exec -T db psql -U postgres -tAc \"select to_regclass('hf_marker')\"").output()
			.strip()).isEmpty();

		// 실습 환경 다시 만들기: Pod를 새로 만들어 다시 READY
		labCluster.closeTarget(id);
		RedeployService.ResetResult environment = redeployService.resetLab(id, userId, ResetScope.ENVIRONMENT, true);
		assertThat(environment.redeploy()).isNull();
		assertThat(jdbcTemplate.queryForObject("select status from lab_sessions where id = ?", String.class, id))
			.isEqualTo("CREATING");
		waitUntilReady();
		assertThat(labCluster.readFile(id, "web/index.html", 1024).status()).isEqualTo(PodFileRead.Status.NOT_FOUND);
		assertThat(get("/")).contains("nginx");
	}

	/** 테스트용 스크립트로 FAIL(공격이 통함) → 터미널에서 고침 → PASS와 완료 시각 */
	@Test
	void defenseVerificationOnRealPod() throws Exception {
		jdbcTemplate.update("update challenges set exploit = ?, functional_test = ?",
				"test -f /tmp/hf-vulnerable && echo '아직 통함: hf-test'",
				"wget -q -O /dev/null http://127.0.0.1:8080/ && echo '정상 기능 1개 동작'");
		waitUntilReady();
		assertThat(script("touch /tmp/hf-vulnerable").exitCode()).isZero();

		Verification fail = waitUntilVerified(verificationService.requestDefenseVerification(id, userId).getId());
		assertThat(fail.getStatus()).isEqualTo(VerificationStatus.FAIL);
		assertThat(fail.getSecurityPassed()).isFalse();
		assertThat(fail.getSecurityMessage()).isEqualTo("아직 통함: hf-test");
		assertThat(fail.getFunctionalPassed()).isTrue();
		assertThat(fail.getFunctionalMessage()).isEqualTo("정상 기능 1개 동작");

		assertThat(script("rm /tmp/hf-vulnerable").exitCode()).isZero();
		Verification pass = waitUntilVerified(verificationService.requestDefenseVerification(id, userId).getId());
		assertThat(pass.getStatus()).isEqualTo(VerificationStatus.PASS);
		assertThat(pass.getMessage()).isEqualTo("공격이 모두 막히고 정상 기능이 동작해요");
		assertThat(jdbcTemplate.queryForObject("select completed_at from lab_sessions where id = ?", Timestamp.class, id))
			.isNotNull();
	}

	private Verification waitUntilVerified(long verificationId) throws InterruptedException {
		Instant deadline = Instant.now().plus(Duration.ofMinutes(3));
		while (true) {
			Verification verification = verificationService.getVerification(id, verificationId, userId, false);
			if (verification.getStatus() != VerificationStatus.RUNNING) {
				return verification;
			}
			assertThat(Instant.now()).as("3분 안에 끝남").isBefore(deadline);
			Thread.sleep(1000);
		}
	}

	private String compose() {
		return new String(labCluster.readFile(id, "compose.yaml", 1024 * 1024).content(), StandardCharsets.UTF_8);
	}

	private ScriptResult script(String script) {
		return labCluster.runScript(id, script, SCRIPT_TIMEOUT);
	}

	private String get(String path) throws Exception {
		HttpResponse<String> response = HttpClient.newHttpClient()
			.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + labCluster.targetPort(id) + path)).build(),
					HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(200);
		return response.body();
	}

	private void waitUntilReady() throws InterruptedException {
		Instant deadline = Instant.now().plus(Duration.ofMinutes(4));
		while (!"READY".equals(jdbcTemplate.queryForObject("select status from lab_sessions where id = ?", String.class, id))) {
			assertThat(Instant.now()).as("4분 안에 READY").isBefore(deadline);
			labReconciler.reconcileAll();
			Thread.sleep(1000);
		}
	}

	private Redeploy waitUntilDone(long redeployId) throws InterruptedException {
		Instant deadline = Instant.now().plus(Duration.ofMinutes(4));
		while (true) {
			Redeploy redeploy = redeployService.getRedeploy(id, redeployId, userId, false);
			if (redeploy.getStatus() != RedeployStatus.RUNNING) {
				return redeploy;
			}
			assertThat(Instant.now()).as("4분 안에 끝남").isBefore(deadline);
			Thread.sleep(2000);
		}
	}

}
