package com.popogonry.hackforge.labsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
import com.popogonry.hackforge.labsession.cluster.ScriptResult;
import com.popogonry.hackforge.labsession.service.ActivityTracker;
import com.popogonry.hackforge.labsession.service.LabJobRecovery;
import com.popogonry.hackforge.user.ApiClient;

/** 방어 단계의 파일 API, 재배포, 초기화, 방어 힌트(ADR 024). Pod 파일은 {@link FakeLabCluster}의 메모리 파일 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class DefenseApiTests {

	private static final String INDEX = "<?php echo 'hello';\n";

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
	ActivityTracker activityTracker;

	@Autowired
	LabJobRecovery labJobRecovery;

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

	ApiClient login(String nickname) throws Exception {
		jdbcTemplate.update("""
				insert into users (email, password_hash, name, nickname, affiliation, role, status, privacy_agreed_at, created_at)
				values (?, ?, '홍길동', ?, '학교', 'LEARNER', 'APPROVED', now(), now())
				""", nickname + "@example.com", passwordEncoder.encode("Passw0rd!"), nickname);
		ApiClient client = new ApiClient(mockMvc);
		client.post("/api/auth/login", """
				{"email":"%s@example.com","password":"Passw0rd!"}
				""".formatted(nickname)).andExpect(status().isOk());
		return client;
	}

	/** READY 세션. Pod /app에는 compose와 서비스 파일, 큰 파일, 바이너리 파일이 있다 */
	long session(String nickname, String stage) {
		long id = jdbcTemplate.queryForObject("""
				insert into lab_sessions (user_id, challenge_id, image, status, stage, status_changed_at, created_at, ready_at, last_activity_at)
				select id, ?, 'x:1', 'READY', ?, now(), now(), now(), now() from users where nickname = ? returning id
				""", Long.class, challengeId, stage, nickname);
		Map<String, byte[]> files = new LinkedHashMap<>();
		files.put("compose.yaml", "services: {}\n".getBytes(StandardCharsets.UTF_8));
		files.put("web/Dockerfile", "FROM base\n".getBytes(StandardCharsets.UTF_8));
		files.put("web/src/index.php", INDEX.getBytes(StandardCharsets.UTF_8));
		files.put("db/init.sql", "create table t ();\n".getBytes(StandardCharsets.UTF_8));
		files.put("web/logo.png", new byte[] { (byte) 0x89, 'P', 'N', 'G', 0, 1 });
		files.put("web/big.txt", "a".repeat(1024 * 1024 + 1).getBytes(StandardCharsets.UTF_8));
		cluster.setFiles(id, files);
		return id;
	}

	String file(long id, String path) {
		byte[] content = cluster.files(id).get(path);
		return content == null ? null : new String(content, StandardCharsets.UTF_8);
	}

	static String sha(String text) {
		return FakeLabCluster.sha256(text.getBytes(StandardCharsets.UTF_8));
	}

	static String fileUrl(long id, String path) {
		return "/api/labs/" + id + "/files/content?path=" + path;
	}

	static String saveJson(String content, String baseSha256) {
		String escaped = content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
		return baseSha256 == null ? "{\"content\":\"%s\"}".formatted(escaped)
				: "{\"content\":\"%s\",\"baseSha256\":\"%s\"}".formatted(escaped, baseSha256);
	}

	// 파일

	/** 기본 수정 불가 파일(compose.yaml, Dockerfile, *.sql)과 열 수 없는 파일(1MB 초과, 바이너리) */
	@Test
	void listsFilesWithReadOnlyAndOpenable() throws Exception {
		ApiClient kim = login("kim");
		long id = session("kim", "DEFENSE");

		kim.get("/api/labs/" + id + "/files")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[*].path",
					contains("compose.yaml", "db/init.sql", "web/Dockerfile", "web/big.txt", "web/logo.png", "web/src/index.php")))
			.andExpect(jsonPath("$[*].readOnly", contains(true, true, true, false, false, false)))
			.andExpect(jsonPath("$[*].openable", contains(true, true, true, false, false, true)))
			.andExpect(jsonPath("$[5].size").value(INDEX.length()));
	}

	/** 문제가 정한 수정 불가 목록이 기본값을 대신한다 */
	@Test
	void challengeReadOnlyPathsReplaceDefault() throws Exception {
		jdbcTemplate.update("update challenges set readonly_paths = 'web/src/*.php' where id = ?", challengeId);
		ApiClient kim = login("kim");
		long id = session("kim", "DEFENSE");

		kim.get("/api/labs/" + id + "/files")
			.andExpect(jsonPath("$[*].readOnly", contains(false, false, false, false, false, true)));
		kim.put(fileUrl(id, "compose.yaml"), saveJson("services: {web: {}}\n", null)).andExpect(status().isOk());
		kim.put(fileUrl(id, "web/src/index.php"), saveJson("x", null))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FILE_READ_ONLY"));
	}

	@Test
	void readsFileWithHash() throws Exception {
		ApiClient kim = login("kim");
		long id = session("kim", "DEFENSE");

		kim.get(fileUrl(id, "web/src/index.php"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.path").value("web/src/index.php"))
			.andExpect(jsonPath("$.content").value(INDEX))
			.andExpect(jsonPath("$.sha256").value(sha(INDEX)))
			.andExpect(jsonPath("$.readOnly").value(false));
	}

	@Test
	void savesWhenBaseHashMatches() throws Exception {
		ApiClient kim = login("kim");
		long id = session("kim", "DEFENSE");
		String fixed = "<?php echo htmlspecialchars('hello');\n";

		kim.put(fileUrl(id, "web/src/index.php"), saveJson(fixed, sha(INDEX)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content").value(fixed))
			.andExpect(jsonPath("$.sha256").value(sha(fixed)));

		assertThat(file(id, "web/src/index.php")).isEqualTo(fixed);
		assertThat(activityTracker.latest(id)).isPresent();
	}

	/** 터미널에서 바뀐 파일은 덮어쓰지 않는다. 기준 해시 없이 보내면(덮어쓰기) 저장한다 */
	@Test
	void changedFileIsConflictUnlessOverwrite() throws Exception {
		ApiClient kim = login("kim");
		long id = session("kim", "DEFENSE");
		cluster.files(id).put("web/src/index.php", "<?php // 터미널에서 고침\n".getBytes(StandardCharsets.UTF_8));

		kim.put(fileUrl(id, "web/src/index.php"), saveJson("<?php // IDE\n", sha(INDEX)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("FILE_CHANGED"));
		assertThat(file(id, "web/src/index.php")).isEqualTo("<?php // 터미널에서 고침\n");

		kim.put(fileUrl(id, "web/src/index.php"), saveJson("<?php // IDE\n", null)).andExpect(status().isOk());
		assertThat(file(id, "web/src/index.php")).isEqualTo("<?php // IDE\n");
	}

	@Test
	void readOnlyFileCannotBeSavedOrReverted() throws Exception {
		ApiClient kim = login("kim");
		long id = session("kim", "DEFENSE");

		kim.put(fileUrl(id, "compose.yaml"), saveJson("x", null))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FILE_READ_ONLY"));
		kim.post("/api/labs/" + id + "/files/revert?path=db/init.sql", null)
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FILE_READ_ONLY"));
		kim.get(fileUrl(id, "compose.yaml")).andExpect(status().isOk()).andExpect(jsonPath("$.readOnly").value(true));
	}

	@Test
	void revertRestoresOriginal() throws Exception {
		ApiClient kim = login("kim");
		long id = session("kim", "DEFENSE");
		kim.put(fileUrl(id, "web/src/index.php"), saveJson("broken", null)).andExpect(status().isOk());
		kim.put(fileUrl(id, "web/src/new.php"), saveJson("new", null)).andExpect(status().isOk());

		kim.post("/api/labs/" + id + "/files/revert?path=web/src/index.php", null)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content").value(INDEX))
			.andExpect(jsonPath("$.sha256").value(sha(INDEX)));
		kim.post("/api/labs/" + id + "/files/revert?path=web/src/new.php", null)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"));
		assertThat(file(id, "web/src/index.php")).isEqualTo(INDEX);
	}

	@Test
	void pathsOutsideAppAreRejected() throws Exception {
		ApiClient kim = login("kim");
		long id = session("kim", "DEFENSE");

		for (String path : List.of("../etc/passwd", "/etc/passwd", "web/../../etc/passwd", "", "web/a b.php")) {
			kim.get(fileUrl(id, path))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		}
		kim.get(fileUrl(id, "web/nope.php"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("FILE_NOT_FOUND"));
	}

	@Test
	void largeOrBinaryFileIsNotEditable() throws Exception {
		ApiClient kim = login("kim");
		long id = session("kim", "DEFENSE");

		kim.get(fileUrl(id, "web/big.txt"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("FILE_NOT_EDITABLE"));
		kim.get(fileUrl(id, "web/logo.png"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("FILE_NOT_EDITABLE"));
	}

	/** IDE는 방어 단계에서만 연다(유스케이스 3) */
	@Test
	void filesNeedDefenseStageAndReadySession() throws Exception {
		ApiClient kim = login("kim");
		long id = session("kim", "ATTACK");

		kim.get("/api/labs/" + id + "/files")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("WRONG_LAB_STAGE"));
		jdbcTemplate.update("update lab_sessions set stage = 'DEFENSE', status = 'TERMINATING', end_reason = 'USER'");
		kim.get(fileUrl(id, "web/src/index.php"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("LAB_NOT_READY"));
	}

	// 재배포

	long requestRedeploy(ApiClient client, long id) throws Exception {
		String body = client.post("/api/labs/" + id + "/redeploys", null)
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.kind").value("REDEPLOY"))
			.andExpect(jsonPath("$.status").value("RUNNING"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		return ((Number) JsonPath.read(body, "$.id")).longValue();
	}

	String redeployStatus(long redeployId) {
		return jdbcTemplate.queryForObject("select status from redeploys where id = ?", String.class, redeployId);
	}

	void awaitRedeploy(long redeployId) {
		await().atMost(Duration.ofSeconds(5)).until(() -> !redeployStatus(redeployId).equals("RUNNING"));
	}

	@Test
	void redeployBuildsAndKeepsLog() throws Exception {
		ApiClient kim = login("kim");
		long id = session("kim", "DEFENSE");
		List<String> scripts = new CopyOnWriteArrayList<>();
		cluster.onRunScript((sessionId, script) -> {
			scripts.add(script);
			return new ScriptResult(0, "#1 building web\n#2 done\n web  Started\n", false);
		});

		long redeployId = requestRedeploy(kim, id);
		awaitRedeploy(redeployId);

		assertThat(scripts).singleElement().satisfies(s -> assertThat(s).contains("docker compose build", "docker compose up -d", "/hackforge/ready.sh"));
		kim.get("/api/labs/" + id + "/redeploys/" + redeployId)
			.andExpect(jsonPath("$.status").value("SUCCESS"))
			.andExpect(jsonPath("$.message").value("재배포했어요"))
			.andExpect(jsonPath("$.log").value("#1 building web\n#2 done\n web  Started\n"))
			.andExpect(jsonPath("$.durationMs").isNumber());
		kim.get("/api/labs/" + id + "/redeploys")
			.andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].log").value(nullValue()));
		assertThat(activityTracker.latest(id)).isPresent();
	}

	@Test
	void failedOrSlowRedeployIsFail() throws Exception {
		ApiClient kim = login("kim");
		long id = session("kim", "DEFENSE");
		cluster.onRunScript((sessionId, script) -> new ScriptResult(1, "ERROR: failed to solve\n", false));
		long failed = requestRedeploy(kim, id);
		awaitRedeploy(failed);
		cluster.onRunScript((sessionId, script) -> new ScriptResult(143, "building\n", true));
		long slow = requestRedeploy(kim, id);
		awaitRedeploy(slow);

		kim.get("/api/labs/" + id + "/redeploys/" + failed)
			.andExpect(jsonPath("$.status").value("FAIL"))
			.andExpect(jsonPath("$.message").value("빌드나 기동에 실패했어요. 빌드 로그를 확인해 주세요"))
			.andExpect(jsonPath("$.log").value("ERROR: failed to solve\n"));
		kim.get("/api/labs/" + id + "/redeploys/" + slow)
			.andExpect(jsonPath("$.status").value("FAIL"))
			.andExpect(jsonPath("$.message").value("3분 안에 끝나지 않았어요. 빌드 로그를 확인해 주세요"));
	}

	@Test
	void longLogKeepsTail() throws Exception {
		ApiClient kim = login("kim");
		long id = session("kim", "DEFENSE");
		cluster.onRunScript((sessionId, script) -> new ScriptResult(0, "x".repeat(70 * 1024) + "END", false));

		long redeployId = requestRedeploy(kim, id);
		awaitRedeploy(redeployId);

		String log = jdbcTemplate.queryForObject("select log from redeploys where id = ?", String.class, redeployId);
		assertThat(log).hasSize(64 * 1024).endsWith("END");
	}

	@Test
	void redeployNeedsDefenseStage() throws Exception {
		ApiClient kim = login("kim");
		long id = session("kim", "ATTACK");

		kim.post("/api/labs/" + id + "/redeploys", null)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("WRONG_LAB_STAGE"));
	}

	/** 검증, 재배포, 초기화는 세션마다 하나만 실행한다 */
	@Test
	void onlyOneJobAtATime() throws Exception {
		ApiClient kim = login("kim");
		long id = session("kim", "DEFENSE");
		CountDownLatch release = new CountDownLatch(1);
		cluster.onRunScript((sessionId, script) -> {
			try {
				release.await(10, TimeUnit.SECONDS);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return new ScriptResult(0, "", false);
		});
		long redeployId = requestRedeploy(kim, id);

		kim.post("/api/labs/" + id + "/attack-verifications", null)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("LAB_JOB_RUNNING"));
		kim.post("/api/labs/" + id + "/redeploys", null).andExpect(status().isConflict());
		kim.post("/api/labs/" + id + "/reset", "{\"scope\":\"SERVICES\",\"keepStage\":true}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("LAB_JOB_RUNNING"));
		kim.post("/api/labs/" + id + "/reset", "{\"scope\":\"ENVIRONMENT\",\"keepStage\":true}")
			.andExpect(status().isConflict());

		release.countDown();
		awaitRedeploy(redeployId);

		// 검증이 실행 중이면 재배포를 막는다
		CountDownLatch verifying = new CountDownLatch(1);
		cluster.onRunScript((sessionId, script) -> {
			try {
				verifying.await(10, TimeUnit.SECONDS);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return new ScriptResult(1, "", false);
		});
		kim.post("/api/labs/" + id + "/attack-verifications", null).andExpect(status().isAccepted());
		kim.post("/api/labs/" + id + "/redeploys", null)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("LAB_JOB_RUNNING"));
		verifying.countDown();
	}

	@Test
	void otherSessionsRedeployIsNotFound() throws Exception {
		ApiClient lee = login("lee");
		long leeSession = session("lee", "DEFENSE");
		cluster.onRunScript((sessionId, script) -> new ScriptResult(0, "", false));
		long leeRedeploy = requestRedeploy(lee, leeSession);
		ApiClient kim = login("kim");
		long kimSession = session("kim", "DEFENSE");

		kim.get("/api/labs/" + kimSession + "/redeploys/" + leeRedeploy)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("REDEPLOY_NOT_FOUND"));
		kim.get("/api/labs/" + leeSession + "/redeploys").andExpect(status().isNotFound());
	}

	// 초기화

	String stageOf(long id) {
		return jdbcTemplate.queryForObject("select stage from lab_sessions where id = ?", String.class, id);
	}

	/** 서비스 초기화: 원본 복원과 서비스 다시 기동. 재배포처럼 기록한다 */
	@Test
	void serviceResetRestoresOriginalAndKeepsStage() throws Exception {
		ApiClient kim = login("kim");
		long id = session("kim", "DEFENSE");
		List<String> scripts = new CopyOnWriteArrayList<>();
		cluster.onRunScript((sessionId, script) -> {
			scripts.add(script);
			return new ScriptResult(0, "done\n", false);
		});
		kim.put(fileUrl(id, "web/src/index.php"), saveJson("broken", null)).andExpect(status().isOk());

		String body = kim.post("/api/labs/" + id + "/reset", "{\"scope\":\"SERVICES\",\"keepStage\":true}")
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.session.stage").value("DEFENSE"))
			.andExpect(jsonPath("$.redeploy.kind").value("RESET"))
			.andExpect(jsonPath("$.redeploy.status").value("RUNNING"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		long resetId = ((Number) JsonPath.read(body, "$.redeploy.id")).longValue();
		awaitRedeploy(resetId);

		assertThat(redeployStatus(resetId)).isEqualTo("SUCCESS");
		assertThat(scripts).singleElement()
			.satisfies(s -> assertThat(s).contains("docker compose down -v", "/hackforge/original/.", "docker compose up -d"));
		assertThat(file(id, "web/src/index.php")).isEqualTo(INDEX);
		assertThat(stageOf(id)).isEqualTo("DEFENSE");
		assertThat(activityTracker.latest(id)).isPresent();
	}

	/** 처음부터: 단계와 건너뜀만 되돌리고 기록과 공개한 힌트는 남긴다 */
	@Test
	void resetFromStartGoesBackToAttack() throws Exception {
		ApiClient kim = login("kim");
		long id = session("kim", "DEFENSE");
		jdbcTemplate.update("update lab_sessions set attack_skipped = true where id = ?", id);
		jdbcTemplate.update("insert into lab_session_hints (session_id, stage, position, revealed_at) values (?, 'ATTACK', 1, now())", id);
		cluster.onRunScript((sessionId, script) -> new ScriptResult(0, "", false));

		kim.post("/api/labs/" + id + "/reset", "{\"scope\":\"SERVICES\",\"keepStage\":false}")
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.session.stage").value("ATTACK"))
			.andExpect(jsonPath("$.session.attackSkipped").value(false))
			.andExpect(jsonPath("$.session.attackHintsRevealed").value(1));
	}

	/** 실습 환경 다시 만들기: Pod를 지우고 생성부터 다시 */
	@Test
	void environmentResetRecreatesPod() throws Exception {
		ApiClient kim = login("kim");
		long id = session("kim", "DEFENSE");
		cluster.createNamespace(id);
		cluster.createPod(id, "x:1");
		cluster.setPodStatus(id, LabPodStatus.READY);
		jdbcTemplate.update("update lab_sessions set start_attempts = 1 where id = ?", id);

		kim.post("/api/labs/" + id + "/reset", "{\"scope\":\"ENVIRONMENT\",\"keepStage\":true}")
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.redeploy").value(nullValue()))
			.andExpect(jsonPath("$.session.status").value("CREATING"))
			.andExpect(jsonPath("$.session.prepStep").value("SPACE"))
			.andExpect(jsonPath("$.session.retrying").value(false))
			.andExpect(jsonPath("$.session.stage").value("DEFENSE"));

		assertThat(cluster.getPodStatus(id)).isEqualTo(LabPodStatus.ABSENT);
		assertThat(jdbcTemplate.queryForObject("select start_attempts from lab_sessions where id = ?", Integer.class, id)).isZero();
	}

	/** 공격 단계에서도 초기화할 수 있다 */
	@Test
	void resetWorksInAttackStageButNeedsReady() throws Exception {
		ApiClient kim = login("kim");
		long id = session("kim", "ATTACK");
		cluster.onRunScript((sessionId, script) -> new ScriptResult(0, "", false));

		kim.post("/api/labs/" + id + "/reset", "{\"scope\":\"SERVICES\",\"keepStage\":true}").andExpect(status().isAccepted());

		jdbcTemplate.update("update lab_sessions set status = 'INITIALIZING' where id = ?", id);
		kim.post("/api/labs/" + id + "/reset", "{\"scope\":\"ENVIRONMENT\",\"keepStage\":true}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("LAB_NOT_READY"));
	}

	@Test
	void resetNeedsValidScope() throws Exception {
		ApiClient kim = login("kim");
		long id = session("kim", "DEFENSE");

		kim.post("/api/labs/" + id + "/reset", "{\"scope\":\"ALL\",\"keepStage\":true}").andExpect(status().isBadRequest());
		kim.post("/api/labs/" + id + "/reset", "{\"keepStage\":true}").andExpect(status().isBadRequest());
	}

	// 방어 힌트

	@Test
	void defenseHintsAreRevealedInDefenseOnly() throws Exception {
		ApiClient kim = login("kim");
		long id = session("kim", "ATTACK");
		kim.post("/api/labs/" + id + "/defense-hints", null)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("WRONG_LAB_STAGE"));
		jdbcTemplate.update("update lab_sessions set stage = 'DEFENSE' where id = ?", id);

		for (int position = 1; position <= 3; position++) {
			kim.post("/api/labs/" + id + "/defense-hints", null)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.stage").value("DEFENSE"))
				.andExpect(jsonPath("$.position").value(position))
				.andExpect(jsonPath("$.content").value("방어 힌트 " + position));
		}
		kim.post("/api/labs/" + id + "/defense-hints", null)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("NO_MORE_HINTS"));
		kim.get("/api/labs/" + id)
			.andExpect(jsonPath("$.defenseHintsRevealed").value(3))
			.andExpect(jsonPath("$.attackHintsRevealed").value(0));
	}

	// 백엔드 재시작 복구

	/** ADR 024. 백엔드가 시작할 때 남은 RUNNING(검증, 재배포)을 모두 ERROR로 */
	@Test
	void startupRecoveryFailsAllRunningJobs() throws Exception {
		login("kim");
		long id = session("kim", "DEFENSE");
		long verification = jdbcTemplate.queryForObject(
				"insert into verifications (session_id, type, status, started_at) values (?, 'ATTACK', 'RUNNING', now()) returning id",
				Long.class, id);
		long redeploy = jdbcTemplate.queryForObject(
				"insert into redeploys (session_id, kind, status, started_at) values (?, 'REDEPLOY', 'RUNNING', now()) returning id",
				Long.class, id);

		labJobRecovery.failAllRunning();

		for (String table : List.of("verifications", "redeploys")) {
			long rowId = table.equals("verifications") ? verification : redeploy;
			Map<String, Object> row = jdbcTemplate.queryForMap("select * from " + table + " where id = ?", rowId);
			assertThat(row.get("status")).isEqualTo("ERROR");
			assertThat(row.get("message")).isEqualTo("서버가 다시 시작돼 결과를 잃었어요. 다시 시도해 주세요");
			assertThat(row.get("finished_at")).isNotNull();
		}
	}

}
