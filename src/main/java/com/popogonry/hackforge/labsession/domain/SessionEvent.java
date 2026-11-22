package com.popogonry.hackforge.labsession.domain;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 세션 상태 기록 한 줄(ADR 026). DB trigger가 넣고 앱은 읽기만 한다 */
@Entity
@Immutable
@Table(name = "session_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SessionEvent {

	@Id
	private Long id;

	private Long sessionId;

	/** 세션 생성이면 null */
	@Enumerated(EnumType.STRING)
	private LabStatus fromStatus;

	@Enumerated(EnumType.STRING)
	private LabStatus toStatus;

	@Enumerated(EnumType.STRING)
	private LabEventActor actor;

	private Long actorUserId;

	private Instant createdAt;

}
