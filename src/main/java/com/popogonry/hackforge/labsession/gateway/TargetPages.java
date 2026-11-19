package com.popogonry.hackforge.labsession.gateway;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.popogonry.hackforge.labsession.cluster.LabProperties;

/** 공격 대상으로 넘기지 못할 때 iframe 안에 보여 주는 안내 페이지(ADR 022). 문구는 ADR 019 해요체 */
final class TargetPages {

	record Page(int status, String title, String message) {
	}

	static final Page NOT_LOGGED_IN = new Page(401, "로그인이 필요해요", "플랫폼에 로그인한 뒤 실습 화면에서 다시 열어 주세요.");

	static final Page NOT_READY = new Page(409, "실습 환경이 준비되지 않았어요", "실습을 시작하고 준비가 끝나면 다시 열어 주세요.");

	static final Page UNREACHABLE = new Page(502, "공격 대상에 연결하지 못했어요", "실습 환경의 서비스가 멈췄을 수 있어요. 잠시 뒤 다시 시도해 주세요.");

	static final Page TIMEOUT = new Page(504, "공격 대상이 응답하지 않아요", "응답을 기다리는 시간이 지났어요. 잠시 뒤 다시 시도해 주세요.");

	private TargetPages() {
	}

	static void write(HttpServletRequest request, HttpServletResponse response, LabProperties labProperties, Page page)
			throws IOException {
		response.setStatus(page.status());
		response.setContentType("text/html;charset=UTF-8");
		response.setHeader("Cache-Control", "no-store");
		response.setHeader("Content-Security-Policy", frameAncestors(request, labProperties));
		String html = """
				<!doctype html>
				<html lang="ko"><head><meta charset="utf-8"><title>%s</title></head>
				<body style="font-family:sans-serif;padding:2rem;color:#333"><h1 style="font-size:1.25rem">%s</h1><p>%s</p></body></html>
				""".formatted(page.title(), page.title(), page.message());
		response.getOutputStream().write(html.getBytes(StandardCharsets.UTF_8));
	}

	/** 플랫폼 화면(같은 호스트의 {@code web-port})에서만 iframe으로 띄울 수 있게 한다 */
	static String frameAncestors(HttpServletRequest request, LabProperties labProperties) {
		return "frame-ancestors " + request.getScheme() + "://" + request.getServerName() + ":" + labProperties.webPort();
	}

}
