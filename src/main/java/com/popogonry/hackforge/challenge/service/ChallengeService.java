package com.popogonry.hackforge.challenge.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.popogonry.hackforge.challenge.domain.ChallengeSort;
import com.popogonry.hackforge.challenge.dto.ChallengeDetailResponse;
import com.popogonry.hackforge.challenge.dto.ChallengeFacetsResponse;
import com.popogonry.hackforge.challenge.dto.ChallengePageResponse;
import com.popogonry.hackforge.challenge.dto.DifficultyCountResponse;
import com.popogonry.hackforge.challenge.repository.ChallengeQueryRepository;
import com.popogonry.hackforge.challenge.repository.ChallengeQueryRepository.Filter;
import com.popogonry.hackforge.web.ApiException;
import com.popogonry.hackforge.web.ErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChallengeService {

	public static final int PAGE_SIZE = 20;

	private final ChallengeQueryRepository challengeQueryRepository;

	/** 범위를 넘은 페이지는 빈 목록이고 개수는 그대로다 */
	public ChallengePageResponse list(Filter filter, ChallengeSort sort, int page) {
		long totalItems = challengeQueryRepository.count(filter);
		int totalPages = (int) ((totalItems + PAGE_SIZE - 1) / PAGE_SIZE);
		List<DifficultyCountResponse> difficulties = challengeQueryRepository.countByDifficulty(filter)
			.entrySet()
			.stream()
			.map(e -> new DifficultyCountResponse(e.getKey(), e.getValue()))
			.toList();
		return new ChallengePageResponse(
				challengeQueryRepository.findPage(filter, sort, PAGE_SIZE, (long) (page - 1) * PAGE_SIZE), page,
				PAGE_SIZE, totalItems, totalPages,
				new ChallengeFacetsResponse(challengeQueryRepository.countByCategory(filter), difficulties));
	}

	public ChallengeDetailResponse get(long id) {
		return challengeQueryRepository.findDetail(id)
			.orElseThrow(() -> new ApiException(ErrorCode.CHALLENGE_NOT_FOUND));
	}

}
