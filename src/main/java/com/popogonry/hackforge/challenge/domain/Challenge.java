package com.popogonry.hackforge.challenge.domain;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 문제. v1은 DB(개발은 seed)에 직접 넣고, 등록 방식은 #7에서 정한다. */
@Entity
@Table(name = "challenges")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Challenge {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** 사람이 읽는 식별자. seed, runbook, 로그에서 쓴다. */
	private String slug;

	private String title;

	/** Challenge 이미지(태그 포함). 세션은 시작할 때 이 값을 복사해 둔다. */
	private String image;

	private Instant createdAt;

}
