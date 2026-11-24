package com.popogonry.hackforge.challenge.controller;

import java.util.List;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.popogonry.hackforge.challenge.domain.ChallengeDifficulty;
import com.popogonry.hackforge.challenge.domain.ChallengeMyStatus;
import com.popogonry.hackforge.challenge.domain.ChallengeSort;
import com.popogonry.hackforge.challenge.dto.ChallengeDetailResponse;
import com.popogonry.hackforge.challenge.dto.ChallengePageResponse;
import com.popogonry.hackforge.challenge.dto.MyActivityResponse;
import com.popogonry.hackforge.challenge.dto.MyProgressResponse;
import com.popogonry.hackforge.challenge.repository.ChallengeQueryRepository.Filter;
import com.popogonry.hackforge.challenge.service.ChallengeService;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
class ChallengeController {

	private final ChallengeService challengeService;

	/** 승인 대기·거절 계정도 볼 수 있다. 승인은 실습을 시작할 때 확인한다 */
	@GetMapping("/api/challenges")
	ChallengePageResponse listChallenges(@RequestParam(required = false) @Size(max = 100) String q,
			@RequestParam(name = "category", required = false) List<String> categories,
			@RequestParam(name = "difficulty", required = false) List<ChallengeDifficulty> difficulties,
			@RequestParam(name = "myStatus", required = false) List<ChallengeMyStatus> myStatuses,
			@RequestParam(defaultValue = "NEWEST") ChallengeSort sort, @RequestParam(defaultValue = "false") boolean reverse,
			@RequestParam(defaultValue = "1") @Min(1) int page, Authentication authentication) {
		return challengeService.listChallenges(
				new Filter(q, categories, difficulties, myStatuses, userId(authentication)), sort, reverse, page);
	}

	@GetMapping("/api/challenges/{id}")
	ChallengeDetailResponse getChallenge(@PathVariable long id, Authentication authentication) {
		return challengeService.getChallenge(id, userId(authentication));
	}

	/** 홈의 내 진행. 문제별 내 상태와 같은 규칙으로 센다(ADR 027) */
	@GetMapping("/api/me/progress")
	MyProgressResponse getMyProgress(Authentication authentication) {
		return challengeService.getMyProgress(userId(authentication));
	}

	/** 내 기록 분석. 판정은 화면이 하고 서버는 사실만 준다(ADR 030) */
	@GetMapping("/api/me/activity")
	MyActivityResponse getMyActivity(Authentication authentication) {
		return challengeService.getMyActivity(userId(authentication));
	}

	private static long userId(Authentication authentication) {
		return Long.parseLong(authentication.getName());
	}

}
