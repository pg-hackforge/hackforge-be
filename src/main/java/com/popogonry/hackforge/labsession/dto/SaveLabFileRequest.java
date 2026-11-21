package com.popogonry.hackforge.labsession.dto;

import jakarta.validation.constraints.NotNull;

/** openapi.yaml `SaveLabFileRequest`. 1MB 넘는 내용은 서비스가 거절한다 */
public record SaveLabFileRequest(@NotNull String content, String baseSha256) {
}
