package com.popogonry.hackforge.labsession.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.popogonry.hackforge.labsession.domain.LabEndReason;
import com.popogonry.hackforge.labsession.domain.LabPrepStep;
import com.popogonry.hackforge.labsession.domain.LabStage;
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
			set s.status = :terminating, s.endReason = :reason, s.prepStep = null, s.statusChangedAt = :now,
			  s.version = s.version + 1
			where s.id = :id and s.userId = :userId and s.status in :from
			""")
	int terminate(long id, long userId, Collection<LabStatus> from, LabStatus terminating, LabEndReason reason, Instant now);

	/**
	 * {@link com.popogonry.hackforge.labsession.service.ActivityTracker}가 모은 활동 시각을 쓴다. {@code READY}이고 지금 값보다 최근일
	 * 때만 바꾼다. version을 올리는 이유는 {@link #terminate}와 같다. 바뀐 행 수를 반환한다
	 */
	@Modifying
	@Query("""
			update LabSession s set s.lastActivityAt = :at, s.version = s.version + 1
			where s.id = :id and s.status = :ready and s.lastActivityAt < :at
			""")
	int writeActivity(long id, Instant at, LabStatus ready);

	/** 공격 단계일 때만 방어 단계로 넘긴다(공격 검증 PASS, ADR 023). version을 올리는 이유는 {@link #terminate}와 같다 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update LabSession s set s.stage = :defense, s.version = s.version + 1
			where s.id = :id and s.stage = :attack
			""")
	int enterDefense(long id, LabStage attack, LabStage defense);

	/** {@code READY}이고 공격 단계일 때만 건너뛴다(ADR 023). 바뀐 행 수를 반환한다 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update LabSession s set s.stage = :defense, s.attackSkipped = true, s.version = s.version + 1
			where s.id = :id and s.userId = :userId and s.status = :ready and s.stage = :attack
			""")
	int skipAttack(long id, long userId, LabStatus ready, LabStage attack, LabStage defense);

	/** 초기화의 "처음부터"(ADR 024). 단계를 공격으로, 건너뜀을 false로 되돌린다. 검증 기록과 공개한 힌트는 남는다 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update LabSession s set s.stage = :attack, s.attackSkipped = false, s.version = s.version + 1
			where s.id = :id
			""")
	int restartStage(long id, LabStage attack);

	/**
	 * 실습 환경 다시 만들기(ADR 024). {@code READY}인 세션을 생성 단계로 되돌린다. Pod는 호출하는 쪽이 지우고
	 * reconciler가 새로 만든다. 생성 재시도 횟수는 0부터 다시 센다
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update LabSession s set s.status = :creating, s.prepStep = :space, s.startAttempts = 0, s.statusChangedAt = :now,
			  s.version = s.version + 1
			where s.id = :id and s.status = :ready
			""")
	int recreate(long id, LabStatus ready, LabStatus creating, LabPrepStep space, Instant now);

	/** 힌트를 순서대로 공개하려고 세션 행을 잠근다. 같은 세션의 공개 요청이 줄을 선다 */
	@Query(value = "select id from lab_sessions where id = :id for update", nativeQuery = true)
	Long lockById(long id);

	/** {@code READY}일 때만 마지막 활동 시각을 바꾼다. version을 올리는 이유는 {@link #terminate}와 같다. 바뀐 행 수를 반환한다 */
	@Modifying
	@Query("""
			update LabSession s set s.lastActivityAt = :now, s.version = s.version + 1
			where s.id = :id and s.userId = :userId and s.status = :ready
			""")
	int recordActivity(long id, long userId, LabStatus ready, Instant now);

}
