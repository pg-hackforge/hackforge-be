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

/**
 * 문제(ADR 012, ADR 014). Challenge 저장소의 register.sh가 DB에 직접 넣는다(등록 API 없음).
 * 해답, 검증 스크립트, 힌트 컬럼은 쓰는 기능(#9, #10, #11)에서 매핑한다.
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

	/** 지금 쓰는 Challenge 이미지(태그 포함) */
	private String image;

	/** 문제 목록에 보이는 설명(Markdown) */
	private String description;

	private Instant createdAt;

}
