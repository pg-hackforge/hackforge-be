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

	void deletePod(long sessionId);

	/** 삭제를 요청만 하고 기다리지 않는다. 끝났는지는 {@link #namespaceExists}로 확인한다. */
	void deleteNamespace(long sessionId);

	/** 삭제 중인 namespace도 있는 것으로 본다. */
	boolean namespaceExists(long sessionId);

	/** label {@code hackforge/lab=true}인 {@code lab-<세션 id>} namespace의 세션 id. 삭제 중인 것도 포함한다. */
	Set<Long> listSessionIds();

}
