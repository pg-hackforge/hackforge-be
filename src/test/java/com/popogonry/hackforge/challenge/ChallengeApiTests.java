package com.popogonry.hackforge.challenge;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.popogonry.hackforge.TestcontainersConfiguration;
import com.popogonry.hackforge.user.ApiClient;

/** 문제 목록(GET /api/challenges). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ChallengeApiTests {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("delete from lab_sessions");
		jdbcTemplate.update("delete from challenges");
		jdbcTemplate.update("delete from spring_session");
		jdbcTemplate.update("delete from users");
	}

	void insertChallenge(String slug, String title) {
		jdbcTemplate.update("insert into challenges (slug, title, image) values (?, ?, ?)", slug, title,
				"registry.example.com/hackforge/" + slug + ":1.0.0");
	}

	@Test
	void requiresLogin() throws Exception {
		new ApiClient(mockMvc).get("/api/challenges").andExpect(status().isUnauthorized());
	}

	@Test
	void pendingUserSeesChallengesInRegistrationOrderWithoutImage() throws Exception {
		insertChallenge("sqli-login", "SQL Injection 로그인 우회");
		insertChallenge("test-web", "테스트 문제");
		ApiClient learner = new ApiClient(mockMvc).signupAndLogin("kim@example.com", "kim");

		learner.get("/api/challenges")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[*].slug", contains("sqli-login", "test-web")))
			.andExpect(jsonPath("$[0].id").isNumber())
			.andExpect(jsonPath("$[0].title").value("SQL Injection 로그인 우회"))
			.andExpect(jsonPath("$[0].image").doesNotExist());
	}

}
