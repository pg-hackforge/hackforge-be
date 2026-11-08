package com.popogonry.hackforge.user.service;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.popogonry.hackforge.user.domain.User;
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
