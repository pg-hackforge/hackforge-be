package com.popogonry.hackforge.challenge.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.popogonry.hackforge.challenge.dto.ChallengeResponse;
import com.popogonry.hackforge.challenge.service.ChallengeService;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
class ChallengeController {

	private final ChallengeService challengeService;

	/** 승인 대기·거절 계정도 볼 수 있다. 승인은 실습을 시작할 때 확인한다 */
	@GetMapping("/api/challenges")
	List<ChallengeResponse> list() {
		return challengeService.list().stream().map(ChallengeResponse::from).toList();
	}

}
