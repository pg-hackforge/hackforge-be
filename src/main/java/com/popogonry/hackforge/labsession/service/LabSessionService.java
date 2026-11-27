package com.popogonry.hackforge.labsession.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.Optional;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.popogonry.hackforge.challenge.domain.Challenge;
import com.popogonry.hackforge.challenge.domain.ChallengeStatus;
import com.popogonry.hackforge.challenge.repository.ChallengeRepository;
import com.popogonry.hackforge.labsession.cluster.LabProperties;
import com.popogonry.hackforge.labsession.domain.LabEndReason;
import com.popogonry.hackforge.labsession.domain.LabEventActor;
import com.popogonry.hackforge.labsession.domain.LabSession;
import com.popogonry.hackforge.labsession.domain.LabStage;
import com.popogonry.hackforge.labsession.domain.LabStatus;
import com.popogonry.hackforge.labsession.domain.SessionEvent;
import com.popogonry.hackforge.labsession.domain.VerificationStatus;
import com.popogonry.hackforge.labsession.domain.VerificationType;
import com.popogonry.hackforge.labsession.dto.AdminLabFilter;
import com.popogonry.hackforge.labsession.dto.HintResponse;
import com.popogonry.hackforge.labsession.dto.LabHintsResponse;
import com.popogonry.hackforge.labsession.dto.SolutionResponse;
import com.popogonry.hackforge.labsession.repository.LabHintRepository;
import com.popogonry.hackforge.labsession.repository.LabSessionRepository;
import com.popogonry.hackforge.labsession.repository.SessionEventRepository;
import com.popogonry.hackforge.labsession.repository.VerificationRepository;
import com.popogonry.hackforge.user.domain.User;
import com.popogonry.hackforge.user.domain.UserStatus;
import com.popogonry.hackforge.user.repository.UserRepository;
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

	private final LabHintRepository labHintRepository;

	private final ActivityTracker activityTracker;

	private final VerificationRepository verificationRepository;

	private final SessionEventRepository sessionEventRepository;

	private final UserRepository userRepository;

	/** 세션 목록 한 페이지 크기(문제 목록과 같음) */
	public static final int PAGE_SIZE = 20;

	/**
	 * 승인 상태는 로그인 세션이 아니라 DB에서 읽는다(ADR 011). 정원과 학습자당 1세션은 advisory lock을 잡고 센다.
	 * 진행 중 세션이 있는 학습자는 정원이 차 있어도 409로 기존 세션을 안내받는다.
	 */
	@Transactional
	public LabSession startLab(long userId, long challengeId) {
		User user = userService.getCurrentUser(userId);
		if (user.getStatus() != UserStatus.APPROVED) {
			throw new ApiException(ErrorCode.ACCOUNT_NOT_APPROVED);
		}
		Challenge challenge = challengeRepository.findById(challengeId)
			.orElseThrow(() -> new ApiException(ErrorCode.CHALLENGE_NOT_FOUND));
		if (challenge.getStatus() != ChallengeStatus.PUBLISHED) {
			throw new ApiException(ErrorCode.CHALLENGE_NOT_PUBLISHED);
		}
		labSessionRepository.lockForStart(START_LOCK_KEY);
		if (labSessionRepository.existsByUserIdAndStatusNotIn(userId, LabStatus.ENDED)) {
			throw new ApiException(ErrorCode.LAB_ALREADY_ACTIVE);
		}
		if (labSessionRepository.countByStatusNotIn(LabStatus.ENDED) >= labProperties.maxConcurrent()) {
			throw new ApiException(ErrorCode.LAB_CAPACITY_FULL);
		}
		try {
			// lock을 거치지 않은 insert가 생겨도 partial unique index가 학습자당 1세션을 지킨다
			sessionEventRepository.setActor(LabEventActor.LEARNER.name(), String.valueOf(userId));
			return labSessionRepository.saveAndFlush(new LabSession(userId, challenge, clock.instant()));
		}
		catch (DataIntegrityViolationException e) {
			throw new ApiException(ErrorCode.LAB_ALREADY_ACTIVE);
		}
	}

	public Optional<LabSession> findCurrentLab(long userId) {
		return labSessionRepository.findByUserIdAndStatusNotIn(userId, LabStatus.ENDED);
	}

	/** 상태만 바꾸고 응답한다. 환경 삭제는 reconciler가 한다. 이미 종료 중이거나 끝났으면 그대로 둔다 */
	@Transactional
	public LabSession endLab(long id, long userId) {
		sessionEventRepository.setActor(LabEventActor.LEARNER.name(), String.valueOf(userId));
		labSessionRepository.terminate(id, userId, ENDABLE, LabStatus.TERMINATING, LabEndReason.USER, clock.instant());
		return getLab(id, userId);
	}

	/** 관리자 강제 종료(ADR 026). 이미 종료 중이거나 끝났으면 바꾸지 않고 지금 상태를 반환한다 */
	@Transactional
	public LabSession terminateLab(long id, long adminId) {
		if (!labSessionRepository.existsById(id)) {
			throw new ApiException(ErrorCode.LAB_NOT_FOUND);
		}
		sessionEventRepository.setActor(LabEventActor.ADMIN.name(), String.valueOf(adminId));
		labSessionRepository.terminateByAdmin(id, ENDABLE, LabStatus.TERMINATING, LabEndReason.ADMIN, clock.instant());
		return labSessionRepository.findById(id).orElseThrow();
	}

	/** 내 실습 기록. 종료한 세션도 최근 순으로(ADR 026) */
	public Page<LabSession> listLabs(long userId, int page) {
		return labSessionRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(page - 1, PAGE_SIZE));
	}

	/** 관리자 세션 목록과 그 학습자(ADR 026) */
	public record AdminLabPage(Page<LabSession> sessions, Map<Long, User> users) {
	}

	public AdminLabPage listAdminLabs(AdminLabFilter filter, int page) {
		PageRequest pageRequest = PageRequest.of(page - 1, PAGE_SIZE);
		Page<LabSession> sessions = filter == AdminLabFilter.ALL
				? labSessionRepository.findAllByOrderByCreatedAtDescIdDesc(pageRequest)
				: labSessionRepository.findByStatusNotInOrderByCreatedAtDescIdDesc(LabStatus.ENDED, pageRequest);
		Map<Long, User> users = userRepository
			.findAllById(sessions.stream().map(LabSession::getUserId).distinct().toList())
			.stream()
			.collect(Collectors.toMap(User::getId, Function.identity()));
		return new AdminLabPage(sessions, users);
	}

	/** 상태 기록(ADR 026). 관리자는 모든 세션 */
	public List<SessionEvent> listLabEvents(long id, long userId, boolean admin) {
		getLabForRead(id, userId, admin);
		return sessionEventRepository.findBySessionIdOrderByIdAsc(id);
	}

	/** 이 세션에서 연 해답을 다시 준다. 상태와 상관없이, 세션 주인만(ADR 026) */
	public SolutionResponse getSolution(long id, long userId) {
		LabSession lab = getLab(id, userId);
		if (lab.getSolutionViewedAt() == null) {
			throw new ApiException(ErrorCode.SOLUTION_LOCKED);
		}
		return new SolutionResponse(lab.getChallenge().getSolution(), lab.getSolutionViewedAt());
	}

	/** [계속하기]. 남의 세션과 없는 세션은 404, {@code READY}가 아니면 409 */
	@Transactional
	public LabSession recordLabActivity(long id, long userId) {
		int updated = labSessionRepository.recordActivity(id, userId, LabStatus.READY, clock.instant());
		LabSession session = getLab(id, userId);
		if (updated == 0) {
			throw new ApiException(ErrorCode.LAB_NOT_READY);
		}
		return session;
	}

	/** 다음 공격 힌트 하나를 공개하고 공개 시각을 남긴다(ADR 023). {@code READY}이고 공격 단계일 때만 */
	@Transactional
	public HintResponse revealAttackHint(long id, long userId) {
		return revealHint(id, userId, LabStage.ATTACK);
	}

	/** 방어 검증 시도 횟수. PASS와 FAIL만 센다(ADR 025) */
	public int countDefenseAttempts(long id) {
		return (int) verificationRepository.countBySessionIdAndTypeAndStatusIn(id, VerificationType.DEFENSE,
				List.of(VerificationStatus.PASS, VerificationStatus.FAIL));
	}

	/** 방어 검증 FAIL이 문제의 최대 시도 횟수만큼 쌓였으면 해답을 열 수 있다(ADR 025) */
	public boolean isSolutionAvailable(LabSession lab) {
		return verificationRepository.countBySessionIdAndTypeAndStatusIn(lab.getId(), VerificationType.DEFENSE,
				List.of(VerificationStatus.FAIL)) >= lab.getChallenge().getMaxDefenseAttempts();
	}

	/** 해답을 주고 처음 연 시각을 남긴다(ADR 025). {@code READY}일 때만 */
	@Transactional
	public SolutionResponse openSolution(long id, long userId) {
		LabSession lab = getLab(id, userId);
		requireReady(lab);
		if (!isSolutionAvailable(lab)) {
			throw new ApiException(ErrorCode.SOLUTION_LOCKED);
		}
		String solution = lab.getChallenge().getSolution();
		labSessionRepository.markSolutionViewed(id, clock.instant());
		activityTracker.record(id);
		return new SolutionResponse(solution, getLab(id, userId).getSolutionViewedAt());
	}

	/** 다음 방어 힌트 하나를 공개한다(ADR 024). {@code READY}이고 방어 단계일 때만 */
	@Transactional
	public HintResponse revealDefenseHint(long id, long userId) {
		return revealHint(id, userId, LabStage.DEFENSE);
	}

	/** 그 단계의 힌트는 그 단계에서만 1 → 2 → 3 순서로 공개한다. 세션 행을 잠가 같은 세션의 공개 요청이 줄을 선다 */
	private HintResponse revealHint(long id, long userId, LabStage stage) {
		LabSession lab = getLab(id, userId);
		labSessionRepository.lockById(id);
		requireReady(lab);
		if (lab.getStage() != stage) {
			throw new ApiException(ErrorCode.WRONG_LAB_STAGE);
		}
		long challengeId = lab.getChallenge().getId();
		int revealed = labHintRepository.countRevealed(id, stage);
		if (revealed >= labHintRepository.countHints(challengeId, stage)) {
			throw new ApiException(ErrorCode.NO_MORE_HINTS);
		}
		int position = revealed + 1;
		Instant now = clock.instant();
		labHintRepository.reveal(id, stage, position, now);
		activityTracker.record(id);
		return new HintResponse(stage, position, labHintRepository.content(challengeId, stage, position), now);
	}

	/** 공개한 힌트는 단계와 상관없이 다시 볼 수 있다. 관리자는 모든 세션(ADR 026) */
	public LabHintsResponse listLabHints(long id, long userId, boolean admin) {
		LabSession lab = getLabForRead(id, userId, admin);
		long challengeId = lab.getChallenge().getId();
		return new LabHintsResponse(labHintRepository.countHints(challengeId, LabStage.ATTACK),
				labHintRepository.countHints(challengeId, LabStage.DEFENSE), labHintRepository.findRevealed(id, challengeId));
	}

	public int countHintsRevealed(long id, LabStage stage) {
		return labHintRepository.countRevealed(id, stage);
	}

	/** 공격 단계에서는 힌트를 몇 개 봤든 건너뛸 수 있다(ADR 023) */
	@Transactional
	public LabSession skipAttack(long id, long userId) {
		LabSession lab = getLab(id, userId);
		requireReady(lab);
		int updated = labSessionRepository.skipAttack(id, userId, LabStatus.READY, LabStage.ATTACK, LabStage.DEFENSE);
		if (updated == 0) {
			throw new ApiException(ErrorCode.WRONG_LAB_STAGE);
		}
		activityTracker.record(id);
		return getLab(id, userId);
	}

	private static void requireReady(LabSession lab) {
		if (lab.getStatus() != LabStatus.READY) {
			throw new ApiException(ErrorCode.LAB_NOT_READY);
		}
	}

	/** 남의 세션도 없는 세션과 같은 404. 존재 여부를 노출하지 않는다 */
	public LabSession getLab(long id, long userId) {
		return labSessionRepository.findByIdAndUserId(id, userId)
			.orElseThrow(() -> new ApiException(ErrorCode.LAB_NOT_FOUND));
	}

	/** 조회만 하는 요청. 관리자는 모든 세션을 본다(ADR 026). 바꾸는 요청은 {@link #getLab}으로 주인만 */
	public LabSession getLabForRead(long id, long userId, boolean admin) {
		return admin ? labSessionRepository.findById(id).orElseThrow(() -> new ApiException(ErrorCode.LAB_NOT_FOUND))
				: getLab(id, userId);
	}

}
