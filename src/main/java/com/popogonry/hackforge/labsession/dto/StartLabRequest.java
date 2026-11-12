package com.popogonry.hackforge.labsession.dto;

import jakarta.validation.constraints.NotNull;

/** openapi.yaml `StartLabRequest`. */
public record StartLabRequest(@NotNull Long challengeId) {
}
