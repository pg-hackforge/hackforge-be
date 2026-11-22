package com.popogonry.hackforge.challenge.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.popogonry.hackforge.challenge.domain.ChallengeDifficulty;
import com.popogonry.hackforge.challenge.domain.ChallengeMyStatus;
import com.popogonry.hackforge.challenge.domain.ChallengeSort;
import com.popogonry.hackforge.challenge.dto.CategoryCountResponse;
import com.popogonry.hackforge.challenge.dto.ChallengeCategoryResponse;
import com.popogonry.hackforge.challenge.dto.ChallengeDetailResponse;
import com.popogonry.hackforge.challenge.dto.ChallengeResponse;

import lombok.RequiredArgsConstructor;

/**
 * 문제 목록의 검색, 필터, 정렬, 페이지, 필터별 개수(ADR 020).
 * 필터별 개수는 그 조건만 빼고 나머지 조건을 다시 적용해야 해서 SQL을 조건별로 조립한다.
 * ponytail: 검색은 ILIKE 순차 비교. 문제가 수천 개로 늘어 느려지면 pg_trgm 색인을 더한다
 */
@Repository
@RequiredArgsConstructor
public class ChallengeQueryRepository {

	private static final String FROM = " from challenges c join challenge_categories cat on cat.key = c.category_key";

	/**
	 * 문제별 내 상태(ADR 027). 내 세션 중 하나라도 완료했으면 COMPLETED, 세션만 있으면 UNFINISHED, 없으면 NOT_STARTED.
	 * ponytail: 문제마다 내 세션을 찾는 subquery. 세션 색인(user_id)으로 충분하고, 느려지면 학습자·문제별 요약 테이블을 둔다
	 */
	private static final String MY_STATUS = """
			(case when exists (select 1 from lab_sessions s where s.challenge_id = c.id and s.user_id = :userId
			    and s.completed_at is not null) then 'COMPLETED'
			  when exists (select 1 from lab_sessions s where s.challenge_id = c.id and s.user_id = :userId) then 'UNFINISHED'
			  else 'NOT_STARTED' end)""";

	private static final String COLUMNS = """
			select c.id, c.slug, c.title, c.summary, cat.key as category_key, cat.name as category_name, c.difficulty,
			  c.estimated_minutes, c.created_at > now() - interval '14 days' as is_new, """ + MY_STATUS + " as my_status";

	private static final String NEWEST = "c.created_at desc, c.id desc";

	private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

	/** 검색어는 앞뒤 빈칸을 빼고, 비어 있으면 검색하지 않는다. 목록이 비면 그 조건은 없다. 내 상태는 {@code userId}의 세션으로 본다 */
	public record Filter(String q, List<String> categories, List<ChallengeDifficulty> difficulties,
			List<ChallengeMyStatus> myStatuses, long userId) {

		public Filter {
			q = q == null || q.isBlank() ? null : q.strip();
			categories = categories == null ? List.of() : List.copyOf(categories);
			difficulties = difficulties == null ? List.of() : List.copyOf(difficulties);
			myStatuses = myStatuses == null ? List.of() : List.copyOf(myStatuses);
		}

	}

	/** {@code reverse}면 그 정렬의 반대 방향. 동점은 반대 방향이어도 새로 추가된 순(ADR 028) */
	public List<ChallengeResponse> findPage(Filter filter, ChallengeSort sort, boolean reverse, int limit, long offset) {
		MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", limit).addValue("offset", offset);
		String sql = COLUMNS + FROM + " where " + conditions(filter, true, true, true, params) + " order by " + order(sort, reverse)
				+ " limit :limit offset :offset";
		return namedParameterJdbcTemplate.query(sql, params, (rs, row) -> item(rs));
	}

	public Optional<ChallengeDetailResponse> findDetail(long id, long userId) {
		String sql = COLUMNS + ", c.description" + FROM + " where c.id = :id";
		return namedParameterJdbcTemplate
			.query(sql, Map.of("id", id, "userId", userId),
					(rs, row) -> ChallengeDetailResponse.of(item(rs), rs.getString("description")))
			.stream()
			.findFirst();
	}

	private static ChallengeResponse item(ResultSet rs) throws SQLException {
		return new ChallengeResponse(rs.getLong("id"), rs.getString("slug"), rs.getString("title"),
				rs.getString("summary"),
				new ChallengeCategoryResponse(rs.getString("category_key"), rs.getString("category_name")),
				ChallengeDifficulty.valueOf(rs.getString("difficulty")), rs.getInt("estimated_minutes"),
				rs.getBoolean("is_new"), ChallengeMyStatus.valueOf(rs.getString("my_status")));
	}

	public long count(Filter filter) {
		MapSqlParameterSource params = new MapSqlParameterSource();
		String sql = "select count(*)" + FROM + " where " + conditions(filter, true, true, true, params);
		return namedParameterJdbcTemplate.queryForObject(sql, params, Long.class);
	}

