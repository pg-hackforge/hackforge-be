package com.popogonry.hackforge.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 테스트에만 있는 controller. 예상하지 못한 예외가 500 `INTERNAL_ERROR`로 나가는지 확인하는 데 쓴다. */
@RestController
class FailingTestController {

	static final String SECRET_DETAIL = "select password_hash from users";

	@GetMapping("/api/test/fail")
	void fail() {
		throw new IllegalStateException(SECRET_DETAIL);
	}

}
