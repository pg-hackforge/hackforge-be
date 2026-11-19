package com.popogonry.hackforge.labsession.cluster;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import io.kubernetes.client.Exec;

/** {@code kubectl exec -it}와 같은 exec(TTY) 연결. 터미널 크기는 exec의 resize 채널로 보낸다 */
final class KubernetesShell implements LabShell {

	private final Exec.ExecProcess process;

	KubernetesShell(Exec.ExecProcess process) {
		this.process = process;
	}

	@Override
	public OutputStream input() {
		return process.getOutputStream();
	}

	@Override
	public InputStream output() {
		return process.getInputStream();
	}

	@Override
	public void resize(int cols, int rows) throws IOException {
		process.resize(cols, rows);
	}

	@Override
	public void close() {
		process.destroy();
	}

}
