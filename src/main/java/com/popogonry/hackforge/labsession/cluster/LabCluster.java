package com.popogonry.hackforge.labsession.cluster;

import java.util.Set;

/**
 * 세션마다 만드는 클러스터 오브젝트(ADR 013). namespace는 {@code lab-<세션 id>}, Pod 이름은 {@code lab}.
 * 모든 메서드는 멱등하다. 생성할 때 이미 있거나 삭제할 때 없으면 성공으로 처리한다(ADR 012).
 * 호출하는 곳은 reconciler 하나다.
 */
public interface LabCluster {

	/** namespace, NetworkPolicy {@code deny-all}, ResourceQuota {@code lab} 순서로 만든다. */
	void createNamespace(long sessionId);

	void createPod(long sessionId, String image);

	LabPodStatus getPodStatus(long sessionId);

	void deletePod(long sessionId);

	/** 삭제를 요청만 하고 기다리지 않는다. 끝났는지는 {@link #namespaceExists}로 확인한다. */
	void deleteNamespace(long sessionId);

	/** 삭제 중인 namespace도 있는 것으로 본다. */
	boolean namespaceExists(long sessionId);

	/** label {@code hackforge/lab=true}인 {@code lab-<세션 id>} namespace의 세션 id. 삭제 중인 것도 포함한다. */
	Set<Long> listSessionIds();

}
