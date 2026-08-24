package com.ddf.vodsystem;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableAsync
public class VodSystemApplication {

	/**
	 * Application entry point; boots the Spring context.
	 *
	 * @param args command-line arguments passed to Spring Boot
	 */
	public static void main(String[] args) {
		SpringApplication.run(VodSystemApplication.class, args);
	}

}
