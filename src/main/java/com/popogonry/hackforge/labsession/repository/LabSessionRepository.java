package com.popogonry.hackforge.labsession.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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

}
