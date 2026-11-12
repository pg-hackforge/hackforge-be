package com.popogonry.hackforge.labsession.domain;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

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

	/** 시작 요청. 환경은 reconciler가 비동기로 만든다 */
	public LabSession(long userId, Challenge challenge, Instant now) {
		this.userId = userId;
		this.challenge = challenge;
		this.image = challenge.getImage();
		this.status = LabStatus.CREATING;
		this.statusChangedAt = now;
		this.createdAt = now;
	}

}
