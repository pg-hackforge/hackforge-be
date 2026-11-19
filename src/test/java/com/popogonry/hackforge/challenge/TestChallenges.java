package com.popogonry.hackforge.challenge;

import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;

import org.springframework.jdbc.core.JdbcTemplate;

/** 테스트용 문제 행. 검증 스크립트와 해답은 자리 채움 값이고 힌트는 넣지 않는다 */
public final class TestChallenges {

	private TestChallenges() {
	}

	public static long insert(JdbcTemplate jdbcTemplate, String slug, String title, String image) {
		return jdbcTemplate.queryForObject("""
				insert into challenges (slug, title, image, description, solution, attack_check, exploit, functional_test,
				  max_defense_attempts, category_key, difficulty, estimated_minutes, summary)
				values (?, ?, ?, ?, '해답', 'exit 1', 'exit 1', 'exit 0', 5, 'sqli', 'INTRO', 30, ?) returning id
				""", Long.class, slug, title, image, title + " 설명", title + " 요약");
	}

	/** 목록 테스트용. createdAgo만큼 전에 처음 등록한 문제 */
	public static long insertListed(JdbcTemplate jdbcTemplate, String slug, String title, String summary, String category,
			String difficulty, int minutes, Duration createdAgo) {
		return jdbcTemplate.queryForObject("""
				insert into challenges (slug, title, image, description, solution, attack_check, exploit, functional_test,
				  max_defense_attempts, category_key, difficulty, estimated_minutes, summary, created_at)
				values (?, ?, 'x:1', ?, '해답', 'exit 1', 'exit 1', 'exit 0', 5, ?, ?, ?, ?, ?) returning id
				""", Long.class, slug, title, title + " 설명", category, difficulty, minutes, summary,
				Timestamp.from(Instant.now().minus(createdAgo)));
	}

}
