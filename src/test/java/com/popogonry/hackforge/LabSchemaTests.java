package com.popogonry.hackforge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.popogonry.hackforge.challenge.TestChallenges;

/** challenges, challenge_categories, challenge_hints, lab_sessions 테이블 제약(ADR 012, ADR 014, ADR 020)과 개발 전용 seed. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class LabSchemaTests {

	@Autowired
	org.springframework.transaction.support.TransactionTemplate transactionTemplate;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	DataSource dataSource;

	long userId;

	long challengeId;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("delete from lab_sessions");
		jdbcTemplate.update("delete from challenges");
		jdbcTemplate.update("delete from spring_session");
		jdbcTemplate.update("delete from users");
		userId = jdbcTemplate.queryForObject("""
				insert into users (email, password_hash, name, nickname, affiliation, role, status, privacy_agreed_at, created_at)
				values ('kim@example.com', 'x', '김', 'kim', '학교', 'LEARNER', 'APPROVED', now(), now()) returning id
				""", Long.class);
		challengeId = TestChallenges.insert(jdbcTemplate, "test-web", "테스트", "test-web:0.1.0");
	}

	void insertSession(String status) {
		jdbcTemplate.update("""
				insert into lab_sessions (user_id, challenge_id, image, status, status_changed_at, created_at)
				values (?, ?, 'test-web:0.1.0', ?, now(), now())
				""", userId, challengeId, status);
	}

	long sessionCount() {
		return jdbcTemplate.queryForObject("select count(*) from lab_sessions", Long.class);
	}

	long sessionId() {
		return jdbcTemplate.queryForObject("select id from lab_sessions", Long.class);
	}

	/** ADR 023. 세션마다 실행 중 검증은 하나. 끝난 검증은 여러 개 */
	@Test
	void onlyOneRunningVerificationPerSession() {
		insertSession("READY");
		long id = sessionId();
		String insert = "insert into verifications (session_id, type, status, started_at) values (?, ?, ?, now())";
		jdbcTemplate.update(insert, id, "ATTACK", "PASS");
		jdbcTemplate.update(insert, id, "ATTACK", "FAIL");
		jdbcTemplate.update(insert, id, "ATTACK", "RUNNING");

		assertThatThrownBy(() -> jdbcTemplate.update(insert, id, "DEFENSE", "RUNNING"))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbcTemplate.update(insert, id, "ATTACK", "DONE"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	/** 같은 힌트는 한 번만 공개 기록. 세션을 지우면 검증과 힌트 기록도 지워진다 */
	@Test
	void hintRevealIsUniqueAndCascades() {
		insertSession("READY");
		long id = sessionId();
		String reveal = "insert into lab_session_hints (session_id, stage, position, revealed_at) values (?, 'ATTACK', ?, now())";
		jdbcTemplate.update(reveal, id, 1);
		assertThatThrownBy(() -> jdbcTemplate.update(reveal, id, 1)).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbcTemplate.update(reveal, id, 4)).isInstanceOf(DataIntegrityViolationException.class);
		jdbcTemplate.update(
				"insert into verifications (session_id, type, status, started_at) values (?, 'ATTACK', 'PASS', now())", id);

		jdbcTemplate.update("delete from lab_sessions");

		assertThat(jdbcTemplate.queryForObject("select count(*) from lab_session_hints", Long.class)).isZero();
		assertThat(jdbcTemplate.queryForObject("select count(*) from verifications", Long.class)).isZero();
	}

	/** ADR 024. 재배포·초기화도 세션마다 RUNNING 하나. 수정 불가 목록은 비워 둘 수 있다(기본값 사용) */
	@Test
	void onlyOneRunningRedeployPerSession() {
		insertSession("READY");
		long id = sessionId();
		String insert = "insert into redeploys (session_id, kind, status, started_at) values (?, ?, ?, now())";
		jdbcTemplate.update(insert, id, "REDEPLOY", "SUCCESS");
		jdbcTemplate.update(insert, id, "RESET", "RUNNING");

		assertThatThrownBy(() -> jdbcTemplate.update(insert, id, "REDEPLOY", "RUNNING"))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbcTemplate.update(insert, id, "BUILD", "FAIL"))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(jdbcTemplate.queryForObject("select readonly_paths from challenges", String.class)).isNull();
	}

	/** ADR 025. 방어 검증 결과 컬럼과 세션의 완료·해답 시각은 비어 있을 수 있다 */
	@Test
	void defenseColumnsAreNullable() {
		insertSession("READY");
		long id = sessionId();
		jdbcTemplate.update("insert into verifications (session_id, type, status, started_at) values (?, 'ATTACK', 'PASS', now())", id);
		jdbcTemplate.update("""
				insert into verifications (session_id, type, status, started_at, security_passed, functional_passed,
				  security_message, functional_message, defense_hints_revealed, after_solution)
				values (?, 'DEFENSE', 'FAIL', now(), false, true, 'a', 'b', 2, false)
				""", id);
		jdbcTemplate.update("update lab_sessions set completed_at = now(), solution_viewed_at = now() where id = ?", id);

		assertThatThrownBy(() -> jdbcTemplate.update(
				"insert into verifications (session_id, type, status, started_at, defense_hints_revealed) values (?, 'DEFENSE', 'FAIL', now(), 4)",
				id))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	/** ADR 026. 상태가 바뀔 때만 기록한다. 주체는 transaction 설정에서 읽고, 없으면 SYSTEM */
	@Test
	void statusChangesAreRecordedByTrigger() {
		insertSession("CREATING");
		long id = sessionId();
		jdbcTemplate.update("update lab_sessions set last_activity_at = now() where id = ?", id);
		transactionTemplate.executeWithoutResult(tx -> {
			jdbcTemplate.queryForObject("select set_config('hackforge.actor', 'ADMIN', true) || set_config('hackforge.actor_user_id', '7', true)", String.class);
			jdbcTemplate.update("update lab_sessions set status = 'TERMINATING', end_reason = 'ADMIN' where id = ?", id);
		});
		jdbcTemplate.update("update lab_sessions set status = 'DELETED' where id = ?", id);

		assertThat(jdbcTemplate.queryForList("""
				select coalesce(from_status, '-') || '>' || to_status || ':' || actor || ':' || coalesce(actor_user_id::text, '-')
				from session_events where session_id = ? order by id
				""", String.class, id))
			.containsExactly("->CREATING:SYSTEM:-", "CREATING>TERMINATING:ADMIN:7", "TERMINATING>DELETED:SYSTEM:-");
	}

	@Test
	void stageStartsAtAttackAndRejectsUnknown() {
		insertSession("READY");
		assertThat(jdbcTemplate.queryForObject("select stage from lab_sessions", String.class)).isEqualTo("ATTACK");
		assertThatThrownBy(() -> jdbcTemplate.update("update lab_sessions set stage = 'DONE'"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	/** ADR 021 */
	@Test
	void unknownPrepStepIsRejected() {
		insertSession("INITIALIZING");

		jdbcTemplate.update("update lab_sessions set prep_step = 'STARTING'");
		assertThatThrownBy(() -> jdbcTemplate.update("update lab_sessions set prep_step = 'READY'"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void secondActiveSessionOfSameUserIsRejected() {
		insertSession("CREATING");

		assertThatThrownBy(() -> insertSession("TERMINATING")).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void endedSessionsDoNotBlockNewSession() {
		insertSession("DELETED");
		insertSession("FAILED");
		insertSession("DELETED");

		insertSession("CREATING");

		assertThat(sessionCount()).isEqualTo(4);
	}

	@Test
	void deletingUserDeletesSessions() {
		insertSession("DELETED");
		insertSession("READY");

		jdbcTemplate.update("delete from users where id = ?", userId);

		assertThat(sessionCount()).isZero();
	}

	@Test
	void challengeWithSessionsCannotBeDeleted() {
		insertSession("DELETED");

		assertThatThrownBy(() -> jdbcTemplate.update("delete from challenges where id = ?", challengeId))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void unknownStatusIsRejected() {
		assertThatThrownBy(() -> insertSession("RUNNING")).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void slugAllowsOnlyLowercaseDigitsAndHyphen() {
		assertThatThrownBy(() -> TestChallenges.insert(jdbcTemplate, "Test_Web", "t", "i"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	void insertHint(String stage, int position) {
		jdbcTemplate.update("insert into challenge_hints (challenge_id, stage, position, content) values (?, ?, ?, 'h')",
				challengeId, stage, position);
	}

	long hintCount() {
		return jdbcTemplate.queryForObject("select count(*) from challenge_hints", Long.class);
	}

	@Test
	void hintsHaveThreePositionsPerStage() {
		for (String stage : new String[] { "ATTACK", "DEFENSE" }) {
			for (int position = 1; position <= 3; position++) {
				insertHint(stage, position);
			}
		}

		assertThat(hintCount()).isEqualTo(6);
		assertThatThrownBy(() -> insertHint("ATTACK", 1)).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertHint("ATTACK", 4)).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertHint("ATTACK", 0)).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insertHint("HINT", 1)).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void deletingChallengeDeletesHints() {
		insertHint("ATTACK", 1);
		insertHint("DEFENSE", 1);

		jdbcTemplate.update("delete from challenges where id = ?", challengeId);

		assertThat(hintCount()).isZero();
	}

	@Test
	void maxDefenseAttemptsMustBePositive() {
		assertThatThrownBy(() -> jdbcTemplate.update("update challenges set max_defense_attempts = 0"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	/** 분류 17개가 비슷한 주제끼리 표시 순서대로 들어 있다(ADR 020, ADR 032) */
	@Test
	void categoriesAreSeededInDisplayOrder() {
		var keys = jdbcTemplate.queryForList("select key from challenge_categories order by display_order", String.class);

		assertThat(keys).containsExactly("sqli", "nosql-injection", "xss", "ssti", "access-control", "auth-session",
				"business-logic", "command-injection", "path-traversal", "file-upload", "ssrf", "csrf", "xxe",
				"deserialization", "prototype-pollution", "misconfiguration", "info-disclosure");
	}

	/** ADR 031. 기존 문제는 공개 상태이고 공개 시각이 있다 */
	@Test
	void challengesArePublishedByDefault() {
		assertThat(jdbcTemplate.queryForObject("select status from challenges where id = ?", String.class, challengeId))
			.isEqualTo("PUBLISHED");
		assertThat(jdbcTemplate.queryForObject("select published_at is not null from challenges where id = ?",
				Boolean.class, challengeId))
			.isTrue();
	}

	/** 예정 문제는 실습 칸이 비어도 되고, 공개하려면 모두 채워야 한다 */
	@Test
	void publishedChallengeMustHaveLabContent() {
		long planned = TestChallenges.insertPlanned(jdbcTemplate, "planned", "sqli", "INTRO", 10, Duration.ZERO);

		assertThatThrownBy(() -> jdbcTemplate.update(
				"update challenges set status = 'PUBLISHED', published_at = now() where id = ?", planned))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbcTemplate.update("update challenges set image = null where id = ?", challengeId))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbcTemplate.update("update challenges set published_at = null where id = ?", challengeId))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbcTemplate.update("update challenges set status = 'DRAFT' where id = ?", challengeId))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void unknownCategoryIsRejected() {
		assertThatThrownBy(() -> jdbcTemplate.update("update challenges set category_key = 'sqlinjection'"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void categoryWithChallengesCannotBeDeleted() {
		assertThatThrownBy(() -> jdbcTemplate.update("delete from challenge_categories where key = 'sqli'"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void unknownDifficultyIsRejected() {
		assertThatThrownBy(() -> jdbcTemplate.update("update challenges set difficulty = 'EASY'"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void estimatedMinutesMustBePositive() {
		assertThatThrownBy(() -> jdbcTemplate.update("update challenges set estimated_minutes = 0"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void summaryIsOneToEightyCharacters() {
		jdbcTemplate.update("update challenges set summary = ?", "가".repeat(80));

		assertThatThrownBy(() -> jdbcTemplate.update("update challenges set summary = ?", "가".repeat(81)))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbcTemplate.update("update challenges set summary = ''"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	/** 형식의 모든 항목이 있어야 등록된다 */
	@Test
	void challengeWithoutScriptsIsRejected() {
		assertThatThrownBy(() -> jdbcTemplate
			.update("insert into challenges (slug, title, image, description) values ('no-scripts', 't', 'i', 'd')"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	/** 테스트·운영 설정의 Flyway location에는 db/dev가 없다. */
	@Test
	void devSeedIsNotAppliedOutsideBootRun() {
		Long applied = jdbcTemplate.queryForObject(
				"select count(*) from flyway_schema_history where script like '%dev_challenges%'", Long.class);

		assertThat(applied).isZero();
	}

	/** bootRun과 같은 location(db/migration + db/dev)으로 빈 schema에 적용하면 테스트 문제가 들어간다. */
	@Test
	void devSeedAddsTestChallenge() {
		jdbcTemplate.execute("drop schema if exists dev_seed_check cascade");
		Flyway.configure()
			.dataSource(dataSource)
			.schemas("dev_seed_check")
			.locations("classpath:db/migration", "classpath:db/dev")
			.load()
			.migrate();

		String slug = jdbcTemplate.queryForObject("select slug from dev_seed_check.challenges where status = 'PUBLISHED'",
				String.class);
		// 예정 문제 18개(ADR 031, hackforge-docs roadmap.md "문제 목록")는 migration이 넣는다
		Long planned = jdbcTemplate.queryForObject(
				"select count(*) from dev_seed_check.challenges where status = 'PLANNED' and image is null", Long.class);
		Long hints = jdbcTemplate.queryForObject("select count(*) from dev_seed_check.challenge_hints", Long.class);

		assertThat(slug).isEqualTo("test-web");
		assertThat(hints).isEqualTo(6);
		assertThat(planned).isEqualTo(18);
		jdbcTemplate.execute("drop schema dev_seed_check cascade");
	}

}
