package com.popogonry.hackforge.user;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.popogonry.hackforge.user.domain.User;

interface UserRepository extends JpaRepository<User, Long> {

	Optional<User> findByEmail(String email);

}
