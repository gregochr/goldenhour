package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.model.SunsetEvaluation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins Claude's {@code mist_potential} echo (valley-mist-plan M2) on both parser paths: read when a
 * recognised band, null when absent or garbage, never coerced into NONE.
 */
class SunsetEvaluationParserMistTest {

    private final SunsetEvaluationParser parser = new SunsetEvaluationParser();

    private static String json(String mistFragment) {
        return "{\"rating\":4,\"fiery_sky\":20,\"golden_hour\":70,"
                + "\"summary\":\"Thin mist over the river with a clear sky above.\""
                + mistFragment + "}";
    }

    /** Invalid JSON (trailing comma), so the regex fallback path parses it. */
    private static String malformed(String mistFragment) {
        return "{\"rating\":4,\"fiery_sky\":20,\"golden_hour\":70,"
                + "\"summary\":\"Thin mist over the river with a clear sky above.\""
                + mistFragment + ", }";
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"NONE", "POSSIBLE", "LIKELY", "FOGBOUND"})
    @DisplayName("strict JSON path reads each recognised band")
    void jsonPath_readsEachBand(String band) {
        SunsetEvaluation result = parser.parseEvaluation(
                json(",\"mist_potential\":\"" + band + "\""), new ObjectMapper());

        assertThat(result.mistPotential()).isEqualTo(band);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"NONE", "POSSIBLE", "LIKELY", "FOGBOUND"})
    @DisplayName("regex fallback path reads each recognised band")
    void regexPath_readsEachBand(String band) {
        SunsetEvaluation result = parser.parseEvaluation(
                malformed(",\"mist_potential\":\"" + band + "\""), new ObjectMapper());

        assertThat(result.mistPotential()).isEqualTo(band);
    }

    @Test
    @DisplayName("a lower-case or padded band is normalised to the enum name")
    void jsonPath_normalisesCaseAndPadding() {
        SunsetEvaluation result = parser.parseEvaluation(
                json(",\"mist_potential\":\" likely \""), new ObjectMapper());

        assertThat(result.mistPotential()).isEqualTo("LIKELY");
    }

    @Test
    @DisplayName("garbage is null on the JSON path, not NONE")
    void jsonPath_garbageIsNull() {
        SunsetEvaluation result = parser.parseEvaluation(
                json(",\"mist_potential\":\"Moderate Valley Mist\""), new ObjectMapper());

        assertThat(result.mistPotential()).isNull();
    }

    @Test
    @DisplayName("garbage is null on the regex path, not NONE")
    void regexPath_garbageIsNull() {
        SunsetEvaluation result = parser.parseEvaluation(
                malformed(",\"mist_potential\":\"SOMEWHAT\""), new ObjectMapper());

        assertThat(result.mistPotential()).isNull();
    }

    @Test
    @DisplayName("absent is null on both paths")
    void absentIsNull() {
        assertThat(parser.parseEvaluation(json(""), new ObjectMapper()).mistPotential()).isNull();
        assertThat(parser.parseEvaluation(malformed(""), new ObjectMapper()).mistPotential()).isNull();
    }

    @Test
    @DisplayName("an explicit JSON null is null")
    void jsonNullIsNull() {
        SunsetEvaluation result = parser.parseEvaluation(
                json(",\"mist_potential\":null"), new ObjectMapper());

        assertThat(result.mistPotential()).isNull();
    }

    @Test
    @DisplayName("the rest of the evaluation is untouched by the new field")
    void otherFieldsUnchanged() {
        SunsetEvaluation result = parser.parseEvaluation(
                json(",\"mist_potential\":\"LIKELY\",\"inversion_score\":3,"
                        + "\"inversion_potential\":\"NONE\""), new ObjectMapper());

        assertThat(result.rating()).isEqualTo(4);
        assertThat(result.inversionScore()).isEqualTo(3);
        assertThat(result.inversionPotential()).isEqualTo("NONE");
        assertThat(result.mistPotential()).isEqualTo("LIKELY");
    }

    @Test
    @DisplayName("withRating keeps the mist reading")
    void withRating_keepsMistPotential() {
        SunsetEvaluation result = parser.parseEvaluation(
                json(",\"mist_potential\":\"POSSIBLE\""), new ObjectMapper());

        assertThat(result.withRating(2).mistPotential()).isEqualTo("POSSIBLE");
    }
}
