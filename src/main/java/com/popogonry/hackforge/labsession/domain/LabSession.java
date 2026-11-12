package com.popogonry.hackforge.labsession.domain;

import java.time.Duration;
import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.popogonry.hackforge.challenge.domain.Challenge;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 실습 세션(ADR 012). id로 namespace 이름 {@code lab-<id>}를 만든다. */
@Entity
@Table(name = "lab_sessions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LabSession {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private Long userId;

	/** 응답에 문제 제목이 들어가서 함께 읽는다 */
	@ManyToOne
	private Challenge challenge;

	/** 시작할 때의 Challenge 이미지. 재시도 Pod도 이 값으로 만든다 */
	private String image;

	@Enumerated(EnumType.STRING)
	private LabStatus status;

	@Enumerated(EnumType.STRING)
	private LabEndReason endReason;

	private short startAttempts;

	private Instant statusChangedAt;

	private Instant lastActivityAt;

	private Instant createdAt;

	private Instant readyAt;

	private Instant endedAt;

	/** 낙관적 잠금. 읽은 뒤 다른 쪽이 먼저 저장했으면 이쪽 저장이 실패한다 */
	@Version
	private long version;

	/** 시작 요청. 환경은 reconciler가 비동기로 만든다 */
	public LabSession(long userId, Challenge challenge, Instant now) {
		this.userId = userId;
		this.challenge = challenge;
		this.image = challenge.getImage();
		this.status = LabStatus.CREATING;
		this.statusChangedAt = now;
		this.createdAt = now;
	}

	/** Pod 생성 요청이 들어감. 이제 Ready를 기다린다 */
	public void podCreated(Instant now) {
		changeStatus(LabStatus.INITIALIZING, now);
	}

	/** idle 시간은 이때부터 센다(ADR 012) */
	public void ready(Instant now) {
		changeStatus(LabStatus.READY, now);
		this.readyAt = now;
		this.lastActivityAt = now;
	}

	/** {@code READY}일 때만 값이 있다. 이 시각이 지나도록 활동이 없으면 idle 종료한다 */
	public Instant idleDeadline(Duration idleTimeout) {
		return status == LabStatus.READY ? lastActivityAt.plus(idleTimeout) : null;
	}

	/** 생성 실패 후 다시 만든다. 지금까지 재시도한 횟수를 {@code startAttempts}에 센다 */
	public void retry(Instant now) {
		changeStatus(LabStatus.CREATING, now);
		this.startAttempts++;
	}

	/** 환경 삭제 시작. 생성 실패로 끝내면 사유가 없고(null) 삭제가 끝난 뒤 {@code FAILED}가 된다 */
	public void terminate(LabEndReason reason, Instant now) {
		changeStatus(LabStatus.TERMINATING, now);
		this.endReason = reason;
	}

	/** namespace 삭제가 끝남 */
	public void end(Instant now) {
		changeStatus(endReason == null ? LabStatus.FAILED : LabStatus.DELETED, now);
		this.endedAt = now;
	}

	private void changeStatus(LabStatus status, Instant now) {
		this.status = status;
		this.statusChangedAt = now;
	}

}
