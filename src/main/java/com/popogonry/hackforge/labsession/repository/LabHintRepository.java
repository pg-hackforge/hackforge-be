package com.popogonry.hackforge.labsession.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.popogonry.hackforge.labsession.domain.LabStage;
import com.popogonry.hackforge.labsession.dto.HintResponse;

import lombok.RequiredArgsConstructor;

/** 공개한 힌트(ADR 023). 힌트 내용은 문제의 {@code challenge_hints}에 있고 여기에는 공개 시각만 남긴다 */
@Repository
@RequiredArgsConstructor
public class LabHintRepository {

	private final JdbcTemplate jdbcTemplate;

	public int countRevealed(long sessionId, LabStage stage) {
		return jdbcTemplate.queryForObject("select count(*) from lab_session_hints where session_id = ? and stage = ?",
				Integer.class, sessionId, stage.name());
	}

	/** 문제의 그 단계 힌트 수 */
	public int countHints(long challengeId, LabStage stage) {
		return jdbcTemplate.queryForObject("select count(*) from challenge_hints where challenge_id = ? and stage = ?",
				Integer.class, challengeId, stage.name());
	}

	public String content(long challengeId, LabStage stage, int position) {
		return jdbcTemplate.queryForObject(
				"select content from challenge_hints where challenge_id = ? and stage = ? and position = ?", String.class,
				challengeId, stage.name(), position);
	}

	public void reveal(long sessionId, LabStage stage, int position, Instant at) {
		jdbcTemplate.update("insert into lab_session_hints (session_id, stage, position, revealed_at) values (?, ?, ?, ?)",
				sessionId, stage.name(), position, Timestamp.from(at));
	}

	/** 공개한 힌트와 내용. 공개 순서 */
	public List<HintResponse> findRevealed(long sessionId, long challengeId) {
		return jdbcTemplate.query("""
				select h.stage, h.position, c.content, h.revealed_at
				from lab_session_hints h
				join challenge_hints c on c.challenge_id = ? and c.stage = h.stage and c.position = h.position
				where h.session_id = ?
				order by h.revealed_at, h.stage, h.position
				""", (rs, row) -> new HintResponse(LabStage.valueOf(rs.getString("stage")), rs.getInt("position"),
				rs.getString("content"), rs.getTimestamp("revealed_at").toInstant()), challengeId, sessionId);
	}

}
