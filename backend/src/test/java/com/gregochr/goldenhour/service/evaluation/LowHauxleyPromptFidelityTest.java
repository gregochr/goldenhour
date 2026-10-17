package com.gregochr.goldenhour.service.evaluation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pins the Low Hauxley regression fixture to the exact user message production sent for
 * {@code forecast_evaluation} id 86677. No API call is made, so this runs in the ordinary build:
 * it is what proves the tagged regression case sends what production sent, and it fails if either
 * the fixture or the prompt builder drifts.
 */
class LowHauxleyPromptFidelityTest {

    @Test
    void fixtureProducesTheStoredProductionPromptExactly() {
        String built = LowHauxleyFixture.userMessage(LowHauxleyFixture.atmosphericData());

        assertEquals(LowHauxleyFixture.storedProductionPrompt(), built);
    }

    @Test
    void fixtureCarriesATideSoTheCoastalBuilderIsSelected() {
        assertNotNull(LowHauxleyFixture.atmosphericData().tide(),
                "Production's row carried a tide, so the request went to the coastal system prompt");
    }
}
