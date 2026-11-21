package com.popogonry.hackforge.labsession.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.popogonry.hackforge.labsession.cluster.LabCluster;
import com.popogonry.hackforge.labsession.cluster.ScriptResult;
import com.popogonry.hackforge.labsession.domain.LabStage;
import com.popogonry.hackforge.labsession.domain.Verification;
import com.popogonry.hackforge.labsession.domain.VerificationStatus;
import com.popogonry.hackforge.labsession.domain.VerificationType;
import com.popogonry.hackforge.labsession.repository.LabSessionRepository;
import com.popogonry.hackforge.labsession.repository.VerificationRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 요청 transaction이 commit된 뒤 별도 thread에서 검증 스크립트를 실행하고 결과를 저장한다(ADR 023, ADR 025).
 * 실행 중 백엔드가 재시작하면 {@code RUNNING}이 남고, 시작할 때 {@link LabJobRecovery}가, 안전망으로 reconciler가
 * 공격 검증은 2분, 방어 검증은 3분 뒤 {@code ERROR}로 바꾼다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class VerificationRunner {

	/** 검증 스크립트 제한 시간(ADR 014) */
	static final Duration SCRIPT_TIMEOUT = Duration.ofSeconds(60);

	static final String TIMEOUT_MESSAGE = "60초 안에 끝나지 않았어요";

	static final String ERROR_MESSAGE = "검증을 실행하지 못했어요. 잠시 뒤 다시 시도해 주세요";

	private final LabCluster labCluster;

	private final VerificationRepository verificationRepository;

	private final LabSessionRepository labSessionRepository;

	private final TransactionTemplate transactionTemplate;

	private final Clock clock;

	void start(long verificationId, long sessionId, String script) {
		Thread.ofVirtual().name("verification-" + verificationId).start(() -> run(verificationId, sessionId, script));
	}

	private void run(long verificationId, long sessionId, String script) {
		VerificationStatus status;
		String message;
		try {
			ScriptResult result = labCluster.runScript(sessionId, script, SCRIPT_TIMEOUT);
			if (result.timedOut()) {
				status = VerificationStatus.FAIL;
				message = TIMEOUT_MESSAGE;
			}
			else {
				status = result.exitCode() == 0 ? VerificationStatus.PASS : VerificationStatus.FAIL;
				message = lastLine(result.output());
			}
		}
		catch (RuntimeException e) {
			log.warn("검증 {}(실습 세션 {}) 실행 실패: {}", verificationId, sessionId, e.getMessage());
			status = VerificationStatus.ERROR;
			message = ERROR_MESSAGE;
		}
		VerificationStatus finalStatus = status;
		String finalMessage = message;
		transactionTemplate.executeWithoutResult(tx -> {
			Verification verification = verificationRepository.findById(verificationId).orElseThrow();
			verification.finish(finalStatus, finalMessage, clock.instant());
			// 공격 단계의 PASS만 방어 단계로 넘긴다. 이미 방어 단계면 바뀌지 않는다
			if (verification.getType() == VerificationType.ATTACK && finalStatus == VerificationStatus.PASS) {
				labSessionRepository.enterDefense(sessionId, LabStage.ATTACK, LabStage.DEFENSE);
			}
		});
	}

	void startDefense(long verificationId, long sessionId, String exploit, String functionalTest) {
		Thread.ofVirtual()
			.name("verification-" + verificationId)
			.start(() -> runDefense(verificationId, sessionId, exploit, functionalTest));
	}

	/**
	 * 기존 공격(exploit.sh)과 정상 기능 테스트(functional-test.sh)를 차례로 둘 다 실행한다(ADR 025).
	 * 공격이 모두 막히고(0 아님) 기능이 동작하면(0) PASS. 60초를 넘긴 테스트는 통과가 아니다. 하나라도 실행하지 못하면 ERROR
	 */
	private void runDefense(long verificationId, long sessionId, String exploit, String functionalTest) {
		VerificationStatus status;
		String message;
		Boolean securityPassed = null;
		Boolean functionalPassed = null;
		String securityMessage = null;
		String functionalMessage = null;
		try {
			ScriptResult attack = labCluster.runScript(sessionId, exploit, SCRIPT_TIMEOUT);
			ScriptResult functions = labCluster.runScript(sessionId, functionalTest, SCRIPT_TIMEOUT);
			securityPassed = !attack.timedOut() && attack.exitCode() != 0;
			functionalPassed = !functions.timedOut() && functions.exitCode() == 0;
			securityMessage = attack.timedOut() ? TIMEOUT_MESSAGE : lastLine(attack.output());
			functionalMessage = functions.timedOut() ? TIMEOUT_MESSAGE : lastLine(functions.output());
			status = securityPassed && functionalPassed ? VerificationStatus.PASS : VerificationStatus.FAIL;
			message = defenseSummary(securityPassed, functionalPassed);
		}
		catch (RuntimeException e) {
			log.warn("방어 검증 {}(실습 세션 {}) 실행 실패: {}", verificationId, sessionId, e.getMessage());
			status = VerificationStatus.ERROR;
			message = ERROR_MESSAGE;
			securityPassed = null;
			functionalPassed = null;
			securityMessage = null;
			functionalMessage = null;
		}
		VerificationStatus finalStatus = status;
		String finalMessage = message;
		Boolean security = securityPassed;
		Boolean functional = functionalPassed;
		String securityLine = securityMessage;
		String functionalLine = functionalMessage;
		transactionTemplate.executeWithoutResult(tx -> {
			Instant now = clock.instant();
			Verification verification = verificationRepository.findById(verificationId).orElseThrow();
			verification.finishDefense(finalStatus, finalMessage, security, securityLine, functional, functionalLine, now);
			// 첫 PASS만 완료 시각을 남긴다
			if (finalStatus == VerificationStatus.PASS) {
				labSessionRepository.complete(sessionId, now);
			}
		});
	}

	static String defenseSummary(boolean securityPassed, boolean functionalPassed) {
		if (securityPassed && functionalPassed) {
			return "공격이 모두 막히고 정상 기능이 동작해요";
		}
		if (!securityPassed && !functionalPassed) {
			return "공격이 아직 통해요. 정상 기능이 동작하지 않아요";
		}
		return securityPassed ? "정상 기능이 동작하지 않아요" : "공격이 아직 통해요";
	}

	/** 학습자에게 보여 줄 stdout의 비어 있지 않은 마지막 줄. 없으면 null */
	static String lastLine(String output) {
		if (output == null) {
			return null;
		}
		String[] lines = output.split("\\R");
		for (int i = lines.length - 1; i >= 0; i--) {
			if (!lines[i].isBlank()) {
				return lines[i].strip();
			}
		}
		return null;
	}

}
