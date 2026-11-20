package com.popogonry.hackforge.labsession.cluster;

/**
 * 검증 스크립트 실행 결과(ADR 014, ADR 023).
 * @param exitCode 스크립트의 exit code. 0이 "확인하려는 일이 일어남"
 * @param output stdout(앞부분만 보관할 수 있음)
 * @param timedOut 제한 시간을 넘겨 끝냈으면 true
 */
public record ScriptResult(int exitCode, String output, boolean timedOut) {
}
