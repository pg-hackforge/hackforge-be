package com.popogonry.hackforge.user;

import java.time.Instant;
import java.util.Locale;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.popogonry.hackforge.web.ApiException;
import com.popogonry.hackforge.web.ErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
class UserService {

	private final UserRepository userRepository;

	private final PasswordEncoder passwordEncoder;

	User signup(SignupRequest request) {
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

	/** 이메일이 없든 비밀번호가 틀리든 같은 오류. */
	User authenticate(String email, String password) {
		return userRepository.findByEmail(normalize(email))
			.filter(user -> passwordEncoder.matches(password, user.getPasswordHash()))
			.orElseThrow(() -> new ApiException(ErrorCode.INVALID_CREDENTIALS));
	}

	/**
	 * 세션에 든 사용자 id로 조회. 계정이 없으면 세션이 무효라 다시 로그인하게 401로 응답한다.
	 * id로 다른 사용자를 찾는 곳(관리자 API)은 이 메서드 대신 404 `USER_NOT_FOUND`를 쓴다.
	 */
	User getCurrentUser(long id) {
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
