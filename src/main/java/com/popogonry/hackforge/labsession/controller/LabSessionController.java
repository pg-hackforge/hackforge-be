package com.popogonry.hackforge.labsession.controller;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;

import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.popogonry.hackforge.labsession.cluster.LabProperties;
import com.popogonry.hackforge.labsession.domain.LabSession;
import com.popogonry.hackforge.labsession.domain.LabStage;
import com.popogonry.hackforge.labsession.domain.LabStatus;
import com.popogonry.hackforge.labsession.dto.AdminLabFilter;
import com.popogonry.hackforge.labsession.dto.AdminLabSessionPageResponse;
import com.popogonry.hackforge.labsession.dto.AdminLabSessionResponse;
import com.popogonry.hackforge.labsession.dto.HintResponse;
import com.popogonry.hackforge.labsession.dto.LabEventResponse;
import com.popogonry.hackforge.labsession.dto.LabSessionPageResponse;
import com.popogonry.hackforge.labsession.dto.LabHintsResponse;
import com.popogonry.hackforge.labsession.dto.LabSessionResponse;
import com.popogonry.hackforge.labsession.dto.RedeployResponse;
import com.popogonry.hackforge.labsession.dto.SolutionResponse;
import com.popogonry.hackforge.labsession.dto.ResetLabRequest;
import com.popogonry.hackforge.labsession.dto.ResetLabResponse;
import com.popogonry.hackforge.labsession.dto.VerificationResponse;
import com.popogonry.hackforge.labsession.dto.StartLabRequest;
import com.popogonry.hackforge.labsession.service.ActivityTracker;
import com.popogonry.hackforge.labsession.service.LabSessionService;
import com.popogonry.hackforge.user.domain.User;
import com.popogonry.hackforge.labsession.service.RedeployService;
import com.popogonry.hackforge.labsession.service.VerificationService;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
class LabSessionController {

	private final LabSessionService labSessionService;

	private final LabProperties labProperties;

	private final ActivityTracker activityTracker;

	private final VerificationService verificationService;

	private final RedeployService redeployService;

	/** 요청마다 다른 객체가 주입되는 request scope proxy. 공격 대상 주소의 호스트를 읽는다 */
	private final HttpServletRequest request;

	/** 세션 행만 만들고 응답한다. 환경은 reconciler가 비동기로 만든다(ADR 012) */
	@PostMapping("/api/labs")
	@ResponseStatus(HttpStatus.ACCEPTED)
	LabSessionResponse startLab(@Valid @RequestBody StartLabRequest body, Authentication authentication) {
		return response(labSessionService.startLab(userId(authentication), body.challengeId()));
	}

	@GetMapping("/api/labs/current")
	ResponseEntity<LabSessionResponse> getCurrentLab(Authentication authentication) {
		return labSessionService.findCurrentLab(userId(authentication))
			.map(session -> ResponseEntity.ok(response(session)))
			.orElseGet(() -> ResponseEntity.noContent().build());
	}

	@GetMapping("/api/labs")
	LabSessionPageResponse listLabs(@RequestParam(defaultValue = "1") @Min(1) int page, Authentication authentication) {
		Page<LabSession> sessions = labSessionService.listLabs(userId(authentication), page);
		return new LabSessionPageResponse(sessions.map(this::response).toList(), page, sessions.getSize(),
				sessions.getTotalElements(), sessions.getTotalPages());
	}

	/** 관리자는 모든 세션을 조회한다(ADR 026) */
	@GetMapping("/api/labs/{id}")
	LabSessionResponse getLab(@PathVariable long id, Authentication authentication) {
		return response(labSessionService.getLabForRead(id, userId(authentication), isAdmin(authentication)));
	}

	@GetMapping("/api/labs/{id}/events")
	List<LabEventResponse> listLabEvents(@PathVariable long id, Authentication authentication) {
		return labSessionService.listLabEvents(id, userId(authentication), isAdmin(authentication))
			.stream()
			.map(LabEventResponse::from)
			.toList();
	}

