package com.popogonry.hackforge.user;

import static com.popogonry.hackforge.user.AuthApiTests.loginJson;
import static com.popogonry.hackforge.user.AuthApiTests.signupJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.popogonry.hackforge.TestcontainersConfiguration;

/** 로그인 실패 잠금(ADR 011): 5번 연속 실패하면 15분 잠금. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class LoginLockoutTests {

	static final String EMAIL = "hong@example.com";

	static final String RIGHT = "Passw0rd!";

	static final String WRONG = "Wrong000!";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbcTemplate;

	ApiClient apiClient;

	@BeforeEach
	void setUp() throws Exception {
		jdbcTemplate.update("delete from spring_session");
		jdbcTemplate.update("delete from users");
		apiClient = new ApiClient(mockMvc);
		apiClient.post("/api/auth/signup", signupJson(EMAIL, "hong")).andExpect(status().isCreated());
	}

	ResultActions login(ApiClient client, String password) throws Exception {
		return client.post("/api/auth/login", loginJson(EMAIL, password));
	}

	void failTimes(int times) throws Exception {
		for (int i = 0; i < times; i++) {
			login(apiClient, WRONG).andExpect(status().isUnauthorized());
		}
	}

	@Test
	void fifthFailureLocksAccountFor15Minutes() throws Exception {
		failTimes(4);

		Instant before = Instant.now();
		login(apiClient, WRONG)
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"))
			.andExpect(header().string("Retry-After", "900"));

		Instant lockedUntil = jdbcTemplate.queryForObject("select locked_until from users", Timestamp.class).toInstant();
		assertThat(lockedUntil).isBetween(before.plus(Duration.ofMinutes(15)),
				Instant.now().plus(Duration.ofMinutes(15)));
	}

	@Test
	void lockedAccountRejectsRightPasswordWithoutLoggingIn() throws Exception {
		failTimes(4);
		login(apiClient, WRONG).andExpect(status().isTooManyRequests());

		login(apiClient, RIGHT)
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
		apiClient.get("/api/me").andExpect(status().isUnauthorized());
	}

	@Test
	void lockExpiresAndFailuresStartFromZero() throws Exception {
		failTimes(4);
		login(apiClient, WRONG).andExpect(status().isTooManyRequests());

		// 15분이 지난 상태
		jdbcTemplate.update("update users set locked_until = now() - interval '1 second'");

		failTimes(4);
		login(apiClient, RIGHT).andExpect(status().isOk());
	}

	@Test
	void successResetsFailureCount() throws Exception {
		failTimes(4);
		login(apiClient, RIGHT).andExpect(status().isOk());

		assertThat(jdbcTemplate.queryForObject("select failed_login_count from users", Integer.class)).isZero();
		failTimes(4);
	}

	@Test
	void lockoutByLoginFailuresKeepsExistingSession() throws Exception {
		login(apiClient, RIGHT).andExpect(status().isOk());

		ApiClient attacker = new ApiClient(mockMvc);
		for (int i = 0; i < 4; i++) {
			login(attacker, WRONG).andExpect(status().isUnauthorized());
		}
		login(attacker, WRONG).andExpect(status().isTooManyRequests());

		apiClient.get("/api/me").andExpect(status().isOk());
	}

}
