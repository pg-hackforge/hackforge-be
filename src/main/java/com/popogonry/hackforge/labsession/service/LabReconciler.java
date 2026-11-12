package com.popogonry.hackforge.labsession.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.popogonry.hackforge.labsession.cluster.LabCluster;
import com.popogonry.hackforge.labsession.cluster.LabPodStatus;
import com.popogonry.hackforge.labsession.cluster.LabProperties;
import com.popogonry.hackforge.labsession.domain.LabEndReason;
import com.popogonry.hackforge.labsession.domain.LabSession;
import com.popogonry.hackforge.labsession.domain.LabStatus;
import com.popogonry.hackforge.labsession.repository.LabSessionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 진행 중인 세션을 하나씩 읽어 클러스터 상태에 맞춰 한 단계씩 진행한다(ADR 012). 클러스터를 호출하는 곳은 여기뿐이다.
 * 세션마다 transaction을 따로 쓰고, 한 세션의 예외는 로그만 남기고 다음 세션으로 넘어간다. 실패한 세션은 다음 주기에 다시 본다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LabReconciler {

	/** 생성 실패 후 다시 만드는 횟수 */
	private static final int MAX_RETRIES = 1;

	private final LabSessionRepository labSessionRepository;

	private final LabCluster labCluster;

	private final LabProperties labProperties;

	private final Clock clock;

	private final TransactionTemplate transactionTemplate;

	public void reconcileAll() {
		for (long id : labSessionRepository.findIdsByStatusNotIn(LabStatus.ENDED)) {
			try {
				transactionTemplate.executeWithoutResult(tx -> reconcile(id));
			}
			catch (ObjectOptimisticLockingFailureException e) {
				// 읽은 뒤 종료 요청 등이 먼저 저장됨. 바뀐 상태로 다음 주기에 다시 본다
				log.info("실습 세션 {}: 다른 요청이 먼저 바꿔서 이번 주기는 건너뜀", id);
			}
			catch (RuntimeException e) {
				log.warn("실습 세션 {} 처리 실패. 다음 주기에 다시 시도", id, e);
			}
		}
	}

	/**
	 * 진행 중 세션이 없는 lab namespace를 지운다(끝난 세션에 남은 것, 탈퇴로 세션 행이 지워진 것). namespace 목록을 DB보다 먼저 읽는다.
	 * namespace는 세션 행이 commit된 뒤에만 만들어지므로, 목록에 있는 namespace의 세션은 뒤에 읽는 DB에 반드시 있다.
	 */
	public void deleteOrphans() {
		Set<Long> namespaces = labCluster.listSessionIds();
		List<Long> active = labSessionRepository.findIdsByStatusNotIn(LabStatus.ENDED);
		for (long id : namespaces) {
			if (!active.contains(id)) {
				log.info("실습 namespace lab-{}: 진행 중 세션이 없어서 삭제", id);
				labCluster.deleteNamespace(id);
			}
		}
	}

	private void reconcile(long id) {
		LabSession session = labSessionRepository.findById(id).orElse(null);
		if (session == null || LabStatus.ENDED.contains(session.getStatus())) {
			return;
		}
		Instant now = clock.instant();
		LabStatus before = session.getStatus();
		String reason = switch (before) {
			case CREATING -> creating(session, now);
			case INITIALIZING -> initializing(session, now);
			case READY -> ready(session, now);
			case TERMINATING -> terminating(session, now);
			case DELETED, FAILED -> null;
		};
		if (session.getStatus() != before) {
			log.info("실습 세션 {} (사용자 {}): {} -> {}, {}", id, session.getUserId(), before, session.getStatus(), reason);
		}
	}

	/** namespace와 Pod를 만든다. 이전 시도의 Pod가 남아 있으면 지워지기를 기다린다 */
	private String creating(LabSession session, Instant now) {
		long id = session.getId();
		if (timedOut(session, now)) {
			return fail(session, now, "start-timeout 초과");
		}
		labCluster.createNamespace(id);
		switch (labCluster.getPodStatus(id)) {
			case ABSENT -> {
				labCluster.createPod(id, session.getImage());
				session.podCreated(now);
			}
			// 이전 주기에 Pod를 만들고 DB 저장만 실패한 경우
			case STARTING, READY -> session.podCreated(now);
			case FAILED -> labCluster.deletePod(id);
			case DELETING -> {
			}
		}
		return "Pod 생성";
	}

	private String initializing(LabSession session, Instant now) {
		LabPodStatus pod = labCluster.getPodStatus(session.getId());
		if (pod == LabPodStatus.READY) {
			session.ready(now);
			return "Pod Ready";
		}
		if (pod == LabPodStatus.STARTING) {
			return timedOut(session, now) ? fail(session, now, "start-timeout 초과") : null;
		}
		return fail(session, now, "Pod " + pod);
	}

	/** 실습 중 Pod가 죽거나(메모리·디스크 초과 등) 사라짐 */
	private String ready(LabSession session, Instant now) {
		LabPodStatus pod = labCluster.getPodStatus(session.getId());
		if (pod == LabPodStatus.READY || pod == LabPodStatus.STARTING) {
			return null;
		}
		session.terminate(LabEndReason.POD_LOST, now);
		return "Pod " + pod;
	}

	/** namespace 삭제를 요청하고, 다 지워졌으면 끝낸다. 삭제 중에 다시 요청해도 API server는 성공으로 응답한다 */
	private String terminating(LabSession session, Instant now) {
		long id = session.getId();
		labCluster.deleteNamespace(id);
		if (!labCluster.namespaceExists(id)) {
			session.end(now);
			return "namespace 삭제 완료";
		}
		return null;
	}

	/** 첫 실패는 Pod만 지우고 다시 만들고, 재시도도 실패하면 환경을 지운 뒤 FAILED로 끝낸다 */
	private String fail(LabSession session, Instant now, String reason) {
		if (session.getStartAttempts() < MAX_RETRIES) {
			labCluster.deletePod(session.getId());
			session.retry(now);
			return reason + ", 재시도";
		}
		session.terminate(null, now);
		return reason + ", 재시도 후에도 실패";
	}

	private boolean timedOut(LabSession session, Instant now) {
		return session.getStatusChangedAt().plus(labProperties.startTimeout()).isBefore(now);
	}

}
