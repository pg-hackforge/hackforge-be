package com.popogonry.hackforge.labsession.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.popogonry.hackforge.labsession.domain.Redeploy;
import com.popogonry.hackforge.labsession.domain.RedeployStatus;

public interface RedeployRepository extends JpaRepository<Redeploy, Long> {

	/** 최근 순. 같은 시각이면 나중에 만든 것이 먼저 */
	List<Redeploy> findBySessionIdOrderByStartedAtDescIdDesc(long sessionId);

	Optional<Redeploy> findByIdAndSessionId(long id, long sessionId);

	boolean existsBySessionIdAndStatus(long sessionId, RedeployStatus status);

	/** {@code before}보다 먼저 시작해 아직 {@code RUNNING}인 것을 실패로 끝낸다(백엔드 재시작, ADR 024). 바뀐 행 수를 반환한다 */
	@Modifying
	@Query("""
			update Redeploy r set r.status = :error, r.message = :message, r.finishedAt = :now
			where r.status = :running and r.startedAt < :before
			""")
	int failStale(RedeployStatus running, RedeployStatus error, String message, Instant before, Instant now);

	/** 남은 {@code RUNNING}을 모두 실패로 끝낸다. 백엔드가 시작할 때만 부른다 */
	@Modifying
	@Query("update Redeploy r set r.status = :error, r.message = :message, r.finishedAt = :now where r.status = :running")
	int failAll(RedeployStatus running, RedeployStatus error, String message, Instant now);

}
