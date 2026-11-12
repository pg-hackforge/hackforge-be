package com.popogonry.hackforge.labsession.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.popogonry.hackforge.labsession.domain.LabEndReason;
import com.popogonry.hackforge.labsession.domain.LabSession;
import com.popogonry.hackforge.labsession.domain.LabStatus;

public interface LabSessionRepository extends JpaRepository<LabSession, Long> {

	/** 시작 요청끼리 줄을 세운다. transaction이 끝나면 풀린다 */
	@Query(value = "select 1 from pg_advisory_xact_lock(:key)", nativeQuery = true)
	int lockForStart(long key);

	long countByStatusNotIn(Collection<LabStatus> statuses);

	boolean existsByUserIdAndStatusNotIn(long userId, Collection<LabStatus> statuses);

	Optional<LabSession> findByUserIdAndStatusNotIn(long userId, Collection<LabStatus> statuses);

	Optional<LabSession> findByIdAndUserId(long id, long userId);

	@Query("select s.id from LabSession s where s.status not in :statuses order by s.id")
	List<Long> findIdsByStatusNotIn(Collection<LabStatus> statuses);

	/**
	 * {@code from} 상태일 때만 {@code TERMINATING}으로 바꾼다. 읽고 저장하는 사이에 reconciler가 먼저 저장해도 충돌하지 않는다.
	 * version을 올려서 그 전에 세션을 읽은 reconciler의 저장은 실패한다. 바뀐 행 수를 반환한다.
	 */
	@Modifying
	@Query("""
			update LabSession s
			set s.status = :terminating, s.endReason = :reason, s.statusChangedAt = :now, s.version = s.version + 1
			where s.id = :id and s.userId = :userId and s.status in :from
			""")
	int terminate(long id, long userId, Collection<LabStatus> from, LabStatus terminating, LabEndReason reason, Instant now);

	/** {@code READY}일 때만 마지막 활동 시각을 바꾼다. version을 올리는 이유는 {@link #terminate}와 같다. 바뀐 행 수를 반환한다 */
	@Modifying
	@Query("""
			update LabSession s set s.lastActivityAt = :now, s.version = s.version + 1
			where s.id = :id and s.userId = :userId and s.status = :ready
			""")
	int recordActivity(long id, long userId, LabStatus ready, Instant now);

}
