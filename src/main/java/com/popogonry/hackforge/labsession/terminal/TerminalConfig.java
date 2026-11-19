package com.popogonry.hackforge.labsession.terminal;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import lombok.RequiredArgsConstructor;

/**
 * 웹 터미널 WebSocket(ADR 022, openapi {@code openLabTerminal}). 허용 origin을 정하지 않으면 Spring이 같은 origin만 받는다
 * (다른 사이트가 학습자 브라우저로 터미널을 여는 것을 막음, 403). 로그인 확인은 Spring Security가 upgrade 요청에서 한다(401)
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
class TerminalConfig implements WebSocketConfigurer {

	private final TerminalHandler terminalHandler;

	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		registry.addHandler(terminalHandler, "/api/labs/*/terminal");
	}

}
