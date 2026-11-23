package com.popogonry.hackforge.labsession.cluster;

/**
 * 실습 Pod {@code /app} 아래 파일 하나(ADR 024).
 * @param path {@code /app} 기준 상대 경로
 * @param size 바이트
 * @param binary NUL 바이트가 있으면 true. 1MB를 넘는 파일은 확인하지 않고 false
 * @param modified 원본({@code /hackforge/original})과 내용이 다르거나 원본에 없으면 true(ADR 029)
 */
public record PodFile(String path, long size, boolean binary, boolean modified) {
}
