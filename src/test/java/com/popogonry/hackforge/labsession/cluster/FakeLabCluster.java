package com.popogonry.hackforge.labsession.cluster;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 메모리에만 상태를 두는 {@link LabCluster}. Pod 상태는 테스트가 {@link #setPodStatus}로 바꾼다.
 * 삭제는 바로 끝난 것으로 처리한다. Spring context에 하나만 있으므로 테스트마다 {@link #reset}한다.
 */
public class FakeLabCluster implements LabCluster {

	private final Set<Long> namespaces = new HashSet<>();

	private final Map<Long, LabPodStatus> pods = new HashMap<>();

	private final Map<Long, String> images = new HashMap<>();

	private final Map<Long, RuntimeException> failures = new HashMap<>();

	private Runnable onCreatePod = () -> {
	};

	@Override
	public void createNamespace(long sessionId) {
		throwIfFailing(sessionId);
		namespaces.add(sessionId);
	}

	@Override
	public void createPod(long sessionId, String image) {
		throwIfFailing(sessionId);
		if (!namespaces.contains(sessionId)) {
			throw new IllegalStateException("namespace 없음: " + sessionId);
		}
		if (pods.putIfAbsent(sessionId, LabPodStatus.STARTING) == null) {
			images.put(sessionId, image);
		}
		onCreatePod.run();
	}

	@Override
	public LabPodStatus getPodStatus(long sessionId) {
		throwIfFailing(sessionId);
		return pods.getOrDefault(sessionId, LabPodStatus.ABSENT);
	}

	@Override
	public void deletePod(long sessionId) {
		throwIfFailing(sessionId);
		pods.remove(sessionId);
	}

	@Override
	public void deleteNamespace(long sessionId) {
		throwIfFailing(sessionId);
		namespaces.remove(sessionId);
		pods.remove(sessionId);
	}

	@Override
	public boolean namespaceExists(long sessionId) {
		throwIfFailing(sessionId);
		return namespaces.contains(sessionId);
	}

	public void setPodStatus(long sessionId, LabPodStatus status) {
		pods.put(sessionId, status);
	}

	/** 마지막으로 만든 Pod의 image. 한 번도 만들지 않았으면 null */
	public String podImage(long sessionId) {
		return images.get(sessionId);
	}

	/** 이 세션에 대한 모든 호출이 예외를 던진다(API server 오류 흉내). null이면 해제 */
	public void failWith(long sessionId, RuntimeException failure) {
		failures.put(sessionId, failure);
	}

	/** Pod를 만든 직후 실행할 동작. 그 사이에 다른 요청이 세션을 바꾸는 상황을 만들 때 쓴다 */
	public void onCreatePod(Runnable action) {
		this.onCreatePod = action;
	}

	public void reset() {
		namespaces.clear();
		pods.clear();
		images.clear();
		failures.clear();
		onCreatePod = () -> {
		};
	}

	private void throwIfFailing(long sessionId) {
		RuntimeException failure = failures.get(sessionId);
		if (failure != null) {
			throw failure;
		}
	}

}
