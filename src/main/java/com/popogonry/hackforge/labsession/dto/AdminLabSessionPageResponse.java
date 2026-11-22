package com.popogonry.hackforge.labsession.dto;

import java.util.List;

/** openapi.yaml `AdminLabSessionPageResponse` */
public record AdminLabSessionPageResponse(List<AdminLabSessionResponse> items, int page, int size, long totalItems,
		int totalPages) {
}
