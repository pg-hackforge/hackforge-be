package com.popogonry.hackforge.labsession.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.popogonry.hackforge.labsession.domain.SessionEvent;

public interface SessionEventRepository extends JpaRepository<SessionEvent, Long> {

	List<SessionEvent> findBySessionIdOrderByIdAsc(long sessionId);

	/**
	 * 이 transaction에서 세션 상태를 바꾸는 주체를 trigger에 알린다(ADR 026). transaction 범위 설정이라 다음 요청으로 새지 않는다.
	 * 상태를 바꾸는 update보다 먼저, 같은 transaction에서 부른다
	 */
	@Query(value = "select set_config('hackforge.actor', :actor, true) || set_config('hackforge.actor_user_id', :userId, true)",
			nativeQuery = true)
	String setActor(String actor, String userId);

}
