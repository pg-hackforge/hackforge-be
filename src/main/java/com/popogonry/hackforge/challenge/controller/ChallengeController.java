package com.popogonry.hackforge.challenge.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.popogonry.hackforge.challenge.dto.ChallengeResponse;
import com.popogonry.hackforge.challenge.repository.ChallengeRepository;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
class ChallengeController {

	private final ChallengeRepository challengeRepository;

	/** 승인 대기·거절 계정도 조회 가능. 승인은 실습 시작 시점에만 확인한다. 등록 순서. */
	@GetMapping("/api/challenges")
	List<ChallengeResponse> list() {
		return challengeRepository.findAllByOrderByIdAsc().stream().map(ChallengeResponse::from).toList();
	}

}
