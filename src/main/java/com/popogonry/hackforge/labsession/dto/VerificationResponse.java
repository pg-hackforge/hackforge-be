package com.popogonry.hackforge.labsession.dto;

import java.time.Instant;

import com.popogonry.hackforge.labsession.domain.Verification;
import com.popogonry.hackforge.labsession.domain.VerificationStatus;
import com.popogonry.hackforge.labsession.domain.VerificationType;

/** openapi.yaml `VerificationResponse` */
public record VerificationResponse(Long id, VerificationType type, VerificationStatus status, String message,
		Long durationMs, Instant startedAt, Instant finishedAt, Boolean securityPassed, Boolean functionalPassed,
		String securityMessage, String functionalMessage, Short defenseHintsRevealed, Boolean afterSolution) {

	public static VerificationResponse from(Verification verification) {
		return new VerificationResponse(verification.getId(), verification.getType(), verification.getStatus(),
				verification.getMessage(), verification.getDurationMs(), verification.getStartedAt(),
				verification.getFinishedAt(), verification.getSecurityPassed(), verification.getFunctionalPassed(),
				verification.getSecurityMessage(), verification.getFunctionalMessage(),
				verification.getDefenseHintsRevealed(), verification.getAfterSolution());
	}

}
