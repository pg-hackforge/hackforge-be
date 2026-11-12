package com.popogonry.hackforge.challenge;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/** 문제 목록(ADR 014). 힌트, 해답, 검증 스크립트는 응답에 없다 */
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

	@Test
	void listsChallengesInIdOrderWithoutHintsOrScripts() throws Exception {
		long first = TestChallenges.insert(jdbcTemplate, "test-web", "테스트 문제", "hackforge/lab-test-challenge:0.1.0");
		long second = TestChallenges.insert(jdbcTemplate, "sqli-login", "로그인 우회", "hackforge/challenge-sqli-login:1.0.0");
		// PostgreSQL은 update한 행을 뒤에 새로 쓰므로, 정렬하지 않으면 first가 뒤로 간다
		jdbcTemplate.update("update challenges set title = title where id = ?", first);
		jdbcTemplate.update("insert into challenge_hints (challenge_id, stage, position, content) values (?, 'ATTACK', 1, 'h')",
				second);

		login("APPROVED").get("/api/challenges")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(2)))
			.andExpect(jsonPath("$[0].id").value(first))
			.andExpect(jsonPath("$[0].slug").value("test-web"))
			.andExpect(jsonPath("$[1].id").value(second))
			.andExpect(jsonPath("$[1].slug").value("sqli-login"))
			.andExpect(jsonPath("$[1].title").value("로그인 우회"))
			.andExpect(jsonPath("$[1].description").value("로그인 우회 설명"))
			.andExpect(jsonPath("$[1].image").doesNotExist())
			.andExpect(jsonPath("$[1].hints").doesNotExist())
			.andExpect(jsonPath("$[1].solution").doesNotExist())
			.andExpect(jsonPath("$[1].attackCheck").doesNotExist())
			.andExpect(jsonPath("$[1].exploit").doesNotExist())
			.andExpect(jsonPath("$[1].functionalTest").doesNotExist());
	}

	/** 승인 여부는 실습을 시작할 때만 본다 */
	@Test
	void pendingAccountCanList() throws Exception {
		TestChallenges.insert(jdbcTemplate, "test-web", "테스트 문제", "x:1");

		login("PENDING").get("/api/challenges").andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
	}

	@Test
	void anonymousCannotList() throws Exception {
		new ApiClient(mockMvc).get("/api/challenges").andExpect(status().isUnauthorized());
	}

}
