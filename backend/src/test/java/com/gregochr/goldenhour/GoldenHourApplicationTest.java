package com.gregochr.goldenhour;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;

import static org.mockito.Mockito.mockStatic;

/** Tests that {@link GoldenHourApplication#main} hands its own class and the arguments to Spring Boot. */
class GoldenHourApplicationTest {

    @Test
    @DisplayName("main() runs the Spring application for GoldenHourApplication with the given arguments")
    void main_delegatesToSpringApplication() {
        String[] args = {"--spring.profiles.active=local"};

        try (MockedStatic<SpringApplication> spring = mockStatic(SpringApplication.class)) {
            GoldenHourApplication.main(args);

            spring.verify(() -> SpringApplication.run(GoldenHourApplication.class, args));
        }
    }
}
