package com.popogonry.hackforge.labsession.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;

import com.popogonry.hackforge.TestcontainersConfiguration;
import com.popogonry.hackforge.challenge.TestChallenges;
import com.popogonry.hackforge.labsession.cluster.FakeLabCluster;
import com.popogonry.hackforge.labsession.cluster.LabCluster;
import com.popogonry.hackforge.labsession.service.ActivityTracker;
import com.sun.net.httpserver.HttpServer;

/**
 * 공격 대상 gateway(ADR 022). 플랫폼 포트로 로그인한 브라우저(cookie 저장소)로 공격 대상 포트에 요청한다.
 * 공격 대상 앱은 JDK 내장 HTTP 서버로 흉내 내고 받은 요청을 기록한다
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TargetGatewayTests {

	static final int TARGET_PORT = freePort();

	@DynamicPropertySource
	static void targetPort(DynamicPropertyRegistry registry) {
		registry.add("hackforge.lab.target-port", () -> TARGET_PORT);
		registry.add("hackforge.lab.target-timeout", () -> "1s");
	}

	@TestBean
	LabCluster labCluster;

	static LabCluster labCluster() {
		return new FakeLabCluster();
	}

	@LocalServerPort
	int platformPort;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	ActivityTracker activityTracker;

	HttpServer app;

	/** 공격 대상 앱이 마지막으로 받은 요청 */
	final Map<String, String> received = new ConcurrentHashMap<>();

	/** 공격 대상 앱이 돌려줄 응답 헤더 */
	final Map<String, List<String>> appHeaders = new ConcurrentHashMap<>();

	volatile int appStatus = 200;

	volatile Duration appDelay = Duration.ZERO;

	long challengeId;

	@BeforeEach
	void setUp() throws IOException {
		((FakeLabCluster) labCluster).reset();
		jdbcTemplate.update("delete from lab_sessions");
		jdbcTemplate.update("delete from challenges");
		jdbcTemplate.update("delete from spring_session");
		jdbcTemplate.update("delete from users");
		challengeId = TestChallenges.insert(jdbcTemplate, "test-web", "테스트", "x:1");
		app = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		app.createContext("/", exchange -> {
			received.put("method", exchange.getRequestMethod());
			received.put("uri", exchange.getRequestURI().toString());
			received.put("host", String.valueOf(exchange.getRequestHeaders().getFirst("Host")));
			received.put("cookie", String.valueOf(exchange.getRequestHeaders().getFirst("Cookie")));
			received.put("xsrf", String.valueOf(exchange.getRequestHeaders().getFirst("X-XSRF-TOKEN")));
			received.put("body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			try {
				Thread.sleep(appDelay);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			appHeaders.forEach((name, values) -> exchange.getResponseHeaders().put(name, values));
			byte[] body = "<title>공격 대상</title>".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(appStatus, appStatus == 302 ? -1 : body.length);
			if (appStatus != 302) {
				exchange.getResponseBody().write(body);
			}
			exchange.close();
		});
		app.start();
	}

	@AfterEach
	void tearDown() {
		app.stop(0);
	}

	static int freePort() {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	/** 쿠키를 저장하고 보내는 브라우저. 쿠키는 포트를 구분하지 않는다(RFC 6265) */
	record Browser(HttpClient http, CookieManager cookies) {

		String cookie(String name) {
			return cookies.getCookieStore()
				.getCookies()
				.stream()
				.filter(c -> c.getName().equals(name))
				.map(HttpCookie::getValue)
				.findFirst()
				.orElse(null);
		}

	}

	Browser browser() {
		CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
		return new Browser(HttpClient.newBuilder().cookieHandler(cookies).build(), cookies);
	}

	/** 승인된 학습자로 로그인한 브라우저 */
	Browser login(String nickname) throws Exception {
		jdbcTemplate.update("""
				insert into users (email, password_hash, name, nickname, affiliation, role, status, privacy_agreed_at, created_at)
				values (?, ?, '홍길동', ?, '학교', 'LEARNER', 'APPROVED', now(), now())
				""", nickname + "@example.com", passwordEncoder.encode("Passw0rd!"), nickname);
		Browser browser = browser();
		platform(browser, "GET", "/api/me", null);
		HttpResponse<String> res = platform(browser, "POST", "/api/auth/login", """
				{"email":"%s@example.com","password":"Passw0rd!"}""".formatted(nickname));
		assertThat(res.statusCode()).isEqualTo(200);
		return browser;
	}

	HttpResponse<String> platform(Browser browser, String method, String path, String json) throws Exception {
		HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://localhost:" + platformPort + path));
		String xsrf = browser.cookie("XSRF-TOKEN");
		if (xsrf != null) {
			req.header("X-XSRF-TOKEN", xsrf);
		}
		if (json != null) {
			req.header("Content-Type", "application/json");
		}
		req.method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
		return browser.http().send(req.build(), HttpResponse.BodyHandlers.ofString());
	}

	HttpResponse<String> target(Browser browser, String method, String path, String body) throws Exception {
		HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://localhost:" + TARGET_PORT + path))
			.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
		if (body != null) {
			req.header("Content-Type", "application/x-www-form-urlencoded");
		}
		return browser.http().send(req.build(), HttpResponse.BodyHandlers.ofString());
	}

	/** 그 학습자의 READY 세션. 공격 대상은 이 테스트의 가짜 앱 */
	long readySession(String nickname) {
		long id = jdbcTemplate.queryForObject("""
				insert into lab_sessions (user_id, challenge_id, image, status, status_changed_at, created_at, ready_at, last_activity_at)
				select id, ?, 'x:1', 'READY', now(), now(), now(), now() from users where nickname = ? returning id
				""", Long.class, challengeId, nickname);
		((FakeLabCluster) labCluster).setTargetPort(id, app.getAddress().getPort());
		return id;
	}

	@Test
	void forwardsPathQueryMethodBodyAndBrowserHost() throws Exception {
		Browser kim = login("kim");
		readySession("kim");

		HttpResponse<String> res = target(kim, "POST", "/login.php?next=%2F", "username=admin&password=x");

		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(res.body()).isEqualTo("<title>공격 대상</title>");
		assertThat(received).containsEntry("method", "POST")
			.containsEntry("uri", "/login.php?next=%2F")
			.containsEntry("body", "username=admin&password=x")
			.containsEntry("host", "localhost:" + TARGET_PORT);
	}

	/** 플랫폼 API 경로도 공격 대상으로 간다. 공격 대상 포트에서 플랫폼은 응답하지 않는다 */
	@Test
	void platformPathsAreForwardedToTarget() throws Exception {
		Browser kim = login("kim");
		readySession("kim");

		HttpResponse<String> res = target(kim, "GET", "/api/me", null);

		assertThat(res.body()).isEqualTo("<title>공격 대상</title>");
		assertThat(received).containsEntry("uri", "/api/me");
	}

	/** 공격 대상 앱의 폼은 플랫폼 CSRF 헤더 없이 동작해야 한다 */
	@Test
	void noCsrfCheckOnTargetPort() throws Exception {
		Browser kim = login("kim");
		readySession("kim");

		assertThat(target(kim, "POST", "/", "a=1").statusCode()).isEqualTo(200);
	}

	@Test
	void platformCookiesAndCsrfHeaderAreNotForwarded() throws Exception {
		Browser kim = login("kim");
		readySession("kim");
		target(kim, "GET", "/", null);
		kim.cookies().getCookieStore().add(URI.create("http://localhost"), cookie("PHPSESSID", "app1"));

		HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + TARGET_PORT + "/"))
			.header("X-XSRF-TOKEN", kim.cookie("XSRF-TOKEN"))
			.build();
		kim.http().send(req, HttpResponse.BodyHandlers.ofString());

		assertThat(received.get("cookie")).contains("PHPSESSID=app1")
			.doesNotContain("SESSION=")
			.doesNotContain("XSRF-TOKEN")
			.doesNotContain("HACKFORGE_TARGET_LAB");
		assertThat(received).containsEntry("xsrf", "null");
	}

	/** 공격 대상 앱은 플랫폼 cookie를 덮을 수 없고, iframe은 플랫폼 화면에서만 띄울 수 있다 */
	@Test
	void responseHeadersAreFiltered() throws Exception {
		Browser kim = login("kim");
		readySession("kim");
		String platformSession = kim.cookie("SESSION");
		appHeaders.put("Set-Cookie", List.of("SESSION=evil; Path=/", "XSRF-TOKEN=evil; Path=/", "PHPSESSID=app1; Path=/"));
		appHeaders.put("X-Frame-Options", List.of("DENY"));

		HttpResponse<String> res = target(kim, "GET", "/", null);

		assertThat(res.headers().allValues("Set-Cookie")).noneMatch(c -> c.startsWith("SESSION=") || c.startsWith("XSRF-TOKEN="))
			.anyMatch(c -> c.startsWith("PHPSESSID=app1"));
		assertThat(kim.cookie("SESSION")).isEqualTo(platformSession);
		assertThat(res.headers().firstValue("X-Frame-Options")).isEmpty();
		assertThat(res.headers().allValues("Content-Security-Policy")).contains("frame-ancestors http://localhost:5173");
	}

	/** 실습이 바뀌면 이전 실습의 공격 대상 cookie를 지우고 넘기지 않는다 */
	@Test
	void cookiesFromPreviousLabAreCleared() throws Exception {
		Browser kim = login("kim");
		kim.cookies().getCookieStore().add(URI.create("http://localhost"), cookie("PHPSESSID", "old"));
		long id = readySession("kim");

		HttpResponse<String> first = target(kim, "GET", "/", null);

		assertThat(received.get("cookie")).doesNotContain("PHPSESSID");
		assertThat(first.headers().allValues("Set-Cookie")).anyMatch(c -> c.startsWith("PHPSESSID=;") && c.contains("Max-Age=0"))
			.anyMatch(c -> c.startsWith("HACKFORGE_TARGET_LAB=" + id + ";"));

		kim.cookies().getCookieStore().add(URI.create("http://localhost"), cookie("PHPSESSID", "new"));
		target(kim, "GET", "/", null);
		assertThat(received.get("cookie")).contains("PHPSESSID=new");
	}

	/** redirect는 따라가지 않고 그대로 브라우저에 준다 */
	@Test
	void redirectIsPassedThrough() throws Exception {
		Browser kim = login("kim");
		readySession("kim");
		appStatus = 302;
		appHeaders.put("Location", List.of("/welcome.php"));

		HttpResponse<String> res = target(kim, "GET", "/", null);

		assertThat(res.statusCode()).isEqualTo(302);
		assertThat(res.headers().firstValue("Location")).hasValue("/welcome.php");
	}

	@Test
	void anonymousGetsLoginPage() throws Exception {
		HttpResponse<String> res = target(browser(), "GET", "/", null);

		assertThat(res.statusCode()).isEqualTo(401);
		assertThat(res.headers().firstValue("Content-Type")).hasValueSatisfying(t -> assertThat(t).startsWith("text/html"));
		assertThat(received).isEmpty();
	}

	/** 다른 학습자의 READY 세션이 있어도 자기 세션이 없으면 넘기지 않는다 */
	@Test
	void learnerWithoutReadySessionGetsNotReadyPage() throws Exception {
		login("kim");
		readySession("kim");
		Browser lee = login("lee");

		assertThat(target(lee, "GET", "/", null).statusCode()).isEqualTo(409);
		assertThat(received).isEmpty();
	}

	@Test
	void unreachableTargetIsBadGateway() throws Exception {
		Browser kim = login("kim");
		readySession("kim");
		app.stop(0);

		assertThat(target(kim, "GET", "/", null).statusCode()).isEqualTo(502);
	}

	@Test
	void slowTargetIsGatewayTimeout() throws Exception {
		Browser kim = login("kim");
		readySession("kim");
		appDelay = Duration.ofSeconds(3);

		assertThat(target(kim, "GET", "/", null).statusCode()).isEqualTo(504);
	}

	@Test
	void targetRequestIsActivity() throws Exception {
		Browser kim = login("kim");
		long id = readySession("kim");

		target(kim, "GET", "/", null);

		assertThat(activityTracker.latest(id)).isPresent();
	}

	static HttpCookie cookie(String name, String value) {
		HttpCookie cookie = new HttpCookie(name, value);
		cookie.setPath("/");
		cookie.setVersion(0);
		return cookie;
	}

}
