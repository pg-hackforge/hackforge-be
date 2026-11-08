package com.popogonry.hackforge;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class HackforgeApplicationTests {

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	SessionRepository<? extends Session> sessionRepository;

	@Test
	void migrationCreatesSessionTables() {
		Integer applied = jdbcTemplate.queryForObject(
				"select count(*) from flyway_schema_history where version = '1' and success", Integer.class);
		assertThat(applied).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("select count(*) from spring_session", Integer.class)).isZero();
		assertThat(jdbcTemplate.queryForObject("select count(*) from spring_session_attributes", Integer.class)).isZero();
	}

	@Test
	void sessionIdleTimeoutIs30Minutes() {
		assertThat(sessionRepository.createSession().getMaxInactiveInterval()).isEqualTo(Duration.ofMinutes(30));
	}

}
