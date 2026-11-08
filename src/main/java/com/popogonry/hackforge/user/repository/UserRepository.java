package com.popogonry.hackforge.user.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.popogonry.hackforge.user.domain.User;
import com.popogonry.hackforge.user.domain.UserStatus;

public interface UserRepository extends JpaRepository<User, Long> {

	Optional<User> findByEmail(String email);

	List<User> findByStatusOrderByCreatedAtAscIdAsc(UserStatus status);

	/** 지금 상태가 from 중 하나일 때만 to로 바꾼다. 바뀐 행 수(0 또는 1)를 돌려준다. */
	@Modifying
	@Query("update User u set u.status = :to where u.id = :id and u.status in :from")
	int changeStatus(Long id, UserStatus to, Collection<UserStatus> from);

	/** 읽고 더해서 저장하지 않고 DB에서 바로 1을 더한다. 동시에 틀려도 횟수가 빠지지 않음. */
	@Modifying
	@Query("update User u set u.failedLoginCount = u.failedLoginCount + 1 where u.id = :id")
	void incrementFailedLoginCount(Long id);

	/** 실패 횟수가 max 이상이면 잠그고 횟수를 0으로. 잠갔으면 1을 돌려준다. */
	@Modifying
	@Query("update User u set u.failedLoginCount = 0, u.lockedUntil = :until where u.id = :id and u.failedLoginCount >= :max")
	int lockIfFailedAtLeast(Long id, int max, Instant until);

	@Modifying
	@Query("update User u set u.failedLoginCount = 0 where u.id = :id and u.failedLoginCount > 0")
	void resetFailedLoginCount(Long id);

}