	/** 모든 분류를 표시 순서로. 분류 조건은 빼고 센다 */
	public List<CategoryCountResponse> countByCategory(Filter filter) {
		MapSqlParameterSource params = new MapSqlParameterSource();
		String sql = "select cat.key, cat.name, count(c.id) as n from challenge_categories cat"
				+ " left join challenges c on c.category_key = cat.key and " + conditions(filter, false, true, true, params)
				+ " group by cat.key, cat.name, cat.display_order order by cat.display_order";
		return namedParameterJdbcTemplate.query(sql, params,
				(rs, row) -> new CategoryCountResponse(rs.getString("key"), rs.getString("name"), rs.getLong("n")));
	}

	/** 모든 난이도를 쉬운 순서로. 난이도 조건은 빼고 센다 */
	public Map<ChallengeDifficulty, Long> countByDifficulty(Filter filter) {
		MapSqlParameterSource params = new MapSqlParameterSource();
		String sql = "select c.difficulty, count(*) as n" + FROM + " where " + conditions(filter, true, false, true, params)
				+ " group by c.difficulty";
		Map<ChallengeDifficulty, Long> counts = new EnumMap<>(ChallengeDifficulty.class);
		for (ChallengeDifficulty difficulty : ChallengeDifficulty.values()) {
			counts.put(difficulty, 0L);
		}
		namedParameterJdbcTemplate.query(sql, params,
				rs -> {
					counts.put(ChallengeDifficulty.valueOf(rs.getString("difficulty")), rs.getLong("n"));
				});
		return counts;
	}

	/** 모든 내 상태를 {@link ChallengeMyStatus} 순서로. 내 상태 조건은 빼고 센다 */
	public Map<ChallengeMyStatus, Long> countByMyStatus(Filter filter) {
		MapSqlParameterSource params = new MapSqlParameterSource();
		String sql = "select " + MY_STATUS + " as my_status, count(*) as n" + FROM + " where "
				+ conditions(filter, true, true, false, params) + " group by 1";
		Map<ChallengeMyStatus, Long> counts = new EnumMap<>(ChallengeMyStatus.class);
		for (ChallengeMyStatus status : ChallengeMyStatus.values()) {
			counts.put(status, 0L);
		}
		namedParameterJdbcTemplate.query(sql, params, rs -> {
			counts.put(ChallengeMyStatus.valueOf(rs.getString("my_status")), rs.getLong("n"));
		});
		return counts;
	}

	/** 분류별 전체·완료·끝내지 못한 수(ADR 027). 모든 분류를 표시 순서로 */
	public List<CategoryProgress> countProgressByCategory(long userId) {
		String sql = "select cat.key, cat.name, count(c.id) as total,"
				+ " count(c.id) filter (where " + MY_STATUS + " = 'COMPLETED') as completed,"
				+ " count(c.id) filter (where " + MY_STATUS + " = 'UNFINISHED') as unfinished"
				+ " from challenge_categories cat left join challenges c on c.category_key = cat.key"
				+ " group by cat.key, cat.name, cat.display_order order by cat.display_order";
		return namedParameterJdbcTemplate.query(sql, Map.of("userId", userId),
				(rs, row) -> new CategoryProgress(rs.getString("key"), rs.getString("name"), rs.getLong("total"),
						rs.getLong("completed"), rs.getLong("unfinished")));
	}

	public record CategoryProgress(String key, String name, long total, long completed, long unfinished) {
	}

	private static String conditions(Filter filter, boolean byCategory, boolean byDifficulty, boolean byMyStatus,
			MapSqlParameterSource params) {
		params.addValue("userId", filter.userId());
		List<String> conditions = new ArrayList<>(List.of("true"));
		if (filter.q() != null) {
			params.addValue("q", "%" + escapeLike(filter.q()) + "%");
			conditions.add("(c.title ilike :q or c.summary ilike :q or cat.name ilike :q)");
		}
		if (byCategory && !filter.categories().isEmpty()) {
			params.addValue("categories", filter.categories());
			conditions.add("c.category_key in (:categories)");
		}
		if (byDifficulty && !filter.difficulties().isEmpty()) {
			params.addValue("difficulties", filter.difficulties().stream().map(Enum::name).toList());
			conditions.add("c.difficulty in (:difficulties)");
		}
		if (byMyStatus && !filter.myStatuses().isEmpty()) {
			params.addValue("myStatuses", filter.myStatuses().stream().map(Enum::name).toList());
			conditions.add(MY_STATUS + " in (:myStatuses)");
		}
		return String.join(" and ", conditions);
	}

	/** LIKE의 기본 escape 문자(백슬래시)로 %, _를 글자로 찾게 한다 */
	private static String escapeLike(String text) {
		return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	private static String order(ChallengeSort sort, boolean reverse) {
		String direction = reverse ? " desc, " : ", ";
		return switch (sort) {
			case NEWEST -> reverse ? "c.created_at, c.id" : NEWEST;
			case DIFFICULTY -> "array_position(array['INTRO', 'BEGINNER', 'INTERMEDIATE', 'ADVANCED']::varchar[],"
					+ " c.difficulty)" + direction + NEWEST;
			case TIME -> "c.estimated_minutes" + direction + NEWEST;
		};
	}

}
