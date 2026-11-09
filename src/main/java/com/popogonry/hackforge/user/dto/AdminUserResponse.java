package com.popogonry.hackforge.user.dto;

import java.time.Instant;

import com.popogonry.hackforge.user.domain.User;
import com.popogonry.hackforge.user.domain.UserRole;
import com.popogonry.hackforge.user.domain.UserStatus;

/** openapi.yaml `AdminUserResponse`. `UserResponse`에 가입 메모를 더한다(record는 상속이 안 돼서 필드를 다시 적음). */
public record AdminUserResponse(
		Long id,
		String email,
		String name,
		String nickname,
		String affiliation,
		UserRole role,
		UserStatus status,
		Instant createdAt,
		String signupNote
) {

	public static AdminUserResponse from(User user) {
		return new AdminUserResponse(user.getId(), user.getEmail(), user.getName(), user.getNickname(),
				user.getAffiliation(), user.getRole(), user.getStatus(), user.getCreatedAt(), user.getSignupNote());
	}

}
