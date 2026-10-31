package com.gregochr.goldenhour.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gregochr.goldenhour.config.AppConfig;
import com.gregochr.goldenhour.entity.TargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deploy-hazard pin for the briefing fields the dead-code sweep (phase D) removed.
 *
 * <p>{@code BriefingService.loadPersistedBriefing} reads {@code daily_briefing_cache.payload} with
 * the Jackson 2 {@link AppConfig#objectMapper()}, which fails on unknown properties by default. The
 * payload written by the release before the removal still carries every removed key, so the first
 * start after deploy would throw, the failure would be swallowed, and the Plan tab would stay empty
 * until the next pipeline cycle. Each record whose own JSON object carried a removed key is
 * annotated {@code @JsonIgnoreProperties(ignoreUnknown = true)}; this test proves it by loading a
 * payload that carries ALL of them at their real nesting.
 */
class PersistedBriefingRemovedKeysTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 11);

    @Test
    @DisplayName("a payload carrying every removed key, at its real nesting, loads through "
            + "AppConfig.objectMapper()")
    void persistedPayloadWithAllRemovedKeys_loads() throws Exception {
        ObjectMapper mapper = new AppConfig().objectMapper();
        JsonNode current = mapper.readTree(mapper.writeValueAsString(briefing()));
        ObjectNode root = (ObjectNode) current;

        // DailyBriefingResponse
        root.put("partialFailure", true);
        root.put("failedLocationCount", 3);

        ObjectNode summary = (ObjectNode) root.get("days").get(0).get("eventSummaries").get(0);
        // BriefingWindow
        ((ObjectNode) summary.get("window")).put("topRarityRank", 2);
        // BriefingRegion
        ObjectNode region = (ObjectNode) summary.get("regions").get(0);
        region.put("lightlyEvaluated", true);
        // BriefingSlot
        ((ObjectNode) region.get("slots").get(0)).put("standdownReason", "Tide mismatch");
        // HotTopic.tideRun (TideRunDay)
        ObjectNode tideRun = (ObjectNode) root.get("hotTopics").get(0).get("tideRun");
        tideRun.put("highWaterAnomaly", "+0.4 m over spring");
        tideRun.put("highWaterRank", "0.4 m off the record");
        tideRun.put("bestAligned", true);
        tideRun.put("alignmentPhrase", "HW 07:12 · 31m after sunrise");

        DailyBriefingResponse loaded = mapper.readValue(root.toString(), DailyBriefingResponse.class);

        assertThat(loaded.headline()).isEqualTo("h");
        BriefingEventSummary loadedSummary = loaded.days().get(0).eventSummaries().get(0);
        assertThat(loadedSummary.window().verdict()).isEqualTo(DisplayVerdict.WORTH_IT);
        assertThat(loadedSummary.regions().get(0).regionName()).isEqualTo("North East");
        assertThat(loadedSummary.regions().get(0).slots().get(0).locationName()).isEqualTo("Bamburgh");
        TideRunDay loadedRun = loaded.hotTopics().get(0).tideRun();
        assertThat(loadedRun).isNotNull();
        assertThat(loadedRun.aligned()).isTrue();
        assertThat(loadedRun.verdict()).isEqualTo("HW 07:12 · 31m after sunrise");
    }

    @Test
    @DisplayName("the ignore-unknown tolerance holds at a nested record the removed keys never touched, "
            + "so a later removal anywhere in the tree is safe too")
    void unknownKeyOnAnyTreeRecord_isIgnored() throws Exception {
        ObjectMapper mapper = new AppConfig().objectMapper();
        ObjectNode root = (ObjectNode) mapper.readTree(mapper.writeValueAsString(briefing()));
        ObjectNode day = (ObjectNode) root.get("days").get(0);
        day.put("someFutureRemovedKey", 1);
        ((ObjectNode) day.get("eventSummaries").get(0)).put("someFutureRemovedKey", 1);
        ((ObjectNode) root.get("hotTopics").get(0)).put("someFutureRemovedKey", 1);
        ((ObjectNode) root.get("hotTopics").get(0).get("tideRun").get("roster")).put("someFutureRemovedKey", 1);

        DailyBriefingResponse loaded = mapper.readValue(root.toString(), DailyBriefingResponse.class);

        assertThat(loaded.days()).hasSize(1);
        assertThat(loaded.hotTopics()).hasSize(1);
    }

    /** A briefing with a window, a region, a slot and a hot topic carrying a tide-run day. */
    private static DailyBriefingResponse briefing() {
        TideRunDay run = new TideRunDay("KING RUN", 1, 3, "SUN 11", "Whitby", "4.2 m",
                null, null, "06:41", "17:55", null, List.of(new TideRunDay.Extreme("H", "07:12")),
                "HW 07:12 · 31m after sunrise", true, "sunrise", new TideRunDay.RosterAlignment(47, 3, 61),
                false, null);
        HotTopic king = new HotTopic(
                "KING_TIDE", "King tide", "tide aligned with sunrise", DAY, 1, null, List.of("North East"), "d",
                new ExpandedHotTopicDetail(List.of(), null,
                        new ExpandedHotTopicDetail.TideMetrics("King tide", "Full Moon", 47, 3)),
                "SUNRISE", "06:41", null, null, null, null, null, run, null, null, null, null);
        BriefingWindow window = new BriefingWindow(null, DisplayVerdict.WORTH_IT, 4, null, null,
                List.of(), null, null);
        BriefingSlot slot = new BriefingSlot(7L, "Bamburgh", LocalDateTime.of(2026, 10, 11, 6, 0),
                Verdict.STANDDOWN, null, null, List.of(), null, null, null, null, null,
                DisplayVerdict.STAND_DOWN, null, false, null);
        BriefingRegion region = new BriefingRegion("North East", Verdict.GO, "s", List.of(),
                List.of(slot), null, null, null, null, null, null, DisplayVerdict.AWAITING, 0,
                null, null);
        BriefingEventSummary summary = new BriefingEventSummary(TargetType.SUNRISE,
                List.of(region), List.of(), null, window);
        return new DailyBriefingResponse(LocalDateTime.of(2026, 10, 8, 6, 0), "h",
                List.of(new BriefingDay(DAY, List.of(summary))),
                List.of(), null, null, false, null, List.of(king), List.of());
    }
}
