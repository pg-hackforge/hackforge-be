package com.popogonry.hackforge.challenge.dto;

import java.util.List;

/** openapi.yaml `ChallengeFacetsResponse`. 한 조건의 개수는 그 조건을 뺀 나머지 조건으로 센다 */
public record ChallengeFacetsResponse(List<CategoryCountResponse> categories,
		List<DifficultyCountResponse> difficulties, List<MyStatusCountResponse> myStatuses) {
}
