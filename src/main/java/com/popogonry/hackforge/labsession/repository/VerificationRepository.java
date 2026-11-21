package com.popogonry.hackforge.labsession.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.popogonry.hackforge.labsession.domain.Verification;
import com.popogonry.hackforge.labsession.domain.VerificationStatus;
import com.popogonry.hackforge.labsession.domain.VerificationType;

public interface VerificationRepository extends JpaRepository<Verification, Long> {

	/** 최근 순. 같은 시각이면 나중에 만든 것이 먼저 */
	List<Verification> findBySessionIdOrderByStartedAtDescIdDesc(long sessionId);

	Optional<Verification> findByIdAndSessionId(long id, long sessionId);

	boolean existsBySessionIdAndStatus(long sessionId, VerificationStatus status);

	/** 방어 검증 시도 횟수는 PASS와 FAIL, 해답 공개는 FAIL 수로 센다(ADR 025) */
	long countBySessionIdAndTypeAndStatusIn(long sessionId, VerificationType type, java.util.Collection<VerificationStatus> statuses);

	/** 남은 {@code RUNNING}을 모두 실패로 끝낸다. 백엔드가 시작할 때만 부른다(ADR 024) */
	@Modifying
	@Query("update Verification v set v.status = :error, v.message = :message, v.finishedAt = :now where v.status = :running")
	int failAll(VerificationStatus running, VerificationStatus error, String message, Instant now);

	/**
	 * {@code before}보다 먼저 시작해 아직 {@code RUNNING}인 그 종류의 검증을 실패로 끝낸다(백엔드 재시작, ADR 023).
	 * 방어 검증은 스크립트가 둘이라 기준이 다르다(ADR 025). 바뀐 행 수를 반환한다
	 */
	@Modifying
	@Query("""
			update Verification v set v.status = :error, v.message = :message, v.finishedAt = :now
			where v.type = :type and v.status = :running and v.startedAt < :before
			""")
	int failStale(VerificationType type, VerificationStatus running, VerificationStatus error, String message, Instant before,
			Instant now);

}
