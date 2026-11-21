package com.popogonry.hackforge.labsession.service;

import org.springframework.stereotype.Component;

import com.popogonry.hackforge.labsession.domain.RedeployStatus;
import com.popogonry.hackforge.labsession.domain.VerificationStatus;
import com.popogonry.hackforge.labsession.repository.LabSessionRepository;
import com.popogonry.hackforge.labsession.repository.RedeployRepository;
import com.popogonry.hackforge.labsession.repository.VerificationRepository;
import com.popogonry.hackforge.web.ApiException;
import com.popogonry.hackforge.web.ErrorCode;

import lombok.RequiredArgsConstructor;

/**
 * 세션마다 검증, 재배포, 초기화 중 하나만 실행한다(ADR 024). 두 테이블에 걸친 규칙이라 세션 행을 잠그고 둘 다 확인한다.
 * 같은 세션의 새 작업 요청이 이 잠금에서 줄을 선다. 호출하는 쪽의 transaction 안에서 부른다
 */
@Component
@RequiredArgsConstructor
class LabJobGuard {

	private final LabSessionRepository labSessionRepository;

	private final VerificationRepository verificationRepository;

	private final RedeployRepository redeployRepository;

	void lockAndRequireIdle(long sessionId) {
		labSessionRepository.lockById(sessionId);
		if (verificationRepository.existsBySessionIdAndStatus(sessionId, VerificationStatus.RUNNING)
				|| redeployRepository.existsBySessionIdAndStatus(sessionId, RedeployStatus.RUNNING)) {
			throw new ApiException(ErrorCode.LAB_JOB_RUNNING);
		}
	}

}
