package com.popogonry.hackforge.labsession.domain;

import java.util.regex.Pattern;

/** openapi.yaml `LabPrepStep`. 준비 단계(ADR 021). 선언 순서가 진행 순서다 */
public enum LabPrepStep {

	SPACE, CONTAINER, LOADING, STARTING;

	/** 시작 스크립트(Challenge 저장소 hackforge/entrypoint.sh)가 남기는 줄: {@code hackforge: <epoch 초> <단계>} */
	private static final Pattern DOCKERD_READY = Pattern.compile("(?m)^hackforge: \\d+ dockerd-ready$");

	private static final Pattern IMAGES_LOADED = Pattern.compile("(?m)^hackforge: \\d+ images-loaded$");

	/** {@code INITIALIZING} Pod의 로그로 본 단계. 단계 기록이 없으면 {@code CONTAINER} */
	public static LabPrepStep fromStartLog(String log) {
		if (IMAGES_LOADED.matcher(log).find()) {
			return STARTING;
		}
		return DOCKERD_READY.matcher(log).find() ? LOADING : CONTAINER;
	}

}
