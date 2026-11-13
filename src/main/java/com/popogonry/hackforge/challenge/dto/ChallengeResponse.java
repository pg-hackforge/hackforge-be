package com.popogonry.hackforge.challenge.dto;

import com.popogonry.hackforge.challenge.domain.Challenge;

/** openapi.yaml `ChallengeResponse`. 힌트, 해답, 검증 스크립트는 넣지 않는다(ADR 014). */
public record ChallengeResponse(Long id, String slug, String title, String description) {

	public static ChallengeResponse from(Challenge challenge) {
		return new ChallengeResponse(challenge.getId(), challenge.getSlug(), challenge.getTitle(),
				challenge.getDescription());
	}

}
