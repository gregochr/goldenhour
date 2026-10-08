package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gregochr.goldenhour.entity.TargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Jackson 2 side: {@code daily_briefing_cache} serialises with this graph, and windows (hence
 * {@code tideFacts}) are never persisted, so the cache JSON must be unchanged by their existence.
 */
class WindowTideFactsCacheJsonTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private static BriefingSlot coastalSlot() {
        BriefingSlot.TideInfo tide = new BriefingSlot.TideInfo("HIGH", true, null, null, false,
                false, null, null, null, 25, "HW", true, "HW 06:12", 0.8, "RISING", "4.9 m", null,
                "fits", 0.6);
        BriefingSlot.WeatherConditions weather = new BriefingSlot.WeatherConditions(10,
                java.math.BigDecimal.ZERO, 20000, 60, 12.0, 10.0, 1, java.math.BigDecimal.ONE, 0, 0);
        return new BriefingSlot(7L, "Bamburgh", LocalDateTime.of(2026, 10, 11, 6, 0), Verdict.GO,
                weather, tide, List.of(), null);
    }

    private static DailyBriefingResponse cacheShape() {
        BriefingRegion region = new BriefingRegion("North East", Verdict.GO, "s", List.of(),
                List.of(coastalSlot()), null, null, null, null, null, null,
                DisplayVerdict.WORTH_IT, 1, null, false, null);
        return new DailyBriefingResponse(LocalDateTime.of(2026, 10, 8, 6, 0), "h",
                List.of(new BriefingDay(LocalDate.of(2026, 10, 11), List.of(
                        new BriefingEventSummary(TargetType.SUNRISE, List.of(region), List.of())))),
                List.of(), null, null, false, false, 0, null, List.of(), List.of());
    }

    @Test
    @DisplayName("the cache JSON of a windowless briefing has no tideFacts key and round-trips equal")
    void cacheJsonCarriesNoTideFacts() throws Exception {
        DailyBriefingResponse cached = cacheShape();

        String json = mapper.writeValueAsString(cached);

        assertThat(json).doesNotContain("tideFacts");
        assertThat(json).doesNotContain("\"window\"");
        assertThat(mapper.readValue(json, DailyBriefingResponse.class)).isEqualTo(cached);
        // The slot keeps its own flat tide fields: the cache is where the facts are derived FROM.
        assertThat(mapper.readTree(json).at("/days/0/eventSummaries/0/regions/0/slots/0/tideState")
                .asText()).isEqualTo("HIGH");
    }

    @Test
    @DisplayName("a window with null tideFacts omits the key; a legacy window JSON reads back null")
    void nullTideFactsOmittedAndLegacyReadsNull() throws Exception {
        BriefingWindow none = new BriefingWindow(null, DisplayVerdict.AWAITING, null, null, null,
                List.of(), null, null);

        JsonNode node = mapper.valueToTree(none);

        assertThat(node.has("tideFacts")).isFalse();
        BriefingWindow legacy = mapper.readValue(
                "{\"verdict\":\"AWAITING\",\"badges\":[]}", BriefingWindow.class);
        assertThat(legacy.tideFacts()).isNull();
    }

    @Test
    @DisplayName("tideFacts round-trip when present")
    void tideFactsRoundTrip() throws Exception {
        LocationTideFact fact = LocationTideFact.from(coastalSlot());
        BriefingWindow w = new BriefingWindow(null, DisplayVerdict.AWAITING, null, null, null,
                List.of(), null, null, List.of(fact));

        BriefingWindow back = mapper.readValue(mapper.writeValueAsString(w), BriefingWindow.class);

        assertThat(back.tideFacts()).containsExactly(fact);
    }
}
