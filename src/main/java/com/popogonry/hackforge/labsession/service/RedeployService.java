package com.popogonry.hackforge.labsession.service;

import java.time.Clock;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.popogonry.hackforge.labsession.cluster.LabCluster;
import com.popogonry.hackforge.labsession.domain.LabPrepStep;
import com.popogonry.hackforge.labsession.domain.LabSession;
import com.popogonry.hackforge.labsession.domain.LabStage;
import com.popogonry.hackforge.labsession.domain.LabStatus;
import com.popogonry.hackforge.labsession.domain.Redeploy;
import com.popogonry.hackforge.labsession.domain.RedeployKind;
import com.popogonry.hackforge.labsession.domain.ResetScope;
import com.popogonry.hackforge.labsession.repository.LabSessionRepository;
import com.popogonry.hackforge.labsession.repository.SessionEventRepository;
import com.popogonry.hackforge.labsession.domain.LabEventActor;
import com.popogonry.hackforge.labsession.repository.RedeployRepository;
import com.popogonry.hackforge.web.ApiException;
import com.popogonry.hackforge.web.ErrorCode;

import lombok.RequiredArgsConstructor;

/** 재배포와 초기화(ADR 024). 재배포와 서비스 초기화의 실행은 {@link RedeployRunner}가 commit 뒤에 한다 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RedeployService {

	/** 초기화 결과. 서비스 초기화면 기록이 있고, 실습 환경 다시 만들기면 null */
	public record ResetResult(LabSession session, Redeploy redeploy) {
	}

	private final LabSessionService labSessionService;

	private final LabSessionRepository labSessionRepository;

	private final RedeployRepository redeployRepository;

	private final RedeployRunner redeployRunner;

	private final LabJobGuard labJobGuard;

	private final LabCluster labCluster;

	private final ActivityTracker activityTracker;

	private final SessionEventRepository sessionEventRepository;

	private final Clock clock;

	/** {@code READY}이고 방어 단계일 때만. 검증, 재배포, 초기화 중 하나가 실행 중이면 409 */
	@Transactional
	public Redeploy requestRedeploy(long id, long userId) {
		LabSession lab = labSessionService.getLab(id, userId);
		requireReady(lab);
		if (lab.getStage() != LabStage.DEFENSE) {
			throw new ApiException(ErrorCode.WRONG_LAB_STAGE);
		}
		labJobGuard.lockAndRequireIdle(id);
		return start(id, RedeployKind.REDEPLOY);
	}

	/**
	 * 단계와 상관없이 {@code READY}일 때. 처음부터({@code keepStage=false})면 단계와 건너뜀을 먼저 되돌린다.
	 * 서비스 초기화는 재배포처럼 기록하고, 실습 환경 다시 만들기는 Pod를 지우고 세션을 생성 단계로 되돌린다(reconciler가 새로 만듦)
	 */
	@Transactional
	public ResetResult resetLab(long id, long userId, ResetScope scope, boolean keepStage) {
		LabSession lab = labSessionService.getLab(id, userId);
		requireReady(lab);
		labJobGuard.lockAndRequireIdle(id);
		if (!keepStage) {
			labSessionRepository.restartStage(id, LabStage.ATTACK);
		}
		activityTracker.record(id);
		if (scope == ResetScope.SERVICES) {
			Redeploy redeploy = start(id, RedeployKind.RESET);
			return new ResetResult(labSessionService.getLab(id, userId), redeploy);
		}
		labCluster.deletePod(id);
		sessionEventRepository.setActor(LabEventActor.LEARNER.name(), String.valueOf(userId));
		labSessionRepository.recreate(id, LabStatus.READY, LabStatus.CREATING,
				LabPrepStep.SPACE, clock.instant());
		return new ResetResult(labSessionService.getLab(id, userId), null);
	}

	/** 관리자는 모든 세션의 기록을 본다(ADR 026) */
	public List<Redeploy> listRedeploys(long id, long userId, boolean admin) {
		labSessionService.getLabForRead(id, userId, admin);
		return redeployRepository.findBySessionIdOrderByStartedAtDescIdDesc(id);
	}

	public Redeploy getRedeploy(long id, long redeployId, long userId, boolean admin) {
		labSessionService.getLabForRead(id, userId, admin);
		return redeployRepository.findByIdAndSessionId(redeployId, id)
			.orElseThrow(() -> new ApiException(ErrorCode.REDEPLOY_NOT_FOUND));
	}

	private Redeploy start(long id, RedeployKind kind) {
		Redeploy redeploy = redeployRepository.saveAndFlush(new Redeploy(id, kind, clock.instant()));
		activityTracker.record(id);
		long redeployId = redeploy.getId();
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

			@Override
			public void afterCommit() {
				redeployRunner.start(redeployId, id, kind);
			}

		});
		return redeploy;
	}

	private static void requireReady(LabSession lab) {
		if (lab.getStatus() != LabStatus.READY) {
			throw new ApiException(ErrorCode.LAB_NOT_READY);
		}
	}

}
