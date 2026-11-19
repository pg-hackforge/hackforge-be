package com.popogonry.hackforge.challenge.dto;

import com.popogonry.hackforge.challenge.domain.ChallengeDifficulty;

/** openapi.yaml `DifficultyCountResponse` */
public record DifficultyCountResponse(ChallengeDifficulty difficulty, long count) {
}
