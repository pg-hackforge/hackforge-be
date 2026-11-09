package com.popogonry.hackforge.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRepository;

import com.popogonry.hackforge.web.ErrorCode;
import com.popogonry.hackforge.web.Problem;

import tools.jackson.databind.json.JsonMapper;

@Configuration
class SecurityConfig {

	@Bean
	SecurityFilterChain api(HttpSecurity http, CsrfTokenRepository csrfTokenRepository, JsonMapper jsonMapper) {
		http.authorizeHttpRequests(a -> a
				.requestMatchers(HttpMethod.POST, "/api/auth/signup", "/api/auth/login").permitAll()
				.requestMatchers("/actuator/health", "/error").permitAll()
				// Swagger UI(bootRun 전용)와 그 화면이 읽는 spec. 운영 jar에는 Swagger UI가 없어서 spec 파일만 열림
				.requestMatchers(HttpMethod.GET, "/openapi.yaml", "/swagger-ui.html", "/swagger-ui/**",
						"/v3/api-docs/swagger-config")
					.permitAll()
				.requestMatchers("/api/admin/**").hasRole("ADMIN")
				.anyRequest().authenticated())
			// SPA cookie 방식: XSRF-TOKEN cookie(JS에서 읽음) 값을 X-XSRF-TOKEN header로 받는다
			.csrf(c -> c.spa().csrfTokenRepository(csrfTokenRepository))
			// 401 받은 요청을 로그인 후 돌아갈 곳으로 세션에 저장하지 않는다. 비로그인 요청마다 세션이 생김
			.requestCache(r -> r.disable())
			.logout(l -> l.logoutUrl("/api/auth/logout")
				.logoutSuccessHandler((req, res, auth) -> res.setStatus(HttpStatus.NO_CONTENT.value())))
			.exceptionHandling(e -> e
				.authenticationEntryPoint((req, res, ex) -> Problem.of(ErrorCode.UNAUTHENTICATED)
					.write(res, jsonMapper))
				.accessDeniedHandler((req, res, ex) -> (ex instanceof CsrfException
						? Problem.of(ErrorCode.CSRF_REJECTED)
						: Problem.of(ErrorCode.FORBIDDEN))
					.write(res, jsonMapper)));
		return http.build();
	}

	@Bean
	CsrfTokenRepository csrfTokenRepository() {
		return CookieCsrfTokenRepository.withHttpOnlyFalse();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

}
