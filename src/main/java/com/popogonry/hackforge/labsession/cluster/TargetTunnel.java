package com.popogonry.hackforge.labsession.cluster;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.kubernetes.client.PortForward.PortForwardResult;
import io.kubernetes.client.openapi.ApiException;

import lombok.extern.slf4j.Slf4j;

/**
 * {@code kubectl port-forward}처럼 백엔드 안 {@code 127.0.0.1}의 빈 포트를 열고, 들어온 TCP 연결마다 port-forward를 하나씩 열어
 * Pod 안 포트와 잇는다(ADR 022). 연결은 받은 쪽이 닫을 때까지 유지되므로 HTTP keep-alive 연결이 port-forward를 재사용한다.
 */
@Slf4j
final class TargetTunnel implements Closeable {

	/** Pod 안 포트로 port-forward를 연다 */
	@FunctionalInterface
	interface Forwarder {

		PortForwardResult open() throws ApiException, IOException;

	}

	private final long sessionId;

	private final int podPort;

	private final Forwarder forwarder;

	private final ServerSocket server;

	private final Set<Socket> connections = ConcurrentHashMap.newKeySet();

	TargetTunnel(long sessionId, int podPort, Forwarder forwarder) throws IOException {
		this.sessionId = sessionId;
		this.podPort = podPort;
		this.forwarder = forwarder;
		this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
		Thread.ofVirtual().name("target-tunnel-" + sessionId).start(this::accept);
	}

	int port() {
		return server.getLocalPort();
	}

	boolean isClosed() {
		return server.isClosed();
	}

	private void accept() {
		while (!server.isClosed()) {
			try {
				Socket socket = server.accept();
				connections.add(socket);
				Thread.ofVirtual().name("target-tunnel-" + sessionId + "-conn").start(() -> relay(socket));
			}
			catch (IOException e) {
				if (!server.isClosed()) {
					log.warn("실습 세션 {} 공격 대상 연결 받기 실패", sessionId, e);
				}
			}
		}
	}

	/** 한쪽이 끝나면 양쪽을 모두 닫는다 */
	private void relay(Socket socket) {
		PortForwardResult forward = null;
		try {
			forward = forwarder.open();
			PortForwardResult opened = forward;
			InputStream fromPod = opened.getInputStream(podPort);
			OutputStream toPod = opened.getOutboundStream(podPort);
			Thread.ofVirtual().start(() -> {
				try {
					copy(socket.getInputStream(), toPod);
				}
				catch (IOException ignored) {
					// 브라우저 쪽 연결이 끊김
				}
				finally {
					closeQuietly(opened);
				}
			});
			copy(fromPod, socket.getOutputStream());
		}
		catch (ApiException | IOException e) {
			log.info("실습 세션 {} port-forward 실패: {}", sessionId, e.getMessage());
		}
		finally {
			closeQuietly(socket);
			closeQuietly(forward);
			connections.remove(socket);
		}
	}

	/** port-forward 쪽 stream은 쓴 만큼 바로 보내도록 매번 flush한다 */
	private static void copy(InputStream in, OutputStream out) throws IOException {
		byte[] buffer = new byte[16 * 1024];
		int n;
		while ((n = in.read(buffer)) != -1) {
			out.write(buffer, 0, n);
			out.flush();
		}
	}

	@Override
	public void close() {
		closeQuietly(server);
		connections.forEach(TargetTunnel::closeQuietly);
	}

	private static void closeQuietly(Closeable closeable) {
		if (closeable == null) {
			return;
		}
		try {
			closeable.close();
		}
		catch (IOException ignored) {
			// 닫는 중 오류는 무시한다
		}
	}

}
