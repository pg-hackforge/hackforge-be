package com.popogonry.hackforge.labsession.cluster;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import io.kubernetes.client.openapi.ApiException;

/**
 * 메모리에만 상태를 두는 {@link LabCluster}. Pod 상태는 테스트가 {@link #setPodStatus}로 바꾼다.
 * 삭제는 바로 끝난 것으로 처리한다({@link #holdNamespaceDeletion}으로 미룰 수 있음). Spring context에 하나만 있으므로 테스트마다 {@link #reset}한다.
 */
public class FakeLabCluster implements LabCluster {

	private final Set<Long> namespaces = new HashSet<>();

	private final Map<Long, LabPodStatus> pods = new HashMap<>();

	private final Map<Long, String> images = new HashMap<>();

	private final Map<Long, RuntimeException> failures = new HashMap<>();

	private final Map<Long, String> logs = new HashMap<>();

	private final Map<Long, RuntimeException> logFailures = new HashMap<>();

	private final Map<Long, Integer> targetPorts = new java.util.concurrent.ConcurrentHashMap<>();

	private final Set<Long> closedTargets = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private final Map<Long, java.util.List<FakeShell>> shells = new java.util.concurrent.ConcurrentHashMap<>();

	private volatile boolean shellFailure;

	private Runnable onCreatePod = () -> {
	};

	private boolean holdNamespaceDeletion;

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

	/** 로그를 넣지 않았으면 빈 문자열 */
	@Override
	public String readPodLog(long sessionId) {
		throwIfFailing(sessionId);
		RuntimeException failure = logFailures.get(sessionId);
		if (failure != null) {
			throw failure;
		}
		return logs.getOrDefault(sessionId, "");
	}

	/** {@link #setTargetPort}로 정한 포트. 정하지 않았으면 연결 실패 */
	@Override
	public int targetPort(long sessionId) {
		Integer port = targetPorts.get(sessionId);
		if (port == null) {
			throw new LabClusterException(new ApiException(500, "port-forward 실패"));
		}
		return port;
	}

	@Override
	public void closeTarget(long sessionId) {
		closedTargets.add(sessionId);
	}

	/** 연 셸은 {@link #shells}로 꺼내 쓴다 */
	@Override
	public LabShell openShell(long sessionId) {
		throwIfFailing(sessionId);
		if (shellFailure) {
			throw new LabClusterException(new ApiException(500, "exec 실패"));
		}
		FakeShell shell = new FakeShell();
		shells.computeIfAbsent(sessionId, id -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(shell);
		return shell;
	}

	/** 이 세션에 연 셸들. 연 순서대로 */
	public java.util.List<FakeShell> shells(long sessionId) {
		return shells.getOrDefault(sessionId, java.util.List.of());
	}

	/** true면 셸 열기가 실패한다 */
	public void failShell(boolean fail) {
		this.shellFailure = fail;
	}

	@Override
	public void deletePod(long sessionId) {
		throwIfFailing(sessionId);
		pods.remove(sessionId);
		logs.remove(sessionId);
	}

	@Override
	public void deleteNamespace(long sessionId) {
		throwIfFailing(sessionId);
		if (holdNamespaceDeletion) {
			return;
		}
		namespaces.remove(sessionId);
		pods.remove(sessionId);
	}

	@Override
	public boolean namespaceExists(long sessionId) {
		throwIfFailing(sessionId);
		return namespaces.contains(sessionId);
	}

	@Override
	public Set<Long> listSessionIds() {
		return new TreeSet<>(namespaces);
	}

	public void setPodStatus(long sessionId, LabPodStatus status) {
		pods.put(sessionId, status);
	}

	/** 지금까지 Pod가 출력한 로그 전체 */
	public void setPodLog(long sessionId, String log) {
		logs.put(sessionId, log);
	}

	/** 로그 읽기만 403으로 실패한다(RoleBinding 권한 반영 전 흉내) */
	public void failPodLog(long sessionId) {
		logFailures.put(sessionId, new LabClusterException(new ApiException(403, "pods \"lab\" is forbidden")));
	}

	/** 마지막으로 만든 Pod의 image. 한 번도 만들지 않았으면 null */
	/** 공격 대상 연결이 이 포트(테스트의 가짜 공격 대상 앱)로 이어진다 */
	public void setTargetPort(long sessionId, int port) {
		targetPorts.put(sessionId, port);
	}

	public boolean targetClosed(long sessionId) {
		return closedTargets.contains(sessionId);
	}

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

	/** true면 namespace 삭제 요청을 받아도 지우지 않는다(삭제에 시간이 걸리는 상황). false로 바꾼 뒤 다음 요청에서 지워진다 */
	public void holdNamespaceDeletion(boolean hold) {
		this.holdNamespaceDeletion = hold;
	}

	public void reset() {
		holdNamespaceDeletion = false;
		namespaces.clear();
		pods.clear();
		images.clear();
		failures.clear();
		logs.clear();
		logFailures.clear();
		targetPorts.clear();
		closedTargets.clear();
		shells.clear();
		shellFailure = false;
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
