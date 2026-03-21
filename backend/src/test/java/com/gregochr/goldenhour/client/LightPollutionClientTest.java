package com.gregochr.goldenhour.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LightPollutionClient} Bortle conversion logic.
 */
class LightPollutionClientTest {

    private LightPollutionClient client;

    @BeforeEach
    void setUp() {
        // RestClient not needed for toBortleClass tests
        client = new LightPollutionClient(org.springframework.web.client.RestClient.create());
    }

    @ParameterizedTest(name = "{0} mcd/m² → Bortle {1}")
    @CsvSource({
        "0.0,      1",
        "0.005,    1",
        "0.0099,   1",
        "0.01,     2",
        "0.015,    2",
        "0.0199,   2",
        "0.02,     3",
        "0.05,     3",
        "0.06,     4",
        "0.16,     4",
        "0.17,     5",
        "0.49,     5",
        "0.50,     6",
        "1.69,     6",
        "1.70,     7",
        "4.99,     7",
        "5.00,     8",
        "14.99,    8",
        "15.0,     9",
        "100.0,    9"
    })
    @DisplayName("toBortleClass converts mcd/m² to correct Bortle class")
    void toBortleClass_boundaries(double mcd, int expectedBortle) {
        assertThat(client.toBortleClass(mcd)).isEqualTo(expectedBortle);
    }

    @Test
    @DisplayName("Bortle class is always in range 1–9")
    void toBortleClass_alwaysInRange() {
        double[] testValues = {0.0, 0.001, 0.05, 0.5, 5.0, 50.0, 500.0};
        for (double mcd : testValues) {
            int bortle = client.toBortleClass(mcd);
            assertThat(bortle).isBetween(1, 9);
        }
    }

    @Test
    @DisplayName("Very high brightness (urban core) classifies as Bortle 9")
    void toBortleClass_extremeUrbanBrightness() {
        assertThat(client.toBortleClass(1000.0)).isEqualTo(9);
    }

    @Test
    @DisplayName("Near-zero brightness (remote wilderness) classifies as Bortle 1")
    void toBortleClass_nearZeroBrightness() {
        assertThat(client.toBortleClass(0.0001)).isEqualTo(1);
    }
}
