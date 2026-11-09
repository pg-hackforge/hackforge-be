package com.popogonry.hackforge.user.controller;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.popogonry.hackforge.user.dto.ChangePasswordRequest;
import com.popogonry.hackforge.user.dto.UserResponse;
import com.popogonry.hackforge.user.service.UserService;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
class UserController {

	private final UserService userService;

	/** 승인 상태는 세션이 아니라 DB에서 매번 읽는다. */
	@GetMapping("/api/me")
	UserResponse me(Authentication authentication) {
		return UserResponse.from(userService.getCurrentUser(Long.parseLong(authentication.getName())));
	}

	@PutMapping("/api/me/password")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void changePassword(@Valid @RequestBody ChangePasswordRequest body, Authentication authentication,
			HttpSession session) {
		userService.changePassword(Long.parseLong(authentication.getName()), session.getId(), body.currentPassword(),
				body.newPassword());
	}

}
