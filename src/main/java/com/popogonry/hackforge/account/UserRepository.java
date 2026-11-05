package com.popogonry.hackforge.account;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface UserRepository extends JpaRepository<User, Long> {

	Optional<User> findByEmail(String email);

}
