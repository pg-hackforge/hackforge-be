package com.popogonry.hackforge.challenge;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
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

/** 문제 목록(ADR 014, ADR 020). 힌트, 해답, 검증 스크립트는 응답에 없다 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ChallengeApiTests {

	private static final String PASSWORD = "Passw0rd!";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	PasswordEncoder passwordEncoder;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("delete from lab_sessions");
		jdbcTemplate.update("delete from challenges");
		jdbcTemplate.update("delete from spring_session");
		jdbcTemplate.update("delete from users");
	}

	ApiClient login(String status) throws Exception {
		jdbcTemplate.update("""
				insert into users (email, password_hash, name, nickname, affiliation, role, status, privacy_agreed_at, created_at)
				values ('kim@example.com', ?, '홍길동', 'kim', '학교', 'LEARNER', ?, now(), now())
				""", passwordEncoder.encode(PASSWORD), status);
		ApiClient client = new ApiClient(mockMvc);
		client.post("/api/auth/login", """
				{"email":"kim@example.com","password":"%s"}
				""".formatted(PASSWORD)).andExpect(status().isOk());
		return client;
	}

	static Duration days(long n) {
		return Duration.ofDays(n);
	}

	long add(String slug, String title, String category, String difficulty, int minutes, Duration ago) {
		return TestChallenges.insertListed(jdbcTemplate, slug, title, title + " 요약", category, difficulty, minutes, ago);
	}

	@Test
	void listItemHasSummaryCategoryAndPageInfoWithoutDescriptionHintsOrScripts() throws Exception {
		long id = TestChallenges.insertListed(jdbcTemplate, "sqli-login", "로그인 우회", "관리자로 로그인하기", "sqli",
				"BEGINNER", 30, days(3));
		jdbcTemplate.update("insert into challenge_hints (challenge_id, stage, position, content) values (?, 'ATTACK', 1, 'h')",
				id);

		login("APPROVED").get("/api/challenges")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.page").value(1))
			.andExpect(jsonPath("$.size").value(20))
			.andExpect(jsonPath("$.totalItems").value(1))
			.andExpect(jsonPath("$.totalPages").value(1))
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].id").value(id))
			.andExpect(jsonPath("$.items[0].slug").value("sqli-login"))
			.andExpect(jsonPath("$.items[0].title").value("로그인 우회"))
			.andExpect(jsonPath("$.items[0].summary").value("관리자로 로그인하기"))
			.andExpect(jsonPath("$.items[0].category.key").value("sqli"))
			.andExpect(jsonPath("$.items[0].category.name").value("SQL Injection"))
			.andExpect(jsonPath("$.items[0].difficulty").value("BEGINNER"))
			.andExpect(jsonPath("$.items[0].estimatedMinutes").value(30))
			.andExpect(jsonPath("$.items[0].isNew").value(true))
			.andExpect(jsonPath("$.items[0].description").doesNotExist())
			.andExpect(jsonPath("$.items[0].image").doesNotExist())
			.andExpect(jsonPath("$.items[0].hints").doesNotExist())
			.andExpect(jsonPath("$.items[0].solution").doesNotExist())
			.andExpect(jsonPath("$.items[0].attackCheck").doesNotExist())
			.andExpect(jsonPath("$.items[0].exploit").doesNotExist())
			.andExpect(jsonPath("$.items[0].functionalTest").doesNotExist());
	}

	/** 처음 등록한 지 14일 안이면 새 문제 */
	@Test
	void newMeansRegisteredWithinFourteenDays() throws Exception {
		add("recent", "최근", "sqli", "INTRO", 10, days(14).minusMinutes(5));
		add("old", "오래됨", "sqli", "INTRO", 10, days(14).plusMinutes(5));

		login("APPROVED").get("/api/challenges")
			.andExpect(jsonPath("$.items[0].slug").value("recent"))
			.andExpect(jsonPath("$.items[0].isNew").value(true))
			.andExpect(jsonPath("$.items[1].slug").value("old"))
			.andExpect(jsonPath("$.items[1].isNew").value(false));
	}

	/** 제목, 요약, 분류 이름에서 대소문자 구분 없이 찾는다. 빈칸만 있으면 검색하지 않는다 */
	@Test
	void searchMatchesTitleSummaryAndCategoryNameIgnoringCase() throws Exception {
		TestChallenges.insertListed(jdbcTemplate, "by-title", "Login 우회", "a", "auth-session", "INTRO", 10, days(4));
		TestChallenges.insertListed(jdbcTemplate, "by-summary", "b", "관리자 LOGIN 화면", "auth-session", "INTRO", 10,
				days(3));
		TestChallenges.insertListed(jdbcTemplate, "by-category", "c", "c", "xss", "INTRO", 10, days(2));
		TestChallenges.insertListed(jdbcTemplate, "none", "d", "d", "auth-session", "INTRO", 10, days(1));
		ApiClient client = login("APPROVED");

		client.get("/api/challenges", "q", "login")
			.andExpect(jsonPath("$.items[*].slug", contains("by-summary", "by-title")));
		client.get("/api/challenges", "q", "Xs").andExpect(jsonPath("$.items[*].slug", contains("by-category")));
		client.get("/api/challenges", "q", "   ").andExpect(jsonPath("$.totalItems").value(4));
	}

	@Test
	void percentAndUnderscoreAreSearchedAsText() throws Exception {
		TestChallenges.insertListed(jdbcTemplate, "percent", "100% 우회", "a", "sqli", "INTRO", 10, days(2));
		TestChallenges.insertListed(jdbcTemplate, "underscore", "user_id 조작", "b", "sqli", "INTRO", 10, days(1));
		TestChallenges.insertListed(jdbcTemplate, "plain", "100점 userXid", "c", "sqli", "INTRO", 10, days(3));
		ApiClient client = login("APPROVED");

		client.get("/api/challenges", "q", "%").andExpect(jsonPath("$.items[*].slug", contains("percent")));
		client.get("/api/challenges", "q", "r_i").andExpect(jsonPath("$.items[*].slug", contains("underscore")));
	}

	/** 같은 조건의 여러 값은 "또는", 다른 조건끼리는 "그리고" */
	@Test
	void filtersAreOrWithinAndAndAcross() throws Exception {
		add("sqli-intro", "a", "sqli", "INTRO", 10, days(4));
		add("xss-intro", "b", "xss", "INTRO", 10, days(3));
		add("xss-advanced", "c", "xss", "ADVANCED", 10, days(2));
		add("ssrf-intro", "d", "ssrf", "INTRO", 10, days(1));
		ApiClient client = login("APPROVED");

		client.get("/api/challenges", "category", "sqli", "category", "xss")
			.andExpect(jsonPath("$.items[*].slug", contains("xss-advanced", "xss-intro", "sqli-intro")));
		client.get("/api/challenges", "category", "sqli", "category", "xss", "difficulty", "INTRO")
			.andExpect(jsonPath("$.items[*].slug", contains("xss-intro", "sqli-intro")));
	}

	/** 한 조건의 개수는 그 조건을 뺀 나머지 조건(검색어 포함)으로 센다. 0개인 항목도 순서대로 */
	@Test
	void facetsCountWithoutTheirOwnCondition() throws Exception {
		add("sqli-intro", "웹 a", "sqli", "INTRO", 10, days(4));
		add("xss-intro", "웹 b", "xss", "INTRO", 10, days(3));
		add("xss-advanced", "웹 c", "xss", "ADVANCED", 10, days(2));
		add("ssrf-intro", "다른 것", "ssrf", "INTRO", 10, days(1));

		login("APPROVED").get("/api/challenges", "q", "웹", "category", "xss", "difficulty", "INTRO")
			.andExpect(jsonPath("$.totalItems").value(1))
			.andExpect(jsonPath("$.facets.categories", hasSize(12)))
			.andExpect(jsonPath("$.facets.categories[0].key").value("sqli"))
			.andExpect(jsonPath("$.facets.categories[0].name").value("SQL Injection"))
			.andExpect(jsonPath("$.facets.categories[0].count").value(1))
			.andExpect(jsonPath("$.facets.categories[1].key").value("xss"))
			.andExpect(jsonPath("$.facets.categories[1].count").value(1))
			.andExpect(jsonPath("$.facets.categories[7].key").value("ssrf"))
			.andExpect(jsonPath("$.facets.categories[7].count").value(0))
			.andExpect(jsonPath("$.facets.categories[11].key").value("info-disclosure"))
			.andExpect(jsonPath("$.facets.difficulties[*].difficulty",
					contains("INTRO", "BEGINNER", "INTERMEDIATE", "ADVANCED")))
			.andExpect(jsonPath("$.facets.difficulties[*].count", contains(1, 0, 0, 1)));
	}

	/** 분류는 DB에서 늘어나므로 없는 키는 오류가 아니라 결과 없음 */
	@Test
	void unknownCategoryMatchesNothing() throws Exception {
		add("sqli-intro", "a", "sqli", "INTRO", 10, days(1));

		login("APPROVED").get("/api/challenges", "category", "nope")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(0)))
			.andExpect(jsonPath("$.totalItems").value(0))
			.andExpect(jsonPath("$.totalPages").value(0));
	}

	/** 기본은 새로 추가된 순. 값이 같으면 새로 추가된 순 */
	@Test
	void sortsByNewestDifficultyOrTime() throws Exception {
		add("advanced-short", "a", "sqli", "ADVANCED", 10, days(4));
		add("intro-long", "b", "sqli", "INTRO", 60, days(3));
		add("beginner-short", "c", "sqli", "BEGINNER", 10, days(2));
		add("intro-mid", "d", "sqli", "INTRO", 30, days(1));
		ApiClient client = login("APPROVED");

		client.get("/api/challenges")
			.andExpect(jsonPath("$.items[*].slug",
					contains("intro-mid", "beginner-short", "intro-long", "advanced-short")));
		client.get("/api/challenges", "sort", "DIFFICULTY")
			.andExpect(jsonPath("$.items[*].slug",
					contains("intro-mid", "intro-long", "beginner-short", "advanced-short")));
		client.get("/api/challenges", "sort", "TIME")
			.andExpect(jsonPath("$.items[*].slug",
					contains("beginner-short", "advanced-short", "intro-mid", "intro-long")));
	}

	/** 20개씩. 범위를 넘은 페이지는 빈 목록이고 개수는 그대로 */
	@Test
	void pagesByTwenty() throws Exception {
		for (int i = 1; i <= 45; i++) {
			add("c" + i, "문제 " + i, "sqli", "INTRO", 10, Duration.ofMinutes(100 - i));
		}
		ApiClient client = login("APPROVED");

		client.get("/api/challenges")
			.andExpect(jsonPath("$.items", hasSize(20)))
			.andExpect(jsonPath("$.items[0].slug").value("c45"))
			.andExpect(jsonPath("$.totalItems").value(45))
			.andExpect(jsonPath("$.totalPages").value(3));
		client.get("/api/challenges", "page", "2")
			.andExpect(jsonPath("$.page").value(2))
			.andExpect(jsonPath("$.items[0].slug").value("c25"))
			.andExpect(jsonPath("$.items[19].slug").value("c6"));
		client.get("/api/challenges", "page", "3")
			.andExpect(jsonPath("$.items[*].slug", contains("c5", "c4", "c3", "c2", "c1")));
		client.get("/api/challenges", "page", "4")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(0)))
			.andExpect(jsonPath("$.totalItems").value(45))
			.andExpect(jsonPath("$.facets.categories[0].count").value(45));
	}

	@Test
	void invalidValuesAreRejected() throws Exception {
		ApiClient client = login("APPROVED");

		client.get("/api/challenges", "sort", "OLDEST")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		client.get("/api/challenges", "difficulty", "EASY").andExpect(status().isBadRequest());
		client.get("/api/challenges", "page", "0")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		client.get("/api/challenges", "page", "x").andExpect(status().isBadRequest());
		client.get("/api/challenges", "q", "가".repeat(101)).andExpect(status().isBadRequest());
	}

	/** 승인 여부는 실습을 시작할 때만 본다 */
	@Test
	void pendingAccountCanList() throws Exception {
		TestChallenges.insert(jdbcTemplate, "test-web", "테스트 문제", "x:1");

		login("PENDING").get("/api/challenges").andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(1)));
	}

	@Test
	void anonymousCannotList() throws Exception {
		new ApiClient(mockMvc).get("/api/challenges").andExpect(status().isUnauthorized());
	}

}
