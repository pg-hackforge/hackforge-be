package com.popogonry.hackforge.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** openapi.yaml `ChangePasswordRequest`. 새 비밀번호 규칙은 가입과 같다. */
public record ChangePasswordRequest(
		@NotBlank @Size(max = 64) String currentPassword,
		@NotNull @Pattern(regexp = SignupRequest.PASSWORD, message = "8~64자, 영문·숫자·특수문자(ASCII)를 각각 하나 이상") String newPassword
) {
}
