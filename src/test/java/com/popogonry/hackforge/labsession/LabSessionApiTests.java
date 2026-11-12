package com.popogonry.hackforge.labsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

/** 실습 시작과 세션 조회(ADR 012). 정원은 application.properties의 hackforge.lab.max-concurrent=2 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class LabSessionApiTests {

	private static final String PASSWORD = "Passw0rd!";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	PasswordEncoder passwordEncoder;

	long challengeId;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("delete from lab_sessions");
		jdbcTemplate.update("delete from challenges");
		jdbcTemplate.update("delete from spring_session");
		jdbcTemplate.update("delete from users");
		challengeId = jdbcTemplate.queryForObject("""
				insert into challenges (slug, title, image)
				values ('test-web', '테스트 문제', 'hackforge/lab-test-challenge:0.1.0') returning id
				""", Long.class);
	}

	/** 계정을 DB에 바로 넣고 로그인한 client */
	ApiClient login(String nickname, String status) throws Exception {
		jdbcTemplate.update("""
				insert into users (email, password_hash, name, nickname, affiliation, role, status, privacy_agreed_at, created_at)
				values (?, ?, '홍길동', ?, '학교', 'LEARNER', ?, now(), now())
				""", nickname + "@example.com", passwordEncoder.encode(PASSWORD), nickname, status);
		ApiClient client = new ApiClient(mockMvc);
		client.post("/api/auth/login", """
				{"email":"%s@example.com","password":"%s"}
				""".formatted(nickname, PASSWORD)).andExpect(status().isOk());
		return client;
	}

	ApiClient approved(String nickname) throws Exception {
		return login(nickname, "APPROVED");
	}

	String startJson(long id) {
		return "{\"challengeId\":%d}".formatted(id);
	}

	/** 다른 학습자의 진행 중이거나 끝난 세션을 DB에 바로 넣는다 */
	long insertSession(String nickname, String status) {
		jdbcTemplate.update("""
				insert into users (email, password_hash, name, nickname, affiliation, role, status, privacy_agreed_at, created_at)
				values (?, 'x', '김', ?, '학교', 'LEARNER', 'APPROVED', now(), now())
				""", nickname + "@example.com", nickname);
		return jdbcTemplate.queryForObject("""
				insert into lab_sessions (user_id, challenge_id, image, status, status_changed_at, created_at)
				select id, ?, 'x:1', ?, now(), now() from users where nickname = ? returning id
				""", Long.class, challengeId, status, nickname);
	}

	long activeCount() {
		return jdbcTemplate.queryForObject(
				"select count(*) from lab_sessions where status not in ('DELETED', 'FAILED')", Long.class);
	}

	@Test
	void approvedLearnerStartsSessionInCreating() throws Exception {
		ApiClient kim = approved("kim");

		kim.post("/api/labs", startJson(challengeId))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.id").isNumber())
			.andExpect(jsonPath("$.challengeId").value(challengeId))
			.andExpect(jsonPath("$.challengeTitle").value("테스트 문제"))
			.andExpect(jsonPath("$.status").value("CREATING"))
			.andExpect(jsonPath("$.endReason").value(nullValue()))
			.andExpect(jsonPath("$.readyAt").value(nullValue()))
			.andExpect(jsonPath("$.idleDeadline").value(nullValue()))
			.andExpect(jsonPath("$.createdAt").isString());

		assertThat(jdbcTemplate.queryForObject("select image from lab_sessions", String.class))
			.isEqualTo("hackforge/lab-test-challenge:0.1.0");
	}

	@Test
	void pendingAndRejectedAccountsCannotStart() throws Exception {
		for (String status : List.of("PENDING", "REJECTED")) {
			login(status.toLowerCase(), status).post("/api/labs", startJson(challengeId))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCOUNT_NOT_APPROVED"));
		}
		assertThat(activeCount()).isZero();
	}

	/** 승인은 로그인할 때가 아니라 시작할 때 DB에서 읽는다 */
	@Test
	void approvalAfterLoginIsUsedWithoutLoggingInAgain() throws Exception {
		ApiClient kim = login("kim", "PENDING");
		jdbcTemplate.update("update users set status = 'APPROVED' where nickname = 'kim'");

		kim.post("/api/labs", startJson(challengeId)).andExpect(status().isAccepted());
	}

	@Test
	void unknownChallengeIsNotFound() throws Exception {
		approved("kim").post("/api/labs", startJson(challengeId + 999))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("CHALLENGE_NOT_FOUND"));
	}

	@Test
	void missingChallengeIdIsValidationFailed() throws Exception {
		approved("kim").post("/api/labs", "{}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void secondStartWhileActiveOrTerminatingIsConflict() throws Exception {
		ApiClient kim = approved("kim");
		kim.post("/api/labs", startJson(challengeId)).andExpect(status().isAccepted());

		kim.post("/api/labs", startJson(challengeId))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("LAB_ALREADY_ACTIVE"));

		jdbcTemplate.update("update lab_sessions set status = 'TERMINATING'");
		kim.post("/api/labs", startJson(challengeId)).andExpect(status().isConflict());

		jdbcTemplate.update("update lab_sessions set status = 'DELETED'");
		kim.post("/api/labs", startJson(challengeId)).andExpect(status().isAccepted());
	}

	@Test
	void fullCapacityIsServiceUnavailableAndTerminatingCounts() throws Exception {
		insertSession("lee", "READY");
		insertSession("park", "TERMINATING");
		insertSession("choi", "DELETED");

		approved("kim").post("/api/labs", startJson(challengeId))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("LAB_CAPACITY_FULL"));
	}

	@Test
	void learnerWithActiveSessionGetsConflictEvenWhenFull() throws Exception {
		ApiClient kim = approved("kim");
		kim.post("/api/labs", startJson(challengeId)).andExpect(status().isAccepted());
		insertSession("lee", "READY");

		kim.post("/api/labs", startJson(challengeId))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("LAB_ALREADY_ACTIVE"));
	}

	@Test
	void sameLearnerStartingTwiceAtOnceGetsOneSession() throws Exception {
		ApiClient kim = approved("kim");
		ApiClient sameBrowserTab = new ApiClient(mockMvc);
		sameBrowserTab.setCookie("SESSION", kim.cookie("SESSION"));
		sameBrowserTab.setCookie("XSRF-TOKEN", kim.cookie("XSRF-TOKEN"));

		List<Integer> statuses = startAtOnce(List.of(kim, sameBrowserTab));

		assertThat(statuses).containsExactlyInAnyOrder(202, 409);
		assertThat(activeCount()).isEqualTo(1);
	}

	@Test
	void manyLearnersStartingAtOnceFillOnlyCapacity() throws Exception {
		List<ApiClient> learners = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			learners.add(approved("learner" + i));
		}

		List<Integer> statuses = startAtOnce(learners);

		assertThat(statuses).containsExactlyInAnyOrder(202, 202, 503, 503, 503);
		assertThat(activeCount()).isEqualTo(2);
	}

	/** 모든 client가 동시에 시작 요청을 보내고 응답 상태 코드를 모은다 */
	List<Integer> startAtOnce(List<ApiClient> clients) throws Exception {
		CountDownLatch go = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newFixedThreadPool(clients.size())) {
			List<Future<Integer>> results = new ArrayList<>();
			for (ApiClient client : clients) {
				Callable<Integer> start = () -> {
					go.await();
					return client.post("/api/labs", startJson(challengeId)).andReturn().getResponse().getStatus();
				};
				results.add(pool.submit(start));
			}
			go.countDown();
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> result : results) {
				statuses.add(result.get());
			}
			return statuses;
		}
	}

	@Test
	void currentReturnsActiveSessionOrNoContent() throws Exception {
		ApiClient kim = approved("kim");
		kim.get("/api/labs/current").andExpect(status().isNoContent());

		kim.post("/api/labs", startJson(challengeId)).andExpect(status().isAccepted());
		long id = jdbcTemplate.queryForObject("select id from lab_sessions", Long.class);
		kim.get("/api/labs/current").andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));

		jdbcTemplate.update("update lab_sessions set status = 'FAILED'");
		kim.get("/api/labs/current").andExpect(status().isNoContent());
	}

	@Test
	void getReturnsOwnSessionIncludingEndedOnes() throws Exception {
		ApiClient kim = approved("kim");
		kim.post("/api/labs", startJson(challengeId)).andExpect(status().isAccepted());
		long id = jdbcTemplate.queryForObject("select id from lab_sessions", Long.class);
		jdbcTemplate.update("update lab_sessions set status = 'DELETED', end_reason = 'USER', ended_at = now()");

		kim.get("/api/labs/" + id)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("DELETED"))
			.andExpect(jsonPath("$.endReason").value("USER"))
			.andExpect(jsonPath("$.endedAt").isString());
	}

	@Test
	void otherLearnersOrUnknownSessionIsNotFound() throws Exception {
		long leeSession = insertSession("lee", "READY");
		ApiClient kim = approved("kim");

		kim.get("/api/labs/" + leeSession)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("LAB_NOT_FOUND"));
		kim.get("/api/labs/" + (leeSession + 999))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("LAB_NOT_FOUND"));
	}

	@Test
	void anonymousCannotStart() throws Exception {
		new ApiClient(mockMvc).post("/api/labs", startJson(challengeId)).andExpect(status().isUnauthorized());
	}

}
