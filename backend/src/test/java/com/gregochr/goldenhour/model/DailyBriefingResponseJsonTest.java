package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gregochr.goldenhour.entity.LunarTideType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Jackson serialisation tests for {@link DailyBriefingResponse}.
 *
 * <p>Verifies that {@code bestBets} is always present in the serialised JSON, including
 * when a global {@code NON_EMPTY} inclusion policy is in effect — as may be configured
 * in production via {@code spring.jackson.default-property-inclusion}.
 */
class DailyBriefingResponseJsonTest {

    private static final LocalDateTime GENERATED_AT = LocalDateTime.of(2026, 4, 15, 4, 0);

    // ── locationId: forward-compatible with cached payloads ───────────────────

    @Test
    @DisplayName("a payload written BEFORE locationId existed still deserialises, with a null id")
    void deserialize_legacyPayloadWithoutLocationId_yieldsNullId() throws Exception {
        // This is the rollover case, and it is not hypothetical: daily_briefing_cache holds
        // payloads serialised by the previous build, and they keep being served until their key
        // ages out. If an unknown-to-them field made deserialisation throw, the briefing would
        // 500 for every cached region the moment this shipped.
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        String legacy = """
                {
                  "locationName": "Durham",
                  "solarEventTime": "2026-04-22T06:00:00",
                  "verdict": "GO",
                  "flags": ["Clear"],
                  "standdownReason": null,
                  "displayVerdict": "WORTH_IT",
                  "canopy": false
                }
                """;

        BriefingSlot slot = mapper.readValue(legacy, BriefingSlot.class);

        assertThat(slot.locationId())
                .as("absent id must read as null, not blow up")
                .isNull();
        assertThat(slot.locationName()).isEqualTo("Durham");
        assertThat(slot.verdict()).isEqualTo(Verdict.GO);
    }

    @Test
    @DisplayName("locationId round-trips when present")
    void roundTrip_locationIdSurvives() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        BriefingSlot original = new BriefingSlot(
                42L, "Durham", LocalDateTime.of(2026, 4, 22, 6, 0), Verdict.GO,
                null, BriefingSlot.TideInfo.NONE, List.of("Clear"), null);

        BriefingSlot restored = mapper.readValue(
                mapper.writeValueAsString(original), BriefingSlot.class);

        assertThat(restored.locationId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("a null locationId is omitted from JSON, so payloads do not grow a null field")
    void serialize_nullLocationId_omitted() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        BriefingSlot slot = new BriefingSlot(
                "Durham", LocalDateTime.of(2026, 4, 22, 6, 0), Verdict.GO,
                null, BriefingSlot.TideInfo.NONE, List.of("Clear"), null);

        JsonNode node = mapper.readTree(mapper.writeValueAsString(slot));

        assertThat(node.has("locationId")).isFalse();
    }

    // ── bestBets always serialised ────────────────────────────────────────────

    @Test
    @DisplayName("empty bestBets is present in JSON with default ObjectMapper")
    void serialize_emptyBestBets_defaultMapper_fieldPresent() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        DailyBriefingResponse response = minimalResponse(List.of());

        JsonNode node = mapper.readTree(mapper.writeValueAsString(response));

