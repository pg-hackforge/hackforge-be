package com.popogonry.hackforge.labsession.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.popogonry.hackforge.labsession.domain.LabStatus;
import com.popogonry.hackforge.labsession.repository.LabSessionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 공격 대상 요청과 터미널 입력의 활동 시각을 메모리에 모았다가 {@code activity-flush-interval}마다 세션당 한 번 DB에 쓴다(ADR 012).
 * 활동이 잦아도 DB 쓰기는 주기당 한 번이다. idle 판단과 응답의 idle 기한은 DB 값과 여기 값 중 최근 값으로 한다.
 * 백엔드가 재시작하면 아직 쓰지 않은 값(최대 한 주기)은 잃는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActivityTracker {

	private final Map<Long, Instant> pending = new ConcurrentHashMap<>();

	private final LabSessionRepository labSessionRepository;

	private final Clock clock;

	private final TransactionTemplate transactionTemplate;

	public void record(long sessionId) {
		record(sessionId, clock.instant());
	}

	public void record(long sessionId, Instant at) {
		pending.merge(sessionId, at, (a, b) -> a.isAfter(b) ? a : b);
	}

	/** 아직 DB에 쓰지 않은 가장 최근 활동 시각 */
	public Optional<Instant> latest(long sessionId) {
		return Optional.ofNullable(pending.get(sessionId));
	}

	/** {@code READY} 세션만 쓰고, 아닌 세션의 값은 버린다. 쓰는 동안 새로 들어온 활동은 다음 주기에 쓴다 */
	public void flush() {
		pending.forEach((id, at) -> {
			try {
				transactionTemplate
					.executeWithoutResult(tx -> labSessionRepository.writeActivity(id, at, LabStatus.READY));
			}
			catch (RuntimeException e) {
				log.warn("실습 세션 {} 활동 기록 실패. 다음 주기에 다시 시도", id, e);
				return;
			}
			pending.remove(id, at);
		});
	}

}
