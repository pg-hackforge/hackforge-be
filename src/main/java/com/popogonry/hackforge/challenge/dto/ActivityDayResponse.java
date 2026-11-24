package com.popogonry.hackforge.challenge.dto;

import java.time.LocalDate;

/** openapi.yaml `ActivityDayResponse`. 한국 시간 날짜 */
public record ActivityDayResponse(LocalDate date, long count) {
}
