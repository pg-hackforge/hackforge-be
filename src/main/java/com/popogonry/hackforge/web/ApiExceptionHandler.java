package com.popogonry.hackforge.web;

import java.util.List;

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
		return respond(Problem.of(e.getErrorCode()));
	}

	@ExceptionHandler
	ResponseEntity<Problem> invalid(MethodArgumentNotValidException e) {
		List<Problem.FieldError> errors = e.getBindingResult()
			.getFieldErrors()
			.stream()
			.map(f -> new Problem.FieldError(f.getField(), f.getDefaultMessage()))
			.toList();
		return respond(Problem.of(ErrorCode.VALIDATION_FAILED, errors));
	}

	@ExceptionHandler
	ResponseEntity<Problem> unreadable(HttpMessageNotReadableException e) {
		return respond(Problem.of(ErrorCode.VALIDATION_FAILED));
	}

	private static ResponseEntity<Problem> respond(Problem problem) {
		return ResponseEntity.status(problem.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
	}

}
