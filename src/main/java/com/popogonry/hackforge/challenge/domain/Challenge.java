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

/** 문제(ADR 012). 등록 방식은 #7에서 정하고, 지금은 migration과 개발 seed로만 들어간다. */
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

	private Instant createdAt;

}
