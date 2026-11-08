package com.popogonry.hackforge.user.dto;

import java.time.Instant;

import com.popogonry.hackforge.user.domain.User;
import com.popogonry.hackforge.user.domain.UserRole;
import com.popogonry.hackforge.user.domain.UserStatus;

/** openapi.yaml `UserResponse`. */
public record UserResponse(
		Long id,
		String email,
		String name,
		String nickname,
		String affiliation,
		UserRole role,
		UserStatus status,
		Instant createdAt
) {

	public static UserResponse from(User user) {
		return new UserResponse(user.getId(), user.getEmail(), user.getName(), user.getNickname(),
				user.getAffiliation(), user.getRole(), user.getStatus(), user.getCreatedAt());
	}

}
