package com.popogonry.hackforge.user.service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 관리자가 발급하는 임시 비밀번호. 비밀번호 규칙(영문·숫자·특수문자 각 1개 이상, 공백 없는 ASCII)을 만족한다.
 * 관리자가 사용자에게 직접 불러 주거나 옮겨 적으므로 헷갈리는 글자(0과 O, 1과 l, I)는 뺐다.
 */
final class TemporaryPasswordGenerator {

	private static final String LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz";

	private static final String DIGITS = "23456789";

	private static final String SYMBOLS = "!@#$%^&*-_=+?";

	private static final String ALL = LETTERS + DIGITS + SYMBOLS;

	private static final int LENGTH = 12;

	private static final SecureRandom RANDOM = new SecureRandom();

	private TemporaryPasswordGenerator() {
	}

	static String generate() {
		List<Character> chars = new ArrayList<>(LENGTH);
		// 종류마다 하나씩은 꼭 넣고, 나머지는 전체에서 고른 뒤 섞는다
		chars.add(pick(LETTERS));
		chars.add(pick(DIGITS));
		chars.add(pick(SYMBOLS));
		while (chars.size() < LENGTH) {
			chars.add(pick(ALL));
		}
		Collections.shuffle(chars, RANDOM);
		StringBuilder password = new StringBuilder(LENGTH);
		chars.forEach(password::append);
		return password.toString();
	}

	private static char pick(String from) {
		return from.charAt(RANDOM.nextInt(from.length()));
	}

}
