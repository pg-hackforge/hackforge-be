package com.popogonry.hackforge.challenge.controller;

import java.util.List;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.popogonry.hackforge.challenge.domain.ChallengeDifficulty;
import com.popogonry.hackforge.challenge.domain.ChallengeSort;
import com.popogonry.hackforge.challenge.dto.ChallengePageResponse;
import com.popogonry.hackforge.challenge.repository.ChallengeQueryRepository.Filter;
import com.popogonry.hackforge.challenge.service.ChallengeService;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
class ChallengeController {

	private final ChallengeService challengeService;

	/** 승인 대기·거절 계정도 볼 수 있다. 승인은 실습을 시작할 때 확인한다 */
	@GetMapping("/api/challenges")
	ChallengePageResponse list(@RequestParam(required = false) @Size(max = 100) String q,
			@RequestParam(name = "category", required = false) List<String> categories,
			@RequestParam(name = "difficulty", required = false) List<ChallengeDifficulty> difficulties,
			@RequestParam(defaultValue = "NEWEST") ChallengeSort sort,
			@RequestParam(defaultValue = "1") @Min(1) int page) {
		return challengeService.list(new Filter(q, categories, difficulties), sort, page);
	}

}
