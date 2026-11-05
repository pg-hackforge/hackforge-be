package com.popogonry.hackforge.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.popogonry.hackforge.TestcontainersConfiguration;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AuthApiTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	ApiClient client;

	@BeforeEach
	void setUp() {
		jdbc.update("delete from spring_session");
		jdbc.update("delete from users");
		client = new ApiClient(mvc);
	}

	static String signupJson(String email, String nickname) {
		return """
				{"email":"%s","password":"Passw0rd!","name":"홍길동","nickname":"%s",
				 "affiliation":"OO대 정보보호학과","signupNote":"웹보안 3분반","privacyAgreed":true}
				""".formatted(email, nickname);
	}

	static String loginJson(String email, String password) {
		return """
				{"email":"%s","password":"%s"}
				""".formatted(email, password);
	}

	void signup(String email, String nickname) throws Exception {
		client.post("/api/auth/signup", signupJson(email, nickname)).andExpect(status().isCreated());
	}

	@Test
	void signupCreatesPendingLearnerWithLowercaseEmail() throws Exception {
		client.post("/api/auth/signup", signupJson("Hong@Example.com", "길동_01"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.email").value("hong@example.com"))
			.andExpect(jsonPath("$.nickname").value("길동_01"))
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andExpect(jsonPath("$.role").value("LEARNER"))
			.andExpect(jsonPath("$.id").isNumber())
			.andExpect(jsonPath("$.createdAt").isString());
	}

	@Test
	void signupStoresBcryptHashAndConsentTime() throws Exception {
		signup("hong@example.com", "hong");

		var row = jdbc.queryForMap("select password_hash, privacy_agreed_at from users where email = 'hong@example.com'");
		assertThat((String) row.get("password_hash")).startsWith("$2").doesNotContain("Passw0rd!");
		assertThat(row.get("privacy_agreed_at")).isNotNull();
	}

	@Test
	void signupDoesNotLogIn() throws Exception {
		signup("hong@example.com", "hong");

		client.get("/api/me").andExpect(status().isUnauthorized());
	}

	@Test
	void signupRejectsDuplicateEmailIgnoringCase() throws Exception {
		signup("hong@example.com", "hong");

		client.post("/api/auth/signup", signupJson("HONG@example.com", "other"))
			.andExpect(status().isConflict())
			.andExpect(header().string("Content-Type", containsString("application/problem+json")))
			.andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
	}

	@Test
	void signupRejectsDuplicateNickname() throws Exception {
		signup("hong@example.com", "hong");

		client.post("/api/auth/signup", signupJson("kim@example.com", "hong"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("NICKNAME_TAKEN"));
	}

	@Test
	void signupRejectsRuleViolationsPerField() throws Exception {
		String json = """
				{"email":"not-an-email","password":"password","name":"","nickname":"a b",
				 "affiliation":"x","privacyAgreed":false}
				""";
		client.post("/api/auth/signup", json)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[*].field",
					containsInAnyOrder("email", "password", "name", "nickname", "privacyAgreed")));
		assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isZero();
	}

	@Test
	void signupRejectsTooLongFields() throws Exception {
		String json = """
				{"email":"a@example.com","password":"Passw0rd!","name":"%s","nickname":"hong",
				 "affiliation":"%s","signupNote":"%s","privacyAgreed":true}
				""".formatted("가".repeat(51), "가".repeat(101), "가".repeat(201));
		client.post("/api/auth/signup", json)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("name", "affiliation", "signupNote")));
	}

	@Test
	void signupRejectsNonAsciiPassword() throws Exception {
		// bcrypt는 72바이트까지만 처리한다. 한글은 3바이트라 64자 안에서도 넘을 수 있어서 ASCII만 받는다
		String json = signupJson("hong@example.com", "hong").replace("Passw0rd!", "가".repeat(30) + "a1!");
		client.post("/api/auth/signup", json)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[0].field").value("password"));
	}

	@Test
	void signupRejectsMalformedJson() throws Exception {
		client.post("/api/auth/signup", "{")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void loginFailureLooksTheSameForUnknownEmailAndWrongPassword() throws Exception {
		signup("hong@example.com", "hong");

		String wrongPassword = client.post("/api/auth/login", loginJson("hong@example.com", "Wrong000!"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
			.andReturn().getResponse().getContentAsString();
		String unknownEmail = client.post("/api/auth/login", loginJson("nobody@example.com", "Passw0rd!"))
			.andExpect(status().isUnauthorized())
			.andReturn().getResponse().getContentAsString();

		assertThat(unknownEmail).isEqualTo(wrongPassword);
	}

	@Test
	void pendingAccountCanLogInAndReadMe() throws Exception {
		signup("hong@example.com", "hong");

		client.post("/api/auth/login", loginJson("HONG@example.com", "Passw0rd!"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("PENDING"));
		client.get("/api/me")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.email").value("hong@example.com"))
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andExpect(jsonPath("$.role").value("LEARNER"));
	}

	@Test
	void sessionHoldsOnlyUserIdAndRole() throws Exception {
		signup("hong@example.com", "hong");
		Long id = jdbc.queryForObject("select id from users", Long.class);

		client.post("/api/auth/login", loginJson("hong@example.com", "Passw0rd!")).andExpect(status().isOk());

		assertThat(jdbc.queryForObject("select principal_name from spring_session", String.class))
			.isEqualTo(id.toString());
		List<String> attributes = jdbc.queryForList("select attribute_name from spring_session_attributes", String.class);
		assertThat(attributes).containsExactly("SPRING_SECURITY_CONTEXT");
	}

	@Test
	void loginIssuesNewSessionId() throws Exception {
		signup("hong@example.com", "hong");
		signup("kim@example.com", "kim");
		client.post("/api/auth/login", loginJson("hong@example.com", "Passw0rd!")).andExpect(status().isOk());
		String before = client.cookie("SESSION");

		client.post("/api/auth/login", loginJson("kim@example.com", "Passw0rd!")).andExpect(status().isOk());

		assertThat(client.cookie("SESSION")).isNotNull().isNotEqualTo(before);
	}

	@Test
	void logoutEndsSession() throws Exception {
		signup("hong@example.com", "hong");
		client.post("/api/auth/login", loginJson("hong@example.com", "Passw0rd!")).andExpect(status().isOk());
		String session = client.cookie("SESSION");

		client.post("/api/auth/logout", null).andExpect(status().isNoContent());

		client.setCookie("SESSION", session);
		client.get("/api/me")
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		assertThat(jdbc.queryForObject("select count(*) from spring_session", Integer.class)).isZero();
	}

	@Test
	void logoutWithoutLoginIsNoContent() throws Exception {
		client.post("/api/auth/logout", null).andExpect(status().isNoContent());
	}

	@Test
	void unauthenticatedMeIsProblemAndIssuesReadableCsrfCookie() throws Exception {
		client.get("/api/me")
			.andExpect(status().isUnauthorized())
			.andExpect(header().string("Content-Type", containsString("application/problem+json")))
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
			.andExpect(header().stringValues("Set-Cookie", org.hamcrest.Matchers.hasItem(
					org.hamcrest.Matchers.allOf(containsString("XSRF-TOKEN="), not(containsString("HttpOnly"))))));
	}

	@Test
	void postWithoutCsrfTokenIsRejected() throws Exception {
		client.postWithoutCsrf("/api/auth/signup", signupJson("hong@example.com", "hong"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
		assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isZero();
	}

}
