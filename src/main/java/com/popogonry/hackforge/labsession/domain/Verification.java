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

	public Verification(long sessionId, VerificationType type, Instant now) {
		this.sessionId = sessionId;
		this.type = type;
		this.status = VerificationStatus.RUNNING;
		this.startedAt = now;
	}

	public void finish(VerificationStatus status, String message, Instant now) {
		this.status = status;
		this.message = message == null || message.length() <= MESSAGE_LIMIT ? message : message.substring(0, MESSAGE_LIMIT);
		this.finishedAt = now;
		this.durationMs = Duration.between(startedAt, now).toMillis();
	}

}
