package com.popogonry.hackforge.labsession.gateway;

import org.apache.catalina.connector.Connector;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.SessionRepositoryFilter;

import com.popogonry.hackforge.labsession.cluster.LabCluster;
import com.popogonry.hackforge.labsession.cluster.LabProperties;
import com.popogonry.hackforge.labsession.service.ActivityTracker;
import com.popogonry.hackforge.labsession.service.LabSessionService;

/**
 * 공격 대상 gateway(ADR 022). 플랫폼 포트와 별도로 공격 대상 포트를 열고, 그 포트로 온 요청은 filter가 모두 공격 대상으로 넘긴다.
 * 백엔드 나머지와는 "로그인한 학습자의 READY 세션 찾기"({@link LabSessionService})와 "활동 기록"({@link ActivityTracker})으로만 연결한다.
 * 사용자가 많아지면 이 패키지를 별도 서비스로 떼어 내고 두 곳을 API 호출로 바꾼다.
 */
@Configuration
class TargetGatewayConfig {

	@Bean
	WebServerFactoryCustomizer<TomcatServletWebServerFactory> targetConnector(LabProperties labProperties) {
		return factory -> {
			if (labProperties.targetPort() > 0) {
				Connector connector = new Connector();
				connector.setPort(labProperties.targetPort());
				factory.addAdditionalConnectors(connector);
			}
		};
	}

	/**
	 * Spring Session이 cookie로 로그인 세션을 읽은 바로 뒤, Spring Security보다 앞에서 돈다.
	 * 공격 대상 포트 요청은 다음 filter로 넘기지 않으므로 플랫폼 API, CSRF 검사, 로그인 처리를 거치지 않는다
	 */
	@Bean
	FilterRegistrationBean<TargetGatewayFilter> targetGatewayFilter(LabProperties labProperties,
			LabSessionService labSessionService, ActivityTracker activityTracker, LabCluster labCluster) {
		TargetProxy targetProxy = new TargetProxy(labProperties);
		FilterRegistrationBean<TargetGatewayFilter> registration = new FilterRegistrationBean<>(
				new TargetGatewayFilter(labProperties, labSessionService, activityTracker, labCluster, targetProxy));
		registration.setOrder(SessionRepositoryFilter.DEFAULT_ORDER + 1);
		return registration;
	}

}
