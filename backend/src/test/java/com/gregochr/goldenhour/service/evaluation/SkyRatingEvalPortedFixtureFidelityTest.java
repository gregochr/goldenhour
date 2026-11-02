package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.eval.SkyRatingEvalFixture;
import com.gregochr.goldenhour.eval.SkyRatingEvalFixtures;
import com.gregochr.goldenhour.model.AtmosphericData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Proves the two "worth going" sky-rating eval fixtures, once loaded from their frozen JSON, build
 * exactly the user message production sent. No API call is made. It lives in this package so it can
 * reach the package-private {@code LowHauxleyFixture} / {@code SteelRiggFixture}.
 */
class SkyRatingEvalPortedFixtureFidelityTest {

    @Test
    void lowHauxleyEvalFixtureBuildsTheStoredProductionPrompt() {
        AtmosphericData loaded = load("low-hauxley-4oct-worth-going");

        assertEquals(LowHauxleyFixture.storedProductionPrompt(), LowHauxleyFixture.userMessage(loaded));
        assertNotNull(loaded.tide(), "coastal: production carried a tide");
    }

    @Test
    void steelRiggEvalFixtureBuildsTheStoredProductionPrompt() {
        AtmosphericData loaded = load("steel-rigg-24oct-worth-going");

        assertEquals(SteelRiggFixture.storedProductionPrompt(), SteelRiggFixture.userMessage(loaded));
        assertNull(loaded.tide(), "inland: production carried no tide");
    }

    private static AtmosphericData load(String name) {
        SkyRatingEvalFixture fixture = SkyRatingEvalFixtures.ALL.stream()
                .filter(f -> f.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No such fixture: " + name));
        return SkyRatingEvalFixtures.load(fixture);
    }
}
