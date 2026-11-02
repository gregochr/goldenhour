package com.gregochr.goldenhour.service.evaluation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PromptTrimsTest {

    private static final String STORED = "Humidity: 81%, Precip probability: 0%\n"
            + "Dew point: 1.0°C\n"
            + "Boundary layer: 400m, Shortwave: 13 W/m²\n"
            + "PM2.5: 1\n"
            + "Location orientation: sunrise-optimised (this location is best suited for sunrise photography)";

    private static final String TRIMMED = "Humidity: 81%\n"
            + "Dew point: 1.0°C\n"
            + "Boundary layer: 400m\n"
            + "PM2.5: 1\n"
            + "Location orientation: sunrise-optimised";

    @Test
    void removesAllThreeTexts() {
        assertEquals(TRIMMED, PromptTrims.apply(STORED));
    }

    @Test
    void failsWhenPrecipProbabilityIsMissing() {
        assertThrows(IllegalStateException.class,
                () -> PromptTrims.apply(STORED.replace(", Precip probability: 0%", "")));
    }

    @Test
    void failsWhenShortwaveIsMissing() {
        assertThrows(IllegalStateException.class,
                () -> PromptTrims.apply(STORED.replace(", Shortwave: 13 W/m²", "")));
    }

    @Test
    void failsWhenOrientationRestatementIsMissing() {
        assertThrows(IllegalStateException.class, () -> PromptTrims.apply(
                STORED.replace(" (this location is best suited for sunrise photography)", "")));
    }

    @Test
    void failsWhenAStepMatchesTwice() {
        assertThrows(IllegalStateException.class,
                () -> PromptTrims.apply(STORED + "\nx, Shortwave: 2 W/m²"));
    }
}
