package com.popogonry.hackforge.labsession.dto;

import java.time.Instant;

import com.popogonry.hackforge.labsession.cluster.LabProperties;
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

	/** idle 기한과 경고 시각은 {@code READY}일 때만 준다(ADR 012) */
	public static LabSessionResponse from(LabSession session, LabProperties labProperties) {
		Instant idleDeadline = session.idleDeadline(labProperties.idleTimeout());
		Instant idleWarningAt = idleDeadline == null ? null : idleDeadline.minus(labProperties.idleWarning());
		return new LabSessionResponse(session.getId(), session.getChallenge().getId(),
				session.getChallenge().getTitle(), session.getStatus(), session.getEndReason(),
				session.getCreatedAt(), session.getReadyAt(), session.getEndedAt(), idleDeadline, idleWarningAt);
	}

}
