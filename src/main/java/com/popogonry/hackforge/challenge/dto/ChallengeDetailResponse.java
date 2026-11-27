package com.popogonry.hackforge.challenge.dto;

import com.popogonry.hackforge.challenge.domain.ChallengeDifficulty;
import com.popogonry.hackforge.challenge.domain.ChallengeMyStatus;
import com.popogonry.hackforge.challenge.domain.ChallengeStatus;

/** 목록 항목에 설명 전문(Markdown)을 더한 문제 하나. 힌트, 해답, 검증 스크립트는 넣지 않는다. 예정 문제는 설명이 null */
public record ChallengeDetailResponse(Long id, String slug, String title, String summary,
		ChallengeCategoryResponse category, ChallengeDifficulty difficulty, int estimatedMinutes, ChallengeStatus status,
		boolean isNew, ChallengeMyStatus myStatus, String description) {

	public static ChallengeDetailResponse of(ChallengeResponse item, String description) {
		return new ChallengeDetailResponse(item.id(), item.slug(), item.title(), item.summary(), item.category(),
				item.difficulty(), item.estimatedMinutes(), item.status(), item.isNew(), item.myStatus(), description);
	}

}
