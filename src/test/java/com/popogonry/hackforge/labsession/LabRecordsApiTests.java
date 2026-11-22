package com.popogonry.hackforge.labsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.popogonry.hackforge.TestcontainersConfiguration;
import com.popogonry.hackforge.challenge.TestChallenges;
import com.popogonry.hackforge.labsession.cluster.FakeLabCluster;
import com.popogonry.hackforge.labsession.cluster.LabCluster;
import com.popogonry.hackforge.labsession.cluster.LabPodStatus;
import com.popogonry.hackforge.labsession.service.LabReconciler;
import com.popogonry.hackforge.user.ApiClient;

/** 관리자 세션 목록·강제 종료, 상태 기록, 내 실습 기록, 조회 API의 관리자 허용(ADR 026) */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class LabRecordsApiTests {

	@TestBean
	LabCluster labCluster;

	static LabCluster labCluster() {
		return new FakeLabCluster();
	}

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	LabReconciler labReconciler;

	FakeLabCluster cluster;

	long challengeId;

	@BeforeEach
	void setUp() {
		cluster = (FakeLabCluster) labCluster;
		cluster.reset();
		jdbcTemplate.update("delete from lab_sessions");
		jdbcTemplate.update("delete from challenges");
		jdbcTemplate.update("delete from spring_session");
		jdbcTemplate.update("delete from users");
		challengeId = TestChallenges.insert(jdbcTemplate, "test-web", "테스트 문제", "x:1");
		TestChallenges.insertHints(jdbcTemplate, challengeId);
	}

	ApiClient login(String nickname, String role) throws Exception {
		jdbcTemplate.update("""
				insert into users (email, password_hash, name, nickname, affiliation, role, status, privacy_agreed_at, created_at)
				values (?, ?, '홍길동', ?, '학교', ?, 'APPROVED', now(), now())
				""", nickname + "@example.com", passwordEncoder.encode("Passw0rd!"), nickname, role);
		ApiClient client = new ApiClient(mockMvc);
		client.post("/api/auth/login", """
				{"email":"%s@example.com","password":"Passw0rd!"}
				""".formatted(nickname)).andExpect(status().isOk());
		return client;
	}

	long session(String nickname, String status, Instant createdAt) {
		return jdbcTemplate.queryForObject("""
				insert into lab_sessions (user_id, challenge_id, image, status, end_reason, status_changed_at, created_at, ready_at, last_activity_at)
				select id, ?, 'x:1', ?, ?, ?, ?, ?, ? from users where nickname = ? returning id
				""", Long.class, challengeId, status, status.equals("DELETED") ? "USER" : null, Timestamp.from(createdAt),
				Timestamp.from(createdAt), Timestamp.from(createdAt), Timestamp.from(createdAt), nickname);
	}

	long startedSession(ApiClient client) throws Exception {
		String body = client.post("/api/labs", "{\"challengeId\":" + challengeId + "}")
			.andExpect(status().isAccepted())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return ((Number) JsonPath.read(body, "$.id")).longValue();
	}

	/** 학습자 시작·종료는 LEARNER, reconciler는 SYSTEM. 생성은 이전 상태가 null */
	@Test
	void statusChangesAreRecordedWithActor() throws Exception {
		ApiClient kim = login("kim", "LEARNER");
		long id = startedSession(kim);
		labReconciler.reconcileAll();
		cluster.setPodStatus(id, LabPodStatus.READY);
		labReconciler.reconcileAll();
		kim.delete("/api/labs/" + id).andExpect(status().isAccepted());

		kim.get("/api/labs/" + id + "/events")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[*].fromStatus").value(contains(null, "CREATING", "INITIALIZING", "READY")))
			.andExpect(jsonPath("$[*].toStatus").value(contains("CREATING", "INITIALIZING", "READY", "TERMINATING")))
			.andExpect(jsonPath("$[*].actor").value(contains("LEARNER", "SYSTEM", "SYSTEM", "LEARNER")))
			.andExpect(jsonPath("$[0].at").isNotEmpty());
		Long kimId = jdbcTemplate.queryForObject("select id from users where nickname = 'kim'", Long.class);
		assertThat(jdbcTemplate.queryForList("select actor_user_id from session_events where session_id = ? order by id", Long.class, id))
			.containsExactly(kimId, null, null, kimId);
	}

	/** 실습 환경 다시 만들기(READY → CREATING)도 학습자 요청으로 남는다 */
	@Test
	void environmentResetIsLearnerEvent() throws Exception {
		ApiClient kim = login("kim", "LEARNER");
		long id = session("kim", "READY", Instant.now());

		kim.post("/api/labs/" + id + "/reset", "{\"scope\":\"ENVIRONMENT\",\"keepStage\":true}").andExpect(status().isAccepted());

		assertThat(jdbcTemplate.queryForList("select actor || ':' || coalesce(from_status, '-') || '>' || to_status from session_events where session_id = ? order by id",
				String.class, id))
			.containsExactly("SYSTEM:->READY", "LEARNER:READY>CREATING");
	}

	/** 관리자 강제 종료는 ADMIN. 이미 종료 중이면 지금 상태를 그대로 준다 */
	@Test
	void adminTerminatesSession() throws Exception {
		ApiClient kim = login("kim", "LEARNER");
		ApiClient admin = login("admin", "ADMIN");
		long id = session("kim", "READY", Instant.now());

		admin.post("/api/admin/labs/" + id + "/terminate", null)
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.status").value("TERMINATING"))
			.andExpect(jsonPath("$.endReason").value("ADMIN"));
		admin.post("/api/admin/labs/" + id + "/terminate", null)
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.status").value("TERMINATING"));
		kim.get("/api/labs/" + id).andExpect(jsonPath("$.endReason").value("ADMIN"));
		assertThat(jdbcTemplate.queryForList("select actor from session_events where session_id = ? order by id", String.class, id))
			.containsExactly("SYSTEM", "ADMIN");

		admin.post("/api/admin/labs/999999/terminate", null)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("LAB_NOT_FOUND"));
		kim.post("/api/admin/labs/" + id + "/terminate", null).andExpect(status().isForbidden());
		kim.get("/api/admin/labs").andExpect(status().isForbidden());
	}

	/** 기본은 종료 전 세션, ALL이면 종료한 세션까지. 최근 시작 순, 학습자 정보(이름, 닉네임, 이메일) */
	@Test
	void adminListsSessions() throws Exception {
		login("kim", "LEARNER");
		login("lee", "LEARNER");
		ApiClient admin = login("admin", "ADMIN");
		Instant t = Instant.parse("2026-11-22T00:00:00Z");
		long old = session("kim", "DELETED", t);
		long kim = session("kim", "READY", t.plusSeconds(60));
		long lee = session("lee", "CREATING", t.plusSeconds(120));

		admin.get("/api/admin/labs")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].session.id").value(contains((int) lee, (int) kim)))
			.andExpect(jsonPath("$.items[0].userName").value("홍길동"))
			.andExpect(jsonPath("$.items[0].userNickname").value("lee"))
			.andExpect(jsonPath("$.items[0].userEmail").value("lee@example.com"))
			.andExpect(jsonPath("$.totalItems").value(2))
			.andExpect(jsonPath("$.page").value(1))
			.andExpect(jsonPath("$.size").value(20));
		admin.get("/api/admin/labs", "status", "ALL")
			.andExpect(jsonPath("$.items[*].session.id").value(contains((int) lee, (int) kim, (int) old)));
		admin.get("/api/admin/labs", "status", "NOPE").andExpect(status().isBadRequest());
		admin.get("/api/admin/labs", "page", "0").andExpect(status().isBadRequest());
	}

	/** 20개씩 나눈다 */
	@Test
	void adminListIsPaged() throws Exception {
		login("kim", "LEARNER");
		ApiClient admin = login("admin", "ADMIN");
		Instant t = Instant.parse("2026-11-22T00:00:00Z");
		for (int i = 0; i < 23; i++) {
			session("kim", "DELETED", t.plusSeconds(i));
		}

		admin.get("/api/admin/labs", "status", "ALL", "page", "2")
			.andExpect(jsonPath("$.items", hasSize(3)))
			.andExpect(jsonPath("$.totalItems").value(23))
			.andExpect(jsonPath("$.totalPages").value(2));
	}

	/** 내 세션만, 종료한 것도 최근 순으로 */
	@Test
	void learnerListsOwnSessions() throws Exception {
		ApiClient kim = login("kim", "LEARNER");
		login("lee", "LEARNER");
		Instant t = Instant.parse("2026-11-22T00:00:00Z");
		long first = session("kim", "DELETED", t);
		long second = session("kim", "READY", t.plusSeconds(60));
		session("lee", "READY", t.plusSeconds(120));

		kim.get("/api/labs")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].id").value(contains((int) second, (int) first)))
			.andExpect(jsonPath("$.totalItems").value(2))
			.andExpect(jsonPath("$.totalPages").value(1));
	}

	/** 관리자는 남의 세션을 조회할 수 있지만 바꿀 수는 없다. 다른 학습자는 조회도 못 한다 */
	@Test
	void adminReadsButCannotChangeOthersSession() throws Exception {
		login("kim", "LEARNER");
		ApiClient lee = login("lee", "LEARNER");
		ApiClient admin = login("admin", "ADMIN");
		long id = session("kim", "READY", Instant.now());
		jdbcTemplate.update("insert into verifications (session_id, type, status, started_at) values (?, 'ATTACK', 'FAIL', now())", id);
		long verificationId = jdbcTemplate.queryForObject("select id from verifications where session_id = ?", Long.class, id);

		for (String path : new String[] { "", "/verifications", "/verifications/" + verificationId, "/redeploys", "/hints", "/events" }) {
			admin.get("/api/labs/" + id + path).andExpect(status().isOk());
			lee.get("/api/labs/" + id + path).andExpect(status().isNotFound());
		}
		admin.post("/api/labs/" + id + "/activity", null).andExpect(status().isNotFound());
		admin.post("/api/labs/" + id + "/attack-verifications", null).andExpect(status().isNotFound());
		admin.delete("/api/labs/" + id).andExpect(status().isNotFound());
		admin.get("/api/labs/" + id + "/solution").andExpect(status().isNotFound());
		assertThat(jdbcTemplate.queryForObject("select status from lab_sessions where id = ?", String.class, id)).isEqualTo("READY");
	}

	/** 연 해답은 세션이 끝나도 다시 본다. 연 적이 없으면 409 */
	@Test
	void openedSolutionCanBeSeenAgainAfterEnd() throws Exception {
		ApiClient kim = login("kim", "LEARNER");
		long id = session("kim", "DELETED", Instant.now());

		kim.get("/api/labs/" + id + "/solution")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SOLUTION_LOCKED"));

		Instant viewed = Instant.parse("2026-11-22T10:00:00Z");
		jdbcTemplate.update("update lab_sessions set solution_viewed_at = ? where id = ?", Timestamp.from(viewed), id);
		kim.get("/api/labs/" + id + "/solution")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content").value("해답"))
			.andExpect(jsonPath("$.viewedAt").value("2026-11-22T10:00:00Z"));
	}

	/** 다른 학습자의 세션 상태 기록과 없는 세션은 404 */
	@Test
	void eventsOfOtherOrUnknownSessionAreNotFound() throws Exception {
		login("kim", "LEARNER");
		ApiClient lee = login("lee", "LEARNER");
		long id = session("kim", "READY", Instant.now());

		lee.get("/api/labs/" + id + "/events").andExpect(status().isNotFound());
		lee.get("/api/labs/999999/events")
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("LAB_NOT_FOUND"));
		lee.get("/api/labs").andExpect(jsonPath("$.items", hasSize(0))).andExpect(jsonPath("$.totalPages").value(0));
	}

}
