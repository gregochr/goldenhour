package com.gregochr.goldenhour.service.evaluation;

import tools.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.model.BluebellEvaluation;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SunsetEvaluationParser}.
 *
 * <p>Moved verbatim from {@code ClaudeEvaluationStrategyTest} when the parser was extracted
 * out of the strategy — the assertions are unchanged, only the class under test moved.
 */
class SunsetEvaluationParserTest {

    private final SunsetEvaluationParser parser = new SunsetEvaluationParser();

    private static final String SYNTHETIC_OVERCAPTURE_SINGLE_QUOTED_HEADLINE =
            "{\"rating\":2,\"fiery_sky\":15,\"golden_hour\":20,"
            + "\"summary\":\"Heavy blanket over the horizon.\",\"headline\":'no colour'}";

    @Test
    @DisplayName("parseEvaluation() handles missing rating field gracefully")
    void parseEvaluation_missingRating_returnsNullRating() {
        SunsetEvaluation result = parser.parseEvaluation(
                "{\"fiery_sky\": 50, \"golden_hour\": 60, \"summary\": \"Moderate conditions.\"}",
                new ObjectMapper());

        assertThat(result.rating()).isNull();
        assertThat(result.fierySkyPotential()).isEqualTo(50);
        assertThat(result.goldenHourPotential()).isEqualTo(60);
    }

    @Test
    @DisplayName("parseEvaluation() does not StackOverflow on a long summary in the regex fallback")
    void parseEvaluation_longSummaryInFallback_doesNotStackOverflow() {
        // A trailing comma makes this invalid JSON, forcing the regex fallback; the summary value is
        // long. The old (?:[^"\\]|\\.)* capture recursed once per character and overflowed the stack
        // on long responses (observed crashing real eval runs). The unrolled-loop form matches it
        // iteratively. 20k chars is well past the recursion limit of the old pattern.
        String longSummary = "a".repeat(20_000);
        String malformed = "{\"rating\": 2, \"fiery_sky\": 50, \"golden_hour\": 60, \"summary\": \""
                + longSummary + "\", }";

        SunsetEvaluation result = parser.parseEvaluation(malformed, new ObjectMapper());

        assertThat(result.rating()).isEqualTo(2);
        assertThat(result.fierySkyPotential()).isEqualTo(50);
        assertThat(result.summary()).hasSize(20_000);
    }

    @Test
    @DisplayName("parseBluebellEvaluation() parses rating, summary and headline (strict JSON)")
    void parseBluebellEvaluation_strictJson_parsesAllFields() {
        BluebellEvaluation result = parser.parseBluebellEvaluation(
                "{\"rating\": 4, \"summary\": \"Soft even light if they're in flower.\","
                + " \"headline\": \"Bright overcast over the carpet\"}",
                new ObjectMapper());

        assertThat(result.rating()).isEqualTo(4);
        assertThat(result.summary()).isEqualTo("Soft even light if they're in flower.");
        assertThat(result.headline()).isEqualTo("Bright overcast over the carpet");
    }

    @Test
    @DisplayName("parseBluebellEvaluation() tolerates a missing headline (optional field)")
    void parseBluebellEvaluation_noHeadline_returnsNullHeadline() {
        BluebellEvaluation result = parser.parseBluebellEvaluation(
                "{\"rating\": 2, \"summary\": \"Too breezy under a part-leafed canopy.\"}",
                new ObjectMapper());

        assertThat(result.rating()).isEqualTo(2);
        assertThat(result.headline()).isNull();
    }

    @Test
    @DisplayName("parseBluebellEvaluation() falls back to regex when the summary has raw quotes")
    void parseBluebellEvaluation_unescapedQuotes_recoversViaRegex() {
        // An unescaped inner quote breaks strict JSON; the bounded/salvage regex still recovers
        // the rating and summary rather than dropping the slot.
        BluebellEvaluation result = parser.parseBluebellEvaluation(
                "{\"rating\": 5, \"summary\": \"Mist and low sun \"crepuscular\" rays.\"}",
                new ObjectMapper());

        assertThat(result.rating()).isEqualTo(5);
        assertThat(result.summary()).isNotBlank();
    }

