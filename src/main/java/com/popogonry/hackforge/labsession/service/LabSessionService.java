package com.popogonry.hackforge.labsession.service;

import java.time.Clock;
import java.util.Set;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.popogonry.hackforge.challenge.domain.Challenge;
import com.popogonry.hackforge.challenge.repository.ChallengeRepository;
import com.popogonry.hackforge.labsession.cluster.LabProperties;
import com.popogonry.hackforge.labsession.domain.LabEndReason;
import com.popogonry.hackforge.labsession.domain.LabSession;
import com.popogonry.hackforge.labsession.domain.LabStatus;
import com.popogonry.hackforge.labsession.repository.LabSessionRepository;
import com.popogonry.hackforge.user.domain.User;
import com.popogonry.hackforge.user.domain.UserStatus;
import com.popogonry.hackforge.user.service.UserService;
import com.popogonry.hackforge.web.ApiException;
import com.popogonry.hackforge.web.ErrorCode;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LabSessionService {

	/** {@code pg_advisory_xact_lock} key. 시작 요청 전체가 이 lock 하나로 줄을 선다(v1 백엔드 1대, ADR 012) */
	private static final long START_LOCK_KEY = 6_000_001L;

	/** 종료 요청으로 {@code TERMINATING}이 되는 상태. 생성 중에도 종료할 수 있다(ADR 012) */
	private static final Set<LabStatus> ENDABLE = Set.of(LabStatus.CREATING, LabStatus.INITIALIZING, LabStatus.READY);

	private final LabSessionRepository labSessionRepository;

	private final ChallengeRepository challengeRepository;

	private final UserService userService;

	private final LabProperties labProperties;

	private final Clock clock;

	/**
	 * 승인 상태는 로그인 세션이 아니라 DB에서 읽는다(ADR 011). 정원과 학습자당 1세션은 advisory lock을 잡고 센다.
	 * 진행 중 세션이 있는 학습자는 정원이 차 있어도 409로 기존 세션을 안내받는다.
	 */
	@Transactional
	public LabSession start(long userId, long challengeId) {
		User user = userService.getCurrentUser(userId);
		if (user.getStatus() != UserStatus.APPROVED) {
			throw new ApiException(ErrorCode.ACCOUNT_NOT_APPROVED);
		}
		Challenge challenge = challengeRepository.findById(challengeId)
			.orElseThrow(() -> new ApiException(ErrorCode.CHALLENGE_NOT_FOUND));
		labSessionRepository.lockForStart(START_LOCK_KEY);
		if (labSessionRepository.existsByUserIdAndStatusNotIn(userId, LabStatus.ENDED)) {
			throw new ApiException(ErrorCode.LAB_ALREADY_ACTIVE);
		}
		if (labSessionRepository.countByStatusNotIn(LabStatus.ENDED) >= labProperties.maxConcurrent()) {
			throw new ApiException(ErrorCode.LAB_CAPACITY_FULL);
		}
		try {
			// lock을 거치지 않은 insert가 생겨도 partial unique index가 학습자당 1세션을 지킨다
			return labSessionRepository.saveAndFlush(new LabSession(userId, challenge, clock.instant()));
		}
		catch (DataIntegrityViolationException e) {
			throw new ApiException(ErrorCode.LAB_ALREADY_ACTIVE);
		}
	}

	public Optional<LabSession> findCurrent(long userId) {
		return labSessionRepository.findByUserIdAndStatusNotIn(userId, LabStatus.ENDED);
	}

	/** 상태만 바꾸고 응답한다. 환경 삭제는 reconciler가 한다. 이미 종료 중이거나 끝났으면 그대로 둔다 */
	@Transactional
	public LabSession end(long id, long userId) {
		labSessionRepository.terminate(id, userId, ENDABLE, LabStatus.TERMINATING, LabEndReason.USER, clock.instant());
		return get(id, userId);
	}

	/** 남의 세션도 없는 세션과 같은 404. 존재 여부를 노출하지 않는다 */
	public LabSession get(long id, long userId) {
		return labSessionRepository.findByIdAndUserId(id, userId)
			.orElseThrow(() -> new ApiException(ErrorCode.LAB_NOT_FOUND));
	}

}
