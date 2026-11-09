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
	ACCOUNT_LOCKED(HttpStatus.TOO_MANY_REQUESTS, "로그인을 여러 번 실패해 계정이 잠시 잠겼습니다"),
	UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다"),
	FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다"),
	CANNOT_CHANGE_OWN_ROLE(HttpStatus.FORBIDDEN, "자기 역할은 바꿀 수 없습니다"),
	CSRF_REJECTED(HttpStatus.FORBIDDEN, "CSRF token이 없거나 맞지 않습니다"),
	USER_NOT_FOUND(HttpStatus.NOT_FOUND, "없는 계정입니다"),
	INVALID_STATUS_TRANSITION(HttpStatus.CONFLICT, "지금 상태에서는 할 수 없는 처리입니다"),
	NOT_FOUND(HttpStatus.NOT_FOUND, "없는 경로입니다"),
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "이 경로에서 받지 않는 HTTP method입니다"),
	UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "요청 본문은 JSON이어야 합니다"),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버에서 오류가 났습니다");

	private final HttpStatus status;

	private final String title;

}
