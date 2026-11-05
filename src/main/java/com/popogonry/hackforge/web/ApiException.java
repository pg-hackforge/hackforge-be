package com.popogonry.hackforge.web;

import lombok.Getter;

/** openapi.yaml `Problem`으로 응답하는 오류. */
@Getter
public class ApiException extends RuntimeException {

	private final ErrorCode errorCode;

	public ApiException(ErrorCode errorCode) {
		super(errorCode.getTitle());
		this.errorCode = errorCode;
	}

}
