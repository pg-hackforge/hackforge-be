package com.popogonry.hackforge.labsession.dto;

/** openapi.yaml `ResetLabResponse`. 실습 환경 다시 만들기면 redeploy가 null */
public record ResetLabResponse(LabSessionResponse session, RedeployResponse redeploy) {
}
