package com.gregochr.goldenhour.service.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.model.BestBet;
import com.gregochr.goldenhour.model.BestBetResult;
import com.gregochr.goldenhour.model.BestBetStatus;
import com.gregochr.goldenhour.model.Confidence;
import com.gregochr.goldenhour.model.DiffersBy;
import com.gregochr.goldenhour.model.Relationship;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Unit tests for {@link BestBetResponseParser}: status classification, field extraction defaults,
 * and salvaging complete leading picks from a truncated response.
 */
class BestBetResponseParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private BestBetResult parse(String raw) {
        return BestBetResponseParser.classifyAndParse(raw, mapper);
    }

    @Test
    @DisplayName("a null response is FAILED")
    void nullResponse_failed() {
        assertThat(parse(null)).isEqualTo(BestBetResult.failed());
    }

    @Test
    @DisplayName("a blank response is FAILED")
    void blankResponse_failed() {
        assertThat(parse("   \n ")).isEqualTo(BestBetResult.failed());
    }

    @Test
    @DisplayName("a response with no 'picks' key is FAILED")
    void missingPicksKey_failed() {
        assertThat(parse("{\"other\": 1}").status()).isEqualTo(BestBetStatus.FAILED);
    }

    @Test
    @DisplayName("a response whose 'picks' is not an array is FAILED")
    void picksNotAnArray_failed() {
        assertThat(parse("{\"picks\": \"none\"}").status()).isEqualTo(BestBetStatus.FAILED);
    }

    @Test
    @DisplayName("an empty 'picks' array is an honest decline: SUCCESS_NO_PICKS")
    void emptyPicks_noPicks() {
        BestBetResult result = parse("{\"picks\": []}");

        assertThat(result.status()).isEqualTo(BestBetStatus.SUCCESS_NO_PICKS);
        assertThat(result.picks()).isEmpty();
    }

    @Test
    @DisplayName("a code-fenced response with preamble is parsed; every field is extracted")
    void fencedResponse_allFieldsExtracted() {
        String raw = "```json\n{\"picks\": [{\"rank\": 2, \"headline\": \"Sunset at the coast\","
                + " \"detail\": \"Clear skies\", \"event\": \"2026-06-21_sunset\","
                + " \"region\": \"Northumberland\", \"confidence\": \"HIGH\","
                + " \"relationship\": \"same_slot\","
                + " \"differsBy\": [\"date\", \"bogus\", \"REGION\"]}]}\n```";

        BestBetResult result = parse(raw);

        assertThat(result.status()).isEqualTo(BestBetStatus.SUCCESS_WITH_PICKS);
        BestBet pick = result.picks().get(0);
        assertThat(pick.rank()).isEqualTo(2);
        assertThat(pick.headline()).isEqualTo("Sunset at the coast");
        assertThat(pick.detail()).isEqualTo("Clear skies");
        assertThat(pick.event()).isEqualTo("2026-06-21_sunset");
        assertThat(pick.region()).isEqualTo("Northumberland");
        assertThat(pick.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(pick.relationship()).isEqualTo(Relationship.SAME_SLOT);
        assertThat(pick.differsBy()).containsExactly(DiffersBy.DATE, DiffersBy.REGION);
        assertThat(pick.nearestDriveMinutes()).isNull();
        assertThat(pick.dayName()).isNull();
    }

    @Test
    @DisplayName("a stay-home pick with JSON null event and region, and missing optional fields, takes defaults")
    void stayHomePick_nullsAndDefaults() {
        BestBetResult result = parse("{\"picks\": [{\"headline\": \"Stay in\", \"event\": null,"
                + " \"region\": null}]}");

        BestBet pick = result.picks().get(0);
        assertThat(pick.rank()).isEqualTo(1);
        assertThat(pick.event()).isNull();
        assertThat(pick.region()).isNull();
        assertThat(pick.detail()).isNull();
        assertThat(pick.confidence()).isEqualTo(Confidence.MEDIUM);
        assertThat(pick.relationship()).isNull();
        assertThat(pick.differsBy()).isEmpty();
    }

    @Test
    @DisplayName("a 'differsBy' that is not an array is ignored")
    void differsByNotAnArray_ignored() {
        BestBetResult result = parse("{\"picks\": [{\"headline\": \"h\", \"differsBy\": \"date\"}]}");

        assertThat(result.picks().get(0).differsBy()).isEmpty();
    }

    @Test
    @DisplayName("a response truncated mid-pick keeps the complete leading pick")
    void truncatedMidPick_salvagesCompleteLeadingPick() {
        String raw = "{\"picks\": [{\"rank\": 1, \"headline\": \"First\", \"event\": \"2026-06-21_sunset\","
                + " \"region\": \"Northumberland\"}, {\"rank\": 2, \"headline\": \"cut off here";

        BestBetResult result = parse(raw);

        assertThat(result.status()).isEqualTo(BestBetStatus.SUCCESS_WITH_PICKS);
        assertThat(result.picks()).extracting(BestBet::headline).containsExactly("First");
    }

    @Test
    @DisplayName("a truncated response keeps every complete leading pick, not just the first")
    void truncatedAfterTwoPicks_salvagesBoth() {
        String raw = "{\"picks\": [{\"rank\": 1, \"headline\": \"First\"},\n  {\"rank\": 2, \"headline\": \"Second\"},"
                + " {\"rank\": 3, \"headline\": \"Thi";

        List<BestBet> picks = parse(raw).picks();

        assertThat(picks).extracting(BestBet::headline).containsExactly("First", "Second");
    }

    @Test
    @DisplayName("an invalid response with no 'picks' key cannot be salvaged: FAILED")
    void invalidWithoutPicksKey_failed() {
        assertThat(parse("{\"other\": [1, 2,").status()).isEqualTo(BestBetStatus.FAILED);
    }

    @Test
    @DisplayName("an invalid response whose 'picks' key has no array cannot be salvaged: FAILED")
    void invalidWithPicksKeyButNoArray_failed() {
        assertThat(parse("{\"picks\": \"unterminated").status()).isEqualTo(BestBetStatus.FAILED);
    }

    @Test
    @DisplayName("salvaging stops at a garbled element that is not an object: FAILED when nothing precedes it")
    void garbledFirstElement_failed() {
        assertThat(parse("{\"picks\": [ \"oops\", {\"rank\": 1}").status()).isEqualTo(BestBetStatus.FAILED);
    }

    @Test
    @DisplayName("salvaging stops at a balanced but unparseable object: FAILED when nothing precedes it")
    void balancedButInvalidFirstObject_failed() {
        assertThat(parse("{\"picks\": [{bad json}, {\"rank\": 2}").status()).isEqualTo(BestBetStatus.FAILED);
    }

    @Test
    @DisplayName("an over-long unparseable response is FAILED, not an exception")
    void overLongUnparseable_failed() {
        String raw = "not json at all " + "x".repeat(5000);

        assertThat(parse(raw)).isEqualTo(BestBetResult.failed());
    }
}
