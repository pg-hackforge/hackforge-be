package com.popogonry.hackforge.user;

import static com.popogonry.hackforge.user.AuthApiTests.loginJson;
import static com.popogonry.hackforge.user.AuthApiTests.signupJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.test.web.servlet.ResultActions;

import com.popogonry.hackforge.TestcontainersConfiguration;

/** 내 비밀번호 변경(ADR 011, openapi `PUT /api/me/password`). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ChangePasswordTests {

	static final String EMAIL = "hong@example.com";

	static final String OLD = "Passw0rd!";

	static final String NEW = "N3w-passw0rd";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbcTemplate;

	/** 같은 계정으로 로그인한 두 곳(예: 노트북과 실습실 PC). */
	ApiClient here;

	ApiClient elsewhere;

	@BeforeEach
	void setUp() throws Exception {
		jdbcTemplate.update("delete from spring_session");
		jdbcTemplate.update("delete from users");
		new ApiClient(mockMvc).post("/api/auth/signup", signupJson(EMAIL, "hong")).andExpect(status().isCreated());
		here = login(OLD);
		elsewhere = login(OLD);
	}

	ApiClient login(String password) throws Exception {
		ApiClient client = new ApiClient(mockMvc);
		client.post("/api/auth/login", loginJson(EMAIL, password)).andExpect(status().isOk());
		return client;
	}

	ResultActions change(ApiClient client, String current, String next) throws Exception {
		return client.put("/api/me/password",
				"{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(current, next));
	}

	int failedCount() {
		return jdbcTemplate.queryForObject("select failed_login_count from users", Integer.class);
	}

	@Test
	void changesPasswordAndEndsOnlyOtherSessions() throws Exception {
		change(here, OLD, NEW).andExpect(status().isNoContent());

		here.get("/api/me").andExpect(status().isOk());
		elsewhere.get("/api/me").andExpect(status().isUnauthorized());
		new ApiClient(mockMvc).post("/api/auth/login", loginJson(EMAIL, OLD)).andExpect(status().isUnauthorized());
		login(NEW);
	}

	@Test
	void wrongCurrentPasswordIsRejectedAndCounted() throws Exception {
		change(here, "Wrong000!", NEW)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("CURRENT_PASSWORD_MISMATCH"));

		assertThat(failedCount()).isEqualTo(1);
		// 틀려도 세션은 그대로
		here.get("/api/me").andExpect(status().isOk());
	}

	@Test
	void failuresAddUpWithLoginAndFifthLocksAndEndsAllSessions() throws Exception {
		ApiClient attacker = new ApiClient(mockMvc);
		for (int i = 0; i < 3; i++) {
			attacker.post("/api/auth/login", loginJson(EMAIL, "Wrong000!")).andExpect(status().isUnauthorized());
		}
		change(here, "Wrong000!", NEW).andExpect(status().isBadRequest());

		// 로그인 실패 3 + 비밀번호 변경 실패 1 = 4, 이번이 5번째
		change(here, "Wrong000!", NEW)
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"))
			.andExpect(header().string("Retry-After", "900"));

		// 세션을 가진 사람이 이 API로 비밀번호를 대입하지 못하게 그 계정 세션을 모두 끊는다
		here.get("/api/me").andExpect(status().isUnauthorized());
		elsewhere.get("/api/me").andExpect(status().isUnauthorized());
		assertThat(jdbcTemplate.queryForObject("select count(*) from spring_session", Integer.class)).isZero();
	}

	@Test
	void lockedAccountIsRejectedWithoutCheckingCurrentPassword() throws Exception {
		String hashBefore = jdbcTemplate.queryForObject("select password_hash from users", String.class);
		jdbcTemplate.update("update users set locked_until = now() + interval '10 minutes'");

		change(here, OLD, NEW)
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));

		assertThat(jdbcTemplate.queryForObject("select password_hash from users", String.class)).isEqualTo(hashBefore);
	}

	@Test
	void successResetsFailureCount() throws Exception {
		change(here, "Wrong000!", NEW).andExpect(status().isBadRequest());
		change(here, "Wrong000!", NEW).andExpect(status().isBadRequest());

		change(here, OLD, NEW).andExpect(status().isNoContent());

		assertThat(failedCount()).isZero();
	}

	@Test
	void newPasswordMustFollowRule() throws Exception {
		change(here, OLD, "short")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("newPassword"));

		// 규칙 위반은 현재 비밀번호를 확인하기 전에 거절되므로 실패로 세지 않는다
		assertThat(failedCount()).isZero();
	}

	@Test
	void requiresLogin() throws Exception {
		change(new ApiClient(mockMvc), OLD, NEW)
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

}
