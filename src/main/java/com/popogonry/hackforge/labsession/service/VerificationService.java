package com.popogonry.hackforge.labsession.service;

import java.time.Clock;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.popogonry.hackforge.labsession.domain.LabSession;
import com.popogonry.hackforge.labsession.domain.LabStatus;
import com.popogonry.hackforge.labsession.domain.Verification;
import com.popogonry.hackforge.labsession.domain.VerificationType;
import com.popogonry.hackforge.labsession.repository.VerificationRepository;
import com.popogonry.hackforge.web.ApiException;
import com.popogonry.hackforge.web.ErrorCode;

import lombok.RequiredArgsConstructor;

/** 공격 검증 접수와 조회(ADR 023). 실행은 {@link VerificationRunner}가 commit 뒤에 한다 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VerificationService {

	private final LabSessionService labSessionService;

	private final VerificationRepository verificationRepository;

	private final VerificationRunner verificationRunner;

	private final ActivityTracker activityTracker;

	private final Clock clock;

	/**
	 * {@code RUNNING}으로 저장하고 바로 반환한다. 단계(ATTACK, DEFENSE)와 횟수는 묻지 않는다.
	 * 세션마다 실행 중 검증은 하나뿐이고 DB partial unique index가 막는다
	 */
	@Transactional
	public Verification requestAttackVerification(long labId, long userId) {
		LabSession lab = labSessionService.getLab(labId, userId);
		if (lab.getStatus() != LabStatus.READY) {
			throw new ApiException(ErrorCode.LAB_NOT_READY);
		}
		Verification verification;
		try {
			verification = verificationRepository.saveAndFlush(new Verification(labId, VerificationType.ATTACK, clock.instant()));
		}
		catch (DataIntegrityViolationException e) {
			throw new ApiException(ErrorCode.LAB_JOB_RUNNING);
		}
		activityTracker.record(labId);
		long verificationId = verification.getId();
		String script = lab.getChallenge().getAttackCheck();
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

			@Override
			public void afterCommit() {
				verificationRunner.start(verificationId, labId, script);
			}

		});
		return verification;
	}

	public List<Verification> listVerifications(long labId, long userId) {
		labSessionService.getLab(labId, userId);
		return verificationRepository.findBySessionIdOrderByStartedAtDescIdDesc(labId);
	}

	public Verification getVerification(long labId, long verificationId, long userId) {
		labSessionService.getLab(labId, userId);
		return verificationRepository.findByIdAndSessionId(verificationId, labId)
			.orElseThrow(() -> new ApiException(ErrorCode.VERIFICATION_NOT_FOUND));
	}

}
