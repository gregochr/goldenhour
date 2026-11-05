package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.service.SkyRatingEvalOverviewService;
import com.gregochr.goldenhour.service.SkyRatingEvalService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The null-body guards of {@link SkyRatingEvalController}: a request body the framework hands over as {@code null}
 * is a 400-mapped {@link IllegalArgumentException} and reaches no service.
 */
class SkyRatingEvalControllerGuardTest {

    private final SkyRatingEvalService service = mock(SkyRatingEvalService.class);
    private final SkyRatingEvalController controller =
            new SkyRatingEvalController(service, mock(SkyRatingEvalOverviewService.class));

    @Test
    @DisplayName("a null /check body is refused before any run is started")
    void check_nullBody_isRefused() {
        assertThatThrownBy(() -> controller.check(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Request body is required");
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("a null /schedule body is refused before any model is toggled")
    void setScheduled_nullBody_isRefused() {
        assertThatThrownBy(() -> controller.setScheduled("SONNET", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Request body must carry a boolean 'scheduled'");
        verifyNoInteractions(service);
    }
}
