package com.popogonry.hackforge.user;

import static com.popogonry.hackforge.user.AuthApiTests.loginJson;
import static com.popogonry.hackforge.user.AuthApiTests.signupJson;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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

import com.popogonry.hackforge.TestcontainersConfiguration;

/** openapi.yaml "모든 API에 공통인 오류": 400·404·405·415·500도 우리 오류 형식(Problem + code)으로 나간다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CommonErrorTests {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbcTemplate;

	ApiClient admin;

	@BeforeEach
	void setUp() throws Exception {
		jdbcTemplate.update("delete from spring_session");
		jdbcTemplate.update("delete from users");
		admin = new ApiClient(mockMvc);
		admin.post("/api/auth/signup", signupJson("admin@example.com", "admin")).andExpect(status().isCreated());
		jdbcTemplate.update("update users set role = 'ADMIN', status = 'APPROVED'");
		admin.post("/api/auth/login", loginJson("admin@example.com", "Passw0rd!")).andExpect(status().isOk());
	}

	@Test
	void unknownQueryValueIsValidationFailed() throws Exception {
		admin.get("/api/admin/users?status=FOO")
			.andExpect(status().isBadRequest())
			.andExpect(header().string("Content-Type", containsString("application/problem+json")))
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("status"));
	}

	@Test
	void nonNumericPathIdIsValidationFailed() throws Exception {
		admin.post("/api/admin/users/abc/approve", null)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("id"));
	}

	@Test
	void unknownPathIsNotFoundWhenLoggedIn() throws Exception {
		admin.get("/api/nope")
			.andExpect(status().isNotFound())
			.andExpect(header().string("Content-Type", containsString("application/problem+json")))
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void unknownPathIsUnauthenticatedWhenNotLoggedIn() throws Exception {
		// 로그인 검사가 먼저라 어떤 경로가 있는지 드러나지 않는다
		new ApiClient(mockMvc).get("/api/nope")
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	void unsupportedMethodIsMethodNotAllowedWithAllowHeader() throws Exception {
		// 가입은 POST만 받는다
		admin.get("/api/auth/signup")
			.andExpect(status().isMethodNotAllowed())
			.andExpect(header().string("Allow", "POST"))
			.andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
	}

	@Test
	void nonJsonBodyIsUnsupportedMediaType() throws Exception {
		new ApiClient(mockMvc).post("/api/auth/signup", "email=a@example.com", "application/x-www-form-urlencoded")
			.andExpect(status().isUnsupportedMediaType())
			.andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
	}

	@Test
	void unexpectedErrorIsInternalErrorWithoutDetails() throws Exception {
		admin.get("/api/test/fail")
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
			.andExpect(content().string(not(containsString("password_hash"))));
	}

}
