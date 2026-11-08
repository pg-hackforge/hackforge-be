package com.popogonry.hackforge.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** openapi.yaml `LoginRequest`. */
public record LoginRequest(
		@NotBlank @Size(max = 254) String email,
		@NotBlank @Size(max = 64) String password
) {
}
