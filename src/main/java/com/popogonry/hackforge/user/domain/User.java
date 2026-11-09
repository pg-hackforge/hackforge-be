package com.popogonry.hackforge.user.domain;

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

	/** 연속 로그인 실패 횟수. 잠길 때와 로그인에 성공할 때 0으로 돌아간다. */
	private int failedLoginCount;

	/** 이 시각 전까지 로그인 차단. */
	private Instant lockedUntil;

	public boolean isLockedAt(Instant now) {
		return lockedUntil != null && lockedUntil.isAfter(now);
	}

	public void changeRole(UserRole role) {
		this.role = role;
	}

	public void changePassword(String passwordHash) {
		this.passwordHash = passwordHash;
	}

	/** 로그인 잠금을 풀고 실패 횟수를 0으로. */
	public void unlock() {
		this.failedLoginCount = 0;
		this.lockedUntil = null;
	}

	/** 가입. 승인 대기·학습자로 만든다. 동의 여부는 요청 검증에서 확인한 뒤 호출한다. */
	public User(String email, String passwordHash, String name, String nickname, String affiliation, String signupNote,
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
