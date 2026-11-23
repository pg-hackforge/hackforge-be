package com.popogonry.hackforge.labsession.cluster;

import java.util.Set;

/**
 * 세션마다 만드는 클러스터 오브젝트(ADR 013). namespace는 {@code lab-<세션 id>}, Pod 이름은 {@code lab}.
 * 모든 메서드는 멱등하다. 생성할 때 이미 있거나 삭제할 때 없으면 성공으로 처리한다(ADR 012).
 * 호출하는 곳은 reconciler 하나다.
 */
public interface LabCluster {

	/**
	 * namespace, NetworkPolicy {@code deny-all}, ResourceQuota {@code lab}, Pod 로그 읽기 RoleBinding {@code lab-log-reader},
	 * 공격 대상·터미널 연결 RoleBinding {@code lab-connect} 순서로 만든다.
	 */
	void createNamespace(long sessionId);

	void createPod(long sessionId, String image);

	LabPodStatus getPodStatus(long sessionId);

	/**
	 * Pod가 지금까지 출력한 로그를 처음부터 읽는다(크기 상한 있음). 준비 단계 판정에만 쓴다(ADR 021).
	 * 컨테이너가 시작 전이거나 권한이 아직 반영되지 않았으면 {@link LabClusterException}.
	 */
	String readPodLog(long sessionId);

	/**
	 * 세션 Pod 안 공격 대상 웹({@code 127.0.0.1:8080})으로 이어지는 백엔드 안 {@code 127.0.0.1}의 포트(ADR 022).
	 * 처음 부를 때 열고 그다음부터 재사용한다. 이 포트로 들어온 연결마다 port-forward를 하나씩 연다.
	 */
	int targetPort(long sessionId);

	/** {@link #targetPort}로 연 포트와 그 연결을 닫는다. 없으면 아무것도 하지 않는다 */
	void closeTarget(long sessionId);

	/** 실습 Pod에 exec(TTY)로 {@code /app}에서 {@code sh}를 띄운다(ADR 022). 연결하지 못하면 {@link LabClusterException} */
	LabShell openShell(long sessionId);

	/**
	 * 검증 스크립트 본문을 실습 Pod에 exec(TTY 없음)로 넘겨 {@code /app}에서 {@code sh}로 실행한다(ADR 014, ADR 023).
	 * 제한 시간을 넘기면 끝내고 {@code timedOut}을 표시한다. 실행하지 못하면 {@link LabClusterException}
	 */
	ScriptResult runScript(long sessionId, String script, java.time.Duration timeout);

	/** {@code /app} 아래 모든 파일. 경로 순(ADR 024) */
	java.util.List<PodFile> listFiles(long sessionId);

	/** {@code maxBytes}를 넘으면 {@code TOO_LARGE}로 내용을 보내지 않는다. 경로는 호출하는 쪽이 검사한다 */
	PodFileRead readFile(long sessionId, String path, int maxBytes);

	/** 원본({@code /hackforge/original})의 파일을 읽는다. 규칙은 {@link #readFile}과 같다(ADR 029) */
	PodFileRead readOriginalFile(long sessionId, String path, int maxBytes);

	/** {@code baseSha256}이 null이 아니고 지금 해시와 다르면 저장하지 않는다. 없던 파일이면 지금 해시는 빈 문자열이다 */
	PodFileWrite writeFile(long sessionId, String path, byte[] content, String baseSha256);

	/** 원본({@code /hackforge/original})으로 되돌리고 되돌린 내용을 준다. 원본에 없으면 {@code NOT_FOUND} */
	PodFileRead revertFile(long sessionId, String path);

	void deletePod(long sessionId);

	/** 삭제를 요청만 하고 기다리지 않는다. 끝났는지는 {@link #namespaceExists}로 확인한다. */
	void deleteNamespace(long sessionId);

	/** 삭제 중인 namespace도 있는 것으로 본다. */
	boolean namespaceExists(long sessionId);

	/** label {@code hackforge/lab=true}인 {@code lab-<세션 id>} namespace의 세션 id. 삭제 중인 것도 포함한다. */
	Set<Long> listSessionIds();

}
