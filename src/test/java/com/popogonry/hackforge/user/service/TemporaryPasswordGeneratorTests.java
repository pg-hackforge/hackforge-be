package com.popogonry.hackforge.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.popogonry.hackforge.user.dto.SignupRequest;

class TemporaryPasswordGeneratorTests {

	/** 무작위라 한 번으로는 알 수 없으므로 여러 번 만들어 본다. */
	@Test
	void everyGeneratedPasswordSatisfiesPasswordRule() {
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < 1000; i++) {
			String password = TemporaryPasswordGenerator.generate();
			assertThat(password).matches(SignupRequest.PASSWORD);
			seen.add(password);
		}
		assertThat(seen).hasSize(1000);
	}

}
