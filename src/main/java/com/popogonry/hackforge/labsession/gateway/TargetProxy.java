package com.popogonry.hackforge.labsession.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.popogonry.hackforge.labsession.cluster.LabProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * 요청 하나를 공격 대상으로 넘기고 응답을 그대로 돌려준다(ADR 022). 본문은 메모리에 올리지 않고 흘려 보내며 redirect는 따라가지 않는다.
 *
 * <ul>
 * <li>넘기지 않는 것: 플랫폼 cookie({@code SESSION}, {@code XSRF-TOKEN}, 실습 표시 cookie), {@code X-XSRF-TOKEN}, hop-by-hop 헤더
 * <li>{@code Host}는 브라우저가 보낸 값 그대로 넘겨 앱이 만드는 절대 주소가 맞게 한다
 * <li>응답에서 지우는 것: 플랫폼 cookie 이름의 {@code Set-Cookie}, {@code X-Frame-Options}. 대신 {@code frame-ancestors}를 붙인다
 * <li>실습이 바뀌면(표시 cookie 값이 지금 세션이 아니면) 이전 실습의 cookie를 넘기지 않고 만료시킨다
 * </ul>
 */
@Slf4j
final class TargetProxy {

	static {
		// JDK HttpClient는 Host 헤더를 직접 정하지 못하게 막는다. 브라우저의 Host를 그대로 넘기려고 이 헤더만 풀어 준다.
		// HttpClient가 처음 쓰이기 전에 정해야 반영된다
		System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host");
	}

	/** 실습 표시 cookie. 값은 세션 id */
	static final String LAB_COOKIE = "HACKFORGE_TARGET_LAB";

	static final Set<String> PLATFORM_COOKIES = Set.of("SESSION", "XSRF-TOKEN", LAB_COOKIE);

	/** 넘기지 않는 요청 헤더(소문자). hop-by-hop 헤더와 HttpClient가 직접 정하는 헤더 */
	private static final Set<String> SKIPPED_REQUEST_HEADERS = Set.of("connection", "keep-alive", "proxy-authenticate",
			"proxy-authorization", "proxy-connection", "te", "trailer", "transfer-encoding", "upgrade", "content-length",
			"expect", "http2-settings", "host", "cookie", "x-xsrf-token");

	/** 돌려주지 않는 응답 헤더(소문자) */
	private static final Set<String> SKIPPED_RESPONSE_HEADERS = Set.of("connection", "keep-alive", "proxy-authenticate",
			"proxy-connection", "te", "trailer", "transfer-encoding", "upgrade", "x-frame-options", "set-cookie");

	private final LabProperties labProperties;

	private final HttpClient http = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1)
		.followRedirects(HttpClient.Redirect.NEVER)
		.build();

	TargetProxy(LabProperties labProperties) {
		this.labProperties = labProperties;
	}

	void forward(HttpServletRequest request, HttpServletResponse response, long sessionId, int port) throws IOException {
		List<String> cookies = cookies(request);
		boolean sameLab = cookies.contains(LAB_COOKIE + "=" + sessionId);
		List<String> forwardedCookies = new ArrayList<>();
		List<String> staleNames = new ArrayList<>();
		for (String cookie : cookies) {
			String name = cookieName(cookie);
			if (PLATFORM_COOKIES.contains(name)) {
				continue;
			}
			if (sameLab) {
				forwardedCookies.add(cookie);
			}
			else {
				staleNames.add(name);
			}
		}

		HttpResponse<InputStream> upstream;
		try {
			upstream = http.send(upstreamRequest(request, port, forwardedCookies), HttpResponse.BodyHandlers.ofInputStream());
		}
		catch (HttpTimeoutException e) {
			TargetPages.write(request, response, labProperties, TargetPages.TIMEOUT);
			return;
		}
		catch (IOException e) {
			log.info("실습 세션 {} 공격 대상 요청 실패: {}", sessionId, e.toString());
			TargetPages.write(request, response, labProperties, TargetPages.UNREACHABLE);
			return;
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			TargetPages.write(request, response, labProperties, TargetPages.UNREACHABLE);
			return;
		}

		response.setStatus(upstream.statusCode());
		upstream.headers().map().forEach((name, values) -> {
			if (!SKIPPED_RESPONSE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
				values.forEach(value -> response.addHeader(name, value));
			}
		});
		// 이전 실습 cookie 만료와 표시 cookie를 먼저 넣는다. 같은 이름을 앱이 새로 심으면 뒤에 오는 앱의 값이 이긴다
		if (!sameLab) {
			staleNames.forEach(name -> response.addHeader("Set-Cookie", name + "=; Max-Age=0; Path=/"));
			response.addHeader("Set-Cookie", LAB_COOKIE + "=" + sessionId + "; Path=/; HttpOnly; SameSite=Lax");
		}
		for (String setCookie : upstream.headers().allValues("Set-Cookie")) {
			if (!PLATFORM_COOKIES.contains(cookieName(setCookie))) {
				response.addHeader("Set-Cookie", setCookie);
			}
		}
		response.addHeader("Content-Security-Policy", TargetPages.frameAncestors(request, labProperties));
		try (InputStream body = upstream.body()) {
			body.transferTo(response.getOutputStream());
		}
	}

	private HttpRequest upstreamRequest(HttpServletRequest request, int port, List<String> cookies) throws IOException {
		String query = request.getQueryString();
		URI uri = URI.create("http://127.0.0.1:" + port + request.getRequestURI() + (query == null ? "" : "?" + query));
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
			.timeout(labProperties.targetTimeout())
			.method(request.getMethod(), body(request));
		for (String name : Collections.list(request.getHeaderNames())) {
			if (SKIPPED_REQUEST_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
				continue;
			}
			for (String value : Collections.list(request.getHeaders(name))) {
				builder.header(name, value);
			}
		}
		String host = request.getHeader("Host");
		if (host != null) {
			builder.header("Host", host);
		}
		if (!cookies.isEmpty()) {
			builder.header("Cookie", String.join("; ", cookies));
		}
		return builder.build();
	}

	/** 본문 길이를 알면 그 길이로, chunked면 길이 없이 흘려 보낸다 */
	private static HttpRequest.BodyPublisher body(HttpServletRequest request) throws IOException {
		long length = request.getContentLengthLong();
		String transferEncoding = request.getHeader("Transfer-Encoding");
		if (length <= 0 && (transferEncoding == null || !transferEncoding.toLowerCase(Locale.ROOT).contains("chunked"))) {
			return HttpRequest.BodyPublishers.noBody();
		}
		InputStream in = request.getInputStream();
		HttpRequest.BodyPublisher stream = HttpRequest.BodyPublishers.ofInputStream(() -> in);
		return length > 0 ? HttpRequest.BodyPublishers.fromPublisher(stream, length) : stream;
	}

	/** {@code Cookie} 헤더를 {@code 이름=값} 단위로 나눈다. 값은 바꾸지 않는다 */
	private static List<String> cookies(HttpServletRequest request) {
		List<String> cookies = new ArrayList<>();
		for (String header : Collections.list(request.getHeaders("Cookie"))) {
			for (String part : header.split(";")) {
				String cookie = part.strip();
				if (!cookie.isEmpty()) {
					cookies.add(cookie);
				}
			}
		}
		return cookies;
	}

	/** {@code 이름=값}이나 {@code Set-Cookie} 값에서 이름 */
	private static String cookieName(String cookie) {
		int eq = cookie.indexOf('=');
		return (eq < 0 ? cookie : cookie.substring(0, eq)).strip();
	}

}
