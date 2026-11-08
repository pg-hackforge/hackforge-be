package com.popogonry.hackforge.web;

import org.springframework.http.HttpHeaders;

import lombok.Getter;

/** openapi.yaml `Problem`으로 응답하는 오류. */
@Getter
public class ApiException extends RuntimeException {

	private final ErrorCode errorCode;

	/** 응답에 같이 실을 header(`Retry-After` 등). */
	private final HttpHeaders headers;

	public ApiException(ErrorCode errorCode) {
		this(errorCode, HttpHeaders.EMPTY);
	}

	public ApiException(ErrorCode errorCode, HttpHeaders headers) {
		super(errorCode.getTitle());
		this.errorCode = errorCode;
		this.headers = headers;
	}

}
