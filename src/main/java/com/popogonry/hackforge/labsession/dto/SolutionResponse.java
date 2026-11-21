package com.popogonry.hackforge.labsession.dto;

import java.time.Instant;

/** openapi.yaml `SolutionResponse` */
public record SolutionResponse(String content, Instant viewedAt) {
}
