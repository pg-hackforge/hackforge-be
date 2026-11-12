package com.popogonry.hackforge.user;

import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.http.Cookie;

import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import lombok.RequiredArgsConstructor;

/** 브라우저처럼 cookie를 들고 다니고, GET이 아닌 요청에는 XSRF-TOKEN cookie 값을 header로 넣는다. */
@RequiredArgsConstructor
public class ApiClient {

	private final MockMvc mockMvc;
	private final Map<String, String> cookies = new HashMap<>();

	public ResultActions get(String path) throws Exception {
		return send(MockMvcRequestBuilders.get(path));
	}

	public ResultActions post(String path, String json) throws Exception {
		if (!cookies.containsKey("XSRF-TOKEN")) {
			get("/api/me");
		}
		MockHttpServletRequestBuilder req = MockMvcRequestBuilders.post(path).header("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN"));
		if (json != null) {
			req.contentType(MediaType.APPLICATION_JSON).content(json);
		}
		return send(req);
	}

	ResultActions put(String path, String json) throws Exception {
		if (!cookies.containsKey("XSRF-TOKEN")) {
			get("/api/me");
		}
		return send(MockMvcRequestBuilders.put(path)
			.header("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN"))
			.contentType(MediaType.APPLICATION_JSON)
			.content(json));
	}

	/** CSRF header는 넣고, 본문과 Content-Type은 그대로 보낸다. */
	ResultActions post(String path, String body, String contentType) throws Exception {
		if (!cookies.containsKey("XSRF-TOKEN")) {
			get("/api/me");
		}
		return send(MockMvcRequestBuilders.post(path)
			.header("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN"))
			.contentType(contentType)
			.content(body));
	}

	/** CSRF header 없이 보낸다. */
	ResultActions postWithoutCsrf(String path, String json) throws Exception {
		return send(MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	public String cookie(String name) {
		return cookies.get(name);
	}

	public void setCookie(String name, String value) {
		cookies.put(name, value);
	}

	private ResultActions send(MockHttpServletRequestBuilder req) throws Exception {
		cookies.forEach((k, v) -> req.cookie(new Cookie(k, v)));
		ResultActions result = mockMvc.perform(req);
		remember(result.andReturn().getResponse());
		return result;
	}

	private void remember(MockHttpServletResponse res) {
		for (String header : res.getHeaders("Set-Cookie")) {
			String pair = header.split(";", 2)[0];
			int eq = pair.indexOf('=');
			String name = pair.substring(0, eq);
			String value = pair.substring(eq + 1);
			if (value.isEmpty() || header.contains("Max-Age=0")) {
				cookies.remove(name);
			} else {
				cookies.put(name, value);
			}
		}
	}

}
