package com.popogonry.hackforge.labsession.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import com.popogonry.hackforge.TestcontainersConfiguration;
import com.popogonry.hackforge.challenge.TestChallenges;
import com.popogonry.hackforge.labsession.cluster.LabCluster;
import com.popogonry.hackforge.labsession.service.LabReconciler;
import com.popogonry.hackforge.user.TestBrowser;

/**
 * ADR 022. 개발 클러스터의 실제 Pod로 공격 대상 페이지를 gateway로 받고, 터미널에서 명령을 실행하고, 실습을 끝내면 터미널이 닫히는지 본다.
 * {@code ./gradlew clusterTest}로만 실행한다. 필요한 것은 {@code KubernetesLabClusterTests}와 같다.
 * 예약 실행은 끄고 reconciler와 터미널 정리를 직접 부른다.
 */
@Tag("cluster")
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "hackforge.lab.reconciler.enabled=false")
class LabConnectClusterTests {

	private static final String TEST_IMAGE = "hackforge/lab-test-challenge:0.1.0";

	/** 개발 DB 세션 id, 다른 클러스터 테스트와 겹치지 않게 큰 값부터 */
	private static final long FIRST_ID = 900_000_201L;

	static final int TARGET_PORT = freePort();

	@DynamicPropertySource
	static void targetPort(DynamicPropertyRegistry registry) {
		registry.add("hackforge.lab.target-port", () -> TARGET_PORT);
	}

	@LocalServerPort
	int port;

	@Autowired
	LabReconciler labReconciler;

	@Autowired
	LabCluster labCluster;

	@Autowired
	TerminalHandler terminalHandler;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	PasswordEncoder passwordEncoder;

	long id;

	static int freePort() {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("delete from lab_sessions");
		jdbcTemplate.update("delete from challenges");
		jdbcTemplate.update("delete from spring_session");
		jdbcTemplate.update("delete from users");
		jdbcTemplate.execute("alter table lab_sessions alter column id restart with " + FIRST_ID);
	}

	@AfterEach
	void tearDown() throws InterruptedException {
		labCluster.deleteNamespace(id);
		for (int i = 0; i < 120 && labCluster.namespaceExists(id); i++) {
			Thread.sleep(1000);
		}
	}

	static class Client extends AbstractWebSocketHandler {

		final StringBuilder output = new StringBuilder();

		final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();

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

	}

	@Test
	void targetPageTerminalAndEndOnRealPod() throws Exception {
		TestBrowser kim = TestBrowser.login(port, jdbcTemplate, passwordEncoder, "kim");
		long challengeId = TestChallenges.insert(jdbcTemplate, "test-web", "테스트", TEST_IMAGE);
		id = jdbcTemplate.queryForObject("""
				insert into lab_sessions (user_id, challenge_id, image, status, prep_step, status_changed_at, created_at)
				select id, ?, ?, 'CREATING', 'SPACE', ?, ? from users where nickname = 'kim' returning id
				""", Long.class, challengeId, TEST_IMAGE, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));
		Instant start = Instant.now();
		while (!"READY".equals(status()) && Duration.between(start, Instant.now()).toMinutes() < 3) {
			labReconciler.reconcileAll();
			Thread.sleep(1000);
		}
		assertThat(status()).as("3분 안에 READY").isEqualTo("READY");

		// 공격 대상: 세션 응답의 targetUrl로 gateway를 거쳐 Pod 안 웹 서비스가 열린다
		String targetUrl = kim.platform("GET", "/api/labs/" + id, null).body().replaceAll(".*\"targetUrl\":\"([^\"]+)\".*", "$1");
		assertThat(targetUrl).isEqualTo("http://localhost:" + TARGET_PORT + "/");
		HttpResponse<String> page = kim.http().send(HttpRequest.newBuilder(URI.create(targetUrl)).build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(page.statusCode()).isEqualTo(200);
		assertThat(page.headers().allValues("Content-Security-Policy")).isNotEmpty();

		// 터미널: /app의 sh에서 명령을 실행한다
		WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
		headers.add("Cookie", "SESSION=" + kim.cookie("SESSION"));
		headers.setOrigin("http://localhost:" + port);
		Client client = new Client();
		WebSocketSession socket = new StandardWebSocketClient()
			.execute(client, headers, URI.create("ws://localhost:" + port + "/api/labs/" + id + "/terminal"))
			.get(10, TimeUnit.SECONDS);
		socket.sendMessage(new BinaryMessage("echo \"$(pwd) hf-$((40+2))\"\r".getBytes(StandardCharsets.UTF_8)));
		Instant deadline = Instant.now().plusSeconds(20);
		while (!client.output().contains("/app hf-42") && Instant.now().isBefore(deadline)) {
			Thread.sleep(200);
		}
		assertThat(client.output()).contains("/app hf-42");

		// 실습을 끝내면 열린 터미널이 이유와 함께 닫힌다
		assertThat(kim.platform("DELETE", "/api/labs/" + id, null).statusCode()).isEqualTo(202);
		labReconciler.reconcileAll();
		terminalHandler.closeEndedTerminals();
		assertThat(client.closed.get(20, TimeUnit.SECONDS)).isEqualTo(new CloseStatus(4410, "LAB_ENDED"));
	}

	private String status() {
		return jdbcTemplate.queryForObject("select status from lab_sessions where id = ?", String.class, id);
	}

}
