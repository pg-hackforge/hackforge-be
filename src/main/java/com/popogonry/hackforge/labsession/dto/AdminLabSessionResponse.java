package com.popogonry.hackforge.labsession.dto;

/** openapi.yaml `AdminLabSessionResponse` */
public record AdminLabSessionResponse(long userId, String userNickname, String userEmail, LabSessionResponse session) {
}
