package com.popogonry.hackforge.labsession.dto;

import java.time.Instant;

import com.popogonry.hackforge.labsession.cluster.LabProperties;
import com.popogonry.hackforge.labsession.domain.LabEndReason;
import com.popogonry.hackforge.labsession.domain.LabPrepStep;
import com.popogonry.hackforge.labsession.domain.LabSession;
import com.popogonry.hackforge.labsession.domain.LabStage;
import com.popogonry.hackforge.labsession.domain.LabStatus;

/** openapi.yaml `LabSessionResponse`. namespace, Pod 이름 같은 클러스터 정보는 넣지 않는다(ADR 012). */
public record LabSessionResponse(
		Long id,
		Long challengeId,
		String challengeTitle,
		LabStatus status,
		LabPrepStep prepStep,
		boolean retrying,
		String targetUrl,
		LabStage stage,
		boolean attackSkipped,
		int attackHintsRevealed,
		int defenseHintsRevealed,
		int defenseAttempts,
		int maxDefenseAttempts,
		boolean solutionAvailable,
		Instant solutionViewedAt,
		Instant completedAt,
		LabEndReason endReason,
		Instant createdAt,
		Instant readyAt,
		Instant endedAt,
		Instant idleDeadline,
		Instant idleWarningAt
) {

	/**
	 * idle 기한과 경고 시각은 {@code READY}일 때만 준다(ADR 012).
	 * @param recentActivity 아직 DB에 쓰지 않은 활동 시각. 없으면 null
	 * @param targetUrl 공격 대상 주소. {@code READY}가 아니면 null(ADR 022)
	 * @param attackHintsRevealed 공개한 공격 힌트 수(ADR 023)
	 * @param defenseHintsRevealed 공개한 방어 힌트 수(ADR 024)
	 * @param defenseAttempts 방어 검증 시도 횟수, {@code solutionAvailable} 해답을 열 수 있는지(ADR 025)
	 */
	public static LabSessionResponse from(LabSession session, LabProperties labProperties, Instant recentActivity,
			String targetUrl, int attackHintsRevealed, int defenseHintsRevealed, int defenseAttempts,
			boolean solutionAvailable) {
		Instant idleDeadline = session.idleDeadline(labProperties.idleTimeout(), recentActivity);
		Instant idleWarningAt = idleDeadline == null ? null : idleDeadline.minus(labProperties.idleWarning());
		return new LabSessionResponse(session.getId(), session.getChallenge().getId(),
				session.getChallenge().getTitle(), session.getStatus(), session.getPrepStep(), session.isRetrying(), targetUrl,
				session.getStage(), session.isAttackSkipped(), attackHintsRevealed, defenseHintsRevealed, defenseAttempts,
				session.getChallenge().getMaxDefenseAttempts(), solutionAvailable, session.getSolutionViewedAt(),
				session.getCompletedAt(), session.getEndReason(),
				session.getCreatedAt(), session.getReadyAt(), session.getEndedAt(), idleDeadline, idleWarningAt);
	}

}
