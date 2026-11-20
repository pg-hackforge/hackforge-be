package com.popogonry.hackforge.labsession.cluster;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** stream을 끝까지 읽으면서 마지막 {@code limit} 바이트만 남긴다. limit이 0이면 버리기만 한다 */
final class TailBuffer {

	private final int limit;

	private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

	TailBuffer(int limit) {
		this.limit = limit;
	}

	void readFrom(InputStream in) {
		byte[] chunk = new byte[8192];
		try (in) {
			int n;
			while ((n = in.read(chunk)) != -1) {
				append(chunk, n);
			}
		}
		catch (IOException ignored) {
			// 연결이 끊기면 그때까지 읽은 것으로 끝낸다
		}
	}

	private synchronized void append(byte[] chunk, int n) {
		if (limit == 0) {
			return;
		}
		buffer.write(chunk, 0, n);
		if (buffer.size() > limit * 2) {
			byte[] all = buffer.toByteArray();
			buffer.reset();
			buffer.write(all, all.length - limit, limit);
		}
	}

	synchronized String text() {
		byte[] all = buffer.toByteArray();
		int from = Math.max(0, all.length - limit);
		return new String(all, from, all.length - from, StandardCharsets.UTF_8);
	}

}
