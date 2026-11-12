package com.popogonry.hackforge.labsession.domain;

import java.util.Set;

/** openapi.yaml `LabStatus`. 전이는 ADR 012 */
public enum LabStatus {

	CREATING, INITIALIZING, READY, TERMINATING, DELETED, FAILED;

	/** 끝난 상태. 나머지는 모두 진행 중으로 보고 학습자당 1세션과 동시 정원에 포함한다 */
	public static final Set<LabStatus> ENDED = Set.of(DELETED, FAILED);

}