    @Test
    @DisplayName("parseBluebellEvaluation() throws when neither JSON nor regex recovers a summary")
    void parseBluebellEvaluation_unparseable_throws() {
        assertThatThrownBy(() -> parser.parseBluebellEvaluation(
                "not json at all", new ObjectMapper()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("parseEvaluation() extracts inversion fields from JSON response")
    void parseEvaluation_withInversionFields_extractsInversion() {
        String json = "{\"rating\":5,\"fiery_sky\":85,\"golden_hour\":90,"
                + "\"summary\":\"Dramatic inversion.\","
                + "\"inversion_score\":9,\"inversion_potential\":\"STRONG\"}";

        SunsetEvaluation result = parser.parseEvaluation(json, new ObjectMapper());

        assertThat(result.inversionScore()).isEqualTo(9);
        assertThat(result.inversionPotential()).isEqualTo("STRONG");
    }

    @Test
    @DisplayName("parseEvaluation() returns null inversion fields when not present")
    void parseEvaluation_noInversionFields_returnsNullInversion() {
        String json = "{\"rating\":3,\"fiery_sky\":50,\"golden_hour\":60,"
                + "\"summary\":\"Normal conditions.\"}";

        SunsetEvaluation result = parser.parseEvaluation(json, new ObjectMapper());

        assertThat(result.inversionScore()).isNull();
        assertThat(result.inversionPotential()).isNull();
    }

    @Test
    @DisplayName("parseEvaluation() extracts moderate inversion fields")
    void parseEvaluation_moderateInversion_extractsCorrectly() {
        String json = "{\"rating\":4,\"fiery_sky\":70,\"golden_hour\":75,"
                + "\"summary\":\"Cloud blanket visible.\","
                + "\"inversion_score\":7,\"inversion_potential\":\"MODERATE\"}";

        SunsetEvaluation result = parser.parseEvaluation(json, new ObjectMapper());

        assertThat(result.inversionScore()).isEqualTo(7);
        assertThat(result.inversionPotential()).isEqualTo("MODERATE");
    }

    @Test
    @DisplayName("sanitiseInversionPotential() normalises verbose Claude responses to enum names")
    void sanitiseInversionPotential_normalisesVerboseValues() {
        assertThat(SunsetEvaluationParser.sanitiseInversionPotential("STRONG")).isEqualTo("STRONG");
        assertThat(SunsetEvaluationParser.sanitiseInversionPotential("MODERATE")).isEqualTo("MODERATE");
        assertThat(SunsetEvaluationParser.sanitiseInversionPotential("NONE")).isEqualTo("NONE");
        assertThat(SunsetEvaluationParser.sanitiseInversionPotential(null)).isNull();
    }

    @Test
    @DisplayName("sanitiseInversionPotential() handles verbose labels from Claude")
    void sanitiseInversionPotential_handlesVerboseLabels() {
        assertThat(SunsetEvaluationParser.sanitiseInversionPotential(
                "Moderate Cloud Inversion Potential")).isEqualTo("MODERATE");
        assertThat(SunsetEvaluationParser.sanitiseInversionPotential(
                "Strong Cloud Inversion Potential")).isEqualTo("STRONG");
        assertThat(SunsetEvaluationParser.sanitiseInversionPotential(
                "No inversion potential")).isEqualTo("NONE");
        assertThat(SunsetEvaluationParser.sanitiseInversionPotential(
                "not_applicable")).isEqualTo("NONE");
    }

    @Test
    @DisplayName("parseEvaluation() sanitises verbose inversion_potential from Claude JSON")
    void parseEvaluation_verboseInversionPotential_sanitisedToEnum() {
        String json = "{\"rating\":4,\"fiery_sky\":70,\"golden_hour\":75,"
                + "\"summary\":\"Cloud blanket below.\","
                + "\"inversion_score\":8,"
                + "\"inversion_potential\":\"Moderate Cloud Inversion Potential\"}";

        SunsetEvaluation result = parser.parseEvaluation(json, new ObjectMapper());

        assertThat(result.inversionPotential()).isEqualTo("MODERATE");
    }

    @Test
    @DisplayName("parseEvaluation() extracts basic-tier fields when present")
    void parseEvaluation_withBasicTierFields_extractsBasicScores() {
        String json = "{\"rating\":4,\"fiery_sky\":75,\"golden_hour\":70,"
                + "\"summary\":\"Good conditions.\","
                + "\"basic_fiery_sky\":60,\"basic_golden_hour\":55,"
                + "\"basic_summary\":\"Decent conditions.\"}";

        SunsetEvaluation result = parser.parseEvaluation(json, new ObjectMapper());

        assertThat(result.basicFierySkyPotential()).isEqualTo(60);
        assertThat(result.basicGoldenHourPotential()).isEqualTo(55);
        assertThat(result.basicSummary()).isEqualTo("Decent conditions.");
    }

    @Test
    @DisplayName("parseEvaluation() returns null basic-tier fields when not present")
    void parseEvaluation_noBasicTierFields_returnsNull() {
        String json = "{\"rating\":3,\"fiery_sky\":50,\"golden_hour\":60,"
                + "\"summary\":\"Average.\"}";

        SunsetEvaluation result = parser.parseEvaluation(json, new ObjectMapper());

        assertThat(result.basicFierySkyPotential()).isNull();
        assertThat(result.basicGoldenHourPotential()).isNull();
        assertThat(result.basicSummary()).isNull();
    }

    @Test
    @DisplayName("parseEvaluation() falls back to regex when JSON has unescaped quotes in summary")
    void parseEvaluation_regexFallback_extractsScores() {
        // Invalid JSON due to unescaped quotes — triggers regex fallback
        String text = "{\"rating\":4,\"fiery_sky\":72,\"golden_hour\":65,"
                + "\"summary\":\"Beautiful \"orange\" sky at sunset.\"}";

        SunsetEvaluation result = parser.parseEvaluation(text, new ObjectMapper());

        assertThat(result.rating()).isEqualTo(4);
        assertThat(result.fierySkyPotential()).isEqualTo(72);
        assertThat(result.goldenHourPotential()).isEqualTo(65);
        // Internal unescaped quotes → bounded pattern can't match → greedy salvage keeps the
        // summary (and the rating). This case has no following field, so there is no over-capture.
        assertThat(result.summary()).contains("orange");
    }

    @Test
    @DisplayName("Bug B: over-capture fixed — summary stays clean, does not swallow the next field")
    void parseEvaluation_overCapture_summaryNotPolluted() {
        SunsetEvaluation result = parser.parseEvaluation(
                SYNTHETIC_OVERCAPTURE_SINGLE_QUOTED_HEADLINE, new ObjectMapper());

        assertThat(result.summary()).isEqualTo("Heavy blanket over the horizon.");
        assertThat(result.summary()).doesNotContain("headline");
        assertThat(result.summary()).doesNotContain("','");
        // Rating extracted by its independent pattern — always safe.
        assertThat(result.rating()).isEqualTo(2);
    }

    @Test
    @DisplayName("Bug B: bounded summary + a well-formed following headline both extract cleanly")
    void parseEvaluation_overCapture_summaryCleanHeadlineExtracted() {
        // Trailing comma breaks strict JSON → fallback; summary and headline are both well-formed.
        String text = "{\"rating\":3,\"fiery_sky\":50,\"golden_hour\":55,"
                + "\"summary\":\"Soft pastels over the western ridge.\","
                + "\"headline\":\"Pastel skies\",}";

        SunsetEvaluation result = parser.parseEvaluation(text, new ObjectMapper());

        assertThat(result.summary()).isEqualTo("Soft pastels over the western ridge.");
        assertThat(result.summary()).doesNotContain("headline");
        assertThat(result.headline()).isEqualTo("Pastel skies");
        assertThat(result.rating()).isEqualTo(3);
    }

    @Test
    @DisplayName("Bug B boundary: bounded summary preserves escaped quotes (does not truncate early)")
    void parseEvaluation_overCapture_escapedQuotesPreserved() {
        // Valid \" escapes inside the summary; trailing comma breaks strict → fallback.
        String text = "{\"rating\":4,\"fiery_sky\":70,\"golden_hour\":65,"
                + "\"summary\":\"A so-called \\\"golden\\\" hour over the bay.\","
                + "\"headline\":\"Golden hour\",}";

        SunsetEvaluation result = parser.parseEvaluation(text, new ObjectMapper());

        // The escaped quotes did not cut the summary short, and it did not over-capture.
        assertThat(result.summary()).contains("golden");
        assertThat(result.summary()).contains("bay");
        assertThat(result.summary()).doesNotContain("headline");
        assertThat(result.headline()).isEqualTo("Golden hour");
        assertThat(result.rating()).isEqualTo(4);
    }

    @Test
    @DisplayName("Bug B: basic_summary is also bounded against over-capture")
    void parseEvaluation_overCapture_basicSummaryNotPolluted() {
        String text = "{\"rating\":4,\"fiery_sky\":70,\"golden_hour\":65,"
                + "\"summary\":\"Clear horizon.\",\"basic_summary\":\"Looks clear.\","
                + "\"headline\":\"Clear\",}";

        SunsetEvaluation result = parser.parseEvaluation(text, new ObjectMapper());

        assertThat(result.basicSummary()).isEqualTo("Looks clear.");
        assertThat(result.basicSummary()).doesNotContain("headline");
        assertThat(result.rating()).isEqualTo(4);
    }

    @Test
    @DisplayName("Bug B: happy path unchanged — well-formed JSON parses strictly, fields exact")
    void parseEvaluation_wellFormed_strictPathUnchanged() {
        String text = "{\"rating\":5,\"fiery_sky\":88,\"golden_hour\":72,"
                + "\"summary\":\"Pre-frontal fire — mid cloud catches colour.\","
                + "\"headline\":\"Pre-frontal fire\"}";

        SunsetEvaluation result = parser.parseEvaluation(text, new ObjectMapper());

        assertThat(result.rating()).isEqualTo(5);
        assertThat(result.fierySkyPotential()).isEqualTo(88);
        assertThat(result.goldenHourPotential()).isEqualTo(72);
        assertThat(result.summary()).isEqualTo("Pre-frontal fire — mid cloud catches colour.");
        assertThat(result.headline()).isEqualTo("Pre-frontal fire");
    }

    @Test
    @DisplayName("parseEvaluationWithMetadata() flags usedRegexFallback=false on a strict parse")
    void parseEvaluationWithMetadata_strictParse_flagFalse() {
        String json = "{\"rating\":3,\"fiery_sky\":50,\"golden_hour\":55,\"summary\":\"Calm.\"}";

        SunsetEvaluationParser.ParseResult result =
                parser.parseEvaluationWithMetadata(json, new ObjectMapper());

        assertThat(result.usedRegexFallback()).isFalse();
        assertThat(result.evaluation().rating()).isEqualTo(3);
    }

    @Test
    @DisplayName("parseEvaluationWithMetadata() flags usedRegexFallback=true when strict parse fails")
    void parseEvaluationWithMetadata_regexFallback_flagTrue() {
        // Unescaped inner quotes break strict JSON → regex fallback is used.
        String text = "{\"rating\":4,\"fiery_sky\":72,\"golden_hour\":65,"
                + "\"summary\":\"Beautiful \"orange\" sky.\"}";

        SunsetEvaluationParser.ParseResult result =
                parser.parseEvaluationWithMetadata(text, new ObjectMapper());

        assertThat(result.usedRegexFallback()).isTrue();
        // Rating is extracted by its own pattern, so it stays correct even on fallback.
        assertThat(result.evaluation().rating()).isEqualTo(4);
    }

    @Test
    @DisplayName("parseEvaluation() throws when regex fallback also fails")
    void parseEvaluation_totalGarbage_throws() {
        String text = "This is not JSON and has no matching patterns.";

        assertThatThrownBy(() -> parser.parseEvaluation(text, new ObjectMapper()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Failed to parse");
    }

    @Test
    @DisplayName("parseEvaluation() handles rating-only response (no basic or inversion fields)")
    void parseEvaluation_ratingOnly_minimalResponse() {
        String json = "{\"rating\":2,\"fiery_sky\":25,\"golden_hour\":30,"
                + "\"summary\":\"Cloudy with little colour.\"}";

        SunsetEvaluation result = parser.parseEvaluation(json, new ObjectMapper());

        assertThat(result.rating()).isEqualTo(2);
        assertThat(result.fierySkyPotential()).isEqualTo(25);
        assertThat(result.goldenHourPotential()).isEqualTo(30);
        assertThat(result.summary()).isEqualTo("Cloudy with little colour.");
        assertThat(result.basicFierySkyPotential()).isNull();
        assertThat(result.basicGoldenHourPotential()).isNull();
        assertThat(result.basicSummary()).isNull();
        assertThat(result.inversionScore()).isNull();
        assertThat(result.inversionPotential()).isNull();
    }

    @Test
    @DisplayName("parseEvaluation() without rating field returns null rating")
    void parseEvaluation_noRatingField_returnsNullRating() {
        String json = "{\"fiery_sky\":80,\"golden_hour\":75,"
                + "\"summary\":\"Great sky.\"}";

        SunsetEvaluation result = parser.parseEvaluation(json, new ObjectMapper());

        assertThat(result.rating()).isNull();
        assertThat(result.fierySkyPotential()).isEqualTo(80);
        assertThat(result.goldenHourPotential()).isEqualTo(75);
    }

    @Test
    @DisplayName("sanitiseInversionPotential() handles case variations")
    void sanitiseInversionPotential_caseInsensitive() {
        assertThat(SunsetEvaluationParser.sanitiseInversionPotential("strong")).isEqualTo("STRONG");
        assertThat(SunsetEvaluationParser.sanitiseInversionPotential("Strong")).isEqualTo("STRONG");
        assertThat(SunsetEvaluationParser.sanitiseInversionPotential("moderate")).isEqualTo("MODERATE");
        assertThat(SunsetEvaluationParser.sanitiseInversionPotential("Moderate")).isEqualTo("MODERATE");
        assertThat(SunsetEvaluationParser.sanitiseInversionPotential("none")).isEqualTo("NONE");
        assertThat(SunsetEvaluationParser.sanitiseInversionPotential("")).isEqualTo("NONE");
    }

    @Test
    @DisplayName("parseEvaluation() strips ```json fences around response")
    void parseEvaluation_codeFencedJson_strippedAndParsed() {
        String fenced = "```json\n"
                + "{\"rating\":4,\"fiery_sky\":70,\"golden_hour\":65,"
                + "\"summary\":\"Fenced response.\"}\n"
                + "```";

        SunsetEvaluation result = parser.parseEvaluation(fenced, new ObjectMapper());

        assertThat(result.rating()).isEqualTo(4);
        assertThat(result.fierySkyPotential()).isEqualTo(70);
        assertThat(result.goldenHourPotential()).isEqualTo(65);
        assertThat(result.summary()).isEqualTo("Fenced response.");
    }

    @Test
    @DisplayName("parseEvaluation() strips bare ``` fences (no language tag)")
    void parseEvaluation_bareFences_strippedAndParsed() {
        String fenced = "```\n"
                + "{\"rating\":3,\"fiery_sky\":50,\"golden_hour\":55,"
                + "\"summary\":\"Bare fence.\"}\n"
                + "```";

        SunsetEvaluation result = parser.parseEvaluation(fenced, new ObjectMapper());

        assertThat(result.rating()).isEqualTo(3);
        assertThat(result.summary()).isEqualTo("Bare fence.");
    }

    @Test
    @DisplayName("parseEvaluation() extracts all fields when response contains everything")
    void parseEvaluation_allFieldsPresent_extractsEveryField() {
        String json = "{\"rating\":5,\"fiery_sky\":92,\"golden_hour\":88,"
                + "\"summary\":\"Spectacular conditions.\","
                + "\"basic_fiery_sky\":65,\"basic_golden_hour\":60,"
                + "\"basic_summary\":\"Decent conditions.\","
                + "\"inversion_score\":9,\"inversion_potential\":\"STRONG\"}";

        SunsetEvaluation result = parser.parseEvaluation(json, new ObjectMapper());

        assertThat(result.rating()).isEqualTo(5);
        assertThat(result.fierySkyPotential()).isEqualTo(92);
        assertThat(result.goldenHourPotential()).isEqualTo(88);
        assertThat(result.summary()).isEqualTo("Spectacular conditions.");
        assertThat(result.basicFierySkyPotential()).isEqualTo(65);
        assertThat(result.basicGoldenHourPotential()).isEqualTo(60);
        assertThat(result.basicSummary()).isEqualTo("Decent conditions.");
        assertThat(result.inversionScore()).isEqualTo(9);
        assertThat(result.inversionPotential()).isEqualTo("STRONG");
    }

    @Test
    @DisplayName("parseEvaluation() handles minimum score boundaries (rating=1, fiery=0, golden=0)")
    void parseEvaluation_minimumScores_parsedCorrectly() {
        String json = "{\"rating\":1,\"fiery_sky\":0,\"golden_hour\":0,"
                + "\"summary\":\"Total overcast.\"}";

        SunsetEvaluation result = parser.parseEvaluation(json, new ObjectMapper());

        assertThat(result.rating()).isEqualTo(1);
        assertThat(result.fierySkyPotential()).isZero();
        assertThat(result.goldenHourPotential()).isZero();
    }

    @Test
    @DisplayName("parseEvaluation() handles maximum score boundaries (rating=5, fiery=100, golden=100)")
    void parseEvaluation_maximumScores_parsedCorrectly() {
        String json = "{\"rating\":5,\"fiery_sky\":100,\"golden_hour\":100,"
                + "\"summary\":\"Perfect conditions.\"}";

        SunsetEvaluation result = parser.parseEvaluation(json, new ObjectMapper());

        assertThat(result.rating()).isEqualTo(5);
        assertThat(result.fierySkyPotential()).isEqualTo(100);
        assertThat(result.goldenHourPotential()).isEqualTo(100);
    }

    @Test
    @DisplayName("regex fallback extracts basic-tier fields from malformed JSON")
    void parseEvaluation_regexFallback_extractsBasicFields() {
        // Invalid JSON (unescaped quote) but regex can extract all fields
        String text = "{\"rating\":4,\"fiery_sky\":72,\"golden_hour\":65,"
                + "\"summary\":\"Beautiful \"glow\" at sunset.\","
                + "\"basic_fiery_sky\":55,\"basic_golden_hour\":50,"
                + "\"basic_summary\":\"Moderate conditions.\"}";

        SunsetEvaluation result = parser.parseEvaluation(text, new ObjectMapper());

        assertThat(result.rating()).isEqualTo(4);
        assertThat(result.fierySkyPotential()).isEqualTo(72);
        assertThat(result.basicFierySkyPotential()).isEqualTo(55);
        assertThat(result.basicGoldenHourPotential()).isEqualTo(50);
    }

    @Test
    @DisplayName("regex fallback extracts inversion fields from malformed JSON")
    void parseEvaluation_regexFallback_extractsInversionFields() {
        String text = "{\"rating\":5,\"fiery_sky\":88,\"golden_hour\":90,"
                + "\"summary\":\"Sea of \"clouds\" below.\","
                + "\"inversion_score\":10,\"inversion_potential\":\"STRONG\"}";

        SunsetEvaluation result = parser.parseEvaluation(text, new ObjectMapper());

        assertThat(result.rating()).isEqualTo(5);
        assertThat(result.inversionScore()).isEqualTo(10);
        assertThat(result.inversionPotential()).isEqualTo("STRONG");
    }
}
