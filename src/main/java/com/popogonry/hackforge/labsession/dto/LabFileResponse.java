package com.popogonry.hackforge.labsession.dto;

/** openapi.yaml `LabFileResponse` */
public record LabFileResponse(String path, String content, String sha256, boolean readOnly) {
}
