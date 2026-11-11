package com.popogonry.hackforge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** challenges, lab_sessions 테이블 제약(ADR 012)과 개발 전용 seed. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class LabSchemaTests {

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
		challengeId = jdbcTemplate.queryForObject(
				"insert into challenges (slug, title, image) values ('test-web', '테스트', 'test-web:0.1.0') returning id",
				Long.class);
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
		assertThatThrownBy(() -> jdbcTemplate
			.update("insert into challenges (slug, title, image) values ('Test_Web', 't', 'i')"))
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

		String slug = jdbcTemplate.queryForObject("select slug from dev_seed_check.challenges", String.class);

		assertThat(slug).isEqualTo("test-web");
		jdbcTemplate.execute("drop schema dev_seed_check cascade");
	}

}
