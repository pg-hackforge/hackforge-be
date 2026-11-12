package com.popogonry.hackforge.labsession.controller;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.popogonry.hackforge.labsession.cluster.LabProperties;
import com.popogonry.hackforge.labsession.domain.LabSession;
import com.popogonry.hackforge.labsession.dto.LabSessionResponse;
import com.popogonry.hackforge.labsession.dto.StartLabRequest;
import com.popogonry.hackforge.labsession.service.LabSessionService;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
class LabSessionController {

	private final LabSessionService labSessionService;

	private final LabProperties labProperties;

	/** 세션 행만 만들고 응답한다. 환경은 reconciler가 비동기로 만든다(ADR 012) */
	@PostMapping("/api/labs")
	@ResponseStatus(HttpStatus.ACCEPTED)
	LabSessionResponse start(@Valid @RequestBody StartLabRequest body, Authentication authentication) {
		return response(labSessionService.start(userId(authentication), body.challengeId()));
	}

	@GetMapping("/api/labs/current")
	ResponseEntity<LabSessionResponse> current(Authentication authentication) {
		return labSessionService.findCurrent(userId(authentication))
			.map(session -> ResponseEntity.ok(response(session)))
			.orElseGet(() -> ResponseEntity.noContent().build());
	}

	@GetMapping("/api/labs/{id}")
	LabSessionResponse get(@PathVariable long id, Authentication authentication) {
		return response(labSessionService.get(id, userId(authentication)));
	}

	/** {@code TERMINATING}으로 바꾸고 응답한다. 환경은 reconciler가 비동기로 지운다 */
	@DeleteMapping("/api/labs/{id}")
	@ResponseStatus(HttpStatus.ACCEPTED)
	LabSessionResponse end(@PathVariable long id, Authentication authentication) {
		return response(labSessionService.end(id, userId(authentication)));
	}

	/** [계속하기]. 상태 조회(GET)는 활동으로 세지 않는다(ADR 012) */
	@PostMapping("/api/labs/{id}/activity")
	LabSessionResponse recordActivity(@PathVariable long id, Authentication authentication) {
		return response(labSessionService.recordActivity(id, userId(authentication)));
	}

	private LabSessionResponse response(LabSession session) {
		return LabSessionResponse.from(session, labProperties);
	}

	private static long userId(Authentication authentication) {
		return Long.parseLong(authentication.getName());
	}

}
