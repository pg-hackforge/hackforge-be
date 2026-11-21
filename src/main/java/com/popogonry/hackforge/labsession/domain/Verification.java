package com.popogonry.hackforge.labsession.domain;

import java.time.Duration;
import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 공격·방어 검증 한 번(ADR 023). 요청 때 {@code RUNNING}으로 만들고 스크립트가 끝나면 결과를 채운다 */
@Entity
@Table(name = "verifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Verification {

	/** 학습자에게 보여 주는 메시지 최대 길이(컬럼 길이) */
	public static final int MESSAGE_LIMIT = 500;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private Long sessionId;

	@Enumerated(EnumType.STRING)
	private VerificationType type;

	@Enumerated(EnumType.STRING)
	private VerificationStatus status;

	private String message;

	private Long durationMs;

	private Instant startedAt;

	private Instant finishedAt;

	/** 방어 검증 결과(ADR 025). 공격 검증이면 모두 null */
	private Boolean securityPassed;

	private Boolean functionalPassed;

	private String securityMessage;

	private String functionalMessage;

	private Short defenseHintsRevealed;

	private Boolean afterSolution;

	public Verification(long sessionId, VerificationType type, Instant now) {
		this.sessionId = sessionId;
		this.type = type;
		this.status = VerificationStatus.RUNNING;
		this.startedAt = now;
	}

	/** 방어 검증. 요청할 때까지 공개한 방어 힌트 수와 해답을 열었는지 남긴다 */
	public static Verification defense(long sessionId, int defenseHintsRevealed, boolean afterSolution, Instant now) {
		Verification verification = new Verification(sessionId, VerificationType.DEFENSE, now);
		verification.defenseHintsRevealed = (short) defenseHintsRevealed;
		verification.afterSolution = afterSolution;
		return verification;
	}

	public void finish(VerificationStatus status, String message, Instant now) {
		this.status = status;
		this.message = cut(message);
		this.finishedAt = now;
		this.durationMs = Duration.between(startedAt, now).toMillis();
	}

	/** 방어 검증 결과. {@code ERROR}면 통과 여부와 메시지는 null */
	public void finishDefense(VerificationStatus status, String message, Boolean securityPassed, String securityMessage,
			Boolean functionalPassed, String functionalMessage, Instant now) {
		finish(status, message, now);
		this.securityPassed = securityPassed;
		this.securityMessage = cut(securityMessage);
		this.functionalPassed = functionalPassed;
		this.functionalMessage = cut(functionalMessage);
	}

	private static String cut(String message) {
		return message == null || message.length() <= MESSAGE_LIMIT ? message : message.substring(0, MESSAGE_LIMIT);
	}

}
