package com.popogonry.hackforge.labsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

/** 방어 검증, 시도 횟수와 해답, 실습 완료(ADR 025). 스크립트 실행은 {@link FakeLabCluster#onRunScript}로 정한다 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class DefenseVerificationApiTests {

	private static final String EXPLOIT = "# exploit";

	private static final String FUNCTIONAL = "# functional";

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
		jdbcTemplate.update("update challenges set exploit = ?, functional_test = ?, max_defense_attempts = 2 where id = ?",
				EXPLOIT, FUNCTIONAL, challengeId);
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

	long session(String nickname, String status, String stage) {
		return jdbcTemplate.queryForObject("""
				insert into lab_sessions (user_id, challenge_id, image, status, stage, status_changed_at, created_at, ready_at, last_activity_at)
				select id, ?, 'x:1', ?, ?, now(), now(), now(), now() from users where nickname = ? returning id
				""", Long.class, challengeId, status, stage, nickname);
	}

	/** exploit.sh와 functional-test.sh의 결과를 정한다. 실행한 스크립트를 순서대로 남긴다 */
	List<String> scripts(ScriptResult exploit, ScriptResult functional) {
		List<String> ran = new CopyOnWriteArrayList<>();
		cluster.onRunScript((sessionId, script) -> {
			ran.add(script);
			return script.equals(EXPLOIT) ? exploit : functional;
		});
		return ran;
	}

	static ScriptResult blocked() {
		return new ScriptResult(1, "payload 5개 중 0개가 통함\n", false);
	}

	static ScriptResult stillWorks() {
		return new ScriptResult(0, "payload 5개 중 2개가 아직 통함: a, b\n", false);
	}

	static ScriptResult works() {
		return new ScriptResult(0, "정상 기능 3개 모두 동작\n", false);
	}

	static ScriptResult broken() {
		return new ScriptResult(1, "o'neil 로그인 실패\n", false);
	}

	long requestDefense(ApiClient client, long id) throws Exception {
		String body = client.post("/api/labs/" + id + "/defense-verifications", null)
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.type").value("DEFENSE"))
			.andExpect(jsonPath("$.status").value("RUNNING"))
			.andExpect(jsonPath("$.securityPassed").value(nullValue()))
			.andReturn()
			.getResponse()
			.getContentAsString();
		long verificationId = ((Number) JsonPath.read(body, "$.id")).longValue();
		await().atMost(Duration.ofSeconds(5))
			.until(() -> !jdbcTemplate.queryForObject("select status from verifications where id = ?", String.class,
					verificationId).equals("RUNNING"));
		return verificationId;
	}

	String verificationUrl(long id, long verificationId) {
		return "/api/labs/" + id + "/verifications/" + verificationId;
	}

	Map<String, Object> sessionRow(long id) {
		return jdbcTemplate.queryForMap("select * from lab_sessions where id = ?", id);
	}

	/** 공격이 모두 막히고 정상 기능이 동작하면 PASS. exploit.sh 다음 functional-test.sh를 실행하고 첫 PASS로 완료 시각을 남긴다 */
	@Test
	void blockedAttackAndWorkingFunctionsPass() throws Exception {
		ApiClient kim = login("kim");
		long id = session("kim", "READY", "DEFENSE");
		List<String> ran = scripts(blocked(), works());

		long verificationId = requestDefense(kim, id);

		assertThat(ran).containsExactly(EXPLOIT, FUNCTIONAL);
		kim.get(verificationUrl(id, verificationId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PASS"))
			.andExpect(jsonPath("$.message").value("공격이 모두 막히고 정상 기능이 동작해요"))
			.andExpect(jsonPath("$.securityPassed").value(true))
			.andExpect(jsonPath("$.functionalPassed").value(true))
			.andExpect(jsonPath("$.securityMessage").value("payload 5개 중 0개가 통함"))
			.andExpect(jsonPath("$.functionalMessage").value("정상 기능 3개 모두 동작"))
			.andExpect(jsonPath("$.defenseHintsRevealed").value(0))
			.andExpect(jsonPath("$.afterSolution").value(false))
			.andExpect(jsonPath("$.durationMs").value(notNullValue()));
		kim.get("/api/labs/" + id)
			.andExpect(jsonPath("$.completedAt").value(notNullValue()))
			.andExpect(jsonPath("$.defenseAttempts").value(1))
			.andExpect(jsonPath("$.maxDefenseAttempts").value(2))
			.andExpect(jsonPath("$.solutionAvailable").value(false))
			.andExpect(jsonPath("$.solutionViewedAt").value(nullValue()));
	}

	/** 통과하지 못한 쪽을 요약에 적는다. 앞이 실패해도 둘 다 실행한다. FAIL은 완료가 아니다 */
	@Test
	void failureSummaryNamesWhatDidNotPass() throws Exception {
		ApiClient kim = login("kim");
		long id = session("kim", "READY", "DEFENSE");

		record Case(ScriptResult exploit, ScriptResult functional, String message, boolean security, boolean functionalOk) {
		}
		for (Case c : List.of(new Case(stillWorks(), works(), "공격이 아직 통해요", false, true),
				new Case(blocked(), broken(), "정상 기능이 동작하지 않아요", true, false),
				new Case(stillWorks(), broken(), "공격이 아직 통해요. 정상 기능이 동작하지 않아요", false, false))) {
			List<String> ran = scripts(c.exploit(), c.functional());
			long verificationId = requestDefense(kim, id);
			assertThat(ran).containsExactly(EXPLOIT, FUNCTIONAL);
			kim.get(verificationUrl(id, verificationId))
				.andExpect(jsonPath("$.status").value("FAIL"))
				.andExpect(jsonPath("$.message").value(c.message()))
				.andExpect(jsonPath("$.securityPassed").value(c.security()))
				.andExpect(jsonPath("$.functionalPassed").value(c.functionalOk()));
		}
		kim.get(verificationUrl(id, jdbcTemplate.queryForObject("select max(id) from verifications", Long.class)))
			.andExpect(jsonPath("$.securityMessage").value("payload 5개 중 2개가 아직 통함: a, b"))
			.andExpect(jsonPath("$.functionalMessage").value("o'neil 로그인 실패"));
		assertThat(sessionRow(id).get("completed_at")).isNull();
	}

	/** 60초를 넘긴 테스트는 통과가 아니다(막혔는지, 동작하는지 알 수 없음) */
	@Test
	void timedOutTestDoesNotPass() throws Exception {
		ApiClient kim = login("kim");
		long id = session("kim", "READY", "DEFENSE");

		scripts(new ScriptResult(124, "", true), works());
		long first = requestDefense(kim, id);
		kim.get(verificationUrl(id, first))
			.andExpect(jsonPath("$.status").value("FAIL"))
			.andExpect(jsonPath("$.securityPassed").value(false))
			.andExpect(jsonPath("$.securityMessage").value("60초 안에 끝나지 않았어요"));

		scripts(blocked(), new ScriptResult(124, "", true));
		long second = requestDefense(kim, id);
		kim.get(verificationUrl(id, second))
			.andExpect(jsonPath("$.status").value("FAIL"))
			.andExpect(jsonPath("$.functionalPassed").value(false))
			.andExpect(jsonPath("$.functionalMessage").value("60초 안에 끝나지 않았어요"));
	}

	/** 스크립트를 실행하지 못하면 ERROR이고 시도 횟수에 세지 않는다 */
	@Test
	void execFailureIsErrorAndNotCounted() throws Exception {
		ApiClient kim = login("kim");
		long id = session("kim", "READY", "DEFENSE");
		cluster.onRunScript((sessionId, script) -> {
			if (script.equals(FUNCTIONAL)) {
				throw new IllegalStateException("exec 연결이 끊김");
			}
			return blocked();
		});

		long verificationId = requestDefense(kim, id);

		kim.get(verificationUrl(id, verificationId))
			.andExpect(jsonPath("$.status").value("ERROR"))
			.andExpect(jsonPath("$.securityPassed").value(nullValue()))
			.andExpect(jsonPath("$.functionalPassed").value(nullValue()));
		kim.get("/api/labs/" + id).andExpect(jsonPath("$.defenseAttempts").value(0));
	}

	/** FAIL이 최대 횟수(여기서는 2)만큼 쌓이면 해답을 열 수 있다. 처음 연 시각만 남고, 그 뒤 검증은 해답 확인 후다 */
	@Test
	void solutionOpensAfterMaxFailures() throws Exception {
		ApiClient kim = login("kim");
		long id = session("kim", "READY", "DEFENSE");
		scripts(stillWorks(), works());

		requestDefense(kim, id);
		kim.post("/api/labs/" + id + "/solution", null)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SOLUTION_LOCKED"));
		requestDefense(kim, id);
		kim.get("/api/labs/" + id)
			.andExpect(jsonPath("$.defenseAttempts").value(2))
			.andExpect(jsonPath("$.solutionAvailable").value(true));

		String viewedAt = JsonPath.read(kim.post("/api/labs/" + id + "/solution", null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content").value("해답"))
			.andReturn()
			.getResponse()
			.getContentAsString(), "$.viewedAt");
		kim.post("/api/labs/" + id + "/solution", null).andExpect(jsonPath("$.viewedAt").value(viewedAt));
		kim.get("/api/labs/" + id).andExpect(jsonPath("$.solutionViewedAt").value(viewedAt));

		scripts(blocked(), works());
		long afterSolution = requestDefense(kim, id);
		kim.get(verificationUrl(id, afterSolution))
			.andExpect(jsonPath("$.status").value("PASS"))
			.andExpect(jsonPath("$.afterSolution").value(true));
		kim.get("/api/labs/" + id).andExpect(jsonPath("$.defenseAttempts").value(3));
	}

	/** 요청할 때까지 공개한 방어 힌트 수를 남긴다 */
	@Test
	void verificationKeepsDefenseHintsRevealed() throws Exception {
		ApiClient kim = login("kim");
		long id = session("kim", "READY", "DEFENSE");
		scripts(stillWorks(), works());
		kim.post("/api/labs/" + id + "/defense-hints", null).andExpect(status().isOk());
		kim.post("/api/labs/" + id + "/defense-hints", null).andExpect(status().isOk());

		long verificationId = requestDefense(kim, id);

		kim.get(verificationUrl(id, verificationId)).andExpect(jsonPath("$.defenseHintsRevealed").value(2));
	}

	/** 완료 시각은 첫 PASS 때만. 완료 뒤에도 검증을 받는다 */
	@Test
	void onlyFirstPassCompletes() throws Exception {
		ApiClient kim = login("kim");
		long id = session("kim", "READY", "DEFENSE");
		scripts(blocked(), works());

		requestDefense(kim, id);
		Object completedAt = sessionRow(id).get("completed_at");
		assertThat(completedAt).isNotNull();
		requestDefense(kim, id);

		assertThat(sessionRow(id).get("completed_at")).isEqualTo(completedAt);
	}

	/** 공격 검증 응답의 방어 필드는 비어 있다 */
	@Test
	void attackVerificationHasNoDefenseFields() throws Exception {
		ApiClient kim = login("kim");
		long id = session("kim", "READY", "ATTACK");
		cluster.onRunScript((sessionId, script) -> new ScriptResult(1, "기록 없음", false));

		String body = kim.post("/api/labs/" + id + "/attack-verifications", null)
			.andExpect(status().isAccepted())
			.andReturn()
			.getResponse()
			.getContentAsString();
		long verificationId = ((Number) JsonPath.read(body, "$.id")).longValue();
		await().atMost(Duration.ofSeconds(5))
			.until(() -> !jdbcTemplate.queryForObject("select status from verifications where id = ?", String.class,
					verificationId).equals("RUNNING"));

		kim.get(verificationUrl(id, verificationId))
			.andExpect(jsonPath("$.securityPassed").value(nullValue()))
			.andExpect(jsonPath("$.functionalPassed").value(nullValue()))
			.andExpect(jsonPath("$.securityMessage").value(nullValue()))
			.andExpect(jsonPath("$.functionalMessage").value(nullValue()))
			.andExpect(jsonPath("$.defenseHintsRevealed").value(nullValue()))
			.andExpect(jsonPath("$.afterSolution").value(nullValue()));
	}

	@Test
	void defenseVerificationNeedsReadyDefenseAndNoRunningJob() throws Exception {
		ApiClient kim = login("kim");
		long attack = session("kim", "READY", "ATTACK");
		kim.post("/api/labs/" + attack + "/defense-verifications", null)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("WRONG_LAB_STAGE"));
		jdbcTemplate.update("update lab_sessions set status = 'DELETED', end_reason = 'USER' where id = ?", attack);

		long id = session("kim", "INITIALIZING", "DEFENSE");
		kim.post("/api/labs/" + id + "/defense-verifications", null)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("LAB_NOT_READY"));

		jdbcTemplate.update("update lab_sessions set status = 'READY' where id = ?", id);
		CountDownLatch release = new CountDownLatch(1);
		cluster.onRunScript((sessionId, script) -> {
			try {
				release.await(10, TimeUnit.SECONDS);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return blocked();
		});
		kim.post("/api/labs/" + id + "/defense-verifications", null).andExpect(status().isAccepted());
		kim.post("/api/labs/" + id + "/defense-verifications", null)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("LAB_JOB_RUNNING"));
		release.countDown();
	}

	/** 해답 열기는 READY일 때만(종료한 세션의 해답은 #13에서 정함) */
	@Test
	void solutionNeedsReadySession() throws Exception {
		ApiClient kim = login("kim");
		long id = session("kim", "READY", "DEFENSE");
		scripts(stillWorks(), works());
		requestDefense(kim, id);
		requestDefense(kim, id);
		jdbcTemplate.update("update lab_sessions set status = 'DELETED', end_reason = 'USER' where id = ?", id);

		kim.post("/api/labs/" + id + "/solution", null)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("LAB_NOT_READY"));
	}

	@Test
	void defenseVerificationAndSolutionAreActivity() throws Exception {
		ApiClient kim = login("kim");
		long id = session("kim", "READY", "DEFENSE");
		scripts(stillWorks(), works());

		requestDefense(kim, id);
		assertThat(activityTracker.latest(id)).isPresent();
		Instant first = activityTracker.latest(id).orElseThrow();
		requestDefense(kim, id);
		Thread.sleep(5);
		kim.post("/api/labs/" + id + "/solution", null).andExpect(status().isOk());

		assertThat(activityTracker.latest(id).orElseThrow()).isAfter(first);
	}

	/** 완료 뒤 활동이 없으면 종료 기한은 완료 시각에서 5분, 완료 뒤 활동이 있으면 평소처럼 마지막 활동에서 30분 */
	@Test
	void idleDeadlineAfterCompletion() throws Exception {
		ApiClient kim = login("kim");
		long id = session("kim", "READY", "DEFENSE");
		Instant completed = Instant.parse("2026-11-22T10:00:00Z");
		jdbcTemplate.update("update lab_sessions set last_activity_at = ?, completed_at = ? where id = ?",
				Timestamp.from(completed.minusSeconds(60)), Timestamp.from(completed), id);

		kim.get("/api/labs/" + id)
			.andExpect(jsonPath("$.idleDeadline").value("2026-11-22T10:05:00Z"))
			.andExpect(jsonPath("$.idleWarningAt").value("2026-11-22T10:00:00Z"));

		jdbcTemplate.update("update lab_sessions set last_activity_at = ? where id = ?",
				Timestamp.from(completed.plusSeconds(60)), id);
		kim.get("/api/labs/" + id).andExpect(jsonPath("$.idleDeadline").value("2026-11-22T10:31:00Z"));
	}

}
