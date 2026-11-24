package com.popogonry.hackforge.challenge;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.popogonry.hackforge.TestcontainersConfiguration;
import com.popogonry.hackforge.user.ApiClient;

/** 내 분석의 사실(ADR 030). 생성 실패는 빼고, 문제마다 가장 좋은 세션, 날짜는 한국 시간 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MyActivityApiTests {

	static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	PasswordEncoder passwordEncoder;

	long sqli;

	long xss;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("delete from lab_sessions");
		jdbcTemplate.update("delete from challenges");
		jdbcTemplate.update("delete from spring_session");
		jdbcTemplate.update("delete from users");
		sqli = TestChallenges.insertListed(jdbcTemplate, "sqli-1", "로그인 우회", "요약", "sqli", "INTERMEDIATE", 30,
				Duration.ofDays(3));
		xss = TestChallenges.insertListed(jdbcTemplate, "xss-1", "게시판 XSS", "요약", "xss", "INTRO", 20,
				Duration.ofDays(2));
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

	/** 40분 전에 시작해 10분 전에 끝난 세션. completed면 20분 전 완료, solutionBeforeCompletion이면 그 전에 해답을 엶 */
	long session(String nickname, long challengeId, String status, boolean completed, boolean solutionBeforeCompletion) {
		return jdbcTemplate.queryForObject("""
				insert into lab_sessions (user_id, challenge_id, image, status, end_reason, status_changed_at, created_at,
				  ended_at, stage, completed_at, solution_viewed_at)
				select id, ?, 'x:1', ?, case when ? in ('DELETED', 'TERMINATING') then 'USER' end, now(),
				  now() - interval '40 minutes', case when ? = 'DELETED' then now() - interval '10 minutes' end, 'DEFENSE',
				  case when ? then now() - interval '20 minutes' end,
				  case when ? then now() - interval '25 minutes' end
				from users where nickname = ? returning id
				""", Long.class, challengeId, status, status, status, completed, solutionBeforeCompletion, nickname);
	}

	void verify(long sessionId, String type, String status, String ago) {
		jdbcTemplate.update("insert into verifications (session_id, type, status, started_at) values (?, ?, ?, now() - ?::interval)",
				sessionId, type, status, ago);
	}

	void hint(long sessionId, String stage, int position) {
		jdbcTemplate.update(
				"insert into lab_session_hints (session_id, stage, position, revealed_at) values (?, ?, ?, now())",
				sessionId, stage, position);
	}

	/** 문제마다 가장 좋은 세션: 해답 없이 완료 > 해답 확인 후 완료 > 끝내지 못함, 같으면 힌트가 적은 세션 */
	@Test
	void picksBestSessionPerChallenge() throws Exception {
		ApiClient kim = login("kim");
		long unfinished = session("kim", sqli, "DELETED", false, false);
		verify(unfinished, "ATTACK", "PASS", "35 minutes");
		long afterSolution = session("kim", sqli, "DELETED", true, true);
		verify(afterSolution, "ATTACK", "PASS", "35 minutes");
		long manyHints = session("kim", sqli, "DELETED", true, false);
		hint(manyHints, "ATTACK", 1);
		hint(manyHints, "ATTACK", 2);
		long best = session("kim", sqli, "DELETED", true, false);
		verify(best, "ATTACK", "FAIL", "36 minutes");
		verify(best, "ATTACK", "PASS", "35 minutes");
		hint(best, "DEFENSE", 1);
		verify(best, "DEFENSE", "FAIL", "30 minutes");
		verify(best, "DEFENSE", "ERROR", "29 minutes");
		verify(best, "DEFENSE", "PASS", "21 minutes");
		verify(best, "DEFENSE", "PASS", "15 minutes");

		kim.get("/api/me/activity")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.challenges", hasSize(1)))
			.andExpect(jsonPath("$.challenges[0].challengeId").value(sqli))
			.andExpect(jsonPath("$.challenges[0].title").value("로그인 우회"))
			.andExpect(jsonPath("$.challenges[0].category.key").value("sqli"))
			.andExpect(jsonPath("$.challenges[0].difficulty").value("INTERMEDIATE"))
			.andExpect(jsonPath("$.challenges[0].sessions").value(4))
			.andExpect(jsonPath("$.challenges[0].result").value("COMPLETED"))
			.andExpect(jsonPath("$.challenges[0].attack.passed").value(true))
			.andExpect(jsonPath("$.challenges[0].attack.skipped").value(false))
			.andExpect(jsonPath("$.challenges[0].attack.hints").value(0))
			// 판정이 난 방어 검증만, 완료(20분 전)까지만
			.andExpect(jsonPath("$.challenges[0].defense.attempts").value(2))
			.andExpect(jsonPath("$.challenges[0].defense.hints").value(1));
	}

	/** 해답 확인 후 완료, 건너뛴 공격. 완료한 진행 중 세션은 넣고, 완료하지 않은 진행 중 세션과 생성 실패는 뺀다 */
	@Test
	void countsSessionsWithResultsOnly() throws Exception {
		ApiClient kim = login("kim");
		long skipped = session("kim", xss, "READY", true, true);
		jdbcTemplate.update("update lab_sessions set attack_skipped = true where id = ?", skipped);
		session("kim", sqli, "FAILED", false, false);

		kim.get("/api/me/activity")
			.andExpect(jsonPath("$.challenges[*].challengeId").value(contains((int) xss)))
			.andExpect(jsonPath("$.challenges[0].result").value("COMPLETED_AFTER_SOLUTION"))
			.andExpect(jsonPath("$.challenges[0].attack.passed").value(false))
			.andExpect(jsonPath("$.challenges[0].attack.skipped").value(true));

		// 진행 중 세션은 하나뿐이라, 앞 세션을 끝낸 뒤(종료 시각은 비워 둠) 완료하지 않은 진행 중 세션을 둔다
		jdbcTemplate.update("update lab_sessions set status = 'DELETED', end_reason = 'USER' where id = ?", skipped);
		session("kim", sqli, "READY", false, false);
		kim.get("/api/me/activity")
			.andExpect(jsonPath("$.challenges[*].challengeId").value(contains((int) xss)))
			// 생성 실패는 세지 않고, 종료 시각이 없는 두 세션은 지금까지(40분씩)
			.andExpect(jsonPath("$.sessions").value(2))
			.andExpect(jsonPath("$.totalMinutes").value(80));
	}

	/** 날짜는 한국 시간으로 자르고 365일 모두, 문제 수와 분류는 진행 요약과 같다. 남의 세션은 세지 않는다 */
	@Test
	void daysAndTotals() throws Exception {
		ApiClient kim = login("kim");
		login("lee");
		session("lee", sqli, "DELETED", true, false);
		LocalDate today = LocalDate.now(SEOUL);
		// 한국 시간 오늘 0시 1분(UTC로는 어제)
		jdbcTemplate.update("""
				insert into lab_sessions (user_id, challenge_id, image, status, end_reason, status_changed_at, created_at, ended_at)
				select id, ?, 'x:1', 'DELETED', 'USER', now(), ?, ? from users where nickname = 'kim'
				""", sqli, ZonedDateTime.of(today.atTime(0, 1), SEOUL).toOffsetDateTime(),
				ZonedDateTime.of(today.atTime(0, 31), SEOUL).toOffsetDateTime());

		kim.get("/api/me/activity")
			.andExpect(jsonPath("$.days", hasSize(365)))
			.andExpect(jsonPath("$.days[364].date").value(today.toString()))
			.andExpect(jsonPath("$.days[364].count").value(1))
			.andExpect(jsonPath("$.days[363].count").value(0))
			.andExpect(jsonPath("$.days[0].date").value(today.minusDays(364).toString()))
			.andExpect(jsonPath("$.sessions").value(1))
			.andExpect(jsonPath("$.totalMinutes").value(30))
			.andExpect(jsonPath("$.totalChallenges").value(2))
			.andExpect(jsonPath("$.categories[0].key").value("sqli"))
			.andExpect(jsonPath("$.categories[0].total").value(1))
			.andExpect(jsonPath("$.categories[?(@.key == 'ssrf')].total").value(contains(0)));
	}

	@Test
	void activityNeedsLogin() throws Exception {
		new ApiClient(mockMvc).get("/api/me/activity").andExpect(status().isUnauthorized());
	}

}
