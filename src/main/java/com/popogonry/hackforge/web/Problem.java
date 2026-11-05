package com.popogonry.hackforge.web;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.http.HttpServletResponse;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.springframework.http.MediaType;

import tools.jackson.databind.json.JsonMapper;

/** RFC 9457 Problem Details + `code`, `errors` (openapi.yaml `Problem`). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Problem(String title, int status, ErrorCode code, List<FieldError> errors) {

	public record FieldError(String field, String message) {
	}

	public static Problem of(ErrorCode code) {
		return of(code, null);
	}

	public static Problem of(ErrorCode code, List<FieldError> errors) {
		return new Problem(code.getTitle(), code.getStatus().value(), code, errors);
	}

	/** Spring MVC 밖(security filter)에서 쓸 때. */
	public void write(HttpServletResponse response, JsonMapper jsonMapper) throws IOException {
		response.setStatus(status);
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		jsonMapper.writeValue(response.getWriter(), this);
	}

}
