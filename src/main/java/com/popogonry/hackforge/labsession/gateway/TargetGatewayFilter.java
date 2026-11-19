package com.popogonry.hackforge.labsession.gateway;

import java.io.IOException;
import java.util.Optional;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import com.popogonry.hackforge.labsession.cluster.LabCluster;
import com.popogonry.hackforge.labsession.cluster.LabClusterException;
import com.popogonry.hackforge.labsession.cluster.LabProperties;
import com.popogonry.hackforge.labsession.domain.LabSession;
import com.popogonry.hackforge.labsession.domain.LabStatus;
import com.popogonry.hackforge.labsession.service.ActivityTracker;
import com.popogonry.hackforge.labsession.service.LabSessionService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 공격 대상 포트로 온 요청을 그 학습자의 {@code READY} 세션 공격 대상으로 넘긴다(ADR 022). 다른 포트 요청은 그대로 지나간다.
 * 로그인은 플랫폼 SESSION cookie로 확인한다. cookie는 포트를 구분하지 않아 이 포트로도 온다.
 */
@Slf4j
@RequiredArgsConstructor
class TargetGatewayFilter extends OncePerRequestFilter {

	private final LabProperties labProperties;

	private final LabSessionService labSessionService;

	private final ActivityTracker activityTracker;

	private final LabCluster labCluster;

	private final TargetProxy targetProxy;

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return labProperties.targetPort() <= 0 || request.getLocalPort() != labProperties.targetPort();
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws IOException {
		Optional<Long> userId = loggedInUser(request);
		if (userId.isEmpty()) {
			TargetPages.write(request, response, labProperties, TargetPages.NOT_LOGGED_IN);
			return;
		}
		Optional<LabSession> session = labSessionService.findCurrentLab(userId.get())
			.filter(s -> s.getStatus() == LabStatus.READY);
		if (session.isEmpty()) {
			TargetPages.write(request, response, labProperties, TargetPages.NOT_READY);
			return;
		}
		long sessionId = session.get().getId();
		activityTracker.record(sessionId);
		int port;
		try {
			port = labCluster.targetPort(sessionId);
		}
		catch (LabClusterException | java.io.UncheckedIOException e) {
			log.info("실습 세션 {} 공격 대상 연결 실패: {}", sessionId, e.getMessage());
			TargetPages.write(request, response, labProperties, TargetPages.UNREACHABLE);
			return;
		}
		targetProxy.forward(request, response, sessionId, port);
	}

	/** 새 세션을 만들지 않는다. 로그인하지 않았으면 비어 있다 */
	private static Optional<Long> loggedInUser(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session == null) {
			return Optional.empty();
		}
		if (!(session.getAttribute(
				HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) instanceof SecurityContext context)) {
			return Optional.empty();
		}
		Authentication authentication = context.getAuthentication();
		if (authentication == null || !authentication.isAuthenticated()
				|| authentication instanceof AnonymousAuthenticationToken) {
			return Optional.empty();
		}
		return Optional.of(Long.parseLong(authentication.getName()));
	}

}
