package com.popogonry.hackforge.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import com.popogonry.hackforge.TestcontainersConfiguration;

/** cookie 속성은 MockMvc의 가짜 servlet container와 달라서 실제 서버로 확인한다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class SessionCookieTests {

	@LocalServerPort
	int port;

	final HttpClient http = HttpClient.newHttpClient();

	@Test
	void loginSetsHttpOnlySameSiteLaxSessionCookie() throws Exception {
		String xsrf = http.send(HttpRequest.newBuilder(uri("/api/me")).build(), BodyHandlers.discarding())
			.headers()
			.allValues("Set-Cookie")
			.stream()
			.filter(c -> c.startsWith("XSRF-TOKEN="))
			.findFirst()
			.orElseThrow()
			.split(";")[0]
			.substring("XSRF-TOKEN=".length());

		post(xsrf, "/api/auth/signup", AuthApiTests.signupJson("cookie@example.com", "cookie"));
		HttpResponse<Void> login = post(xsrf, "/api/auth/login",
				AuthApiTests.loginJson("cookie@example.com", "Passw0rd!"));

		assertThat(login.statusCode()).isEqualTo(200);
		List<String> session = login.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith("SESSION=")).toList();
		assertThat(session).singleElement().asString().contains("HttpOnly").contains("SameSite=Lax");
	}

	private HttpResponse<Void> post(String xsrf, String path, String json) throws Exception {
		HttpRequest req = HttpRequest.newBuilder(uri(path))
			.header("Content-Type", "application/json")
			.header("X-XSRF-TOKEN", xsrf)
			.header("Cookie", "XSRF-TOKEN=" + xsrf)
			.POST(BodyPublishers.ofString(json))
			.build();
		return http.send(req, BodyHandlers.discarding());
	}

	private URI uri(String path) {
		return URI.create("http://127.0.0.1:" + port + path);
	}

}
