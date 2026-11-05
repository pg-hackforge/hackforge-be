package com.popogonry.hackforge.web;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiExceptionHandler {

	@ExceptionHandler
	ResponseEntity<Problem> api(ApiException e) {
		return respond(e.status(), e.toProblem());
	}

	@ExceptionHandler
	ResponseEntity<Problem> invalid(MethodArgumentNotValidException e) {
		List<Problem.FieldError> errors = e.getBindingResult()
			.getFieldErrors()
			.stream()
			.map(f -> new Problem.FieldError(f.getField(), f.getDefaultMessage()))
			.toList();
		return respond(HttpStatus.BAD_REQUEST, new Problem("입력값이 규칙에 맞지 않습니다", 400, "VALIDATION_FAILED", errors));
	}

	@ExceptionHandler
	ResponseEntity<Problem> unreadable(HttpMessageNotReadableException e) {
		return respond(HttpStatus.BAD_REQUEST, new Problem("요청 본문을 읽을 수 없습니다", 400, "VALIDATION_FAILED", null));
	}

	private static ResponseEntity<Problem> respond(HttpStatus status, Problem problem) {
		return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
	}

}
