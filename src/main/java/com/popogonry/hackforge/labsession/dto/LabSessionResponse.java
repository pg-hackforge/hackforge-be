package com.popogonry.hackforge.labsession.dto;

import java.time.Instant;

import com.popogonry.hackforge.labsession.domain.LabEndReason;
import com.popogonry.hackforge.labsession.domain.LabSession;
import com.popogonry.hackforge.labsession.domain.LabStatus;

/** openapi.yaml `LabSessionResponse`. namespace, Pod 이름 같은 클러스터 정보는 넣지 않는다(ADR 012). */
public record LabSessionResponse(
		Long id,
		Long challengeId,
		String challengeTitle,
		LabStatus status,
		LabEndReason endReason,
		Instant createdAt,
		Instant readyAt,
		Instant endedAt,
		Instant idleDeadline,
		Instant idleWarningAt
) {

	// ponytail: idleDeadline, idleWarningAt은 idle 종료(#6 작업 10)에서 READY 세션에 채운다
	public static LabSessionResponse from(LabSession session) {
		return new LabSessionResponse(session.getId(), session.getChallenge().getId(),
				session.getChallenge().getTitle(), session.getStatus(), session.getEndReason(),
				session.getCreatedAt(), session.getReadyAt(), session.getEndedAt(), null, null);
	}

}
