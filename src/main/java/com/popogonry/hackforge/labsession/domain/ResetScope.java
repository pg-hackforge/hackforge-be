package com.popogonry.hackforge.labsession.domain;

/** openapi.yaml `ResetScope`(ADR 024) */
public enum ResetScope {

	/** 원본 복원과 서비스 다시 기동(DB 포함) */
	SERVICES,

	/** Pod 재생성 */
	ENVIRONMENT

}
