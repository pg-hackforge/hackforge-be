package com.popogonry.hackforge.challenge.dto;

import com.popogonry.hackforge.challenge.domain.ChallengeActivityResult;
import com.popogonry.hackforge.challenge.domain.ChallengeDifficulty;

/** openapi.yaml `ChallengeActivityResponse`. 시도한 문제 하나에서 가장 좋은 세션의 사실 */
public record ChallengeActivityResponse(long challengeId, String title, ChallengeCategoryResponse category,
		ChallengeDifficulty difficulty, long sessions, ChallengeActivityResult result, AttackActivityResponse attack,
		DefenseActivityResponse defense) {
}
