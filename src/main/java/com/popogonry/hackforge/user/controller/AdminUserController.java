package com.popogonry.hackforge.user.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.popogonry.hackforge.user.domain.UserStatus;
import com.popogonry.hackforge.user.dto.AdminUserResponse;
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

}
