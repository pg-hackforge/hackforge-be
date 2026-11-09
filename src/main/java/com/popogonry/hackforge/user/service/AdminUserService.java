package com.popogonry.hackforge.user.service;

import java.util.List;
import java.util.Set;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.popogonry.hackforge.user.domain.User;
import com.popogonry.hackforge.user.domain.UserRole;
import com.popogonry.hackforge.user.domain.UserStatus;
import com.popogonry.hackforge.user.repository.UserRepository;
import com.popogonry.hackforge.web.ApiException;
import com.popogonry.hackforge.web.ErrorCode;

import lombok.RequiredArgsConstructor;

/** 관리자의 계정 관리. 상태 전이는 ADR 011: PENDING → APPROVED·REJECTED, REJECTED → APPROVED. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminUserService {

	private final UserRepository userRepository;

	private final UserSessionService userSessionService;

	private final PasswordEncoder passwordEncoder;

	public List<User> list(UserStatus status) {
		return userRepository.findByStatusOrderByCreatedAtAscIdAsc(status);
	}

	@Transactional
	public User approve(long id) {
		return changeStatus(id, UserStatus.APPROVED, Set.of(UserStatus.PENDING, UserStatus.REJECTED));
	}

	@Transactional
	public User reject(long id) {
		return changeStatus(id, UserStatus.REJECTED, Set.of(UserStatus.PENDING));
	}

	/**
	 * 역할 변경. 세션에 든 역할은 로그인할 때 값이라, 대상의 세션을 모두 지워 다시 로그인하게 한다.
	 * 자기 역할은 바꿀 수 없다. 마지막 관리자가 자기를 내려 관리자가 0명이 되는 것을 막는다.
	 */
	@Transactional
	public User changeRole(long adminId, long targetId, UserRole role) {
		if (adminId == targetId) {
			throw new ApiException(ErrorCode.CANNOT_CHANGE_OWN_ROLE);
		}
		User user = getById(targetId);
		user.changeRole(role);
		userSessionService.deleteAll(targetId);
		return user;
	}

	/**
	 * 임시 비밀번호 발급. 새 비밀번호를 돌려주고 서버에는 hash만 남긴다.
	 * 기존 세션을 모두 지우고 로그인 잠금을 푼다.
	 */
	@Transactional
	public String issueTemporaryPassword(long targetId) {
		User user = getById(targetId);
		String temporaryPassword = TemporaryPasswordGenerator.generate();
		user.resetPassword(passwordEncoder.encode(temporaryPassword));
		userSessionService.deleteAll(targetId);
		return temporaryPassword;
	}

	private User getById(long id) {
		return userRepository.findById(id).orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));
	}

	/** 지금 상태를 확인하고 바꾸는 일을 update 한 문장으로 한다. 두 관리자가 동시에 눌러도 하나만 바뀜. */
	private User changeStatus(long id, UserStatus to, Set<UserStatus> from) {
		if (userRepository.changeStatus(id, to, from) == 0) {
			throw new ApiException(userRepository.existsById(id)
					? ErrorCode.INVALID_STATUS_TRANSITION
					: ErrorCode.USER_NOT_FOUND);
		}
		return userRepository.findById(id).orElseThrow();
	}

}
