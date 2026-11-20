package com.popogonry.hackforge.labsession.dto;

import java.util.List;

/** openapi.yaml `LabHintsResponse` */
public record LabHintsResponse(int attackTotal, int defenseTotal, List<HintResponse> hints) {
}
