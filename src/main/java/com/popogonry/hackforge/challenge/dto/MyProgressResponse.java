package com.popogonry.hackforge.challenge.dto;

import java.util.List;

/** openapi.yaml `MyProgressResponse` */
public record MyProgressResponse(long totalChallenges, long completed, long unfinished,
		List<CategoryProgressResponse> categories) {
}
