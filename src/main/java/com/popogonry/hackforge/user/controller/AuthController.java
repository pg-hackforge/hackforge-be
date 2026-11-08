package com.popogonry.hackforge.user.controller;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.popogonry.hackforge.user.UserService;
import com.popogonry.hackforge.user.domain.User;
import com.popogonry.hackforge.user.dto.LoginRequest;
import com.popogonry.hackforge.user.dto.SignupRequest;
import com.popogonry.hackforge.user.dto.UserResponse;

import lombok.RequiredArgsConstructor;

/** 가입, 로그인. 로그아웃은 SecurityConfig의 logout filter가 처리한다. */
@RestController
@RequiredArgsConstructor
class AuthController {

	private final UserService userService;

	private final CsrfTokenRepository csrfTokenRepository;

	private final SecurityContextRepository securityContextRepository = new HttpSessionSecurityContextRepository();

	@PostMapping("/api/auth/signup")
	@ResponseStatus(HttpStatus.CREATED)
	UserResponse signup(@Valid @RequestBody SignupRequest body) {
		return UserResponse.from(userService.signup(body));
	}

	@PostMapping("/api/auth/login")
	UserResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
			HttpServletResponse response) {
		User user = userService.authenticate(body.email(), body.password());

		// 세션 고정 방지: 기존 세션이 있으면 id를 바꾸고, CSRF token도 새로 발급한다
		if (request.getSession(false) != null) {
			request.changeSessionId();
		}
		csrfTokenRepository.saveToken(csrfTokenRepository.generateToken(request), request, response);

		// 세션에는 사용자 id(principal name)와 역할만 둔다 (ADR 011)
		Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(user.getId().toString(),
				null, List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(authentication);
		SecurityContextHolder.setContext(context);
		securityContextRepository.saveContext(context, request, response);
		return UserResponse.from(user);
	}

}
