package com.popogonry.hackforge;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class HackforgeApplication {

	public static void main(String[] args) {
		SpringApplication.run(HackforgeApplication.class, args);
	}

	/** 시간을 바꿔 가며 확인하는 테스트에서 교체한다 */
	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}
