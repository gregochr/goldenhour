package com.gregochr.goldenhour.model.comingup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.config.AppConfig;
import com.gregochr.goldenhour.model.AlmanacEvent;
import com.gregochr.goldenhour.model.AlmanacKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wire tests for the in-memory role carriers of tide-light-pro-plan.md §3 B2: {@code kind} and
 * {@code liteFactsLabel} never serialise, and {@code lightRowsWithheld} is absent unless true. Run
 * through both mappers: the Jackson 3 ({@code tools.jackson}) HTTP mapper and AppConfig's Jackson 2
 * mapper, which the persisted caches use.
 */
class ComingUpRoleWireTest {

    private final JsonMapper http = JsonMapper.builder().build();
    private final ObjectMapper appConfig = new AppConfig().objectMapper();

    private static ComingUpEntry entry(boolean withheld) {
        AlmanacEvent event = new AlmanacEvent(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 30),
                AlmanacKind.ALMANAC, "spring-tide", "Spring tide run", "detail", Map.of(), List.of());
        ComingUpEntry base = ComingUpEntry.from(event, LocalDate.of(2026, 7, 28));
        return base
                .withFacts(List.of(
                        new ComingUpFact(List.of(new ComingUpFact.Segment("sunrise row", "base")),
                                ComingUpFact.Kind.EVENT_WATER),
                        new ComingUpFact(List.of(new ComingUpFact.Segment("range row", "base")))))
                .withCoincidence(List.of(new ComingUpCoincidenceLine("coastal", "Spring tide run",
                        "Sun 25 · HW 15:48", "spring-tide", "Sun 25")))
                .withLightRowsWithheld(withheld);
    }

    @Test
    @DisplayName("HTTP mapper: kind and liteFactsLabel never serialise; the labels and rows still do")
    void carriersNeverSerialiseOnTheHttpMapper() throws Exception {
        JsonNode json = http.readTree(http.writeValueAsString(entry(false)));

        assertThat(json.path("facts").get(0).has("kind")).isFalse();
        assertThat(json.path("facts").get(0).has("segments")).isTrue();
        assertThat(json.path("coincidence").get(0).has("liteFactsLabel")).isFalse();
        assertThat(json.path("coincidence").get(0).path("factsLabel").asString())
                .isEqualTo("Sun 25 · HW 15:48");
        assertThat(http.writeValueAsString(entry(false))).doesNotContain("EVENT_WATER", "liteFactsLabel");
    }

    @Test
    @DisplayName("AppConfig's Jackson 2 mapper: kind and liteFactsLabel never serialise either")
    void carriersNeverSerialiseOnTheJackson2Mapper() throws Exception {
        String json = appConfig.writeValueAsString(entry(false));

        assertThat(json).doesNotContain("EVENT_WATER", "liteFactsLabel", "\"kind\":\"EVENT");
        assertThat(appConfig.readTree(json).path("coincidence").get(0).has("liteFactsLabel")).isFalse();
        assertThat(appConfig.readTree(json).path("facts").get(0).has("kind")).isFalse();
    }

    @Test
    @DisplayName("lightRowsWithheld is absent when false and present as true when set, on both mappers")
    void lightRowsWithheldIsAbsentWhenFalse() throws Exception {
        assertThat(http.readTree(http.writeValueAsString(entry(false))).has("lightRowsWithheld")).isFalse();
        assertThat(appConfig.readTree(appConfig.writeValueAsString(entry(false))).has("lightRowsWithheld"))
                .isFalse();

        assertThat(http.readTree(http.writeValueAsString(entry(true))).path("lightRowsWithheld").asBoolean())
                .isTrue();
        assertThat(appConfig.readTree(appConfig.writeValueAsString(entry(true)))
                .path("lightRowsWithheld").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("the entry's own Pro JSON keeps the pre-B2 keys (the entry-level key set differs by "
            + "lightRowsWithheld alone)")
    void proKeySetIsUnchangedExceptTheMarker() throws Exception {
        List<String> without = http.readTree(http.writeValueAsString(entry(false)))
                .propertyNames().stream().toList();
        List<String> with = http.readTree(http.writeValueAsString(entry(true)))
                .propertyNames().stream().toList();

        assertThat(with).containsAll(without).hasSize(without.size() + 1).contains("lightRowsWithheld");
        assertThat(without).doesNotContain("lightRowsWithheld");
    }
}
