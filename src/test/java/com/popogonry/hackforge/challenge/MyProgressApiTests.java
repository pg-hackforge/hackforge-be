package com.popogonry.hackforge.challenge;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

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

/** 문제별 내 상태와 진행 요약(ADR 027). 내 세션만 센다 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MyProgressApiTests {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	PasswordEncoder passwordEncoder;

	long done;

	long unfinished;

	long untouched;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("delete from lab_sessions");
		jdbcTemplate.update("delete from challenges");
		jdbcTemplate.update("delete from spring_session");
		jdbcTemplate.update("delete from users");
		done = TestChallenges.insertListed(jdbcTemplate, "done", "완료한 문제", "요약", "sqli", "INTRO", 10, Duration.ofDays(3));
		unfinished = TestChallenges.insertListed(jdbcTemplate, "unfinished", "끝내지 못한 문제", "요약", "xss", "BEGINNER", 20,
				Duration.ofDays(2));
		untouched = TestChallenges.insertListed(jdbcTemplate, "untouched", "안 해 본 문제", "요약", "sqli", "INTRO", 30,
				Duration.ofDays(1));
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

	void endedSession(String nickname, long challengeId, boolean completed) {
		jdbcTemplate.update("""
				insert into lab_sessions (user_id, challenge_id, image, status, end_reason, status_changed_at, created_at, completed_at)
				select id, ?, 'x:1', 'DELETED', 'USER', now(), now(), case when ? then now() end from users where nickname = ?
				""", challengeId, completed, nickname);
	}

	/** 완료한 세션이 하나라도 있으면 완료, 세션만 있으면 끝내지 못함, 없으면 시작 안 함. 남의 세션은 세지 않는다 */
	@Test
	void myStatusComesFromMySessionsOnly() throws Exception {
		ApiClient kim = login("kim");
		login("lee");
		endedSession("kim", done, false);
		endedSession("kim", done, true);
		endedSession("kim", unfinished, false);
		endedSession("lee", untouched, true);

		kim.get("/api/challenges")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].slug").value(contains("untouched", "unfinished", "done")))
			.andExpect(jsonPath("$.items[*].myStatus").value(contains("NOT_STARTED", "UNFINISHED", "COMPLETED")));
		kim.get("/api/challenges/" + done).andExpect(jsonPath("$.myStatus").value("COMPLETED"));
		kim.get("/api/challenges/" + untouched).andExpect(jsonPath("$.myStatus").value("NOT_STARTED"));
	}

	/** 내 상태로 거르고(여러 값은 또는), 내 상태별 개수는 내 상태 조건만 빼고 센다 */
	@Test
	void filtersAndCountsByMyStatus() throws Exception {
		ApiClient kim = login("kim");
		endedSession("kim", done, true);
		endedSession("kim", unfinished, false);

		kim.get("/api/challenges", "myStatus", "UNFINISHED")
			.andExpect(jsonPath("$.items[*].slug").value(contains("unfinished")))
			.andExpect(jsonPath("$.totalItems").value(1))
			.andExpect(jsonPath("$.facets.myStatuses[*].myStatus").value(contains("NOT_STARTED", "UNFINISHED", "COMPLETED")))
			.andExpect(jsonPath("$.facets.myStatuses[*].count").value(contains(1, 1, 1)))
			.andExpect(jsonPath("$.facets.categories[?(@.key == 'xss')].count").value(contains(1)))
			.andExpect(jsonPath("$.facets.categories[?(@.key == 'sqli')].count").value(contains(0)));
		kim.get("/api/challenges", "myStatus", "COMPLETED", "myStatus", "NOT_STARTED")
			.andExpect(jsonPath("$.items[*].slug").value(containsInAnyOrder("done", "untouched")));
		kim.get("/api/challenges", "category", "sqli")
			.andExpect(jsonPath("$.facets.myStatuses[*].count").value(contains(1, 0, 1)));
		kim.get("/api/challenges", "myStatus", "NOPE").andExpect(status().isBadRequest());
	}

	/** 전체·완료·끝내지 못한 수와 분류별 전체·완료 수. 문제 없는 분류도 표시 순서로 들어간다 */
	@Test
	void progressSummarizesMyStatusByCategory() throws Exception {
		ApiClient kim = login("kim");
		login("lee");
		endedSession("kim", done, true);
		endedSession("kim", unfinished, false);
		endedSession("lee", untouched, true);
		// 예정 문제는 진행 요약에 세지 않는다(ADR 031)
		TestChallenges.insertPlanned(jdbcTemplate, "sqli-planned", "sqli", "INTRO", 10, Duration.ZERO);

		kim.get("/api/me/progress")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalChallenges").value(3))
			.andExpect(jsonPath("$.completed").value(1))
			.andExpect(jsonPath("$.unfinished").value(1))
			.andExpect(jsonPath("$.categories[0].key").value("sqli"))
			.andExpect(jsonPath("$.categories[0].name").value("SQL Injection"))
			.andExpect(jsonPath("$.categories[0].total").value(2))
			.andExpect(jsonPath("$.categories[0].completed").value(1))
			.andExpect(jsonPath("$.categories[?(@.key == 'xss')].total").value(contains(1)))
			.andExpect(jsonPath("$.categories[?(@.key == 'xss')].completed").value(contains(0)))
			.andExpect(jsonPath("$.categories[?(@.key == 'ssrf')].total").value(contains(0)))
			.andExpect(jsonPath("$.categories.length()").value(
					jdbcTemplate.queryForObject("select count(*) from challenge_categories", Integer.class)));
	}

	@Test
	void progressNeedsLogin() throws Exception {
		new ApiClient(mockMvc).get("/api/me/progress").andExpect(status().isUnauthorized());
	}

}
