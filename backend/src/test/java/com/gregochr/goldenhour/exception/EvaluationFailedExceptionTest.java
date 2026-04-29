package com.gregochr.goldenhour.exception;

import com.gregochr.goldenhour.entity.TargetType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link EvaluationFailedException}'s identity surface.
 *
 * <p>The constructor composes the message from its inputs and exposes each input
 * via a getter; this test guards the round-trip so the engine's {@code Errored}
 * outcome continues to propagate enough context for observability.
 */
class EvaluationFailedExceptionTest {

    @Test
    void preservesAllFieldsAndComposesMessage() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        EvaluationFailedException ex = new EvaluationFailedException(
                "overloaded_error", "busy", "Castlerigg",
                TargetType.SUNRISE, date);

        assertThat(ex.getErrorType()).isEqualTo("overloaded_error");
        assertThat(ex.getLocationName()).isEqualTo("Castlerigg");
        assertThat(ex.getTargetType()).isEqualTo(TargetType.SUNRISE);
        assertThat(ex.getDate()).isEqualTo(date);
        assertThat(ex.getMessage())
                .contains("Castlerigg")
                .contains("SUNRISE")
                .contains("2026-06-21")
                .contains("overloaded_error")
                .contains("busy");
    }
}
