package com.popogonry.hackforge.challenge.service;

import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.popogonry.hackforge.challenge.domain.Challenge;
import com.popogonry.hackforge.challenge.repository.ChallengeRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChallengeService {

	private final ChallengeRepository challengeRepository;

	public List<Challenge> list() {
		return challengeRepository.findAll(Sort.by("id"));
	}

}
