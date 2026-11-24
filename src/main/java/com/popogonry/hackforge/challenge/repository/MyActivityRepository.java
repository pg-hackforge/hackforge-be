package com.popogonry.hackforge.challenge.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.popogonry.hackforge.challenge.domain.ChallengeActivityResult;
import com.popogonry.hackforge.challenge.domain.ChallengeDifficulty;
import com.popogonry.hackforge.challenge.dto.ActivityDayResponse;
import com.popogonry.hackforge.challenge.dto.AttackActivityResponse;
import com.popogonry.hackforge.challenge.dto.ChallengeActivityResponse;
import com.popogonry.hackforge.challenge.dto.ChallengeCategoryResponse;
import com.popogonry.hackforge.challenge.dto.DefenseActivityResponse;

import lombok.RequiredArgsConstructor;

/**
 * 내 분석의 사실(ADR 030). 판정은 화면이 하고 여기서는 센다. 생성 실패(FAILED) 세션은 어디에도 넣지 않는다.
 * ponytail: 요청마다 내 세션 전체를 집계한다. 학습자 한 명의 세션은 수백 개 수준이라 충분하고, 느려지면 학습자별 요약을 둔다
 */
@Repository
@RequiredArgsConstructor
public class MyActivityRepository {

	/** 날짜는 학습자가 있는 한국 시간으로 자른다 */
	private static final String ZONE = "Asia/Seoul";

	/**
	 * 결과가 난 세션(끝났거나 완료)마다 결과와 공격·방어 사실, 문제마다 가장 좋은 세션 하나.
	 * 해답 확인 후 완료는 해답을 연 시각이 완료 이전일 때. 방어 검증은 판정이 난 것(PASS, FAIL)만, 완료한 세션은 완료까지만 센다
	 */
	private static final String CHALLENGES = """
			with s as (
			  select ls.id, ls.challenge_id, ls.created_at, ls.attack_skipped,
			    case when ls.completed_at is null then 'UNFINISHED'
			      when ls.solution_viewed_at is not null and ls.solution_viewed_at <= ls.completed_at
			        then 'COMPLETED_AFTER_SOLUTION'
			      else 'COMPLETED' end as result,
			    exists (select 1 from verifications v where v.session_id = ls.id and v.type = 'ATTACK'
			      and v.status = 'PASS') as attack_passed,
			    (select count(*) from lab_session_hints h where h.session_id = ls.id and h.stage = 'ATTACK') as attack_hints,
			    (select count(*) from lab_session_hints h where h.session_id = ls.id and h.stage = 'DEFENSE') as defense_hints,
			    (select count(*) from verifications v where v.session_id = ls.id and v.type = 'DEFENSE'
			      and v.status in ('PASS', 'FAIL') and (ls.completed_at is null or v.started_at <= ls.completed_at))
			      as defense_attempts
			  from lab_sessions ls
			  where ls.user_id = :userId and ls.status <> 'FAILED'
			    and (ls.completed_at is not null or ls.status in ('TERMINATING', 'DELETED'))
			), ranked as (
			  select s.*, count(*) over (partition by s.challenge_id) as sessions,
			    row_number() over (partition by s.challenge_id order by
			      array_position(array['COMPLETED', 'COMPLETED_AFTER_SOLUTION', 'UNFINISHED'], s.result),
			      s.attack_hints + s.defense_hints, s.defense_attempts, s.created_at desc, s.id desc) as best
			  from s
			)
			select r.challenge_id, c.title, cat.key as category_key, cat.name as category_name, c.difficulty, r.sessions,
			  r.result, r.attack_passed, r.attack_skipped, r.attack_hints, r.defense_attempts, r.defense_hints
			from ranked r join challenges c on c.id = r.challenge_id join challenge_categories cat on cat.key = c.category_key
			where r.best = 1
			order by r.challenge_id
			""";

	private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

	public List<ChallengeActivityResponse> findChallenges(long userId) {
		return namedParameterJdbcTemplate.query(CHALLENGES, Map.of("userId", userId),
				(rs, row) -> new ChallengeActivityResponse(rs.getLong("challenge_id"), rs.getString("title"),
						new ChallengeCategoryResponse(rs.getString("category_key"), rs.getString("category_name")),
						ChallengeDifficulty.valueOf(rs.getString("difficulty")), rs.getLong("sessions"),
						ChallengeActivityResult.valueOf(rs.getString("result")),
						new AttackActivityResponse(rs.getBoolean("attack_passed"), rs.getBoolean("attack_skipped"),
								rs.getInt("attack_hints")),
						new DefenseActivityResponse(rs.getInt("defense_attempts"), rs.getInt("defense_hints"))));
	}

	/** 최근 365일(오늘 포함) 날짜별 시작한 세션 수. 0인 날도 넣는다 */
	public List<ActivityDayResponse> countDays(long userId) {
		String sql = """
				select d.day, count(ls.id) as count
				from (select generate_series(today - 364, today, interval '1 day')::date as day
				  from (select (now() at time zone '%1$s')::date as today) t) d
				left join lab_sessions ls on ls.user_id = :userId and ls.status <> 'FAILED'
				  and (ls.created_at at time zone '%1$s')::date = d.day
				group by d.day order by d.day
				""".formatted(ZONE);
		return namedParameterJdbcTemplate.query(sql, Map.of("userId", userId),
				(rs, row) -> new ActivityDayResponse(rs.getObject("day", LocalDate.class), rs.getLong("count")));
	}

	/** 세션 수와 시작부터 종료(진행 중이면 지금)까지 시간의 합(분, 버림) */
	public Totals sumSessions(long userId) {
		return namedParameterJdbcTemplate.queryForObject("""
				select count(*) as sessions,
				  floor(coalesce(sum(extract(epoch from coalesce(ended_at, now()) - created_at)), 0) / 60)::bigint as minutes
				from lab_sessions where user_id = :userId and status <> 'FAILED'
				""", Map.of("userId", userId), (rs, row) -> new Totals(rs.getLong("sessions"), rs.getLong("minutes")));
	}

	public record Totals(long sessions, long minutes) {
	}

}
