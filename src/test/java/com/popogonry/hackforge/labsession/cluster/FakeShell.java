package com.popogonry.hackforge.labsession.cluster;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/** 메모리 셸. 테스트가 {@link #print}로 출력을 넣고 {@link #typed}로 받은 입력을 본다. {@link #exit}하면 출력이 끝난다 */
public class FakeShell implements LabShell {

	private static final byte[] EOF = new byte[0];

	private final BlockingQueue<byte[]> out = new LinkedBlockingQueue<>();

	private final ByteArrayOutputStream in = new ByteArrayOutputStream();

	private volatile String size;

	private volatile boolean closed;

	private final InputStream output = new InputStream() {

		private byte[] current = new byte[0];

		private int pos;

		@Override
		public int read() {
			byte[] one = new byte[1];
			return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
		}

		@Override
		public int read(byte[] b, int off, int len) {
			while (pos >= current.length) {
				try {
					current = out.take();
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return -1;
				}
				pos = 0;
				if (current == EOF) {
					out.add(EOF);
					return -1;
				}
			}
			int n = Math.min(len, current.length - pos);
			System.arraycopy(current, pos, b, off, n);
			pos += n;
			return n;
		}

	};

	private final OutputStream input = new OutputStream() {

		@Override
		public void write(int b) {
			synchronized (in) {
				in.write(b);
			}
		}

		@Override
		public void write(byte[] b, int off, int len) {
			synchronized (in) {
				in.write(b, off, len);
			}
		}

	};

	@Override
	public OutputStream input() {
		return input;
	}

	@Override
	public InputStream output() {
		return output;
	}

	@Override
	public void resize(int cols, int rows) {
		size = cols + "x" + rows;
	}

	@Override
	public void close() {
		closed = true;
		exit();
	}

	/** 셸이 이만큼 출력한 것처럼 한다 */
	public void print(String text) {
		out.add(text.getBytes(StandardCharsets.UTF_8));
	}

	/** 셸이 끝난 것처럼 출력을 닫는다 */
	public void exit() {
		out.add(EOF);
	}

	/** 지금까지 받은 입력 */
	public String typed() {
		synchronized (in) {
			return in.toString(StandardCharsets.UTF_8);
		}
	}

	/** 마지막으로 받은 터미널 크기({@code 120x30}). 받은 적 없으면 null */
	public String size() {
		return size;
	}

	public boolean isClosed() {
		return closed;
	}

}
