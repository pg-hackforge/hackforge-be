package com.popogonry.hackforge.user;

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

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String email;

	private String passwordHash;

	private String name;

	private String nickname;

	private String affiliation;

	private String signupNote;

	@Enumerated(EnumType.STRING)
	private UserRole role;

	@Enumerated(EnumType.STRING)
	private UserStatus status;

	private Instant privacyAgreedAt;

	private Instant createdAt;

	/** 가입. 승인 대기·학습자로 만든다. 동의 여부는 요청 검증에서 확인한 뒤 호출한다. */
	User(String email, String passwordHash, String name, String nickname, String affiliation, String signupNote,
			Instant now) {
		this.email = email;
		this.passwordHash = passwordHash;
		this.name = name;
		this.nickname = nickname;
		this.affiliation = affiliation;
		this.signupNote = signupNote;
		this.role = UserRole.LEARNER;
		this.status = UserStatus.PENDING;
		this.privacyAgreedAt = now;
		this.createdAt = now;
	}

}
