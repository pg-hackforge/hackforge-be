package com.popogonry.hackforge.labsession.dto;

import java.util.List;

/** openapi.yaml `LabSessionPageResponse` */
public record LabSessionPageResponse(List<LabSessionResponse> items, int page, int size, long totalItems, int totalPages) {
}
