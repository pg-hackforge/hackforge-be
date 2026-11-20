package com.popogonry.hackforge.labsession.service;

import java.time.Clock;
import java.time.Duration;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.popogonry.hackforge.labsession.cluster.LabCluster;
import com.popogonry.hackforge.labsession.cluster.ScriptResult;
import com.popogonry.hackforge.labsession.domain.Redeploy;
import com.popogonry.hackforge.labsession.domain.RedeployKind;
import com.popogonry.hackforge.labsession.domain.RedeployStatus;
import com.popogonry.hackforge.labsession.repository.RedeployRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 요청 transaction이 commit된 뒤 별도 thread에서 재배포나 서비스 초기화를 실행하고 결과와 빌드 로그를 저장한다(ADR 024).
 * 실행 중 백엔드가 재시작하면 남은 {@code RUNNING}은 {@link LabJobRecovery}와 reconciler가 정리한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class RedeployRunner {

	/** 빌드, 기동, healthy 대기를 합친 제한 시간 */
	static final Duration TIMEOUT = Duration.ofMinutes(3);

	/** 빌드와 기동 뒤 모든 서비스가 healthy가 되기를 기다린다(2초씩 최대 60번). stderr도 로그에 남긴다 */
	static final String BUILD_AND_START = """
			cd /app
			docker compose build && docker compose up -d || exit 1
			i=0
			until /hackforge/ready.sh; do
				i=$((i + 1))
				[ "$i" -lt 60 ] || { echo "서비스가 healthy가 되지 않았어요"; exit 1; }
				sleep 2
			done
			""";

	static final String REDEPLOY_SCRIPT = "exec 2>&1\n" + BUILD_AND_START;

	/** 서비스와 volume(DB 데이터)을 지우고 /app을 이미지의 원본 사본으로 되돌린 뒤 다시 기동한다 */
	static final String RESET_SCRIPT = """
			exec 2>&1
			[ -d /hackforge/original ] || { echo "원본 코드가 없어요"; exit 2; }
			cd /app
			docker compose down -v --remove-orphans
			find /app -mindepth 1 -delete
			cp -a /hackforge/original/. /app/
			""" + BUILD_AND_START;

	private final LabCluster labCluster;

	private final RedeployRepository redeployRepository;

	private final TransactionTemplate transactionTemplate;

	private final Clock clock;

	void start(long redeployId, long sessionId, RedeployKind kind) {
		Thread.ofVirtual().name("redeploy-" + redeployId).start(() -> run(redeployId, sessionId, kind));
	}

	private void run(long redeployId, long sessionId, RedeployKind kind) {
		boolean reset = kind == RedeployKind.RESET;
		RedeployStatus status;
		String message;
		String log = null;
		try {
			ScriptResult result = labCluster.runScript(sessionId, reset ? RESET_SCRIPT : REDEPLOY_SCRIPT, TIMEOUT);
			log = result.output();
			if (result.timedOut()) {
				status = RedeployStatus.FAIL;
				message = "3분 안에 끝나지 않았어요. 빌드 로그를 확인해 주세요";
			}
			else if (result.exitCode() == 0) {
				status = RedeployStatus.SUCCESS;
				message = reset ? "초기화했어요" : "재배포했어요";
			}
			else {
				status = RedeployStatus.FAIL;
				message = reset ? "초기화에 실패했어요. 빌드 로그를 확인하고, 계속 안 되면 실습 환경을 다시 만들어 주세요"
						: "빌드나 기동에 실패했어요. 빌드 로그를 확인해 주세요";
			}
		}
		catch (RuntimeException e) {
			RedeployRunner.log.warn("재배포 {}(실습 세션 {}) 실행 실패: {}", redeployId, sessionId, e.getMessage());
			status = RedeployStatus.ERROR;
			message = reset ? "초기화를 실행하지 못했어요. 실습 환경을 다시 만들어 주세요"
					: "재배포를 실행하지 못했어요. 잠시 뒤 다시 시도해 주세요";
		}
		RedeployStatus finalStatus = status;
		String finalMessage = message;
		String finalLog = log;
		transactionTemplate.executeWithoutResult(tx -> {
			Redeploy redeploy = redeployRepository.findById(redeployId).orElseThrow();
			redeploy.finish(finalStatus, finalMessage, finalLog, clock.instant());
		});
	}

}
