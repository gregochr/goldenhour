package com.gregochr.goldenhour;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for The Photographer's Golden Hour application.
 */
@SpringBootApplication
@EnableScheduling
public class GoldenHourApplication {

    /**
     * Starts the Spring Boot application.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(GoldenHourApplication.class, args);
    }
}
