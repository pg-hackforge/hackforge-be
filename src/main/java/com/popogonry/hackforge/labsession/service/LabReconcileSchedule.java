package com.popogonry.hackforge.labsession.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import lombok.RequiredArgsConstructor;

/**
 * {@link LabReconciler}를 주기적으로 실행한다. 앞 실행이 끝난 뒤부터 간격을 세므로 실행이 겹치지 않는다.
 * orphan 정리는 앱이 뜰 때 한 번 돌고, 그 뒤로는 간격마다 돈다.
 * 테스트는 {@code hackforge.lab.reconciler.enabled=false}로 끄고 reconciler를 직접 호출한다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "hackforge.lab.reconciler.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
class LabReconcileSchedule {

	private final LabReconciler labReconciler;

	@Scheduled(fixedDelayString = "${hackforge.lab.reconcile-interval}")
	void reconcile() {
		labReconciler.reconcileAll();
	}

	@Scheduled(fixedDelayString = "${hackforge.lab.orphan-cleanup-interval}")
	void deleteOrphans() {
		labReconciler.deleteOrphans();
	}

}