	@GetMapping("/api/labs/{id}/solution")
	SolutionResponse getSolution(@PathVariable long id, Authentication authentication) {
		return labSessionService.getSolution(id, userId(authentication));
	}

	@GetMapping("/api/admin/labs")
	AdminLabSessionPageResponse listAdminLabs(@RequestParam(defaultValue = "ACTIVE") AdminLabFilter status,
			@RequestParam(defaultValue = "1") @Min(1) int page) {
		LabSessionService.AdminLabPage result = labSessionService.listAdminLabs(status, page);
		Page<LabSession> sessions = result.sessions();
		return new AdminLabSessionPageResponse(sessions.map(s -> {
			User user = result.users().get(s.getUserId());
			return new AdminLabSessionResponse(s.getUserId(), user.getName(), user.getNickname(), user.getEmail(), response(s));
		}).toList(), page, sessions.getSize(), sessions.getTotalElements(), sessions.getTotalPages());
	}

	/** 학습자 종료와 같이 {@code TERMINATING}으로 바꾸고 응답한다. 종료 사유 {@code ADMIN}(ADR 026) */
	@PostMapping("/api/admin/labs/{id}/terminate")
	@ResponseStatus(HttpStatus.ACCEPTED)
	LabSessionResponse terminateLab(@PathVariable long id, Authentication authentication) {
		return response(labSessionService.terminateLab(id, userId(authentication)));
	}

	/** {@code TERMINATING}으로 바꾸고 응답한다. 환경은 reconciler가 비동기로 지운다 */
	@DeleteMapping("/api/labs/{id}")
	@ResponseStatus(HttpStatus.ACCEPTED)
	LabSessionResponse endLab(@PathVariable long id, Authentication authentication) {
		return response(labSessionService.endLab(id, userId(authentication)));
	}

	/** [계속하기]. 상태 조회(GET)는 활동으로 세지 않는다(ADR 012) */
	@PostMapping("/api/labs/{id}/activity")
	LabSessionResponse recordLabActivity(@PathVariable long id, Authentication authentication) {
		return response(labSessionService.recordLabActivity(id, userId(authentication)));
	}

	/** 접수만 하고 바로 응답한다. 결과는 getVerification으로 polling한다(ADR 023) */
	@PostMapping("/api/labs/{id}/attack-verifications")
	@ResponseStatus(HttpStatus.ACCEPTED)
	VerificationResponse requestAttackVerification(@PathVariable long id, Authentication authentication) {
		return VerificationResponse.from(verificationService.requestAttackVerification(id, userId(authentication)));
	}

	/** 접수만 하고 바로 응답한다. 결과는 getVerification으로 polling한다(ADR 025) */
	@PostMapping("/api/labs/{id}/defense-verifications")
	@ResponseStatus(HttpStatus.ACCEPTED)
	VerificationResponse requestDefenseVerification(@PathVariable long id, Authentication authentication) {
		return VerificationResponse.from(verificationService.requestDefenseVerification(id, userId(authentication)));
	}

	@PostMapping("/api/labs/{id}/solution")
	SolutionResponse openSolution(@PathVariable long id, Authentication authentication) {
		return labSessionService.openSolution(id, userId(authentication));
	}

	@GetMapping("/api/labs/{id}/verifications")
	List<VerificationResponse> listVerifications(@PathVariable long id, Authentication authentication) {
		return verificationService.listVerifications(id, userId(authentication), isAdmin(authentication))
			.stream()
			.map(VerificationResponse::from)
			.toList();
	}

	@GetMapping("/api/labs/{id}/verifications/{verificationId}")
	VerificationResponse getVerification(@PathVariable long id, @PathVariable long verificationId,
			Authentication authentication) {
		return VerificationResponse
			.from(verificationService.getVerification(id, verificationId, userId(authentication), isAdmin(authentication)));
	}

