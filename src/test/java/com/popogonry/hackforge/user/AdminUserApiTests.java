package com.popogonry.hackforge.user;

import static com.popogonry.hackforge.user.AuthApiTests.loginJson;
import static com.popogonry.hackforge.user.AuthApiTests.signupJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
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

/** 관리자 API: 계정 목록, 승인, 거절(ADR 011 상태 전이). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AdminUserApiTests {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbcTemplate;

	ApiClient admin;

	@BeforeEach
	void setUp() throws Exception {
		jdbcTemplate.update("delete from spring_session");
		jdbcTemplate.update("delete from users");
		// 첫 관리자는 가입 후 DB에서 승격한다(ADR 011). 역할은 로그인할 때 세션에 담기므로 승격 뒤 로그인
		admin = new ApiClient(mockMvc);
		signup("admin@example.com", "admin");
		jdbcTemplate.update("update users set role = 'ADMIN', status = 'APPROVED' where email = 'admin@example.com'");
		admin.post("/api/auth/login", loginJson("admin@example.com", "Passw0rd!")).andExpect(status().isOk());
	}

	long signup(String email, String nickname) throws Exception {
		new ApiClient(mockMvc).post("/api/auth/signup", signupJson(email, nickname)).andExpect(status().isCreated());
		return jdbcTemplate.queryForObject("select id from users where email = ?", Long.class, email);
	}

	String statusOf(long id) {
		return jdbcTemplate.queryForObject("select status from users where id = ?", String.class, id);
	}

	void setStatus(long id, String status) {
		jdbcTemplate.update("update users set status = ? where id = ?", status, id);
	}

	@Test
	void listDefaultsToPendingInSignupOrderWithSignupNote() throws Exception {
		signup("kim@example.com", "kim");
		signup("lee@example.com", "lee");

		admin.get("/api/admin/users")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[*].email", contains("kim@example.com", "lee@example.com")))
			.andExpect(jsonPath("$[0].status").value("PENDING"))
			.andExpect(jsonPath("$[0].signupNote").value("웹보안 3분반"));
	}

	@Test
	void listFiltersByStatus() throws Exception {
		long kim = signup("kim@example.com", "kim");
		signup("lee@example.com", "lee");
		setStatus(kim, "REJECTED");

		admin.get("/api/admin/users?status=REJECTED")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[*].email", contains("kim@example.com")));
		admin.get("/api/admin/users?status=APPROVED")
			.andExpect(jsonPath("$[*].email", contains("admin@example.com")));
	}

	@Test
	void learnerCannotCallAdminApi() throws Exception {
		long kim = signup("kim@example.com", "kim");
		ApiClient learner = new ApiClient(mockMvc);
		learner.post("/api/auth/login", loginJson("kim@example.com", "Passw0rd!")).andExpect(status().isOk());

		learner.get("/api/admin/users")
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FORBIDDEN"));
		learner.post("/api/admin/users/" + kim + "/approve", null).andExpect(status().isForbidden());
		assertThat(statusOf(kim)).isEqualTo("PENDING");
	}

	@Test
	void anonymousCannotCallAdminApi() throws Exception {
		new ApiClient(mockMvc).get("/api/admin/users")
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	void approvePendingUserTakesEffectInTheirExistingSession() throws Exception {
		long kim = signup("kim@example.com", "kim");
		ApiClient learner = new ApiClient(mockMvc);
		learner.post("/api/auth/login", loginJson("kim@example.com", "Passw0rd!")).andExpect(status().isOk());

		admin.post("/api/admin/users/" + kim + "/approve", null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(kim))
			.andExpect(jsonPath("$.status").value("APPROVED"))
			.andExpect(jsonPath("$.signupNote").value("웹보안 3분반"));

		// 승인 상태는 세션이 아니라 DB에서 읽으므로 다시 로그인하지 않아도 바로 보인다
		learner.get("/api/me").andExpect(jsonPath("$.status").value("APPROVED"));
	}

	@Test
	void rejectedUserCanBeApprovedLater() throws Exception {
		long kim = signup("kim@example.com", "kim");

		admin.post("/api/admin/users/" + kim + "/reject", null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REJECTED"));
		admin.post("/api/admin/users/" + kim + "/approve", null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("APPROVED"));
	}

	@Test
	void approvedUserCannotBeRejectedOrApprovedAgain() throws Exception {
		long kim = signup("kim@example.com", "kim");
		setStatus(kim, "APPROVED");

		admin.post("/api/admin/users/" + kim + "/reject", null)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
		admin.post("/api/admin/users/" + kim + "/approve", null)
			.andExpect(status().isConflict());
		assertThat(statusOf(kim)).isEqualTo("APPROVED");
	}

	@Test
	void rejectedUserCannotBeRejectedAgain() throws Exception {
		long kim = signup("kim@example.com", "kim");
		setStatus(kim, "REJECTED");

		admin.post("/api/admin/users/" + kim + "/reject", null)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
	}

	@Test
	void unknownUserIsNotFound() throws Exception {
		admin.post("/api/admin/users/999999/approve", null)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
		admin.post("/api/admin/users/999999/reject", null)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
	}

	// 역할 변경 (유스케이스 14)

	ApiClient loginAs(String email, String password) throws Exception {
		ApiClient client = new ApiClient(mockMvc);
		client.post("/api/auth/login", loginJson(email, password)).andExpect(status().isOk());
		return client;
	}

	int sessionCount(long userId) {
		return jdbcTemplate.queryForObject("select count(*) from spring_session where principal_name = ?",
				Integer.class, String.valueOf(userId));
	}

	@Test
	void changeRoleEndsTargetSessionsAndNewRoleAppliesAfterRelogin() throws Exception {
		long kim = signup("kim@example.com", "kim");
		ApiClient kimClient = loginAs("kim@example.com", "Passw0rd!");
		kimClient.get("/api/admin/users").andExpect(status().isForbidden());

		admin.put("/api/admin/users/" + kim + "/role", "{\"role\":\"ADMIN\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(kim))
			.andExpect(jsonPath("$.role").value("ADMIN"));

		// 세션에 든 역할은 로그인 때 값이라, 대상의 세션을 지워서 다시 로그인하게 한다
		assertThat(sessionCount(kim)).isZero();
		kimClient.get("/api/me").andExpect(status().isUnauthorized());
		loginAs("kim@example.com", "Passw0rd!").get("/api/admin/users").andExpect(status().isOk());
		// 바꾼 관리자 자신의 세션은 그대로
		admin.get("/api/admin/users").andExpect(status().isOk());
	}

	@Test
	void adminCannotChangeOwnRole() throws Exception {
		long adminId = jdbcTemplate.queryForObject("select id from users where email = 'admin@example.com'", Long.class);

		admin.put("/api/admin/users/" + adminId + "/role", "{\"role\":\"LEARNER\"}")
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("CANNOT_CHANGE_OWN_ROLE"));
		assertThat(jdbcTemplate.queryForObject("select role from users where id = ?", String.class, adminId))
			.isEqualTo("ADMIN");
	}

	@Test
	void changeRoleRequiresValidRole() throws Exception {
		long kim = signup("kim@example.com", "kim");

		admin.put("/api/admin/users/" + kim + "/role", "{}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		admin.put("/api/admin/users/" + kim + "/role", "{\"role\":\"SUPERUSER\"}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void changeRoleOfUnknownUserIsNotFound() throws Exception {
		admin.put("/api/admin/users/999999/role", "{\"role\":\"AUTHOR\"}")
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
	}

	// 임시 비밀번호 (유스케이스 15)

	@Test
	void temporaryPasswordReplacesPasswordEndsSessionsAndUnlocks() throws Exception {
		long kim = signup("kim@example.com", "kim");
		ApiClient kimClient = loginAs("kim@example.com", "Passw0rd!");
		jdbcTemplate.update("update users set failed_login_count = 3, locked_until = now() + interval '10 minutes' where id = ?", kim);

		String body = admin.post("/api/admin/users/" + kim + "/temporary-password", null)
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", containsString("no-store")))
			.andReturn().getResponse().getContentAsString();
		String temporary = com.jayway.jsonpath.JsonPath.read(body, "$.temporaryPassword");

		assertThat(sessionCount(kim)).isZero();
		kimClient.get("/api/me").andExpect(status().isUnauthorized());
		assertThat(jdbcTemplate.queryForObject("select failed_login_count from users where id = ?", Integer.class, kim)).isZero();
		assertThat(jdbcTemplate.queryForObject("select locked_until from users where id = ?", Object.class, kim)).isNull();

		new ApiClient(mockMvc).post("/api/auth/login", loginJson("kim@example.com", "Passw0rd!"))
			.andExpect(status().isUnauthorized());
		loginAs("kim@example.com", temporary).get("/api/me").andExpect(status().isOk());
	}

	@Test
	void temporaryPasswordForUnknownUserIsNotFound() throws Exception {
		admin.post("/api/admin/users/999999/temporary-password", null)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
	}

}
