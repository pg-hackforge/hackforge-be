package com.popogonry.hackforge.labsession.cluster;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param kubeconfig 클러스터 접속용 kubeconfig 경로. 비어 있으면 in-cluster 설정(ADR 013)
 * @param cpu 실습 Pod CPU. requests와 limits에 같은 값
 * @param memory 실습 Pod 메모리
 * @param disk 실습 Pod 디스크. {@code ephemeral-storage}와 {@code /var/lib/docker} emptyDir 상한
 */
@ConfigurationProperties("hackforge.lab")
public record LabProperties(String kubeconfig, String cpu, String memory, String disk) {
}
