package com.popogonry.hackforge.challenge.dto;

import java.util.List;

/** openapi.yaml `MyActivityResponse`(ADR 030) */
public record MyActivityResponse(long totalChallenges, List<CategoryTotalResponse> categories, long sessions,
		long totalMinutes, List<ActivityDayResponse> days, List<ChallengeActivityResponse> challenges) {
}
