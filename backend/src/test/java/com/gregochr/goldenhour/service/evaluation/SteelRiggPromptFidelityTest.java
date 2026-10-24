package com.gregochr.goldenhour.service.evaluation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins the Steel Rigg regression fixture to the exact user message production sent for
 * {@code forecast_evaluation} id 119939. No API call is made, so this runs in the ordinary build:
 * it is what proves the tagged regression case sends what production sent, and it fails if either
 * the fixture or the prompt builder drifts.
 */
class SteelRiggPromptFidelityTest {

    @Test
    void fixtureProducesTheStoredProductionPromptExactly() {
        String built = SteelRiggFixture.userMessage(SteelRiggFixture.atmosphericData());

        assertEquals(SteelRiggFixture.storedProductionPrompt(), built);
    }

    @Test
    void fixtureCarriesNoTideSoTheInlandBuilderIsSelected() {
        assertNull(SteelRiggFixture.atmosphericData().tide(),
                "Steel Rigg is inland: production's row carried no tide, so the plain system prompt was used");
    }
}
