package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
                List.of("Northumberland"), "Peak bluebell season.");
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

    // ── helpers ───────────────────────────────────────────────────────────────

    private static DailyBriefingResponse minimalResponse(List<BestBet> bestBets) {
        return new DailyBriefingResponse(
                GENERATED_AT, "Test headline", List.of(),
                bestBets, null, null, false, false, 0, null,
                List.of(), List.of());
    }

}
