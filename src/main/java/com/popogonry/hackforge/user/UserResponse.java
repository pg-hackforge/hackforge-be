package com.popogonry.hackforge.user;

import java.time.Instant;

/** openapi.yaml `UserResponse`. */
record UserResponse(Long id, String email, String name, String nickname, String affiliation, UserRole role,
		UserStatus status, Instant createdAt) {

	static UserResponse from(User user) {
		return new UserResponse(user.getId(), user.getEmail(), user.getName(), user.getNickname(),
				user.getAffiliation(), user.getRole(), user.getStatus(), user.getCreatedAt());
	}

}
