package com.popogonry.hackforge.user.service;

import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * 특정 사용자의 로그인 세션을 지운다. 세션의 principal name이 사용자 id라서(ADR 011) id로 찾을 수 있다.
 * 세션에 든 역할은 로그인할 때 값이므로, 역할이나 비밀번호를 바꾸면 세션을 지워 다시 로그인하게 한다.
 */
@Service
@RequiredArgsConstructor
public class UserSessionService {

	private final FindByIndexNameSessionRepository<? extends Session> sessionRepository;

	public void deleteAll(long userId) {
		sessionRepository.findByPrincipalName(String.valueOf(userId)).keySet().forEach(sessionRepository::deleteById);
	}

}
