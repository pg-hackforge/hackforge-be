package com.popogonry.hackforge.labsession.cluster;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 메모리에만 상태를 두는 {@link LabCluster}. Pod 상태는 테스트가 {@link #setPodStatus}로 바꾼다.
 * 삭제는 바로 끝난 것으로 처리한다.
 */
public class FakeLabCluster implements LabCluster {

	private final Set<Long> namespaces = new HashSet<>();

	private final Map<Long, LabPodStatus> pods = new HashMap<>();

	private final Map<Long, String> images = new HashMap<>();

	private RuntimeException failure;

	@Override
	public void createNamespace(long sessionId) {
		throwIfFailing();
		namespaces.add(sessionId);
	}

	@Override
	public void createPod(long sessionId, String image) {
		throwIfFailing();
		if (!namespaces.contains(sessionId)) {
			throw new IllegalStateException("namespace 없음: " + sessionId);
		}
		if (pods.putIfAbsent(sessionId, LabPodStatus.STARTING) == null) {
			images.put(sessionId, image);
		}
	}

	@Override
	public LabPodStatus getPodStatus(long sessionId) {
		throwIfFailing();
		return pods.getOrDefault(sessionId, LabPodStatus.ABSENT);
	}

	@Override
	public void deletePod(long sessionId) {
		throwIfFailing();
		pods.remove(sessionId);
		images.remove(sessionId);
	}

	@Override
	public void deleteNamespace(long sessionId) {
		throwIfFailing();
		namespaces.remove(sessionId);
		deletePod(sessionId);
	}

	@Override
	public boolean namespaceExists(long sessionId) {
		throwIfFailing();
		return namespaces.contains(sessionId);
	}

	public void setPodStatus(long sessionId, LabPodStatus status) {
		pods.put(sessionId, status);
	}

	/** 마지막으로 만든 Pod의 image. Pod가 없으면 null */
	public String podImage(long sessionId) {
		return images.get(sessionId);
	}

	/** null이 아니면 모든 호출이 이 예외를 던진다(API server 연결 실패 흉내) */
	public void failWith(RuntimeException failure) {
		this.failure = failure;
	}

	private void throwIfFailing() {
		if (failure != null) {
			throw failure;
		}
	}

}
