package com.gregochr.goldenhour.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gregochr.goldenhour.entity.TargetType;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Serialization contract for the {@code window} projected onto {@link BriefingEventSummary}.
 *
 * <p>Two properties matter and neither is visible from the projector's own tests. The window is
 * derived per request and must be <b>omitted entirely</b> when absent, so the persisted
 * {@code daily_briefing_cache} JSON is unchanged by this feature and no migration is needed. And a
 * row cached before the field existed must deserialise fail-soft rather than throwing, because
 * every already-stored briefing is exactly that on the first serve after deploy.
 *
 * <p>Uses the {@code JavaTimeModule}-registered mapper the cache round-trip uses, since the window
 * carries a {@code LocalDateTime}.
 */
class BriefingEventSummaryWindowSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private static BriefingWindow window() {
        return new BriefingWindow(
                LocalDateTime.of(2026, 5, 23, 21, 11),
                DisplayVerdict.WORTH_IT,
                4,
                Confidence.HIGH,
                new BriefingWindow.Pick(BriefingWindow.PickKind.BEST, "North East",
                        "Breaking clear", "Detail.", 4.0, "Bamburgh", 42L),
                List.of(new BriefingWindow.Badge("NLC", "Clearest in 11 nights", null, "22:04", 14)),
                14);
    }

    private static BriefingEventSummary summary(BriefingWindow w) {
        return new BriefingEventSummary(TargetType.SUNSET, List.of(), List.of(), w);
    }

    @Test
    void windowRoundTrips() throws Exception {
        String json = mapper.writeValueAsString(summary(window()));

        BriefingEventSummary back = mapper.readValue(json, BriefingEventSummary.class);

        assertThat(back.window()).isEqualTo(window());
    }

    @Test
    @DisplayName("an absent window is omitted from the JSON, so the cached payload is unchanged")
    void nullWindowIsOmitted() {
        // This is what makes the feature migration-free: every build-path summary has a null
        // window, so the JSON written to daily_briefing_cache has no new key at all.
        assertThat(mapper.valueToTree(summary(null)).has("window")).isFalse();
    }

    @Test
    @DisplayName("a payload cached before the field existed deserialises to a null window")
    void legacyPayloadDeserialisesToNull() throws Exception {
        ObjectNode node = (ObjectNode) mapper.readTree(mapper.writeValueAsString(summary(window())));
        node.remove("window");

        BriefingEventSummary back =
                mapper.readValue(mapper.writeValueAsString(node), BriefingEventSummary.class);

        assertThat(back.window()).isNull();
        assertThat(back.targetType()).isEqualTo(TargetType.SUNSET);
    }

    @Test
    @DisplayName("a pick serialises with its kind; a window without one omits the field")
    void pickCarriesItsKindAndIsOmittedWhenAbsent() {
        // Absence is the normal case — exactly two windows in a forecast carry a pick — so the
        // field must be omitted rather than written as null, which invites a consumer to render it.
        assertThat(mapper.valueToTree(new BriefingWindow(null, DisplayVerdict.AWAITING, null, null,
                null, List.of(), null)).has("pick")).isFalse();
        var node = mapper.valueToTree(window());

        assertThat(node.has("pick")).isTrue();
        assertThat(node.get("pick").get("kind").asText()).isEqualTo("BEST");
    }

    @Test
    void badgesAreAlwaysPresentEvenWhenEmpty() throws Exception {
        // Empty is a normal state and must not be confused with "we had no topic data at all",
        // so the list is always serialised rather than dropped.
        BriefingWindow empty = new BriefingWindow(null, DisplayVerdict.AWAITING, null, null,
                null, List.of(), null);

        assertThat(mapper.readTree(mapper.writeValueAsString(empty)).get("badges")).isEmpty();
    }

    @Test
    void nullBadgesNormaliseToEmptyRatherThanThrowing() {
        assertThat(new BriefingWindow(null, DisplayVerdict.AWAITING, null, null, null,
                null, null).badges()).isEmpty();
    }
}
