package com.popogonry.hackforge.labsession.cluster;

/**
 * 파일 저장 결과.
 * @param changed 지금 파일 해시가 요청의 기준 해시와 달라 저장하지 않았으면 true
 * @param sha256 저장했으면 새 해시, 저장하지 않았으면 지금 해시
 */
public record PodFileWrite(boolean changed, String sha256) {
}
