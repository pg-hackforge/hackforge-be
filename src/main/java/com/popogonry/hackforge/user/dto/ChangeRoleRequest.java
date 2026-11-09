package com.popogonry.hackforge.user.dto;

import jakarta.validation.constraints.NotNull;

import com.popogonry.hackforge.user.domain.UserRole;

/** openapi.yaml `ChangeRoleRequest`. */
public record ChangeRoleRequest(
		@NotNull UserRole role
) {
}
