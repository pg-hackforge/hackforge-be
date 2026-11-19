package com.popogonry.hackforge.challenge.dto;

import java.util.List;

/** openapi.yaml `ChallengePageResponse` */
public record ChallengePageResponse(List<ChallengeResponse> items, int page, int size, long totalItems, int totalPages,
		ChallengeFacetsResponse facets) {
}
