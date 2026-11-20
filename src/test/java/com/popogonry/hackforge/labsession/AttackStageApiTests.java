package com.popogonry.hackforge.labsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.popogonry.hackforge.TestcontainersConfiguration;
import com.popogonry.hackforge.challenge.TestChallenges;
import com.popogonry.hackforge.labsession.cluster.FakeLabCluster;
import com.popogonry.hackforge.labsession.cluster.LabCluster;
import com.popogonry.hackforge.labsession.cluster.ScriptResult;
import com.popogonry.hackforge.labsession.service.ActivityTracker;
import com.popogonry.hackforge.user.ApiClient;

/** 공격 검증, 공격 힌트, 공격 건너뛰기(ADR 023). 검증 스크립트 실행은 {@link FakeLabCluster#onRunScript}로 정한다 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AttackStageApiTests {

	private static final String ATTACK_CHECK = "test -f /tmp/attacked";

	@TestBean
	LabCluster labCluster;

	static LabCluster labCluster() {
		return new FakeLabCluster();
	}

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	ActivityTracker activityTracker;

	FakeLabCluster cluster;

	long challengeId;

	@BeforeEach
	void setUp() {
		cluster = (FakeLabCluster) labCluster;
		cluster.reset();
		jdbcTemplate.update("delete from lab_sessions");
		jdbcTemplate.update("delete from challenges");
		jdbcTemplate.update("delete from spring_session");
		jdbcTemplate.update("delete from users");
		challengeId = TestChallenges.insert(jdbcTemplate, "test-web", "테스트 문제", "x:1");
		jdbcTemplate.update("update challenges set attack_check = ? where id = ?", ATTACK_CHECK, challengeId);
		TestChallenges.insertHints(jdbcTemplate, challengeId);
	}

	ApiClient login(String nickname) throws Exception {
		jdbcTemplate.update("""
				insert into users (email, password_hash, name, nickname, affiliation, role, status, privacy_agreed_at, created_at)
				values (?, ?, '홍길동', ?, '학교', 'LEARNER', 'APPROVED', now(), now())
				""", nickname + "@example.com", passwordEncoder.encode("Passw0rd!"), nickname);
		ApiClient client = new ApiClient(mockMvc);
		client.post("/api/auth/login", """
				{"email":"%s@example.com","password":"Passw0rd!"}
				""".formatted(nickname)).andExpect(status().isOk());
		return client;
	}

	long session(String nickname, String status) {
		return jdbcTemplate.queryForObject("""
				insert into lab_sessions (user_id, challenge_id, image, status, status_changed_at, created_at, ready_at, last_activity_at)
				select id, ?, 'x:1', ?, now(), now(), now(), now() from users where nickname = ? returning id
				""", Long.class, challengeId, status, nickname);
	}

	String stageOf(long id) {
		return jdbcTemplate.queryForObject("select stage from lab_sessions where id = ?", String.class, id);
	}

	/** 요청해서 받은 검증 id */
	long requestVerification(ApiClient client, long id) throws Exception {
		String body = client.post("/api/labs/" + id + "/attack-verifications", null)
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.type").value("ATTACK"))
			.andExpect(jsonPath("$.status").value("RUNNING"))
			.andExpect(jsonPath("$.message").value(nullValue()))
			.andExpect(jsonPath("$.startedAt").value(notNullValue()))
			.andReturn()
			.getResponse()
			.getContentAsString();
		return ((Number) JsonPath.read(body, "$.id")).longValue();
	}

	String verificationStatus(long verificationId) {
		return jdbcTemplate.queryForObject("select status from verifications where id = ?", String.class, verificationId);
	}

	void awaitFinished(long verificationId) {
		await().atMost(Duration.ofSeconds(5)).until(() -> !verificationStatus(verificationId).equals("RUNNING"));
	}

	// 공격 검증

	/** 문제의 attack-check.sh를 그 세션 Pod에서 실행한다. PASS면 방어 단계로 넘어가고 마지막 줄이 메시지가 된다 */
	@Test
	void passingAttackCheckMovesToDefense() throws Exception {
		ApiClient kim = login("kim");
		long id = session("kim", "READY");
		List<String> ran = new CopyOnWriteArrayList<>();
		cluster.onRunScript((sessionId, script) -> {
			ran.add(sessionId + ":" + script);
			return new ScriptResult(0, "확인 중\nadmin으로 로그인함 (1회)\n\n", false);
		});

		long verificationId = requestVerification(kim, id);
		awaitFinished(verificationId);

		assertThat(ran).containsExactly(id + ":" + ATTACK_CHECK);
		kim.get("/api/labs/" + id + "/verifications/" + verificationId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PASS"))
			.andExpect(jsonPath("$.message").value("admin으로 로그인함 (1회)"))
			.andExpect(jsonPath("$.durationMs").isNumber())
			.andExpect(jsonPath("$.finishedAt").value(notNullValue()));
		assertThat(stageOf(id)).isEqualTo("DEFENSE");
		kim.get("/api/labs/" + id).andExpect(jsonPath("$.stage").value("DEFENSE")).andExpect(jsonPath("$.attackSkipped").value(false));
	}

	@Test
	void failingAttackCheckStaysInAttack() throws Exception {
		ApiClient kim = login("kim");
		long id = session("kim", "READY");
		cluster.onRunScript((sessionId, script) -> new ScriptResult(1, "admin으로 로그인한 기록 없음\n", false));

		long verificationId = requestVerification(kim, id);
		awaitFinished(verificationId);

		kim.get("/api/labs/" + id + "/verifications/" + verificationId)
			.andExpect(jsonPath("$.status").value("FAIL"))
			.andExpect(jsonPath("$.message").value("admin으로 로그인한 기록 없음"));
		assertThat(stageOf(id)).isEqualTo("ATTACK");
	}

	@Test
	void timeoutIsFailWithNotice() throws Exception {
		ApiClient kim = login("kim");
		long id = session("kim", "READY");
		cluster.onRunScript((sessionId, script) -> new ScriptResult(143, "", true));

		long verificationId = requestVerification(kim, id);
		awaitFinished(verificationId);

		kim.get("/api/labs/" + id + "/verifications/" + verificationId)
			.andExpect(jsonPath("$.status").value("FAIL"))
			.andExpect(jsonPath("$.message").value("60초 안에 끝나지 않았어요"));
	}

	/** 플랫폼이 실행하지 못하면 학습자 잘못이 아니므로 ERROR */
	@Test
	void execFailureIsError() throws Exception {
		ApiClient kim = login("kim");
		long id = session("kim", "READY");
		cluster.onRunScript((sessionId, script) -> {
			throw FakeLabCluster.execFailure();
		});

		long verificationId = requestVerification(kim, id);
		awaitFinished(verificationId);

		kim.get("/api/labs/" + id + "/verifications/" + verificationId)
			.andExpect(jsonPath("$.status").value("ERROR"))
			.andExpect(jsonPath("$.message").value("검증을 실행하지 못했어요. 잠시 뒤 다시 시도해 주세요"));
		assertThat(stageOf(id)).isEqualTo("ATTACK");
	}

	@Test
	void longLastLineIsCutTo500() throws Exception {
		ApiClient kim = login("kim");
		long id = session("kim", "READY");
		cluster.onRunScript((sessionId, script) -> new ScriptResult(1, "가".repeat(600), false));

		long verificationId = requestVerification(kim, id);
		awaitFinished(verificationId);

		String message = jdbcTemplate.queryForObject("select message from verifications where id = ?", String.class,
				verificationId);
		assertThat(message).hasSize(500);
	}

	/** 세션마다 검증은 한 번에 하나 */
	@Test
	void secondRequestWhileRunningIsConflict() throws Exception {
		ApiClient kim = login("kim");
		long id = session("kim", "READY");
		CountDownLatch release = new CountDownLatch(1);
		cluster.onRunScript((sessionId, script) -> {
			try {
				release.await(10, TimeUnit.SECONDS);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return new ScriptResult(1, "", false);
		});
		long first = requestVerification(kim, id);

		kim.post("/api/labs/" + id + "/attack-verifications", null)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("VERIFICATION_RUNNING"));

		release.countDown();
		awaitFinished(first);
		requestVerification(kim, id);
	}

	@Test
	void sessionNotReadyIsConflict() throws Exception {
		ApiClient kim = login("kim");
		long id = session("kim", "INITIALIZING");

		kim.post("/api/labs/" + id + "/attack-verifications", null)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("LAB_NOT_READY"));
	}

	/** 방어 단계에서도 공격 검증을 받는다. 단계는 그대로 */
	@Test
	void attackCheckInDefenseKeepsStage() throws Exception {
		ApiClient kim = login("kim");
		long id = session("kim", "READY");
		jdbcTemplate.update("update lab_sessions set stage = 'DEFENSE' where id = ?", id);
		cluster.onRunScript((sessionId, script) -> new ScriptResult(0, "ok", false));

		awaitFinished(requestVerification(kim, id));

		assertThat(stageOf(id)).isEqualTo("DEFENSE");
	}

	@Test
	void verificationsAreListedNewestFirst() throws Exception {
		ApiClient kim = login("kim");
		long id = session("kim", "READY");
		long first = requestVerification(kim, id);
		awaitFinished(first);
		long second = requestVerification(kim, id);
		awaitFinished(second);

		kim.get("/api/labs/" + id + "/verifications")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[*].id", contains((int) second, (int) first)));
	}

	@Test
	void otherLearnersSessionOrVerificationIsNotFound() throws Exception {
		ApiClient lee = login("lee");
		long leeSession = session("lee", "READY");
		long leeVerification = requestVerification(lee, leeSession);
		ApiClient kim = login("kim");
		long kimSession = session("kim", "READY");

		kim.post("/api/labs/" + leeSession + "/attack-verifications", null)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("LAB_NOT_FOUND"));
		kim.get("/api/labs/" + leeSession + "/verifications").andExpect(status().isNotFound());
		kim.get("/api/labs/" + kimSession + "/verifications/" + leeVerification)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("VERIFICATION_NOT_FOUND"));
	}

	// 힌트

	/** 1 → 2 → 3 순서로 공개하고 공개 시각을 남긴다. 다 공개하면 409 */
	@Test
	void attackHintsAreRevealedInOrder() throws Exception {
		ApiClient kim = login("kim");
		long id = session("kim", "READY");

		for (int position = 1; position <= 3; position++) {
			kim.post("/api/labs/" + id + "/attack-hints", null)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.stage").value("ATTACK"))
				.andExpect(jsonPath("$.position").value(position))
				.andExpect(jsonPath("$.content").value("공격 힌트 " + position))
				.andExpect(jsonPath("$.revealedAt").value(notNullValue()));
		}
		kim.post("/api/labs/" + id + "/attack-hints", null)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("NO_MORE_HINTS"));

		kim.get("/api/labs/" + id).andExpect(jsonPath("$.attackHintsRevealed").value(3));
		assertThat(jdbcTemplate.queryForObject(
				"select count(*) from lab_session_hints where session_id = ? and revealed_at is not null", Long.class, id))
			.isEqualTo(3);
	}

	/** 공개한 힌트는 방어 단계에서도 다시 볼 수 있다. 공격 힌트를 새로 공개하지는 못한다 */
	@Test
	void revealedHintsStayVisibleInDefense() throws Exception {
		ApiClient kim = login("kim");
		long id = session("kim", "READY");
		kim.post("/api/labs/" + id + "/attack-hints", null).andExpect(status().isOk());
		kim.post("/api/labs/" + id + "/attack-skip", null).andExpect(status().isOk());

		kim.post("/api/labs/" + id + "/attack-hints", null)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("WRONG_LAB_STAGE"));
		kim.get("/api/labs/" + id + "/hints")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.attackTotal").value(3))
			.andExpect(jsonPath("$.defenseTotal").value(3))
			.andExpect(jsonPath("$.hints", hasSize(1)))
			.andExpect(jsonPath("$.hints[0].content").value("공격 힌트 1"));
	}

	@Test
	void hintNeedsReadySession() throws Exception {
		ApiClient kim = login("kim");
		long id = session("kim", "TERMINATING");

		kim.post("/api/labs/" + id + "/attack-hints", null)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("LAB_NOT_READY"));
	}

	// 건너뛰기

	/** 힌트를 보지 않았어도 공격 단계에서는 언제든 건너뛸 수 있다 */
	@Test
	void skipMovesToDefenseAnytime() throws Exception {
		ApiClient kim = login("kim");
		long id = session("kim", "READY");

		kim.post("/api/labs/" + id + "/attack-skip", null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.stage").value("DEFENSE"))
			.andExpect(jsonPath("$.attackSkipped").value(true));

		kim.post("/api/labs/" + id + "/attack-skip", null)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("WRONG_LAB_STAGE"));
	}

	@Test
	void newSessionStartsInAttack() throws Exception {
		ApiClient kim = login("kim");
		long id = session("kim", "READY");

		kim.get("/api/labs/" + id)
			.andExpect(jsonPath("$.stage").value("ATTACK"))
			.andExpect(jsonPath("$.attackSkipped").value(false))
			.andExpect(jsonPath("$.attackHintsRevealed").value(0));
	}

	/** 검증, 힌트, 건너뛰기 요청은 활동이다 */
	@Test
	void requestsAreActivity() throws Exception {
		ApiClient kim = login("kim");
		long verify = session("kim", "READY");
		requestVerification(kim, verify);
		assertThat(activityTracker.latest(verify)).isPresent();

		jdbcTemplate.update("update lab_sessions set status = 'DELETED', end_reason = 'USER' where id = ?", verify);
		long hint = session("kim", "READY");
		kim.post("/api/labs/" + hint + "/attack-hints", null).andExpect(status().isOk());
		assertThat(activityTracker.latest(hint)).isPresent();
	}

}
