package com.popogonry.hackforge.web;

import org.springframework.http.HttpStatus;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** openapi.yaml `Problem.code`. 새 코드는 spec에 먼저 추가한다(ErrorCodeTests가 확인). */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

	VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "입력한 내용을 다시 확인해 주세요"),
	EMAIL_TAKEN(HttpStatus.CONFLICT, "이미 가입된 이메일이에요"),
	NICKNAME_TAKEN(HttpStatus.CONFLICT, "이미 사용 중인 닉네임이에요"),
	INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호를 다시 확인해 주세요"),
	ACCOUNT_LOCKED(HttpStatus.TOO_MANY_REQUESTS, "비밀번호를 여러 번 잘못 입력해서 잠시 로그인할 수 없어요"),
	CURRENT_PASSWORD_MISMATCH(HttpStatus.BAD_REQUEST, "현재 비밀번호가 맞지 않아요"),
	UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "로그인이 필요해요"),
	FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없어요"),
	CANNOT_CHANGE_OWN_ROLE(HttpStatus.FORBIDDEN, "내 권한은 바꿀 수 없어요"),
	CSRF_REJECTED(HttpStatus.FORBIDDEN, "보안 토큰이 없거나 맞지 않아요. 페이지를 새로 고친 뒤 다시 시도해 주세요"),
	USER_NOT_FOUND(HttpStatus.NOT_FOUND, "회원을 찾을 수 없어요"),
	INVALID_STATUS_TRANSITION(HttpStatus.CONFLICT, "이미 처리된 회원이에요"),
	ACCOUNT_NOT_APPROVED(HttpStatus.FORBIDDEN, "가입이 승인되면 실습을 시작할 수 있어요"),
	CHALLENGE_NOT_FOUND(HttpStatus.NOT_FOUND, "문제를 찾을 수 없어요"),
	LAB_NOT_FOUND(HttpStatus.NOT_FOUND, "실습을 찾을 수 없어요"),
	LAB_ALREADY_ACTIVE(HttpStatus.CONFLICT, "이미 진행 중인 실습이 있어요"),
	LAB_NOT_READY(HttpStatus.CONFLICT, "실습 환경이 아직 준비되지 않았어요"),
	LAB_CAPACITY_FULL(HttpStatus.SERVICE_UNAVAILABLE, "지금은 실습 자리가 모두 찼어요. 잠시 뒤 다시 시도해 주세요"),
	LAB_JOB_RUNNING(HttpStatus.CONFLICT, "검증, 재배포, 초기화 중 하나가 진행 중이에요. 끝난 뒤 다시 시도해 주세요"),
	VERIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "검증 기록을 찾을 수 없어요"),
	WRONG_LAB_STAGE(HttpStatus.CONFLICT, "지금 단계에서는 할 수 없어요"),
	NO_MORE_HINTS(HttpStatus.CONFLICT, "더 볼 수 있는 힌트가 없어요"),
	NOT_FOUND(HttpStatus.NOT_FOUND, "없는 경로예요"),
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "이 경로에서 지원하지 않는 HTTP method예요"),
	UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "요청 본문은 JSON이어야 해요"),
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 생겼어요. 잠시 뒤 다시 시도해 주세요");

	private final HttpStatus status;

	private final String title;

}
