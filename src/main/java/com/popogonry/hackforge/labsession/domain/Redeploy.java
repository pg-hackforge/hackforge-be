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

/** 재배포나 서비스 초기화 한 번(ADR 024). 요청 때 {@code RUNNING}으로 만들고 끝나면 결과와 빌드 로그를 채운다 */
@Entity
@Table(name = "redeploys")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Redeploy {

	/** 빌드 로그는 끝부분만 이만큼 둔다(글자 수) */
	public static final int LOG_LIMIT = 64 * 1024;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private Long sessionId;

	@Enumerated(EnumType.STRING)
	private RedeployKind kind;

	@Enumerated(EnumType.STRING)
	private RedeployStatus status;

	private String message;

	private String log;

	private Long durationMs;

	private Instant startedAt;

	private Instant finishedAt;

	public Redeploy(long sessionId, RedeployKind kind, Instant now) {
		this.sessionId = sessionId;
		this.kind = kind;
		this.status = RedeployStatus.RUNNING;
		this.startedAt = now;
	}

	public void finish(RedeployStatus status, String message, String log, Instant now) {
		this.status = status;
		this.message = message;
		this.log = log == null || log.length() <= LOG_LIMIT ? log : log.substring(log.length() - LOG_LIMIT);
		this.finishedAt = now;
		this.durationMs = Duration.between(startedAt, now).toMillis();
	}

}
