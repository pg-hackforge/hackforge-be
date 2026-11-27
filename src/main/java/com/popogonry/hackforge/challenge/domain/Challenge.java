package com.popogonry.hackforge.challenge.domain;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 문제(ADR 012, ADR 014). Challenge 저장소의 register.sh가 DB에 직접 넣는다(등록 API 없음).
 * 해답, 검증 스크립트, 힌트 컬럼은 쓰는 기능(#9, #10, #11)에서 매핑한다.
 * 예정 문제는 이미지, 설명, 스크립트, 해답이 비어 있다(ADR 031). 공개 문제면 DB 제약이 모두 채워져 있게 지킨다.
 */
@Entity
@Table(name = "challenges")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Challenge {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String slug;

	private String title;

	@Enumerated(EnumType.STRING)
	private ChallengeStatus status;

	/** 지금 쓰는 Challenge 이미지(태그 포함) */
	private String image;

	/** 문제 목록에 보이는 설명(Markdown) */
	private String description;

	/** 공격 검증 스크립트 본문(ADR 014). 실습 Pod 안에서 실행한다(ADR 023) */
	private String attackCheck;

	/** 수정 불가 파일(/app 기준 glob, 빈칸으로 나눔). null이면 백엔드 기본값(ADR 024) */
	private String readonlyPaths;

	/** 방어 검증의 기존 공격과 정상 기능 테스트(ADR 014, ADR 025) */
	private String exploit;

	private String functionalTest;

	/** 해답(Markdown). 방어 검증 FAIL이 {@link #maxDefenseAttempts}만큼 쌓이면 열 수 있다(ADR 025) */
	private String solution;

	private Short maxDefenseAttempts;

	private Instant createdAt;

	/** 처음 공개된 시각. 예정 문제는 null */
	private Instant publishedAt;

}