	@PostMapping("/api/labs/{id}/attack-hints")
	HintResponse revealAttackHint(@PathVariable long id, Authentication authentication) {
		return labSessionService.revealAttackHint(id, userId(authentication));
	}

	@GetMapping("/api/labs/{id}/hints")
	LabHintsResponse listLabHints(@PathVariable long id, Authentication authentication) {
		return labSessionService.listLabHints(id, userId(authentication), isAdmin(authentication));
	}

	@PostMapping("/api/labs/{id}/defense-hints")
	HintResponse revealDefenseHint(@PathVariable long id, Authentication authentication) {
		return labSessionService.revealDefenseHint(id, userId(authentication));
	}

	/** 접수만 하고 바로 응답한다. 결과는 getRedeploy로 polling한다(ADR 024) */
	@PostMapping("/api/labs/{id}/redeploys")
	@ResponseStatus(HttpStatus.ACCEPTED)
	RedeployResponse requestRedeploy(@PathVariable long id, Authentication authentication) {
		return RedeployResponse.from(redeployService.requestRedeploy(id, userId(authentication)), false);
	}

	@GetMapping("/api/labs/{id}/redeploys")
	List<RedeployResponse> listRedeploys(@PathVariable long id, Authentication authentication) {
		return redeployService.listRedeploys(id, userId(authentication), isAdmin(authentication))
			.stream()
			.map(r -> RedeployResponse.from(r, false))
			.toList();
	}

	@GetMapping("/api/labs/{id}/redeploys/{redeployId}")
	RedeployResponse getRedeploy(@PathVariable long id, @PathVariable long redeployId, Authentication authentication) {
		return RedeployResponse
			.from(redeployService.getRedeploy(id, redeployId, userId(authentication), isAdmin(authentication)), true);
	}

	@PostMapping("/api/labs/{id}/reset")
	@ResponseStatus(HttpStatus.ACCEPTED)
	ResetLabResponse resetLab(@PathVariable long id, @Valid @RequestBody ResetLabRequest body,
			Authentication authentication) {
		RedeployService.ResetResult result = redeployService.resetLab(id, userId(authentication), body.scope(),
				body.keepStage());
		return new ResetLabResponse(response(result.session()),
				result.redeploy() == null ? null : RedeployResponse.from(result.redeploy(), false));
	}

	@PostMapping("/api/labs/{id}/attack-skip")
	LabSessionResponse skipAttack(@PathVariable long id, Authentication authentication) {
		return response(labSessionService.skipAttack(id, userId(authentication)));
	}

	private LabSessionResponse response(LabSession session) {
		return LabSessionResponse.from(session, labProperties, activityTracker.latest(session.getId()).orElse(null),
				targetUrl(session), labSessionService.countHintsRevealed(session.getId(), LabStage.ATTACK),
				labSessionService.countHintsRevealed(session.getId(), LabStage.DEFENSE),
				labSessionService.countDefenseAttempts(session.getId()), labSessionService.isSolutionAvailable(session));
	}

	/**
	 * 학습자가 플랫폼에 들어온 호스트에 공격 대상 포트를 붙인다(ADR 022). 같은 호스트여야 SESSION cookie가 공격 대상 포트로도 간다.
	 * reverse proxy 뒤라면 {@code server.forward-headers-strategy}를 켜야 {@code X-Forwarded-Host}가 반영된다
	 */
	private String targetUrl(LabSession session) {
		if (session.getStatus() != LabStatus.READY || labProperties.targetPort() <= 0) {
			return null;
		}
		return request.getScheme() + "://" + request.getServerName() + ":" + labProperties.targetPort() + "/";
	}

	private static long userId(Authentication authentication) {
		return Long.parseLong(authentication.getName());
	}

	/** 역할은 로그인할 때 정해진다(역할을 바꾸면 다시 로그인, ADR 011) */
	private static boolean isAdmin(Authentication authentication) {
		return authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
	}

}