        assertThat(node.has("bestBets")).isTrue();
        assertThat(node.get("bestBets").isArray()).isTrue();
        assertThat(node.get("bestBets").size()).isZero();
    }

    @Test
    @DisplayName("empty bestBets is present in JSON even with global NON_EMPTY inclusion policy")
    void serialize_emptyBestBets_globalNonEmptyPolicy_fieldStillPresent() throws Exception {
        // This is the production risk: spring.jackson.default-property-inclusion=non_empty
        // would strip all empty lists. @JsonInclude(ALWAYS) on bestBets must override that.
        ObjectMapper nonEmptyMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setDefaultPropertyInclusion(JsonInclude.Include.NON_EMPTY);

        DailyBriefingResponse response = minimalResponse(List.of());

        JsonNode node = nonEmptyMapper.readTree(nonEmptyMapper.writeValueAsString(response));

        assertThat(node.has("bestBets"))
                .as("bestBets must be serialised even under NON_EMPTY global policy")
                .isTrue();
        assertThat(node.get("bestBets").isArray()).isTrue();
        assertThat(node.get("bestBets").size()).isZero();
    }

    @Test
    @DisplayName("populated bestBets is present in JSON with global NON_EMPTY inclusion policy")
    void serialize_populatedBestBets_globalNonEmptyPolicy_fieldPresent() throws Exception {
        ObjectMapper nonEmptyMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setDefaultPropertyInclusion(JsonInclude.Include.NON_EMPTY);

        BestBet bet = new BestBet(1, "Head to the coast", "Clear skies until midnight.",
                "2026-04-15_sunset", "Northumberland", Confidence.HIGH, 45,
                "Today", "sunset", "20:48");
        DailyBriefingResponse response = minimalResponse(List.of(bet));

        JsonNode node = nonEmptyMapper.readTree(nonEmptyMapper.writeValueAsString(response));

        assertThat(node.has("bestBets")).isTrue();
        assertThat(node.get("bestBets").size()).isEqualTo(1);
        assertThat(node.get("bestBets").get(0).get("headline").asText())
                .isEqualTo("Head to the coast");
        assertThat(node.get("bestBets").get(0).get("rank").asInt()).isEqualTo(1);
    }

    // ── compact constructor null-safety ───────────────────────────────────────

    @Test
    @DisplayName("null bestBets normalised to empty list by compact constructor")
    void compactConstructor_nullBestBets_normalisedToEmptyList() {
        DailyBriefingResponse response = new DailyBriefingResponse(
                GENERATED_AT, "Test", List.of(),
                null,  // null bestBets
                null, null, false, false, 0, null, List.of(), List.of());

        assertThat(response.bestBets()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("null hotTopics normalised to empty list by compact constructor")
    void compactConstructor_nullHotTopics_normalisedToEmptyList() {
        DailyBriefingResponse response = new DailyBriefingResponse(
                GENERATED_AT, "Test", List.of(),
                List.of(), null, null, false, false, 0, null,
                null,  // null hotTopics
                List.of());

        assertThat(response.hotTopics()).isNotNull().isEmpty();
    }

    // ── round-trip (serialize → deserialize) ────────────────────────────────

    @Test
    @DisplayName("round-trip — empty bestBets deserialises to empty list, not null")
    void roundTrip_emptyBestBets_deserialisesToEmptyList() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        DailyBriefingResponse original = minimalResponse(List.of());

        String json = mapper.writeValueAsString(original);
        DailyBriefingResponse restored = mapper.readValue(json, DailyBriefingResponse.class);

        assertThat(restored.bestBets()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("round-trip — populated bestBets deserialises with correct content")
    void roundTrip_populatedBestBets_contentPreserved() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        BestBet bet = new BestBet(1, "Head to the coast", "Clear skies until midnight.",
                "2026-04-15_sunset", "Northumberland", Confidence.HIGH, 45,
                "Today", "sunset", "20:48");
        DailyBriefingResponse original = minimalResponse(List.of(bet));

        String json = mapper.writeValueAsString(original);
        DailyBriefingResponse restored = mapper.readValue(json, DailyBriefingResponse.class);

        assertThat(restored.bestBets()).hasSize(1);
        assertThat(restored.bestBets().get(0).rank()).isEqualTo(1);
        assertThat(restored.bestBets().get(0).headline()).isEqualTo("Head to the coast");
        assertThat(restored.bestBets().get(0).region()).isEqualTo("Northumberland");
        assertThat(restored.bestBets().get(0).confidence()).isEqualTo(Confidence.HIGH);
        assertThat(restored.bestBets().get(0).nearestDriveMinutes()).isEqualTo(45);
    }

    @Test
    @DisplayName("round-trip — hotTopics deserialises with correct content")
    void roundTrip_hotTopics_contentPreserved() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        HotTopic topic = new HotTopic("BLUEBELL", "Bluebell conditions", "Misty and still",
                LocalDate.of(2026, 4, 20), 1, "BLUEBELL",
                List.of("Northumberland"), "Peak bluebell season.", null);
        DailyBriefingResponse original = new DailyBriefingResponse(
                GENERATED_AT, "Test", List.of(), List.of(), null, null,
                false, false, 0, null, List.of(topic), List.of());

        String json = mapper.writeValueAsString(original);
        DailyBriefingResponse restored = mapper.readValue(json, DailyBriefingResponse.class);

        assertThat(restored.hotTopics()).hasSize(1);
        assertThat(restored.hotTopics().get(0).type()).isEqualTo("BLUEBELL");
        assertThat(restored.hotTopics().get(0).description()).isEqualTo("Peak bluebell season.");
    }

    @Test
    @DisplayName("round-trip — seasonalFeatures null-normalised to empty list")
    void roundTrip_nullSeasonalFeatures_normalisedToEmptyList() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        DailyBriefingResponse original = new DailyBriefingResponse(
                GENERATED_AT, "Test", List.of(), List.of(), null, null,
                false, false, 0, null, List.of(),
                List.of("BLUEBELL", "AURORA"));

        String json = mapper.writeValueAsString(original);
        DailyBriefingResponse restored = mapper.readValue(json, DailyBriefingResponse.class);

        assertThat(restored.seasonalFeatures()).containsExactly("BLUEBELL", "AURORA");
    }

    @Test
    @DisplayName("compact constructor — null seasonalFeatures normalised to empty list")
    void compactConstructor_nullSeasonalFeatures_normalisedToEmptyList() {
        DailyBriefingResponse response = new DailyBriefingResponse(
                GENERATED_AT, "Test", List.of(), List.of(), null, null,
                false, false, 0, null, List.of(),
                null);  // null seasonalFeatures

        assertThat(response.seasonalFeatures()).isNotNull().isEmpty();
    }

    // ── other list fields excluded by NON_EMPTY (no ALWAYS override) ─────────

    @Test
    @DisplayName("empty hotTopics is excluded under NON_EMPTY policy (no ALWAYS override on it)")
    void serialize_emptyHotTopics_globalNonEmptyPolicy_fieldAbsent() throws Exception {
        // Confirms that @JsonInclude(ALWAYS) is specifically scoped to bestBets only.
        // hotTopics has no such override, so it IS stripped under NON_EMPTY.
        ObjectMapper nonEmptyMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .setDefaultPropertyInclusion(JsonInclude.Include.NON_EMPTY);

        DailyBriefingResponse response = minimalResponse(List.of());

        JsonNode node = nonEmptyMapper.readTree(nonEmptyMapper.writeValueAsString(response));

        assertThat(node.has("hotTopics"))
                .as("hotTopics has no @JsonInclude(ALWAYS) — it should be absent under NON_EMPTY")
                .isFalse();
    }

    // ── BestBet relationship/differsBy JSON serialization ──────────────────

    @Test
    @DisplayName("relationship omitted from JSON when null (convenience constructor)")
    void serialize_nullRelationship_fieldOmitted() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        BestBet bet = new BestBet(1, "H", "D", "e", "r",
                Confidence.HIGH, null, null, null, null);
        String json = mapper.writeValueAsString(bet);
        JsonNode node = mapper.readTree(json);
        assertThat(node.has("relationship")).isFalse();
    }

    @Test
    @DisplayName("differsBy omitted from JSON when empty (convenience constructor)")
    void serialize_emptyDiffersBy_fieldOmitted() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        BestBet bet = new BestBet(1, "H", "D", "e", "r",
                Confidence.HIGH, null, null, null, null);
        String json = mapper.writeValueAsString(bet);
        JsonNode node = mapper.readTree(json);
        assertThat(node.has("differsBy")).isFalse();
    }

    @Test
    @DisplayName("relationship present in JSON when SAME_SLOT")
    void serialize_sameSlotRelationship_fieldPresent() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        BestBet bet = new BestBet(2, "H", "D", "e", "r",
                Confidence.HIGH, null, null, null, null,
                Relationship.SAME_SLOT, List.of());
        String json = mapper.writeValueAsString(bet);
        JsonNode node = mapper.readTree(json);
        assertThat(node.get("relationship").asText()).isEqualTo("SAME_SLOT");
    }

    @Test
    @DisplayName("relationship and differsBy both present when DIFFERENT_SLOT with values")
    void serialize_differentSlotWithDiffersBy_bothPresent() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        BestBet bet = new BestBet(2, "H", "D", "e", "r",
                Confidence.HIGH, null, null, null, null,
                Relationship.DIFFERENT_SLOT,
                List.of(DiffersBy.DATE, DiffersBy.EVENT));
        String json = mapper.writeValueAsString(bet);
        JsonNode node = mapper.readTree(json);
        assertThat(node.get("relationship").asText()).isEqualTo("DIFFERENT_SLOT");
        assertThat(node.get("differsBy")).hasSize(2);
        assertThat(node.get("differsBy").get(0).asText()).isEqualTo("DATE");
        assertThat(node.get("differsBy").get(1).asText()).isEqualTo("EVENT");
    }

    // ── map-tab tide-on-the-light fields (bundle rev 2) ─────────────────────────
    //
    // The wire shape these four fields ride on was bet on but unpinned by any serialisation
    // test: @JsonUnwrapped puts them flat on the slot (never nested under "tide"), @JsonInclude
    // NON_NULL omits them for an inland location, and a payload cached before they existed must
    // still deserialise. All three are load-bearing for the map tab's tide-alignment glyph.

    @Test
    @DisplayName("populated tide-on-the-light fields land FLAT on the slot JSON, never nested under \"tide\"")
    void serialize_populatedTideOnTheLight_fieldsFlatOnSlot() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        BriefingSlot.TideInfo tide = new BriefingSlot.TideInfo(
                "HIGH", true, LocalDateTime.of(2026, 4, 22, 19, 45), new BigDecimal("4.8"),
                false, false, LunarTideType.REGULAR_TIDE, "Waxing gibbous", false,
                25, "HW", true, "HW 20:20 · 25m after sunset");
        BriefingSlot slot = new BriefingSlot(7L, "Bamburgh",
                LocalDateTime.of(2026, 4, 22, 19, 55), Verdict.GO,
                null, tide, List.of("Clear"), null);

        String json = mapper.writeValueAsString(slot);
        JsonNode node = mapper.readTree(json);

        assertThat(node.has("tide"))
                .as("TideInfo is @JsonUnwrapped — it must never appear as a nested object")
                .isFalse();
        assertThat(node.get("nearestSolarOffsetMinutes").asInt()).isEqualTo(25);
        assertThat(node.get("nearestExtremeKind").asText()).isEqualTo("HW");
        assertThat(node.get("tideOnTheLight").asBoolean()).isTrue();
        assertThat(node.get("nearestSolarOffsetPhrase").asText())
                .isEqualTo("HW 20:20 · 25m after sunset");

        BriefingSlot restored = mapper.readValue(json, BriefingSlot.class);
        assertThat(restored.tide().nearestSolarOffsetMinutes()).isEqualTo(25);
        assertThat(restored.tide().nearestExtremeKind()).isEqualTo("HW");
        assertThat(restored.tide().tideOnTheLight()).isTrue();
        assertThat(restored.tide().nearestSolarOffsetPhrase())
                .isEqualTo("HW 20:20 · 25m after sunset");
    }

    @Test
    @DisplayName("inland slot: the four tide-on-the-light fields are OMITTED via NON_NULL, not written null")
    void serialize_inlandSlot_tideOnTheLightFieldsOmitted() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        BriefingSlot slot = new BriefingSlot("Derwent Valley",
                LocalDateTime.of(2026, 4, 22, 19, 55), Verdict.GO,
                null, BriefingSlot.TideInfo.NONE, List.of(), null);

        JsonNode node = mapper.readTree(mapper.writeValueAsString(slot));

        assertThat(node.has("nearestSolarOffsetMinutes")).isFalse();
        assertThat(node.has("nearestExtremeKind")).isFalse();
        assertThat(node.has("tideOnTheLight")).isFalse();
        assertThat(node.has("nearestSolarOffsetPhrase")).isFalse();
    }

    @Test
    @DisplayName("a legacy cached slot (nine tide fields, no tide-on-the-light fields) deserialises to nulls")
    void deserialize_legacyTidePayload_tideOnTheLightFieldsNull() throws Exception {
        // daily_briefing_cache holds payloads written before this phase shipped — nine tide
        // fields present, the four new ones absent entirely (not null-valued: ABSENT).
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        String legacy = """
                {
                  "locationName": "Bamburgh",
                  "solarEventTime": "2026-04-22T19:55:00",
                  "verdict": "GO",
                  "flags": [],
                  "tideState": "HIGH",
                  "tideAligned": true,
                  "nearestHighTideTime": "2026-04-22T19:45:00",
                  "nearestHighTideHeight": 4.8,
                  "heightAboveP95": false,
                  "heightAboveSpringThreshold": false,
                  "lunarTideType": "REGULAR_TIDE",
                  "lunarPhase": "Waxing gibbous",
                  "moonAtPerigee": false
                }
                """;

        BriefingSlot slot = mapper.readValue(legacy, BriefingSlot.class);

        assertThat(slot.tide().tideState()).isEqualTo("HIGH");
        assertThat(slot.tide().tideAligned()).isTrue();
        assertThat(slot.tide().nearestSolarOffsetMinutes()).isNull();
        assertThat(slot.tide().nearestExtremeKind()).isNull();
        assertThat(slot.tide().tideOnTheLight()).isNull();
        assertThat(slot.tide().nearestSolarOffsetPhrase()).isNull();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static DailyBriefingResponse minimalResponse(List<BestBet> bestBets) {
        return new DailyBriefingResponse(
                GENERATED_AT, "Test headline", List.of(),
                bestBets, null, null, false, false, 0, null,
                List.of(), List.of());
    }

}
