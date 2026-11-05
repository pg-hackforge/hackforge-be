package com.popogonry.hackforge.web;

import org.springframework.http.HttpStatus;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** openapi.yaml `Problem.code`. 새 코드는 spec에 먼저 추가한다(ErrorCodeTests가 확인). */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

	VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "입력값이 규칙에 맞지 않습니다"),
	EMAIL_TAKEN(HttpStatus.CONFLICT, "이미 가입된 이메일입니다"),
	NICKNAME_TAKEN(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다"),
	INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일이나 비밀번호가 맞지 않습니다"),
	UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다"),
	FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다"),
	CSRF_REJECTED(HttpStatus.FORBIDDEN, "CSRF token이 없거나 맞지 않습니다");

	private final HttpStatus status;

	private final String title;

}
