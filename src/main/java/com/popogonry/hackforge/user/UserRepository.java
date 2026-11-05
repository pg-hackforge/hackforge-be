package com.popogonry.hackforge.user;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface UserRepository extends JpaRepository<User, Long> {

	Optional<User> findByEmail(String email);

}
