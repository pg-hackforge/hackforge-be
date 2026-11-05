package com.popogonry.hackforge.account;

import java.time.Instant;
import java.util.Locale;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.popogonry.hackforge.account.AccountDtos.SignupRequest;
import com.popogonry.hackforge.web.ApiException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
class AccountService {

	private final UserRepository users;

	private final PasswordEncoder encoder;

	User signup(SignupRequest r) {
		User user = new User(normalize(r.email()), encoder.encode(r.password()), r.name(), r.nickname(),
				r.affiliation(), r.signupNote(), Instant.now());
		try {
			// 중복은 앞서 조회하지 않고 unique constraint로 막는다. 동시에 가입해도 하나만 들어감
			return users.saveAndFlush(user);
		}
		catch (DataIntegrityViolationException e) {
			throw duplicate(e);
		}
	}

	/** 이메일이 없든 비밀번호가 틀리든 같은 오류. */
	User authenticate(String email, String password) {
		return users.findByEmail(normalize(email))
			.filter(u -> encoder.matches(password, u.getPasswordHash()))
			.orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
					"이메일이나 비밀번호가 맞지 않습니다"));
	}

	User get(long id) {
		return users.findById(id)
			.orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "로그인이 필요합니다"));
	}

	private static String normalize(String email) {
		return email.toLowerCase(Locale.ROOT);
	}

	private static RuntimeException duplicate(DataIntegrityViolationException e) {
		String constraint = e.getCause() instanceof ConstraintViolationException c ? c.getConstraintName() : null;
		if ("users_email_uk".equals(constraint)) {
			return new ApiException(HttpStatus.CONFLICT, "EMAIL_TAKEN", "이미 가입된 이메일입니다");
		}
		if ("users_nickname_uk".equals(constraint)) {
			return new ApiException(HttpStatus.CONFLICT, "NICKNAME_TAKEN", "이미 사용 중인 닉네임입니다");
		}
		return e;
	}

}
