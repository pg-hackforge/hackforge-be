package com.popogonry.hackforge.challenge.dto;

import com.popogonry.hackforge.challenge.domain.ChallengeMyStatus;

/** openapi.yaml `MyStatusCountResponse` */
public record MyStatusCountResponse(ChallengeMyStatus myStatus, long count) {
}
