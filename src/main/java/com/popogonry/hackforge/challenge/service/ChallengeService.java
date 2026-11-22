package com.popogonry.hackforge.challenge.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.popogonry.hackforge.challenge.domain.ChallengeSort;
import com.popogonry.hackforge.challenge.dto.ChallengeDetailResponse;
import com.popogonry.hackforge.challenge.dto.CategoryProgressResponse;
import com.popogonry.hackforge.challenge.dto.ChallengeFacetsResponse;
import com.popogonry.hackforge.challenge.dto.MyProgressResponse;
import com.popogonry.hackforge.challenge.dto.MyStatusCountResponse;
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
	public ChallengePageResponse listChallenges(Filter filter, ChallengeSort sort, int page) {
		long totalItems = challengeQueryRepository.count(filter);
		int totalPages = (int) ((totalItems + PAGE_SIZE - 1) / PAGE_SIZE);
		List<DifficultyCountResponse> difficulties = challengeQueryRepository.countByDifficulty(filter)
			.entrySet()
			.stream()
			.map(e -> new DifficultyCountResponse(e.getKey(), e.getValue()))
			.toList();
		List<MyStatusCountResponse> myStatuses = challengeQueryRepository.countByMyStatus(filter)
			.entrySet()
			.stream()
			.map(e -> new MyStatusCountResponse(e.getKey(), e.getValue()))
			.toList();
		return new ChallengePageResponse(
				challengeQueryRepository.findPage(filter, sort, PAGE_SIZE, (long) (page - 1) * PAGE_SIZE), page,
				PAGE_SIZE, totalItems, totalPages,
				new ChallengeFacetsResponse(challengeQueryRepository.countByCategory(filter), difficulties, myStatuses));
	}

	public ChallengeDetailResponse getChallenge(long id, long userId) {
		return challengeQueryRepository.findDetail(id, userId)
			.orElseThrow(() -> new ApiException(ErrorCode.CHALLENGE_NOT_FOUND));
	}

	/** 홈의 내 진행(ADR 027) */
	public MyProgressResponse getMyProgress(long userId) {
		List<ChallengeQueryRepository.CategoryProgress> categories = challengeQueryRepository.countProgressByCategory(userId);
		return new MyProgressResponse(categories.stream().mapToLong(ChallengeQueryRepository.CategoryProgress::total).sum(),
				categories.stream().mapToLong(ChallengeQueryRepository.CategoryProgress::completed).sum(),
				categories.stream().mapToLong(ChallengeQueryRepository.CategoryProgress::unfinished).sum(),
				categories.stream().map(c -> new CategoryProgressResponse(c.key(), c.name(), c.total(), c.completed())).toList());
	}

}
