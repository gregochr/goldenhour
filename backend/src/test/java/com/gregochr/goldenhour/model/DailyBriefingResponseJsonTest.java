package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideState;
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

    // ── tide-fit fields (T1): level, direction, height, shortfall, fit phrase ─────────────

    @Test
    @DisplayName("populated tide-fit fields land FLAT on the slot JSON, never nested under \"tide\"")
    void serialize_populatedTideFit_fieldsFlatOnSlot() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        BriefingSlot.TideInfo tide = new BriefingSlot.TideInfo(
                "HIGH", true, LocalDateTime.of(2026, 4, 22, 19, 45), new BigDecimal("4.8"),
                false, false, LunarTideType.REGULAR_TIDE, "Waxing gibbous", false,
                25, "HW", true, "HW 20:20 · 25m after sunset",
                0.92, "FALLING", "3.9 m", null,
                "high water, falling · HW 20:20 · 25m after sunset · 3.9 m", null);
        BriefingSlot slot = new BriefingSlot(7L, "Bamburgh",
                LocalDateTime.of(2026, 4, 22, 19, 55), Verdict.GO,
                null, tide, List.of("Clear"), null);

        String json = mapper.writeValueAsString(slot);
        JsonNode node = mapper.readTree(json);

        assertThat(node.has("tide"))
                .as("TideInfo is @JsonUnwrapped — it must never appear as a nested object")
                .isFalse();
        assertThat(node.get("tideLevel").asDouble()).isEqualTo(0.92);
        assertThat(node.get("tideDirection").asText()).isEqualTo("FALLING");
        assertThat(node.get("tideHeight").asText()).isEqualTo("3.9 m");
        assertThat(node.has("tideShortfall")).as("null on an aligned slot — omitted, not written null")
                .isFalse();
        assertThat(node.get("tideFitPhrase").asText())
                .isEqualTo("high water, falling · HW 20:20 · 25m after sunset · 3.9 m");

        BriefingSlot restored = mapper.readValue(json, BriefingSlot.class);
        assertThat(restored.tide().tideLevel()).isEqualTo(0.92);
        assertThat(restored.tide().tideDirection()).isEqualTo("FALLING");
        assertThat(restored.tide().tideHeight()).isEqualTo("3.9 m");
        assertThat(restored.tide().tideShortfall()).isNull();
        assertThat(restored.tide().tideFitPhrase())
                .isEqualTo("high water, falling · HW 20:20 · 25m after sunset · 3.9 m");
    }

    @Test
    @DisplayName("a served shortfall (a miss) round-trips too")
    void serialize_missShortfall_roundTrips() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        BriefingSlot.TideInfo tide = new BriefingSlot.TideInfo(
                "LOW", false, null, null, false, false, LunarTideType.REGULAR_TIDE,
                "Waxing gibbous", false, null, null, null, null,
                0.0, "RISING", "1.0 m", "HIGHER",
                "wants high water · low water, rising at 09:00 · 1.0 m of 4.0 m", null);
        BriefingSlot slot = new BriefingSlot(7L, "Bamburgh",
                LocalDateTime.of(2026, 1, 27, 9, 0), Verdict.STANDDOWN,
                null, tide, List.of(), null);

        String json = mapper.writeValueAsString(slot);
        JsonNode node = mapper.readTree(json);
        BriefingSlot restored = mapper.readValue(json, BriefingSlot.class);

        // tideLevel is 0.0 here — the documented lower bound, and a value production genuinely
        // emits at low water. @JsonInclude(NON_NULL) must write it (a NON_DEFAULT policy would
        // silently drop it, since 0.0 is Double's default), and a field written as JSON `0.0`
        // must round-trip as the boxed Double 0.0, not null.
        assertThat(node.has("tideLevel")).as("0.0 is not absent under NON_NULL").isTrue();
        assertThat(node.get("tideLevel").asDouble()).isEqualTo(0.0);
        assertThat(restored.tide().tideLevel()).isEqualTo(0.0);
        assertThat(restored.tide().tideShortfall()).isEqualTo("HIGHER");
        assertThat(restored.tide().tideFitPhrase())
                .isEqualTo("wants high water · low water, rising at 09:00 · 1.0 m of 4.0 m");
    }

    @Test
    @DisplayName("inland slot: the five tide-fit fields are OMITTED via NON_NULL, not written null")
    void serialize_inlandSlot_tideFitFieldsOmitted() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        BriefingSlot slot = new BriefingSlot("Derwent Valley",
                LocalDateTime.of(2026, 4, 22, 19, 55), Verdict.GO,
                null, BriefingSlot.TideInfo.NONE, List.of(), null);

        JsonNode node = mapper.readTree(mapper.writeValueAsString(slot));

        assertThat(node.has("tideLevel")).isFalse();
        assertThat(node.has("tideDirection")).isFalse();
        assertThat(node.has("tideHeight")).isFalse();
        assertThat(node.has("tideShortfall")).isFalse();
        assertThat(node.has("tideFitPhrase")).isFalse();
    }

    @Test
    @DisplayName("a payload written before the tide-fit fields existed (13 tide fields, the "
            + "map-tab ones present) deserialises the five new ones to null")
    void deserialize_preTideFitPayload_tideFitFieldsNull() throws Exception {
        // daily_briefing_cache holds payloads written before this phase shipped — the 13
        // pre-existing tide fields present (including the #866 map-tab ones), the five new ones
        // absent entirely (not null-valued: ABSENT).
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
                  "moonAtPerigee": false,
                  "nearestSolarOffsetMinutes": 25,
                  "nearestExtremeKind": "HW",
                  "tideOnTheLight": true,
                  "nearestSolarOffsetPhrase": "HW 20:20 · 25m after sunset"
                }
                """;

        BriefingSlot slot = mapper.readValue(legacy, BriefingSlot.class);

        assertThat(slot.tide().tideState()).isEqualTo("HIGH");
        assertThat(slot.tide().tideOnTheLight()).isTrue();
        assertThat(slot.tide().tideLevel()).isNull();
        assertThat(slot.tide().tideDirection()).isNull();
        assertThat(slot.tide().tideHeight()).isNull();
        assertThat(slot.tide().tideShortfall()).isNull();
        assertThat(slot.tide().tideFitPhrase()).isNull();
    }

    // ── tideAlignmentQuality (C0) ───────────────────────────────────────────────

    @Test
    @DisplayName("a populated tideAlignmentQuality round-trips, flat on the slot JSON")
    void roundTrip_tideAlignmentQuality_survives() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        BriefingSlot.TideInfo tide = new BriefingSlot.TideInfo(
                "HIGH", true, LocalDateTime.of(2026, 4, 22, 19, 45), new BigDecimal("4.8"),
                false, false, LunarTideType.REGULAR_TIDE, "Waxing gibbous", false,
                25, "HW", true, "HW 20:20 · 25m after sunset",
                0.92, "FALLING", "3.9 m", null,
                "high water, falling · HW 20:20 · 25m after sunset · 3.9 m", 0.75);
        BriefingSlot slot = new BriefingSlot(7L, "Bamburgh",
                LocalDateTime.of(2026, 4, 22, 19, 55), Verdict.GO,
                null, tide, List.of("Clear"), null);

        String json = mapper.writeValueAsString(slot);
        JsonNode node = mapper.readTree(json);

        assertThat(node.has("tide"))
                .as("TideInfo is @JsonUnwrapped — it must never appear as a nested object")
                .isFalse();
        assertThat(node.get("tideAlignmentQuality").asDouble()).isEqualTo(0.75);

        BriefingSlot restored = mapper.readValue(json, BriefingSlot.class);
        assertThat(restored.tide().tideAlignmentQuality()).isEqualTo(0.75);
    }

    @Test
    @DisplayName("a null tideAlignmentQuality (not aligned, or inland) is OMITTED via NON_NULL, "
            + "not written null")
    void serialize_nullTideAlignmentQuality_omitted() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        BriefingSlot.TideInfo tide = new BriefingSlot.TideInfo(
                "LOW", false, null, null, false, false, LunarTideType.REGULAR_TIDE,
                "Waxing gibbous", false, null, null, null, null,
                0.0, "RISING", "1.0 m", "HIGHER",
                "wants high water · low water, rising at 09:00 · 1.0 m of 4.0 m", null);
        BriefingSlot slot = new BriefingSlot(7L, "Bamburgh",
                LocalDateTime.of(2026, 1, 27, 9, 0), Verdict.STANDDOWN,
                null, tide, List.of(), null);

        JsonNode node = mapper.readTree(mapper.writeValueAsString(slot));

        assertThat(node.has("tideAlignmentQuality")).isFalse();

        BriefingSlot inland = new BriefingSlot("Derwent Valley",
                LocalDateTime.of(2026, 4, 22, 19, 55), Verdict.GO,
                null, BriefingSlot.TideInfo.NONE, List.of(), null);
        assertThat(mapper.readTree(mapper.writeValueAsString(inland))
                .has("tideAlignmentQuality")).isFalse();
    }

    @Test
    @DisplayName("a payload written before tideAlignmentQuality existed (the 18 T1-and-earlier "
            + "tide fields, no key at all) deserialises the new field to null")
    void deserialize_preC0Payload_tideAlignmentQualityNull() throws Exception {
        // daily_briefing_cache holds payloads written before this phase shipped — every field
        // through tideFitPhrase present, tideAlignmentQuality absent entirely (not null-valued:
        // ABSENT), even on an aligned slot where a live build would compute a real figure.
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
                  "moonAtPerigee": false,
                  "nearestSolarOffsetMinutes": 25,
                  "nearestExtremeKind": "HW",
                  "tideOnTheLight": true,
                  "nearestSolarOffsetPhrase": "HW 20:20 · 25m after sunset",
                  "tideLevel": 0.92,
                  "tideDirection": "FALLING",
                  "tideHeight": "3.9 m",
                  "tideFitPhrase": "high water, falling · HW 20:20 · 25m after sunset · 3.9 m"
                }
                """;

        BriefingSlot slot = mapper.readValue(legacy, BriefingSlot.class);

        assertThat(slot.tide().tideAligned()).isTrue();
        assertThat(slot.tide().tideLevel()).isEqualTo(0.92);
        assertThat(slot.tide().tideAlignmentQuality())
                .as("absent in the cached payload, not recomputed on read").isNull();
    }

    @Test
    @DisplayName("a payload from before EVEN the map-tab fields existed (9 tide fields) still "
            + "deserialises — every field this phase added reads null")
    void deserialize_legacyNineFieldPayload_everyNewerFieldNull() throws Exception {
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
        assertThat(slot.tide().tideLevel()).isNull();
        assertThat(slot.tide().tideDirection()).isNull();
        assertThat(slot.tide().tideHeight()).isNull();
        assertThat(slot.tide().tideShortfall()).isNull();
        assertThat(slot.tide().tideFitPhrase()).isNull();
    }

    // ── map tab tide strip's window facts (T2) ──────────────────────────────
    //
    // BriefingWindowTide's four new fields (sunrisePosition, sunsetPosition, extremes,
    // heightAtWindow) ride the same days -> eventSummaries -> window -> tide path the fields
    // above are nested three levels below the flat BriefingSlot payloads this file otherwise
    // tests. All four are NON_NULL. Unlike BriefingSlot.tide above, BriefingWindow.tide is never
    // itself part of daily_briefing_cache — BriefingHierarchyBuilder always attaches window=null
    // on the build path persistBriefing serialises, and BriefingWindowTide is derived only at
    // serve time. So there is no "cached before this field existed" row to worry about here; the
    // wire contract these tests pin is the JSON shape any caller reading this response gets, and
    // the legacy twelve-field constructor the many existing call sites (mostly tests) still use.

    @Test
    @DisplayName("the strip's window facts round-trip through the full response, nested under days")
    void roundTrip_windowTideStripFields_contentPreserved() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        BriefingWindowTide tide = new BriefingWindowTide("Whitby", TideState.MID,
                BriefingWindowTide.Direction.FALLING, "HW", "19:28", "1h43 before sunset",
                "4.9 m", "1.2 m above an average tide", "0.3 m · smooth",
                List.of(0.0, 0.5, 1.0), 0.88, 0.42,
                0.34, 0.69,
                List.of(new BriefingWindowTide.Extreme("LW", 0.1, "02:20"),
                        new BriefingWindowTide.Extreme("HW", 0.6, "14:24")),
                "1.7 m");
        DailyBriefingResponse original = withTide(tide);

        String json = mapper.writeValueAsString(original);
        DailyBriefingResponse restored = mapper.readValue(json, DailyBriefingResponse.class);

        BriefingWindowTide restoredTide =
                restored.days().get(0).eventSummaries().get(0).window().tide();
        assertThat(restoredTide.sunrisePosition()).isEqualTo(0.34);
        assertThat(restoredTide.sunsetPosition()).isEqualTo(0.69);
        assertThat(restoredTide.heightAtWindow()).isEqualTo("1.7 m");
        assertThat(restoredTide.extremes()).hasSize(2);
        assertThat(restoredTide.extremes().get(0).kind()).isEqualTo("LW");
        assertThat(restoredTide.extremes().get(0).position()).isEqualTo(0.1);
        assertThat(restoredTide.extremes().get(0).time()).isEqualTo("02:20");
        assertThat(restoredTide.extremes().get(1).kind()).isEqualTo("HW");
        // The pre-existing fields ride along untouched by the new ones.
        assertThat(restoredTide.locationName()).isEqualTo("Whitby");
        assertThat(restoredTide.range()).isEqualTo("4.9 m");
    }

    @Test
    @DisplayName("a tide built through the legacy twelve-field constructor round-trips to nulls")
    void deserialize_legacyWindowTidePayload_newFieldsAreNull() throws Exception {
        // The shape any existing call site's BriefingWindowTide has: the twelve original tide
        // fields present, the four new ones absent entirely (not null-valued — ABSENT) because the
        // legacy constructor defaults them and NON_NULL omits them from the JSON.
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        BriefingWindowTide legacyTide = new BriefingWindowTide("Whitby", TideState.MID,
                BriefingWindowTide.Direction.FALLING, "HW", "19:28", "1h43 before sunset",
                "4.9 m", "1.2 m above an average tide", "0.3 m · smooth",
                List.of(0.0, 0.5, 1.0), 0.88, 0.42);
        String json = mapper.writeValueAsString(withTide(legacyTide));
        JsonNode tideNode = navigateToTide(mapper.readTree(json));

        assertThat(tideNode.has("sunrisePosition")).isFalse();
        assertThat(tideNode.has("sunsetPosition")).isFalse();
        assertThat(tideNode.has("extremes")).isFalse();
        assertThat(tideNode.has("heightAtWindow")).isFalse();

        DailyBriefingResponse restored = mapper.readValue(json, DailyBriefingResponse.class);
        BriefingWindowTide restoredTide =
                restored.days().get(0).eventSummaries().get(0).window().tide();
        assertThat(restoredTide.sunrisePosition()).isNull();
        assertThat(restoredTide.sunsetPosition()).isNull();
        assertThat(restoredTide.extremes()).isNull();
        assertThat(restoredTide.heightAtWindow()).isNull();
        // The pre-existing fields are unaffected by the omission.
        assertThat(restoredTide.locationName()).isEqualTo("Whitby");
    }

    private static DailyBriefingResponse withTide(BriefingWindowTide tide) {
        BriefingWindow window = new BriefingWindow(null, DisplayVerdict.AWAITING, null, null,
                null, List.of(), null, tide);
        BriefingEventSummary summary =
                new BriefingEventSummary(TargetType.SUNSET, List.of(), List.of(), null, window);
        return new DailyBriefingResponse(GENERATED_AT, "Test",
                List.of(new BriefingDay(LocalDate.of(2026, 4, 22), List.of(summary))),
                List.of(), null, null, false, false, 0, null, List.of(), List.of());
    }

    private static JsonNode navigateToTide(JsonNode root) {
        return root.get("days").get(0).get("eventSummaries").get(0).get("window").get("tide");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static DailyBriefingResponse minimalResponse(List<BestBet> bestBets) {
        return new DailyBriefingResponse(
                GENERATED_AT, "Test headline", List.of(),
                bestBets, null, null, false, false, 0, null,
                List.of(), List.of());
    }

    // ── evaluationGate ────────────────────────────────────────────────────────

    @Test
    @DisplayName("evaluationGate round-trips, and is OMITTED (not written null) on an eligible slot")
    void evaluationGate_roundTripsAndIsOmittedWhenNull() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        BriefingSlot eligible = new BriefingSlot("Seaham Chemical Beach",
                LocalDateTime.of(2026, 9, 19, 5, 44), Verdict.STANDDOWN,
                null, BriefingSlot.TideInfo.NONE, List.of(), "Tide mismatch");
        BriefingSlot gated = eligible.withEvaluationGate(
                "Tide not right at sunrise · needs low water, mid tide instead");

        JsonNode eligibleNode = mapper.readTree(mapper.writeValueAsString(eligible));
        String gatedJson = mapper.writeValueAsString(gated);

        assertThat(eligibleNode.has("evaluationGate")).isFalse();
        assertThat(mapper.readTree(gatedJson).get("evaluationGate").asText())
                .isEqualTo("Tide not right at sunrise · needs low water, mid tide instead");
        assertThat(mapper.readValue(gatedJson, BriefingSlot.class).evaluationGate())
                .isEqualTo("Tide not right at sunrise · needs low water, mid tide instead");
    }

    @Test
    @DisplayName("a daily_briefing_cache payload written before the field existed reads as null, not eligible")
    void evaluationGate_legacyPayloadReadsNull() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        BriefingSlot gated = new BriefingSlot("Seaham Chemical Beach",
                LocalDateTime.of(2026, 9, 19, 5, 44), Verdict.STANDDOWN,
                null, BriefingSlot.TideInfo.NONE, List.of(), "Tide mismatch")
                .withEvaluationGate("Tide not right at sunrise · mid tide");
        // The shape a pre-field cache row has: the same slot with the key absent altogether.
        var node = (com.fasterxml.jackson.databind.node.ObjectNode)
                mapper.readTree(mapper.writeValueAsString(gated));
        node.remove("evaluationGate");

        BriefingSlot restored = mapper.readValue(node.toString(), BriefingSlot.class);

        assertThat(restored.evaluationGate()).isNull();
        assertThat(restored.standdownReason()).isEqualTo("Tide mismatch");
    }
}
