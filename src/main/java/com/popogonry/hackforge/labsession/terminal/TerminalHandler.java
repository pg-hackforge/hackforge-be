package com.popogonry.hackforge.labsession.terminal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import com.popogonry.hackforge.labsession.cluster.LabCluster;
import com.popogonry.hackforge.labsession.cluster.LabShell;
import com.popogonry.hackforge.labsession.domain.LabSession;
import com.popogonry.hackforge.labsession.domain.LabStatus;
import com.popogonry.hackforge.labsession.repository.LabSessionRepository;
import com.popogonry.hackforge.labsession.service.ActivityTracker;
import com.popogonry.hackforge.labsession.service.LabSessionService;
import com.popogonry.hackforge.web.ApiException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 웹 터미널 WebSocket 하나를 실습 Pod의 exec 셸 하나와 잇는다(ADR 022). 세션 확인 결과는 연결을 받은 뒤 닫는 코드로 알린다.
 * 브라우저 WebSocket API는 upgrade 거절 응답의 상태를 읽지 못해서다.
 * 클라이언트 binary 메시지는 셸 입력, text 메시지는 제어 JSON({@code {"type":"resize","cols":..,"rows":..}}), 셸 출력은 binary로 보낸다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class TerminalHandler extends AbstractWebSocketHandler {

	/** 세션 하나에 동시에 열 수 있는 터미널 수 */
	static final int MAX_PER_LAB = 3;

	static final CloseStatus EXITED = new CloseStatus(1000, "EXITED");

	static final CloseStatus LAB_NOT_FOUND = new CloseStatus(4404, "LAB_NOT_FOUND");

	static final CloseStatus LAB_NOT_READY = new CloseStatus(4409, "LAB_NOT_READY");

	static final CloseStatus LAB_ENDED = new CloseStatus(4410, "LAB_ENDED");

	static final CloseStatus TERMINAL_LIMIT = new CloseStatus(4429, "TERMINAL_LIMIT");

	static final CloseStatus INTERNAL_ERROR = new CloseStatus(1011, "INTERNAL_ERROR");

	private final LabSessionService labSessionService;

	private final LabSessionRepository labSessionRepository;

	private final ActivityTracker activityTracker;

	private final LabCluster labCluster;

	private final JsonMapper jsonMapper;

	/** WebSocket 세션 id → 열린 터미널. 셸을 여는 중이면 shell이 null */
	private final Map<String, Terminal> terminals = new ConcurrentHashMap<>();

	private record Terminal(long labId, WebSocketSession socket, LabShell shell) {
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession session) throws IOException {
		// 붙여넣기 같은 큰 입력은 64KB까지 한 메시지로 받는다. 제어 메시지(크기 변경)는 작다
		session.setBinaryMessageSizeLimit(64 * 1024);
		session.setTextMessageSizeLimit(4 * 1024);
		// 셸 출력 thread와 서버의 close가 동시에 보내도 되도록 감싼다. 느린 클라이언트는 10초, 512KB를 넘으면 끊는다
		WebSocketSession socket = new ConcurrentWebSocketSessionDecorator(session, 10_000, 512 * 1024);
		Long labId = labId(session);
		LabSession lab = labId == null ? null : find(labId, Long.parseLong(session.getPrincipal().getName()));
		if (lab == null) {
			socket.close(LAB_NOT_FOUND);
			return;
		}
		if (lab.getStatus() != LabStatus.READY) {
			socket.close(LAB_NOT_READY);
			return;
		}
		synchronized (terminals) {
			if (terminals.values().stream().filter(t -> t.labId() == labId).count() >= MAX_PER_LAB) {
				socket.close(TERMINAL_LIMIT);
				return;
			}
			terminals.put(session.getId(), new Terminal(labId, socket, null));
		}
		LabShell shell;
		try {
			shell = labCluster.openShell(labId);
		}
		catch (RuntimeException e) {
			log.warn("실습 세션 {} 터미널 연결 실패: {}", labId, e.getMessage());
			terminals.remove(session.getId());
			socket.close(INTERNAL_ERROR);
			return;
		}
		Terminal terminal = new Terminal(labId, socket, shell);
		terminals.put(session.getId(), terminal);
		Thread.ofVirtual().name("terminal-" + labId).start(() -> pump(terminal));
	}

	/** 셸 출력을 그대로 보낸다. 셸이 끝나면 세션이 아직 READY인지에 따라 닫는 코드를 고른다 */
	private void pump(Terminal terminal) {
		byte[] buffer = new byte[8 * 1024];
		try (InputStream output = terminal.shell().output()) {
			int n;
			while ((n = output.read(buffer)) != -1) {
				terminal.socket().sendMessage(new BinaryMessage(Arrays.copyOf(buffer, n)));
			}
		}
		catch (IOException | RuntimeException e) {
			log.debug("실습 세션 {} 터미널 출력 끝: {}", terminal.labId(), e.toString());
		}
		close(terminal, isReady(terminal.labId()) ? EXITED : LAB_ENDED);
	}

	@Override
	protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws IOException {
		Terminal terminal = terminals.get(session.getId());
		if (terminal == null || terminal.shell() == null) {
			return;
		}
		ByteBuffer payload = message.getPayload();
		byte[] bytes = new byte[payload.remaining()];
		payload.get(bytes);
		terminal.shell().input().write(bytes);
		terminal.shell().input().flush();
		activityTracker.record(terminal.labId());
	}

	/** 형식이 틀린 제어 메시지는 무시한다 */
	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage message) {
		Terminal terminal = terminals.get(session.getId());
		if (terminal == null || terminal.shell() == null) {
			return;
		}
		try {
			JsonNode control = jsonMapper.readTree(message.getPayload());
			int cols = control.path("cols").asInt(0);
			int rows = control.path("rows").asInt(0);
			if ("resize".equals(control.path("type").asString()) && cols >= 1 && cols <= 500 && rows >= 1 && rows <= 200) {
				terminal.shell().resize(cols, rows);
			}
		}
		catch (RuntimeException | IOException e) {
			log.debug("실습 세션 {} 터미널 제어 메시지 무시: {}", terminal.labId(), e.toString());
		}
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		Terminal terminal = terminals.remove(session.getId());
		if (terminal != null && terminal.shell() != null) {
			terminal.shell().close();
		}
	}

	/**
	 * 세션이 READY가 아니게 되면(종료, idle 종료, 실습 환경 비정상 종료) 그 세션의 터미널을 닫는다.
	 * reconciler와 같은 간격으로 돈다. 테스트는 직접 부른다
	 */
	@Scheduled(fixedDelayString = "${hackforge.lab.reconcile-interval}")
	void closeEndedTerminals() {
		List<Long> labIds = terminals.values().stream().map(Terminal::labId).distinct().toList();
		for (long labId : labIds) {
			if (!isReady(labId)) {
				terminals.values().stream().filter(t -> t.labId() == labId).forEach(t -> close(t, LAB_ENDED));
			}
		}
	}

	private void close(Terminal terminal, CloseStatus status) {
		try {
			terminal.socket().close(status);
		}
		catch (IOException e) {
			log.debug("터미널 닫기 실패: {}", e.toString());
		}
		if (terminal.shell() != null) {
			terminal.shell().close();
		}
	}

	private boolean isReady(long labId) {
		return labSessionRepository.findById(labId).map(s -> s.getStatus() == LabStatus.READY).orElse(false);
	}

	/** 남의 세션도 없는 세션과 같게 null */
	private LabSession find(long labId, long userId) {
		try {
			return labSessionService.getLab(labId, userId);
		}
		catch (ApiException e) {
			return null;
		}
	}

	/** {@code /api/labs/{id}/terminal}의 id. 숫자가 아니면 null */
	private static Long labId(WebSocketSession session) {
		String[] parts = session.getUri().getPath().split("/");
		try {
			return parts.length >= 4 ? Long.parseLong(parts[3]) : null;
		}
		catch (NumberFormatException e) {
			return null;
		}
	}

}
