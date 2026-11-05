package com.popogonry.hackforge.web;

import org.springframework.http.HttpStatus;

/** openapi.yaml `Problem.code`로 응답하는 오류. */
public class ApiException extends RuntimeException {

	private final HttpStatus status;

	private final String code;

	public ApiException(HttpStatus status, String code, String title) {
		super(title);
		this.status = status;
		this.code = code;
	}

	public Problem toProblem() {
		return new Problem(getMessage(), status.value(), code, null);
	}

	public HttpStatus status() {
		return status;
	}

}
