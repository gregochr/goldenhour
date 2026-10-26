package com.gregochr.goldenhour.config;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DormancyProperties}: the safe Java defaults and the {@code @Min} floors that make
 * Spring Boot refuse a window short enough to delete a reader who simply went on holiday.
 */
class DormancyPropertiesTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void buildValidator() {
        factory = Validation.byDefaultProvider().configure()
                .messageInterpolator(new ParameterMessageInterpolator()).buildValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeFactory() {
        factory.close();
    }

    private static DormancyProperties with(int inactive, int grace) {
        DormancyProperties properties = new DormancyProperties();
        properties.setInactiveDays(inactive);
        properties.setGraceDays(grace);
        return properties;
    }

    @Test
    @DisplayName("the defaults are the safe ones (dry run, 90 days, 14 days) and they validate")
    void defaultsAreSafeAndValid() {
        DormancyProperties properties = new DormancyProperties();

        assertThat(properties.isDryRun()).isTrue();
        assertThat(properties.getInactiveDays()).isEqualTo(90);
        assertThat(properties.getGraceDays()).isEqualTo(14);
        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    @DisplayName("the floors themselves (30 inactive days, 7 grace days) are accepted")
    void floorsAreAccepted() {
        assertThat(validator.validate(with(30, 7))).isEmpty();
    }

    @ParameterizedTest(name = "inactive={0} grace={1} is refused on {2}")
    @CsvSource({
            "29, 14, inactiveDays",
            "0,  14, inactiveDays",
            "-90, 14, inactiveDays",
            "90, 6,  graceDays",
            "90, 0,  graceDays",
            "90, -1, graceDays"
    })
    @DisplayName("a window below its floor is a constraint violation on that field")
    void belowTheFloorIsRefused(int inactive, int grace, String field) {
        Set<ConstraintViolation<DormancyProperties>> violations = validator.validate(with(inactive, grace));

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo(field);
    }
}
