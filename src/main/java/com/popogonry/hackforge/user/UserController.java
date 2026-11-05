package com.popogonry.hackforge.user;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
class UserController {

	private final UserService userService;

	/** 승인 상태는 세션이 아니라 DB에서 매번 읽는다. */
	@GetMapping("/api/me")
	UserResponse me(Authentication authentication) {
		return UserResponse.from(userService.get(Long.parseLong(authentication.getName())));
	}

}
