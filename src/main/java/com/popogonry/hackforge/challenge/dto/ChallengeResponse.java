package com.popogonry.hackforge.challenge.dto;

import com.popogonry.hackforge.challenge.domain.Challenge;

/** openapi.yaml `ChallengeResponse`. 이미지는 내부 정보라 넣지 않는다. */
public record ChallengeResponse(
		Long id,
		String slug,
		String title
) {

	public static ChallengeResponse from(Challenge challenge) {
		return new ChallengeResponse(challenge.getId(), challenge.getSlug(), challenge.getTitle());
	}

}
