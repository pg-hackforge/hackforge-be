package com.popogonry.hackforge.labsession.domain;

/** openapi.yaml `VerificationStatus`(ADR 023) */
public enum VerificationStatus {

	/** 실행 중. 세션마다 하나만 */
	RUNNING,

	/** 스크립트가 0으로 끝남 */
	PASS,

	/** 0이 아닌 값으로 끝나거나 제한 시간을 넘김 */
	FAIL,

	/** 플랫폼이 스크립트를 실행하지 못함 */
	ERROR

}
