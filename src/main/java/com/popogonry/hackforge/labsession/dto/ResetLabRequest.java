package com.popogonry.hackforge.labsession.dto;

import jakarta.validation.constraints.NotNull;

import com.popogonry.hackforge.labsession.domain.ResetScope;

/** openapi.yaml `ResetLabRequest` */
public record ResetLabRequest(@NotNull ResetScope scope, @NotNull Boolean keepStage) {
}
