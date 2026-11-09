package com.popogonry.hackforge.user.controller;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.popogonry.hackforge.user.domain.UserStatus;
import com.popogonry.hackforge.user.dto.AdminUserResponse;
import com.popogonry.hackforge.user.dto.ChangeRoleRequest;
import com.popogonry.hackforge.user.dto.TemporaryPasswordResponse;
import com.popogonry.hackforge.user.service.AdminUserService;

import lombok.RequiredArgsConstructor;

/** `/api/admin/**`는 SecurityConfig에서 ADMIN만 통과시킨다. */
@RestController
@RequiredArgsConstructor
class AdminUserController {

	private final AdminUserService adminUserService;

	@GetMapping("/api/admin/users")
	List<AdminUserResponse> list(@RequestParam(defaultValue = "PENDING") UserStatus status) {
		return adminUserService.list(status).stream().map(AdminUserResponse::from).toList();
	}

	@PostMapping("/api/admin/users/{id}/approve")
	AdminUserResponse approve(@PathVariable long id) {
		return AdminUserResponse.from(adminUserService.approve(id));
	}

	@PostMapping("/api/admin/users/{id}/reject")
	AdminUserResponse reject(@PathVariable long id) {
		return AdminUserResponse.from(adminUserService.reject(id));
	}

	@PutMapping("/api/admin/users/{id}/role")
	AdminUserResponse changeRole(@PathVariable long id, @Valid @RequestBody ChangeRoleRequest body,
			Authentication authentication) {
		long adminId = Long.parseLong(authentication.getName());
		return AdminUserResponse.from(adminUserService.changeRole(adminId, id, body.role()));
	}

	/** 비밀번호가 담긴 응답이라 브라우저·프록시가 저장하지 않게 한다. */
	@PostMapping("/api/admin/users/{id}/temporary-password")
	ResponseEntity<TemporaryPasswordResponse> issueTemporaryPassword(@PathVariable long id) {
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(new TemporaryPasswordResponse(adminUserService.issueTemporaryPassword(id)));
	}

}
