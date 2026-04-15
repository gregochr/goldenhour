package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Jackson deserialisation tests for {@link HotTopic}.
 *
 * <p>Verifies that the {@code @JsonCreator} constructor correctly handles both
 * current (8-field) JSON and legacy (7-field, pre-description) JSON that was
 * persisted to the database before the {@code description} field was introduced.
 */
class HotTopicJsonTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    // ── Deserialise 8-field JSON ─────────────────────────────────────────────

    @Test
    @DisplayName("deserialise 8-field JSON — all fields populated from named properties")
    void deserialize_eightFieldJson_allFieldsPopulated() throws Exception {
        String json = """
                {
                  "type": "BLUEBELL",
                  "label": "Bluebell conditions",
                  "detail": "Misty and still",
                  "date": "2026-04-25",
                  "priority": 1,
                  "filterAction": "BLUEBELL",
                  "regions": ["Northumberland", "The Lake District"],
                  "description": "Peak bluebell season — misty mornings are best."
                }
                """;

        HotTopic topic = mapper.readValue(json, HotTopic.class);

        assertThat(topic.type()).isEqualTo("BLUEBELL");
        assertThat(topic.label()).isEqualTo("Bluebell conditions");
        assertThat(topic.detail()).isEqualTo("Misty and still");
        assertThat(topic.date()).isEqualTo(LocalDate.of(2026, 4, 25));
        assertThat(topic.priority()).isEqualTo(1);
        assertThat(topic.filterAction()).isEqualTo("BLUEBELL");
        assertThat(topic.regions()).containsExactly("Northumberland", "The Lake District");
        assertThat(topic.description()).isEqualTo("Peak bluebell season — misty mornings are best.");
    }

    // ── Deserialise legacy 7-field JSON (no description) ────────────────────

    @Test
    @DisplayName("deserialise legacy 7-field JSON — description defaults to null")
    void deserialize_sevenFieldJson_descriptionIsNull() throws Exception {
        // Simulates a HotTopic object persisted before the description field was added.
        String json = """
                {
                  "type": "SPRING_TIDE",
                  "label": "Spring tide",
                  "detail": "Big tidal range today",
                  "date": "2026-04-26",
                  "priority": 2,
                  "filterAction": null,
                  "regions": ["Northumberland"]
                }
                """;

        HotTopic topic = mapper.readValue(json, HotTopic.class);

        assertThat(topic.type()).isEqualTo("SPRING_TIDE");
        assertThat(topic.label()).isEqualTo("Spring tide");
        assertThat(topic.detail()).isEqualTo("Big tidal range today");
        assertThat(topic.date()).isEqualTo(LocalDate.of(2026, 4, 26));
        assertThat(topic.priority()).isEqualTo(2);
        assertThat(topic.filterAction()).isNull();
        assertThat(topic.regions()).containsExactly("Northumberland");
        assertThat(topic.description()).isNull();
    }

    @Test
    @DisplayName("deserialise legacy 7-field JSON — non-description fields are exact, not swapped")
    void deserialize_sevenFieldJson_noFieldMixup() throws Exception {
        // Uses distinct values for every field so a wrong @JsonProperty mapping would fail.
        String json = """
                {
                  "type": "AURORA",
                  "label": "Aurora possible",
                  "detail": "Kp 5 forecast tonight",
                  "date": "2026-05-01",
                  "priority": 3,
                  "filterAction": "AURORA_FILTER",
                  "regions": ["North York Moors", "Northumberland"]
                }
                """;

        HotTopic topic = mapper.readValue(json, HotTopic.class);

        assertThat(topic.type()).isEqualTo("AURORA");
        assertThat(topic.label()).isEqualTo("Aurora possible");
        assertThat(topic.detail()).isEqualTo("Kp 5 forecast tonight");
        assertThat(topic.date()).isEqualTo(LocalDate.of(2026, 5, 1));
        assertThat(topic.priority()).isEqualTo(3);
        assertThat(topic.filterAction()).isEqualTo("AURORA_FILTER");
        assertThat(topic.regions()).containsExactly("North York Moors", "Northumberland");
        assertThat(topic.description()).isNull();
    }

    @Test
    @DisplayName("deserialise JSON with explicit null description — description is null")
    void deserialize_explicitNullDescription_descriptionIsNull() throws Exception {
        String json = """
                {
                  "type": "DUST",
                  "label": "Elevated dust",
                  "detail": "Saharan dust at sunset",
                  "date": "2026-04-27",
                  "priority": 3,
                  "filterAction": null,
                  "regions": [],
                  "description": null
                }
                """;

        HotTopic topic = mapper.readValue(json, HotTopic.class);

        assertThat(topic.description()).isNull();
        assertThat(topic.type()).isEqualTo("DUST");
        assertThat(topic.priority()).isEqualTo(3);
    }

    // ── Serialise → deserialise round-trip ───────────────────────────────────

    @Test
    @DisplayName("round-trip with populated description — description preserved")
    void roundTrip_withDescription_preserved() throws Exception {
        HotTopic original = new HotTopic(
                "INVERSION", "Cloud inversion", "Strong inversion forecast",
                LocalDate.of(2026, 4, 28), 2, null,
                List.of("The Lake District"),
                "A temperature inversion traps cloud below elevated viewpoints.");

        HotTopic restored = mapper.readValue(mapper.writeValueAsString(original), HotTopic.class);

        assertThat(restored.type()).isEqualTo("INVERSION");
        assertThat(restored.label()).isEqualTo("Cloud inversion");
        assertThat(restored.detail()).isEqualTo("Strong inversion forecast");
        assertThat(restored.date()).isEqualTo(LocalDate.of(2026, 4, 28));
        assertThat(restored.priority()).isEqualTo(2);
        assertThat(restored.filterAction()).isNull();
        assertThat(restored.regions()).containsExactly("The Lake District");
        assertThat(restored.description())
                .isEqualTo("A temperature inversion traps cloud below elevated viewpoints.");
    }

    @Test
    @DisplayName("round-trip with null description — null preserved, no other field swapped")
    void roundTrip_nullDescription_nullPreserved() throws Exception {
        HotTopic original = new HotTopic(
                "SUPERMOON", "Supermoon", "Full moon at perigee",
                LocalDate.of(2026, 4, 29), 3, "MOON_FILTER",
                List.of("Northumberland", "The North Yorkshire Coast"), null);

        HotTopic restored = mapper.readValue(mapper.writeValueAsString(original), HotTopic.class);

        assertThat(restored.description()).isNull();
        assertThat(restored.type()).isEqualTo("SUPERMOON");
        assertThat(restored.filterAction()).isEqualTo("MOON_FILTER");
        assertThat(restored.priority()).isEqualTo(3);
        assertThat(restored.regions()).containsExactly("Northumberland", "The North Yorkshire Coast");
    }

    // ── compareTo ordering ──────────────────────────────────────────────────

    @Test
    @DisplayName("compareTo — lower priority sorts before higher priority")
    void compareTo_lowerPriorityFirst() {
        HotTopic low = new HotTopic("A", "a", "d", LocalDate.of(2026, 5, 1), 1, null, List.of(), null);
        HotTopic high = new HotTopic("B", "b", "d", LocalDate.of(2026, 5, 1), 3, null, List.of(), null);

        assertThat(low.compareTo(high)).isNegative();
        assertThat(high.compareTo(low)).isPositive();
    }

    @Test
    @DisplayName("compareTo — same priority, earlier date sorts first")
    void compareTo_samePriority_earlierDateFirst() {
        LocalDate earlier = LocalDate.of(2026, 4, 20);
        LocalDate later = LocalDate.of(2026, 4, 22);
        HotTopic a = new HotTopic("A", "a", "d", earlier, 2, null, List.of(), null);
        HotTopic b = new HotTopic("B", "b", "d", later, 2, null, List.of(), null);

        assertThat(a.compareTo(b)).isNegative();
        assertThat(b.compareTo(a)).isPositive();
    }

    @Test
    @DisplayName("compareTo — same priority and date returns zero")
    void compareTo_samePriorityAndDate_zero() {
        LocalDate date = LocalDate.of(2026, 4, 21);
        HotTopic a = new HotTopic("X", "x", "d1", date, 2, null, List.of(), null);
        HotTopic b = new HotTopic("Y", "y", "d2", date, 2, "filter", List.of("R"), "desc");

        assertThat(a.compareTo(b)).isZero();
    }

    @Test
    @DisplayName("compareTo — priority dominates date (lower priority + later date wins)")
    void compareTo_priorityDominatesDate() {
        HotTopic lowPriorityLateDate = new HotTopic(
                "A", "a", "d", LocalDate.of(2026, 5, 10), 1, null, List.of(), null);
        HotTopic highPriorityEarlyDate = new HotTopic(
                "B", "b", "d", LocalDate.of(2026, 4, 1), 3, null, List.of(), null);

        assertThat(lowPriorityLateDate.compareTo(highPriorityEarlyDate)).isNegative();
    }
}
