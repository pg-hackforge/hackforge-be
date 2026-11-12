package com.popogonry.hackforge.challenge.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.popogonry.hackforge.challenge.domain.Challenge;

public interface ChallengeRepository extends JpaRepository<Challenge, Long> {

}
