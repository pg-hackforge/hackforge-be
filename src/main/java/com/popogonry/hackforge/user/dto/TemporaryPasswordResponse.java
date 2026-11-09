package com.popogonry.hackforge.user.dto;

/** openapi.yaml `TemporaryPasswordResponse`. 이 응답으로 한 번만 보여 주고 서버에는 hash만 남는다. */
public record TemporaryPasswordResponse(
		String temporaryPassword
) {
}
