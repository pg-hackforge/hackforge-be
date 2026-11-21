package com.popogonry.hackforge.labsession.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.popogonry.hackforge.labsession.domain.RedeployStatus;
import com.popogonry.hackforge.labsession.domain.VerificationStatus;
import com.popogonry.hackforge.labsession.repository.RedeployRepository;
import com.popogonry.hackforge.labsession.repository.VerificationRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 백엔드가 시작할 때 남은 {@code RUNNING}(검증, 재배포, 초기화)을 모두 {@code ERROR}로 끝낸다(ADR 024).
 * v1은 백엔드가 1대라 막 뜬 시점에 실행 중인 작업은 없다. 백엔드를 여러 대로 늘리면 이것은 빼고 reconciler의 시간 기준만 남긴다
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LabJobRecovery {

	static final String LOST_MESSAGE = "서버가 다시 시작돼 결과를 잃었어요. 다시 시도해 주세요";

	private final VerificationRepository verificationRepository;

	private final RedeployRepository redeployRepository;

	private final Clock clock;

	/** 같은 클래스 안 호출은 transaction proxy를 거치지 않아서 여기에도 건다 */
	@EventListener(ApplicationReadyEvent.class)
	@Transactional
	public void onStartup() {
		failAllRunning();
	}

	@Transactional
	public void failAllRunning() {
		Instant now = clock.instant();
		int verifications = verificationRepository.failAll(VerificationStatus.RUNNING, VerificationStatus.ERROR, LOST_MESSAGE, now);
		int redeploys = redeployRepository.failAll(RedeployStatus.RUNNING, RedeployStatus.ERROR, LOST_MESSAGE, now);
		if (verifications + redeploys > 0) {
			log.info("백엔드 시작: 결과를 잃은 검증 {}개, 재배포·초기화 {}개를 ERROR로 바꿈", verifications, redeploys);
		}
	}

}
