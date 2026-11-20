package com.popogonry.hackforge.labsession.dto;

import java.time.Instant;

import com.popogonry.hackforge.labsession.domain.LabStage;

/** openapi.yaml `HintResponse` */
public record HintResponse(LabStage stage, int position, String content, Instant revealedAt) {
}
