package com.gregochr.goldenhour.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.config.AuroraProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for {@link LightPollutionClient} SQM and Bortle conversion logic.
 */
class LightPollutionClientTest {

    private LightPollutionClient client;

    @BeforeEach
    void setUp() {
        AuroraProperties props = new AuroraProperties();
        client = new LightPollutionClient(
                org.springframework.web.client.RestClient.create(), new ObjectMapper(), props);
    }

    // -------------------------------------------------------------------------
    // mcdToSqm — Jurij's formula: log10((mcd + 0.171168465) / 108000000) / -0.4
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("mcdToSqm: zero artificial brightness yields SQM ≈ 22.0 (natural sky)")
    void mcdToSqm_zeroBrightness_naturalSky() {
        double sqm = client.mcdToSqm(0.0);
        assertThat(sqm).isCloseTo(22.0, within(0.1));
    }

    @Test
    @DisplayName("mcdToSqm: natural background offset alone yields SQM ≈ 21.25")
    void mcdToSqm_naturalOffset_yieldsDarkSky() {
        double sqm = client.mcdToSqm(0.171168465);
        // (0.171168465 + 0.171168465) / 108000000 → SQM ≈ 21.25
        assertThat(sqm).isCloseTo(21.25, within(0.1));
    }

    @Test
    @DisplayName("mcdToSqm: high artificial brightness yields low SQM")
    void mcdToSqm_highBrightness_lowSqm() {
        double sqm = client.mcdToSqm(5.0);
        assertThat(sqm).isLessThan(18.38);
    }

    @Test
    @DisplayName("mcdToSqm: moderate brightness yields suburban SQM range")
    void mcdToSqm_moderateBrightness_suburbanRange() {
        double sqm = client.mcdToSqm(0.5);
        assertThat(sqm).isBetween(19.0, 21.0);
    }

    // -------------------------------------------------------------------------
    // sqmToBortle — Handprint reference table
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "SQM {0} → Bortle {1}")
    @CsvSource({
        "22.5,   1",   // excellent dark-sky site
        "22.0,   1",   // boundary: >= 21.99
        "21.99,  1",   // exact boundary
        "21.98,  2",   // just below Bortle 1
        "21.89,  2",   // exact boundary
        "21.88,  3",   // just below Bortle 2
        "21.69,  3",   // exact boundary
        "21.68,  4",   // just below Bortle 3
        "20.49,  4",   // exact boundary
        "20.48,  5",   // just below Bortle 4
        "19.50,  5",   // exact boundary
        "19.49,  6",   // just below Bortle 5
        "18.94,  6",   // exact boundary
        "18.93,  7",   // just below Bortle 6
        "18.38,  7",   // exact boundary
        "18.37,  8",   // just below Bortle 7
        "17.0,   8",   // city sky
        "15.0,   8"    // extreme city sky
    })
    @DisplayName("sqmToBortle converts SQM to correct Bortle class")
    void sqmToBortle_boundaries(double sqm, int expectedBortle) {
        assertThat(client.sqmToBortle(sqm)).isEqualTo(expectedBortle);
    }

    @Test
    @DisplayName("Bortle class is always in range 1–8")
    void sqmToBortle_alwaysInRange() {
        double[] testValues = {25.0, 22.0, 21.0, 20.0, 19.0, 18.0, 15.0, 10.0};
        for (double sqm : testValues) {
            int bortle = client.sqmToBortle(sqm);
            assertThat(bortle).isBetween(1, 8);
        }
    }

    // -------------------------------------------------------------------------
    // End-to-end: mcd → SQM → Bortle
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Known test value: 0.171168465 mcd/m² → SQM ≈ 21.25 → Bortle 4")
    void endToEnd_naturalOffset_bortle4() {
        double sqm = client.mcdToSqm(0.171168465);
        int bortle = client.sqmToBortle(sqm);
        assertThat(bortle).isEqualTo(4);
    }

    @Test
    @DisplayName("Known test value: 0.0 mcd/m² → SQM ≈ 22.0 → Bortle 1")
    void endToEnd_zeroBrightness_bortle1() {
        double sqm = client.mcdToSqm(0.0);
        int bortle = client.sqmToBortle(sqm);
        assertThat(bortle).isEqualTo(1);
    }

    @Test
    @DisplayName("Known test value: 5.0 mcd/m² → SQM < 18.38 → Bortle 8")
    void endToEnd_highBrightness_bortle8() {
        double sqm = client.mcdToSqm(5.0);
        int bortle = client.sqmToBortle(sqm);
        assertThat(bortle).isEqualTo(8);
    }
}
