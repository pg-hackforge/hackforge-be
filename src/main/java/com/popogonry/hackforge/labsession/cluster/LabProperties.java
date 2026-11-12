package com.popogonry.hackforge.labsession.cluster;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param kubeconfig 클러스터 접속용 kubeconfig 경로. 비어 있으면 in-cluster 설정(ADR 013)
 * @param cpu 실습 Pod CPU. requests와 limits에 같은 값
 * @param memory 실습 Pod 메모리
 * @param disk 실습 Pod 디스크. {@code ephemeral-storage}와 {@code /var/lib/docker} emptyDir 상한
 * @param maxConcurrent 동시 진행 세션 수 상한. {@code TERMINATING}도 포함(ADR 012)
 * @param startTimeout {@code CREATING}, {@code INITIALIZING}에 머물 수 있는 시간. 넘으면 생성 실패로 본다
 */
@ConfigurationProperties("hackforge.lab")
public record LabProperties(String kubeconfig, String cpu, String memory, String disk, int maxConcurrent,
		Duration startTimeout) {
}
