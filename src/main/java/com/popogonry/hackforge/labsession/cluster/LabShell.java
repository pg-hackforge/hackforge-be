package com.popogonry.hackforge.labsession.cluster;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** 실습 Pod에 exec(TTY)로 띄운 셸 하나(ADR 022). 셸이 끝나면 {@link #output()}이 끝난다 */
public interface LabShell extends Closeable {

	/** 셸 입력. 쓴 바이트가 그대로 TTY로 간다 */
	OutputStream input();

	/** 셸 출력. TTY라 stdout과 stderr가 섞여 온다 */
	InputStream output();

	/** 터미널 크기(글자 수) */
	void resize(int cols, int rows) throws IOException;

	/** 셸을 끝낸다. 이미 끝났으면 아무것도 하지 않는다 */
	@Override
	void close();

}
