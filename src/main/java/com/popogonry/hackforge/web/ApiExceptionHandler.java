package com.popogonry.hackforge.web;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import lombok.extern.slf4j.Slf4j;

/** 모든 오류를 openapi.yaml `Problem` 형식으로 응답한다. 공통 오류 규칙은 openapi.yaml `info.description`. */
@Slf4j
@RestControllerAdvice
class ApiExceptionHandler {

	@ExceptionHandler
	ResponseEntity<Problem> api(ApiException e) {
		return respond(Problem.of(e.getErrorCode()), e.getHeaders());
	}

	@ExceptionHandler
	ResponseEntity<Problem> invalid(MethodArgumentNotValidException e) {
		List<Problem.FieldError> errors = e.getBindingResult()
			.getFieldErrors()
			.stream()
			.map(f -> new Problem.FieldError(f.getField(), f.getDefaultMessage()))
			.toList();
		return respond(Problem.of(ErrorCode.VALIDATION_FAILED, errors), HttpHeaders.EMPTY);
	}

	@ExceptionHandler
	ResponseEntity<Problem> unreadable(HttpMessageNotReadableException e) {
		return respond(Problem.of(ErrorCode.VALIDATION_FAILED), HttpHeaders.EMPTY);
	}

	/** 경로·쿼리 값의 형식이 틀림. 예: `?status=FOO`, 숫자가 아닌 id. */
	@ExceptionHandler
	ResponseEntity<Problem> typeMismatch(MethodArgumentTypeMismatchException e) {
		List<Problem.FieldError> errors = List.of(new Problem.FieldError(e.getName(), "허용되지 않는 값입니다"));
		return respond(Problem.of(ErrorCode.VALIDATION_FAILED, errors), HttpHeaders.EMPTY);
	}

	/**
	 * 위에서 처리하지 않은 나머지.
	 * Spring MVC가 던지는 요청 오류(없는 경로, 받지 않는 method 등)는 상태 코드에 맞는 code로 바꾸고,
	 * 그 밖의 예외는 500으로 응답한다. 500 응답에는 원인을 넣지 않고 로그에만 남긴다.
	 */
	@ExceptionHandler
	ResponseEntity<Problem> other(Exception e) throws Exception {
		// 인증·권한 예외는 Spring Security filter가 401·403으로 처리하게 돌려보낸다
		if (e instanceof AuthenticationException || e instanceof AccessDeniedException) {
			throw e;
		}
		if (e instanceof ErrorResponse response) {
			ErrorCode code = switch (response.getStatusCode().value()) {
				case 400 -> ErrorCode.VALIDATION_FAILED;
				case 404 -> ErrorCode.NOT_FOUND;
				case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
				case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
				default -> null;
			};
			if (code == null) {
				// spec에 code가 없는 드문 요청 오류(406 등)는 Spring 기본 처리에 맡긴다
				throw e;
			}
			// 405의 Allow, 415의 Accept 같은 header는 Spring이 채워 둔 것을 그대로 쓴다
			return respond(Problem.of(code), response.getHeaders());
		}
		log.error("처리하지 못한 예외", e);
		return respond(Problem.of(ErrorCode.INTERNAL_ERROR), HttpHeaders.EMPTY);
	}

	private static ResponseEntity<Problem> respond(Problem problem, HttpHeaders headers) {
		return ResponseEntity.status(problem.status())
			.headers(headers)
			.contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.body(problem);
	}

}
