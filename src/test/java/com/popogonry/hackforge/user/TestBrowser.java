package com.popogonry.hackforge.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 실제 HTTP로 플랫폼(RANDOM_PORT)에 접속하는 브라우저. cookie를 저장하고 보낸다. cookie는 포트를 구분하지 않는다(RFC 6265).
 * MockMvc로는 다른 포트(공격 대상 포트)나 WebSocket을 확인할 수 없을 때 쓴다
 */
public final class TestBrowser {

	private final HttpClient http;

	private final CookieManager cookies;

	private final int platformPort;

	private TestBrowser(int platformPort) {
		this.platformPort = platformPort;
		this.cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
		this.http = HttpClient.newBuilder().cookieHandler(cookies).build();
	}

	public static TestBrowser anonymous(int platformPort) {
		return new TestBrowser(platformPort);
	}

	/** 승인된 학습자를 DB에 넣고 로그인한다 */
	public static TestBrowser login(int platformPort, JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder,
			String nickname) throws Exception {
		jdbcTemplate.update("""
				insert into users (email, password_hash, name, nickname, affiliation, role, status, privacy_agreed_at, created_at)
				values (?, ?, '홍길동', ?, '학교', 'LEARNER', 'APPROVED', now(), now())
				""", nickname + "@example.com", passwordEncoder.encode("Passw0rd!"), nickname);
		TestBrowser browser = new TestBrowser(platformPort);
		browser.platform("GET", "/api/me", null);
		HttpResponse<String> res = browser.platform("POST", "/api/auth/login", """
				{"email":"%s@example.com","password":"Passw0rd!"}""".formatted(nickname));
		assertThat(res.statusCode()).isEqualTo(200);
		// 로그인하면 CSRF token이 바뀐다
		browser.platform("GET", "/api/me", null);
		return browser;
	}

	public HttpClient http() {
		return http;
	}

	public String cookie(String name) {
		return cookies.getCookieStore()
			.getCookies()
			.stream()
			.filter(c -> c.getName().equals(name))
			.map(HttpCookie::getValue)
			.findFirst()
			.orElse(null);
	}

	/** 다른 사이트나 이전 실습이 남긴 것처럼 localhost에 cookie를 넣는다 */
	public void addCookie(String name, String value) {
		HttpCookie cookie = new HttpCookie(name, value);
		cookie.setPath("/");
		cookie.setVersion(0);
		cookies.getCookieStore().add(URI.create("http://localhost"), cookie);
	}

	/** 플랫폼 API. GET이 아니면 XSRF-TOKEN cookie 값을 헤더로 넣는다 */
	public HttpResponse<String> platform(String method, String path, String json) throws Exception {
		HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://localhost:" + platformPort + path));
		String xsrf = cookie("XSRF-TOKEN");
		if (xsrf != null && !method.equals("GET")) {
			req.header("X-XSRF-TOKEN", xsrf);
		}
		if (json != null) {
			req.header("Content-Type", "application/json");
		}
		req.method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
		return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
	}

}
