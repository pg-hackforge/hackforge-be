package com.popogonry.hackforge.labsession.terminal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import com.popogonry.hackforge.TestcontainersConfiguration;
import com.popogonry.hackforge.challenge.TestChallenges;
import com.popogonry.hackforge.labsession.cluster.FakeLabCluster;
import com.popogonry.hackforge.labsession.cluster.FakeShell;
import com.popogonry.hackforge.labsession.cluster.LabCluster;
import com.popogonry.hackforge.labsession.service.ActivityTracker;
import com.popogonry.hackforge.user.TestBrowser;

/** 웹 터미널 WebSocket(ADR 022, openapi {@code openLabTerminal}). 셸은 {@link FakeShell} */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TerminalTests {

	@TestBean
	LabCluster labCluster;

	static LabCluster labCluster() {
		return new FakeLabCluster();
	}

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	ActivityTracker activityTracker;

	@Autowired
	TerminalHandler terminalHandler;

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
		challengeId = TestChallenges.insert(jdbcTemplate, "test-web", "테스트", "x:1");
	}

	TestBrowser login(String nickname) throws Exception {
		return TestBrowser.login(port, jdbcTemplate, passwordEncoder, nickname);
	}

	long session(String nickname, String status) {
		return jdbcTemplate.queryForObject("""
				insert into lab_sessions (user_id, challenge_id, image, status, status_changed_at, created_at, ready_at, last_activity_at)
				select id, ?, 'x:1', ?, now(), now(), now(), now() from users where nickname = ? returning id
				""", Long.class, challengeId, status, nickname);
	}

	/** 받은 출력과 닫힌 코드를 모은다 */
	static class Client extends AbstractWebSocketHandler {

		final StringBuilder output = new StringBuilder();

		final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();

		WebSocketSession session;

		@Override
		protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
			synchronized (output) {
				output.append(StandardCharsets.UTF_8.decode(message.getPayload()));
			}
		}

		@Override
		public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
			closed.complete(status);
		}

		String output() {
			synchronized (output) {
				return output.toString();
			}
		}

		CloseStatus awaitClose() throws Exception {
			return closed.get(5, TimeUnit.SECONDS);
		}

	}

	/** 브라우저처럼 플랫폼 origin과 cookie로 연결한다 */
	Client connect(TestBrowser browser, String path, String origin) throws Exception {
		WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
		String cookie = browser.cookie("SESSION");
		if (cookie != null) {
			headers.add("Cookie", "SESSION=" + cookie);
		}
		headers.setOrigin(origin);
		Client client = new Client();
		client.session = new StandardWebSocketClient()
			.execute(client, headers, URI.create("ws://localhost:" + port + path))
			.get(5, TimeUnit.SECONDS);
		return client;
	}

	Client connect(TestBrowser browser, long id) throws Exception {
		return connect(browser, "/api/labs/" + id + "/terminal", "http://localhost:" + port);
	}

	@Test
	void relaysInputOutputAndResize() throws Exception {
		TestBrowser kim = login("kim");
		long id = session("kim", "READY");
		Client client = connect(kim, id);
		await().atMost(Duration.ofSeconds(5)).until(() -> cluster.shells(id).size() == 1);
		FakeShell shell = cluster.shells(id).getFirst();

		shell.print("/app # ");
		await().atMost(Duration.ofSeconds(5)).until(() -> client.output().equals("/app # "));

		client.session.sendMessage(new BinaryMessage("ls\r".getBytes(StandardCharsets.UTF_8)));
		await().atMost(Duration.ofSeconds(5)).until(() -> shell.typed().equals("ls\r"));
		assertThat(activityTracker.latest(id)).isPresent();

		client.session.sendMessage(new TextMessage("not json"));
		client.session.sendMessage(new TextMessage("{\"type\":\"resize\",\"cols\":9999,\"rows\":30}"));
		client.session.sendMessage(new TextMessage("{\"type\":\"resize\",\"cols\":120,\"rows\":30}"));
		await().atMost(Duration.ofSeconds(5)).until(() -> "120x30".equals(shell.size()));
		assertThat(client.session.isOpen()).isTrue();
	}

	/** 붙여넣기처럼 기본 한도(8KB)보다 큰 입력도 한 메시지로 받는다 */
	@Test
	void largePasteIsAccepted() throws Exception {
		TestBrowser kim = login("kim");
		long id = session("kim", "READY");
		Client client = connect(kim, id);
		await().atMost(Duration.ofSeconds(5)).until(() -> cluster.shells(id).size() == 1);
		String paste = "a".repeat(20 * 1024);

		client.session.sendMessage(new BinaryMessage(paste.getBytes(StandardCharsets.UTF_8)));

		await().atMost(Duration.ofSeconds(5)).until(() -> cluster.shells(id).getFirst().typed().length() == paste.length());
		assertThat(client.session.isOpen()).isTrue();
	}

	@Test
	void shellExitClosesWithExited() throws Exception {
		TestBrowser kim = login("kim");
		long id = session("kim", "READY");
		Client client = connect(kim, id);
		await().atMost(Duration.ofSeconds(5)).until(() -> cluster.shells(id).size() == 1);

		cluster.shells(id).getFirst().exit();

		assertThat(client.awaitClose()).isEqualTo(new CloseStatus(1000, "EXITED"));
	}

	@Test
	void closingTerminalClosesShell() throws Exception {
		TestBrowser kim = login("kim");
		long id = session("kim", "READY");
		Client client = connect(kim, id);
		await().atMost(Duration.ofSeconds(5)).until(() -> cluster.shells(id).size() == 1);

		client.session.close();

		await().atMost(Duration.ofSeconds(5)).until(() -> cluster.shells(id).getFirst().isClosed());
	}

	@Test
	void anonymousIsRejectedBeforeUpgrade() {
		assertThatThrownBy(() -> connect(TestBrowser.anonymous(port), 1)).hasStackTraceContaining("401");
	}

	/** 다른 사이트가 학습자 브라우저(cookie)로 터미널을 열 수 없다 */
	@Test
	void otherOriginIsRejectedBeforeUpgrade() throws Exception {
		TestBrowser kim = login("kim");
		long id = session("kim", "READY");

		assertThatThrownBy(() -> connect(kim, "/api/labs/" + id + "/terminal", "http://evil.example"))
			.hasStackTraceContaining("403");
		assertThat(cluster.shells(id)).isEmpty();
	}

	@Test
	void otherLearnersOrUnknownSessionIsNotFound() throws Exception {
		login("lee");
		long leeSession = session("lee", "READY");
		TestBrowser kim = login("kim");

		assertThat(connect(kim, leeSession).awaitClose()).isEqualTo(new CloseStatus(4404, "LAB_NOT_FOUND"));
		assertThat(connect(kim, leeSession + 999).awaitClose().getCode()).isEqualTo(4404);
		assertThat(connect(kim, "/api/labs/abc/terminal", "http://localhost:" + port).awaitClose().getCode())
			.isEqualTo(4404);
		assertThat(cluster.shells(leeSession)).isEmpty();
	}

	@Test
	void sessionNotReadyIsRejected() throws Exception {
		TestBrowser kim = login("kim");
		long id = session("kim", "INITIALIZING");

		assertThat(connect(kim, id).awaitClose()).isEqualTo(new CloseStatus(4409, "LAB_NOT_READY"));
	}

	@Test
	void fourthTerminalIsRejected() throws Exception {
		TestBrowser kim = login("kim");
		long id = session("kim", "READY");
		for (int i = 0; i < 3; i++) {
			connect(kim, id);
		}
		await().atMost(Duration.ofSeconds(5)).until(() -> cluster.shells(id).size() == 3);

		assertThat(connect(kim, id).awaitClose()).isEqualTo(new CloseStatus(4429, "TERMINAL_LIMIT"));
		assertThat(cluster.shells(id)).hasSize(3);
	}

	/** 세션이 끝나면(종료, idle, 비정상 종료) 열린 터미널을 이유와 함께 닫는다 */
	@Test
	void endedSessionClosesTerminals() throws Exception {
		TestBrowser kim = login("kim");
		long id = session("kim", "READY");
		Client client = connect(kim, id);
		await().atMost(Duration.ofSeconds(5)).until(() -> cluster.shells(id).size() == 1);

		jdbcTemplate.update("update lab_sessions set status = 'TERMINATING', end_reason = 'USER' where id = ?", id);
		terminalHandler.closeEndedTerminals();

		assertThat(client.awaitClose()).isEqualTo(new CloseStatus(4410, "LAB_ENDED"));
		assertThat(cluster.shells(id).getFirst().isClosed()).isTrue();
	}

	@Test
	void shellOpenFailureIsInternalError() throws Exception {
		TestBrowser kim = login("kim");
		long id = session("kim", "READY");
		cluster.failShell(true);

		assertThat(connect(kim, id).awaitClose()).isEqualTo(new CloseStatus(1011, "INTERNAL_ERROR"));
	}

}
