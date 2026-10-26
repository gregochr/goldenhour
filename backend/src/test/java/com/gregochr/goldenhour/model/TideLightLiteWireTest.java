package com.gregochr.goldenhour.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gregochr.goldenhour.entity.CoastalStructure;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.service.BriefingRoleShaper;
import com.gregochr.goldenhour.service.HotTopicSimulationService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wire and persistence behaviour of the Lite tide-at-the-light shaping
 * (docs/engineering/tide-light-pro-plan.md §3 B1): the in-memory {@code liteDetail} carrier is never
 * serialised by either mapper, an old cached briefing still loads, and a reloaded or simulated tide
 * topic is shaped to NO detail for Lite (fail closed).
 */
class TideLightLiteWireTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 28);
    private static final LocalDateTime AT = DAY.atTime(6, 0);

    /** AppConfig.objectMapper(): the Jackson 2 mapper that persists and reloads the briefing cache. */
    private final ObjectMapper jackson2 = new ObjectMapper().registerModule(new JavaTimeModule());
    /** The Jackson 3 mapper family the HTTP response chain uses. */
    private final JsonMapper jackson3 = JsonMapper.builder().build();
    private final BriefingRoleShaper shaper = new BriefingRoleShaper();

    private static HotTopic king() {
        return new HotTopic("KING_TIDE", "King tide", "aligned with sunrise at 47 of 61 coastal locations",
                DAY, 1, null, List.of("North East"), "d",
                new ExpandedHotTopicDetail(List.of(), null,
                        new ExpandedHotTopicDetail.TideMetrics("King tide", "Full Moon", 47, 3)),
                "SUNRISE", "06:41", null, null, null, null, null, null, null, null, null, null)
                .withLiteDetail("LITE-ONLY-SENTENCE");
    }

    private static DailyBriefingResponse briefingOf(HotTopic topic) {
        BriefingWindow window = new BriefingWindow(AT, DisplayVerdict.WORTH_IT, 4, null, null,
                List.of(new BriefingWindow.Badge("KING_TIDE", "King tide", topic.detail(), List.of(),
                        "06:41", 2, null, null, null)), null, null, null);
        BriefingEventSummary summary = new BriefingEventSummary(TargetType.SUNRISE, List.of(), List.of(), AT,
                window);
        return new DailyBriefingResponse(AT, "h", List.of(new BriefingDay(DAY, List.of(summary))),
                List.of(), null, null, false, false, 0, null, List.of(topic), List.of());
    }

    @Test
    @DisplayName("liteDetail is never serialised by the Jackson 2 mapper nor the Jackson 3 HTTP mapper")
    void liteDetailNeverSerialises() throws Exception {
        assertThat(jackson2.writeValueAsString(king())).doesNotContain("liteDetail")
                .doesNotContain("LITE-ONLY-SENTENCE");
        assertThat(jackson3.writeValueAsString(king())).doesNotContain("liteDetail")
                .doesNotContain("LITE-ONLY-SENTENCE");
        assertThat(jackson2.writeValueAsString(briefingOf(king()))).doesNotContain("liteDetail");
        assertThat(jackson3.writeValueAsString(briefingOf(king()))).doesNotContain("liteDetail");
    }

    @Test
    @DisplayName("Pro JSON: the aligned counts are still written as numbers, and the topic keys are unchanged")
    void proJsonKeepsTheAlignedCounts() {
        String json = jackson3.writeValueAsString(king());

        assertThat(json).contains(
                "\"tideMetrics\":{\"tidalClassification\":\"King tide\",\"lunarPhase\":\"Full Moon\","
                        + "\"sunriseAlignedCount\":47,\"sunsetAlignedCount\":3}");
        assertThat(json).contains("\"detail\":\"aligned with sunrise at 47 of 61 coastal locations\"")
                .contains("\"eventType\":\"SUNRISE\"").contains("\"eventTime\":\"06:41\"");
    }

    @Test
    @DisplayName("Lite JSON: no aligned counts, no run row, no event, and the light-free detail")
    void liteJsonCarriesNoLight() {
        String json = jackson3.writeValueAsString(king().forLite());

        assertThat(json).doesNotContain("AlignedCount").doesNotContain("tideRun")
                .doesNotContain("eventType").doesNotContain("eventTime")
                .contains("\"detail\":\"LITE-ONLY-SENTENCE\"")
                .contains("\"tideMetrics\":{\"tidalClassification\":\"King tide\",\"lunarPhase\":\"Full Moon\"}");
    }

    @Test
    @DisplayName("Old cached JSON (written with int counts and no liteDetail) still loads, with null liteDetail")
    void oldCachedJsonStillLoads() throws Exception {
        String old = """
                {"type":"KING_TIDE","label":"King tide","detail":"aligned with sunrise at 47 of 61 coastal locations",
                 "date":"2026-10-28","priority":1,"filterAction":null,"regions":["North East"],"description":"d",
                 "expandedDetail":{"tideMetrics":{"tidalClassification":"King tide","lunarPhase":"Full Moon",
                 "sunriseAlignedCount":47,"sunsetAlignedCount":3}}}
                """;

        HotTopic loaded = jackson2.readValue(old, HotTopic.class);

        assertThat(loaded.liteDetail()).isNull();
        assertThat(loaded.expandedDetail().tideMetrics().sunriseAlignedCount()).isEqualTo(47);
        assertThat(loaded.forLite().detail()).isNull();
        assertThat(loaded.forLite().expandedDetail().tideMetrics().sunriseAlignedCount()).isNull();
    }

    @Test
    @DisplayName("A briefing reloaded from JSON (the persisted-cache path) is shaped for Lite with NO tide "
            + "detail on the topic or its badge, and no run row, event or counts")
    void jsonReloadedBriefingShapesToNoDetail() throws Exception {
        String persisted = jackson2.writeValueAsString(briefingOf(king()));

        DailyBriefingResponse reloaded = jackson2.readValue(persisted, DailyBriefingResponse.class);
        DailyBriefingResponse lite = shaper.forLite(reloaded);

        assertThat(reloaded.hotTopics().get(0).liteDetail()).isNull();
        HotTopic topic = lite.hotTopics().get(0);
        assertThat(topic.detail()).isNull();
        assertThat(topic.tideRun()).isNull();
        assertThat(topic.eventType()).isNull();
        assertThat(topic.eventTime()).isNull();
        assertThat(topic.expandedDetail().tideMetrics().sunriseAlignedCount()).isNull();
        BriefingWindow.Badge badge = lite.days().get(0).eventSummaries().get(0).window().badges().get(0);
        assertThat(badge.detail()).isNull();
        assertThat(badge.eventTime()).isNull();
        assertThat(jackson3.writeValueAsString(lite)).doesNotContain("47 of 61");
    }

    @Test
    @DisplayName("A simulated king and spring tide topic (no liteDetail) are shaped to NO detail for Lite")
    void simulatedTideTopicShapesToNoDetail() {
        HotTopicSimulationService sim = new HotTopicSimulationService();
        sim.setEnabled(true);
        sim.setTypeActive("KING_TIDE", true);
        sim.setTypeActive("SPRING_TIDE", true);
        List<HotTopic> topics = sim.getSimulatedTopics(DAY, DAY.plusDays(3));
        assertThat(topics).extracting(HotTopic::type).contains("KING_TIDE", "SPRING_TIDE");

        for (HotTopic t : topics) {
            assertThat(t.liteDetail()).isNull();
            HotTopic lite = t.forLite();
            assertThat(lite.detail()).as(t.type()).isNull();
            assertThat(lite.expandedDetail().tideMetrics().sunriseAlignedCount()).isNull();
            assertThat(lite.expandedDetail().tideMetrics().sunsetAlignedCount()).isNull();
        }
    }

    @Test
    @DisplayName("Big seas Lite JSON: no alignedEvent, gapMinutes or gapSide, aligned false, daylight HW kept")
    void bigSeasLiteWire() {
        BigSeasStructure roker = new BigSeasStructure(243L, "Roker Pier", CoastalStructure.PIER, 4.2, 80, 9.5, 90,
                AT, true);
        BigSeasDay day = new BigSeasDay(DAY, AT.minusHours(1), AT.plusHours(1), AT,
                BigSeasDay.AlignedEvent.SUNRISE, 12L, BigSeasDay.GapSide.AFTER, 4.2, 80, 9.5, List.of(roker), 0,
                true, AT.minusHours(2), AT.plusHours(9));

        String pro = jackson3.writeValueAsString(day);
        String lite = jackson3.writeValueAsString(day.forLite());

        assertThat(pro).contains("\"alignedEvent\":\"SUNRISE\"").contains("\"gapMinutes\":12")
                .contains("\"gapSide\":\"AFTER\"").contains("\"aligned\":true");
        assertThat(lite).doesNotContain("alignedEvent").doesNotContain("gapMinutes").doesNotContain("gapSide")
                .contains("\"aligned\":false").contains("\"daylightHighWater\":true")
                .contains("\"daylightStart\"").contains("\"daylightEnd\"").contains("\"hwTime\"");
    }
}
