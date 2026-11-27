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

	/** 공격 힌트 3개, 방어 힌트 3개. 내용은 "공격 힌트 1"처럼 단계와 순서 */
	public static void insertHints(JdbcTemplate jdbcTemplate, long challengeId) {
		for (String stage : new String[] { "ATTACK", "DEFENSE" }) {
			for (int position = 1; position <= 3; position++) {
				jdbcTemplate.update("insert into challenge_hints (challenge_id, stage, position, content) values (?, ?, ?, ?)",
						challengeId, stage, position, (stage.equals("ATTACK") ? "공격" : "방어") + " 힌트 " + position);
			}
		}
	}

	/** 목록 테스트용. createdAgo만큼 전에 등록하고 바로 공개한 문제 */
	public static long insertListed(JdbcTemplate jdbcTemplate, String slug, String title, String summary, String category,
			String difficulty, int minutes, Duration createdAgo) {
		Timestamp at = Timestamp.from(Instant.now().minus(createdAgo));
		return jdbcTemplate.queryForObject("""
				insert into challenges (slug, title, image, description, solution, attack_check, exploit, functional_test,
				  max_defense_attempts, category_key, difficulty, estimated_minutes, summary, created_at, published_at)
				values (?, ?, 'x:1', ?, '해답', 'exit 1', 'exit 1', 'exit 0', 5, ?, ?, ?, ?, ?, ?) returning id
				""", Long.class, slug, title, title + " 설명", category, difficulty, minutes, summary, at, at);
	}

	/** 예정 문제(ADR 031). 목록 정보만 있고 이미지, 설명, 스크립트, 해답이 없다. createdAgo만큼 전에 등록 */
	public static long insertPlanned(JdbcTemplate jdbcTemplate, String slug, String category, String difficulty,
			int minutes, Duration createdAgo) {
		return jdbcTemplate.queryForObject("""
				insert into challenges (slug, title, category_key, difficulty, estimated_minutes, summary, status,
				  published_at, created_at)
				values (?, ?, ?, ?, ?, ?, 'PLANNED', null, ?) returning id
				""", Long.class, slug, slug + " 제목", category, difficulty, minutes, slug + " 요약",
				Timestamp.from(Instant.now().minus(createdAgo)));
	}

}
