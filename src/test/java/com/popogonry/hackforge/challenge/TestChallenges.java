package com.popogonry.hackforge.challenge;

import org.springframework.jdbc.core.JdbcTemplate;

/** 테스트용 문제 행. 검증 스크립트와 해답은 자리 채움 값이고 힌트는 넣지 않는다 */
public final class TestChallenges {

	private TestChallenges() {
	}

	public static long insert(JdbcTemplate jdbcTemplate, String slug, String title, String image) {
		return jdbcTemplate.queryForObject("""
				insert into challenges (slug, title, image, description, solution, attack_check, exploit, functional_test,
				  max_defense_attempts)
				values (?, ?, ?, ?, '해답', 'exit 1', 'exit 1', 'exit 0', 5) returning id
				""", Long.class, slug, title, image, title + " 설명");
	}

}
