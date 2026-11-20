package com.popogonry.hackforge.labsession.dto;

import java.time.Instant;

import com.popogonry.hackforge.labsession.domain.Redeploy;
import com.popogonry.hackforge.labsession.domain.RedeployKind;
import com.popogonry.hackforge.labsession.domain.RedeployStatus;

/** openapi.yaml `RedeployResponse`. 빌드 로그는 한 건 조회에서만 준다 */
public record RedeployResponse(Long id, RedeployKind kind, RedeployStatus status, String message, String log,
		Long durationMs, Instant startedAt, Instant finishedAt) {

	public static RedeployResponse from(Redeploy redeploy, boolean withLog) {
		return new RedeployResponse(redeploy.getId(), redeploy.getKind(), redeploy.getStatus(), redeploy.getMessage(),
				withLog ? redeploy.getLog() : null, redeploy.getDurationMs(), redeploy.getStartedAt(),
				redeploy.getFinishedAt());
	}

}
