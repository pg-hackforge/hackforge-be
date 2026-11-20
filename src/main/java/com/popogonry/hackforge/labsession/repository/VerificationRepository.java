package com.popogonry.hackforge.labsession.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.popogonry.hackforge.labsession.domain.Verification;
import com.popogonry.hackforge.labsession.domain.VerificationStatus;

public interface VerificationRepository extends JpaRepository<Verification, Long> {

	/** 최근 순. 같은 시각이면 나중에 만든 것이 먼저 */
	List<Verification> findBySessionIdOrderByStartedAtDescIdDesc(long sessionId);

	Optional<Verification> findByIdAndSessionId(long id, long sessionId);

	/** {@code before}보다 먼저 시작해 아직 {@code RUNNING}인 검증을 실패로 끝낸다(백엔드 재시작, ADR 023). 바뀐 행 수를 반환한다 */
	@Modifying
	@Query("""
			update Verification v set v.status = :error, v.message = :message, v.finishedAt = :now
			where v.status = :running and v.startedAt < :before
			""")
	int failStale(VerificationStatus running, VerificationStatus error, String message, Instant before, Instant now);

}
