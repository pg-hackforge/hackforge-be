package com.popogonry.hackforge.account;

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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.popogonry.hackforge.account.AccountDtos.LoginRequest;
import com.popogonry.hackforge.account.AccountDtos.Me;
import com.popogonry.hackforge.account.AccountDtos.SignupRequest;

@RestController
class AccountController {

	private final AccountService accounts;

	private final CsrfTokenRepository csrfTokens;

	private final SecurityContextRepository contexts = new HttpSessionSecurityContextRepository();

	AccountController(AccountService accounts, CsrfTokenRepository csrfTokens) {
		this.accounts = accounts;
		this.csrfTokens = csrfTokens;
	}

	@PostMapping("/api/auth/signup")
	@ResponseStatus(HttpStatus.CREATED)
	Me signup(@Valid @RequestBody SignupRequest body) {
		return Me.of(accounts.signup(body));
	}

	@PostMapping("/api/auth/login")
	Me login(@Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
		User user = accounts.authenticate(body.email(), body.password());

		// 세션 고정 방지: 기존 세션이 있으면 id를 바꾸고, CSRF token도 새로 발급한다
		if (request.getSession(false) != null) {
			request.changeSessionId();
		}
		csrfTokens.saveToken(csrfTokens.generateToken(request), request, response);

		// 세션에는 사용자 id(principal name)와 역할만 둔다 (ADR 011)
		Authentication auth = UsernamePasswordAuthenticationToken.authenticated(user.getId().toString(), null,
				List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(auth);
		SecurityContextHolder.setContext(context);
		contexts.saveContext(context, request, response);
		return Me.of(user);
	}

	/** 승인 상태는 세션이 아니라 DB에서 매번 읽는다. */
	@GetMapping("/api/me")
	Me me(Authentication auth) {
		return Me.of(accounts.get(Long.parseLong(auth.getName())));
	}

}
