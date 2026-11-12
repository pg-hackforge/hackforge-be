package com.popogonry.hackforge.labsession.cluster;

import io.kubernetes.client.openapi.ApiException;

/** API server가 거절했거나 연결하지 못함. reconciler는 로그를 남기고 다음 주기에 다시 시도한다(ADR 012). */
public class LabClusterException extends RuntimeException {

	LabClusterException(ApiException cause) {
		super("Kubernetes API 오류 " + cause.getCode() + ": " + cause.getResponseBody(), cause);
	}

}
