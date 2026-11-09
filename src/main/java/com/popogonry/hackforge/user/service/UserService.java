package com.popogonry.hackforge.user.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.popogonry.hackforge.user.domain.User;
import com.popogonry.hackforge.user.dto.SignupRequest;
import com.popogonry.hackforge.user.repository.UserRepository;
import com.popogonry.hackforge.web.ApiException;
import com.popogonry.hackforge.web.ErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

	static final int MAX_FAILED_LOGINS = 5;

	static final Duration LOCK_DURATION = Duration.ofMinutes(15);

	private final UserRepository userRepository;

	private final PasswordEncoder passwordEncoder;

	private final UserSessionService userSessionService;

	@Transactional
	public User signup(SignupRequest request) {
		User user = new User(normalize(request.email()), passwordEncoder.encode(request.password()), request.name(),
				request.nickname(), request.affiliation(), request.signupNote(), Instant.now());
		try {
			// 중복은 앞서 조회하지 않고 unique constraint로 막는다. 동시에 가입해도 하나만 들어감
			return userRepository.saveAndFlush(user);
		}
		catch (DataIntegrityViolationException e) {
			throw duplicate(e);
		}
	}

	/**
	 * 이메일이 없든 비밀번호가 틀리든 같은 오류. 같은 계정으로 5번 연속 틀리면 15분 잠근다(ADR 011).
	 * 잠긴 동안은 비밀번호를 확인하지 않는다. 이미 로그인한 세션은 건드리지 않는다.
	 * 실패 응답(ApiException)을 던져도 늘린 실패 횟수는 commit해야 해서 rollback하지 않는다.
	 */
	@Transactional(noRollbackFor = ApiException.class)
	public User authenticate(String email, String password) {
		User user = userRepository.findByEmail(normalize(email))
			.orElseThrow(() -> new ApiException(ErrorCode.INVALID_CREDENTIALS));
		Instant now = Instant.now();
		if (user.isLockedAt(now)) {
			throw locked(Duration.between(now, user.getLockedUntil()));
		}
		if (!passwordEncoder.matches(password, user.getPasswordHash())) {
			if (recordFailure(user.getId(), now)) {
				throw locked(LOCK_DURATION);
			}
			throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
		}
		userRepository.resetFailedLoginCount(user.getId());
		return user;
	}

	/**
	 * 내 비밀번호 변경. 현재 비밀번호가 틀리면 로그인 실패와 같은 횟수에 더한다.
	 * 이 요청으로 5번째가 되면 잠그고 그 계정의 세션을 모두 끊는다. 세션을 가진 사람이 이 API로
	 * 비밀번호를 대입해 로그인 잠금을 피하지 못하게 하려는 것(ADR 011). 성공하면 지금 세션만 남긴다.
	 */
	@Transactional(noRollbackFor = ApiException.class)
	public void changePassword(long userId, String currentSessionId, String currentPassword, String newPassword) {
		User user = getCurrentUser(userId);
		Instant now = Instant.now();
		if (user.isLockedAt(now)) {
			throw locked(Duration.between(now, user.getLockedUntil()));
		}
		if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
			if (recordFailure(userId, now)) {
				userSessionService.deleteAll(userId);
				throw locked(LOCK_DURATION);
			}
			throw new ApiException(ErrorCode.CURRENT_PASSWORD_MISMATCH);
		}
		user.changePassword(passwordEncoder.encode(newPassword));
		userRepository.resetFailedLoginCount(userId);
		userSessionService.deleteAllExcept(userId, currentSessionId);
	}

	/** 실패 횟수를 1 올리고, 이번 실패로 잠겼으면 true. 로그인과 비밀번호 변경이 같은 횟수를 쓴다. */
	private boolean recordFailure(long userId, Instant now) {
		userRepository.incrementFailedLoginCount(userId);
		return userRepository.lockIfFailedAtLeast(userId, MAX_FAILED_LOGINS, now.plus(LOCK_DURATION)) == 1;
	}

	/** 남은 시간을 초 단위로 올림해 `Retry-After`에 담는다. */
	private static ApiException locked(Duration remaining) {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.RETRY_AFTER, String.valueOf((remaining.toMillis() + 999) / 1000));
		return new ApiException(ErrorCode.ACCOUNT_LOCKED, headers);
	}

	/**
	 * 세션에 든 사용자 id로 조회. 계정이 없으면 세션이 무효라 다시 로그인하게 401로 응답한다.
	 * id로 다른 사용자를 찾는 곳(관리자 API)은 이 메서드 대신 404 `USER_NOT_FOUND`를 쓴다.
	 */
	public User getCurrentUser(long id) {
		return userRepository.findById(id)
			.orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
	}

	private static String normalize(String email) {
		return email.toLowerCase(Locale.ROOT);
	}

	private static RuntimeException duplicate(DataIntegrityViolationException e) {
		String constraint = e.getCause() instanceof ConstraintViolationException c ? c.getConstraintName() : null;
		if ("users_email_uk".equals(constraint)) {
			return new ApiException(ErrorCode.EMAIL_TAKEN);
		}
		if ("users_nickname_uk".equals(constraint)) {
			return new ApiException(ErrorCode.NICKNAME_TAKEN);
		}
		return e;
	}

}
