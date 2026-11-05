package com.popogonry.hackforge.account;

import java.time.Instant;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** openapi.yaml의 계정 schema. 규칙을 바꾸면 openapi.yaml도 같이 바꾼다. */
final class AccountDtos {

	/** ASCII(공백 포함 키보드 기호)만. bcrypt는 72바이트까지만 처리하므로 64자 = 64바이트로 둔다. */
	static final String PASSWORD = "^(?=.*[A-Za-z])(?=.*[0-9])(?=.*[^A-Za-z0-9])[\\x21-\\x7E]{8,64}$";

	static final String NICKNAME = "^[가-힣A-Za-z0-9_]{2,20}$";

	private AccountDtos() {
	}

	record SignupRequest(
			@NotBlank @Email @Size(max = 254) String email,
			@NotNull @Pattern(regexp = PASSWORD, message = "8~64자, 영문·숫자·특수문자(ASCII)를 각각 하나 이상") String password,
			@NotBlank @Size(max = 50) String name,
			@NotNull @Pattern(regexp = NICKNAME, message = "2~20자, 한글·영문·숫자·_") String nickname,
			@NotBlank @Size(max = 100) String affiliation,
			@Size(max = 200) String signupNote,
			@NotNull @AssertTrue Boolean privacyAgreed) {
	}

	record LoginRequest(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 64) String password) {
	}

	record Me(Long id, String email, String name, String nickname, String affiliation, UserRole role,
			UserStatus status, Instant createdAt) {

		static Me of(User u) {
			return new Me(u.getId(), u.getEmail(), u.getName(), u.getNickname(), u.getAffiliation(), u.getRole(),
					u.getStatus(), u.getCreatedAt());
		}

	}

}
