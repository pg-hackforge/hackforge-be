package com.popogonry.hackforge.challenge.dto;

import com.popogonry.hackforge.challenge.domain.ChallengeDifficulty;

/** openapi.yaml `ChallengeResponse`. 설명 전문, 힌트, 해답, 검증 스크립트는 넣지 않는다(ADR 020) */
public record ChallengeResponse(Long id, String slug, String title, String summary, ChallengeCategoryResponse category,
		ChallengeDifficulty difficulty, int estimatedMinutes, boolean isNew) {
}
