package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.gregochr.goldenhour.entity.CoastalStructure;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BigSeasDay;
import com.gregochr.goldenhour.model.BigSeasStructure;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.ExpandedHotTopicDetail;
import com.gregochr.goldenhour.model.GlanceLine;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.HotTopicFact;
import com.gregochr.goldenhour.model.TideRunDay;
import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The hot-topic, window-badge and big-seas half of {@link BriefingRoleShaper#forLite}
 * (docs/engineering/tide-light-pro-plan.md §3 B1): the tide at the light is Pro and Admin only, and
 * a missing in-memory {@code liteDetail} carrier means Lite gets nothing, never the Pro sentence.
 */
class TideLightLiteShapingTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 28);
    private static final LocalDateTime AT = DAY.atTime(6, 0);
    private static final String PRO_SENTENCE = "tide aligned with sunrise at 47 of 61 coastal locations";
    private static final String LITE_SENTENCE = "61 coastal locations";

    private final BriefingRoleShaper shaper = new BriefingRoleShaper();

    // ── fixtures ──────────────────────────────────────────────────────────────────────────────

    private static TideRunDay runDay() {
        return new TideRunDay("KING RUN", 2, 4, "WED 28", "St Mary's", "4.2 m", "+0.4", "5.8 m",
                "+0.4 m over spring", "highest recorded here", "06:41", "16:55", "0.3 m · smooth",
                List.of(new TideRunDay.Extreme("H", "07:12")), "HW 07:12 · 31m after sunrise",
                true, true, "sunrise", "HW 07:12 · 31m after sunrise",
                new TideRunDay.RosterAlignment(47, 3, 61), true, "high water floods the foreshore");
    }

    private static ExpandedHotTopicDetail expanded() {
        return new ExpandedHotTopicDetail(List.of(), null,
                new ExpandedHotTopicDetail.TideMetrics("King tide", "Full Moon", 47, 3));
    }

    /** A king tide topic with every component set, as the strategy plus the serve-time enrichers emit it. */
    private static HotTopic fullKing() {
        return new HotTopic("KING_TIDE", "King tide", PRO_SENTENCE, DAY, 1, null, List.of("North East"),
                "A king tide", expanded(), "SUNRISE", "06:41", List.of("Bamburgh"), null, null,
                List.of(new HotTopicFact(null, "5.8 m", null, false, true)), "look low",
                runDay(), null, "rarity", "safety", new GlanceLine("v", "s", null, false))
                .withLiteDetail(LITE_SENTENCE);
    }

    private static HotTopic fullSpring() {
        return new HotTopic("SPRING_TIDE", "Spring tide", PRO_SENTENCE, DAY, 2, null, List.of("North East"),
                "A spring tide", expanded(), "SUNSET", "16:55", List.of("Bamburgh"), null, null,
                null, null, runDay(), null, null, null, null).withLiteDetail(LITE_SENTENCE);
    }

    private static HotTopic aurora() {
        return new HotTopic("AURORA", "Aurora", "Kp 6", DAY, 3, null, List.of("North East"), "d", null,
                "NIGHT", "22:00", List.of("Bamburgh"), null, null, null, null, null, null, null, null, null);
    }

    private static BriefingWindow.Badge badge(String type, String detail, String eventTime) {
        return new BriefingWindow.Badge(type, type, detail, List.of(), eventTime, 2, "note", "rarity", "safety");
    }

    private static DailyBriefingResponse briefing(List<HotTopic> topics, List<BriefingWindow.Badge> badges,
            List<BigSeasDay> bigSeas) {
        BriefingWindow window = new BriefingWindow(AT, DisplayVerdict.WORTH_IT, 4, null, null, badges, null,
                null, null);
        BriefingEventSummary summary = new BriefingEventSummary(TargetType.SUNRISE, List.of(), List.of(),
                AT, window);
        return new DailyBriefingResponse(AT, "h", List.of(new BriefingDay(DAY, List.of(summary))),
                List.of(), null, null, false, false, 0, null, topics, List.of()).withBigSeas(bigSeas);
    }

    private static BriefingWindow windowOf(DailyBriefingResponse r) {
        return r.days().get(0).eventSummaries().get(0).window();
    }

    private static BigSeasStructure structure(boolean aligned) {
        return new BigSeasStructure(243L, "Roker Pier", CoastalStructure.PIER, 4.2, 80, 9.5, 90, AT, aligned);
    }

    private static BigSeasDay bigSeasDay() {
        return new BigSeasDay(DAY, AT.minusHours(1), AT.plusHours(1), AT, BigSeasDay.AlignedEvent.SUNRISE, 12L,
                BigSeasDay.GapSide.AFTER, 4.2, 80, 9.5, List.of(structure(true)), 1, true,
                AT.minusHours(2), AT.plusHours(9));
    }

    private static Set<String> names(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName)
                .collect(Collectors.toSet());
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> all = new HashSet<>(a);
        all.addAll(b);
        return all;
    }

    // ── reflection classification: a new component must be classified here ────────────────────

    private static final Set<String> TOPIC_STRIPPED = Set.of("detail", "tideRun", "eventType", "eventTime");
    private static final Set<String> TOPIC_NARROWED = Set.of("expandedDetail");
    private static final Set<String> TOPIC_KEPT = Set.of("type", "label", "date", "priority", "filterAction",
            "regions", "description", "locationNames", "eveningWindow", "morningWindow", "facts", "note",
            "surgeRun", "rarityNote", "safetyNote", "glance", "liteDetail");

    @Test
    @DisplayName("Every HotTopic component is classified stripped, narrowed or kept")
    void everyHotTopicComponentIsClassified() {
        assertThat(names(HotTopic.class))
                .as("a new component must be classified here and handled in HotTopic.forLite()")
                .containsExactlyInAnyOrderElementsOf(union(union(TOPIC_STRIPPED, TOPIC_NARROWED), TOPIC_KEPT));
    }

    @Test
    @DisplayName("HotTopic.forLite on a king and a spring topic nulls exactly the stripped components")
    void hotTopicForLiteNullsExactlyTheStrippedComponents() throws Exception {
        for (HotTopic full : List.of(fullKing(), fullSpring())) {
            HotTopic lite = full.forLite();
            for (RecordComponent c : HotTopic.class.getRecordComponents()) {
                Object value = c.getAccessor().invoke(lite);
                Object original = c.getAccessor().invoke(full);
                if (c.getName().equals("detail")) {
                    assertThat(value).as("detail is the lite sentence").isEqualTo(LITE_SENTENCE);
                } else if (TOPIC_STRIPPED.contains(c.getName())) {
                    assertThat(value).as(c.getName()).isNull();
                    assertThat(original).as(c.getName() + " in the fixture").isNotNull();
                } else if (TOPIC_NARROWED.contains(c.getName())) {
                    assertThat(value).as("narrowed by ExpandedHotTopicDetail.forLite").isEqualTo(
                            full.expandedDetail().forLite());
                } else if (full.type().equals("SPRING_TIDE") && original == null) {
                    assertThat(value).as(c.getName()).isNull();
                } else {
                    assertThat(value).as(c.getName()).isEqualTo(original);
                }
            }
        }
    }

    @Test
    @DisplayName("HotTopic.forLite leaves every other topic type as the same instance")
    void nonTideTopicIsUntouched() {
        HotTopic aurora = aurora();

        assertThat(aurora.forLite()).isSameAs(aurora);
    }

    @Test
    @DisplayName("HotTopic.forLite with no liteDetail serves NO detail: fail closed, never the Pro sentence")
    void missingLiteDetailFailsClosed() {
        HotTopic reloaded = fullKing().withLiteDetail(null);

        assertThat(reloaded.forLite().detail()).isNull();
        assertThat(reloaded.forLite().tideRun()).isNull();
        assertThat(reloaded.detail()).as("the input is not mutated").isEqualTo(PRO_SENTENCE);
    }

    @Test
    @DisplayName("Every wither forwards liteDetail")
    void everyWitherForwardsLiteDetail() {
        HotTopic t = fullKing();

        assertThat(t.withEvent("SUNSET", "17:00").liteDetail()).isEqualTo(LITE_SENTENCE);
        assertThat(t.withLocations(List.of("x")).liteDetail()).isEqualTo(LITE_SENTENCE);
        assertThat(t.withNlcWindows(null, null).liteDetail()).isEqualTo(LITE_SENTENCE);
        assertThat(t.withScience(null, null).liteDetail()).isEqualTo(LITE_SENTENCE);
        assertThat(t.withExpandedDetail(null).liteDetail()).isEqualTo(LITE_SENTENCE);
        assertThat(t.withTideRun(null).liteDetail()).isEqualTo(LITE_SENTENCE);
        assertThat(t.withSurgeRun(null).liteDetail()).isEqualTo(LITE_SENTENCE);
        assertThat(t.withRarity("r").liteDetail()).isEqualTo(LITE_SENTENCE);
        assertThat(t.withSafety("s").liteDetail()).isEqualTo(LITE_SENTENCE);
        assertThat(t.withGlance(null).liteDetail()).isEqualTo(LITE_SENTENCE);
        assertThat(t.withDetail("d").liteDetail()).isEqualTo(LITE_SENTENCE);
    }

    @Test
    @DisplayName("Every TideRunDay component goes with the tideRun: the run row is stripped whole")
    void everyTideRunDayComponentIsClassifiedAsStrippedWhole() {
        Set<String> strippedWhole = Set.of("runLabel", "dayNumber", "dayCount", "dayLabel", "locationName",
                "range", "rangeAnomaly", "highWater", "highWaterAnomaly", "highWaterRank", "sunrise", "sunset",
                "seas", "tides", "verdict", "aligned", "bestAligned", "alignedEvent", "alignmentPhrase",
                "roster", "peak", "phrase");

        assertThat(names(TideRunDay.class))
                .as("a new TideRunDay component is stripped with the whole run row; classify it here")
                .containsExactlyInAnyOrderElementsOf(strippedWhole);
        assertThat(fullKing().forLite().tideRun()).isNull();
    }

    @Test
    @DisplayName("Every TideMetrics component is classified, and forLite nulls exactly the aligned counts")
    void tideMetricsClassification() throws Exception {
        Set<String> stripped = Set.of("sunriseAlignedCount", "sunsetAlignedCount");
        Set<String> kept = Set.of("tidalClassification", "lunarPhase");
        assertThat(names(ExpandedHotTopicDetail.TideMetrics.class))
                .containsExactlyInAnyOrderElementsOf(union(stripped, kept));

        ExpandedHotTopicDetail.TideMetrics full = new ExpandedHotTopicDetail.TideMetrics("King tide", "Full Moon",
                47, 3);
        ExpandedHotTopicDetail.TideMetrics lite = full.forLite();
        for (RecordComponent c : ExpandedHotTopicDetail.TideMetrics.class.getRecordComponents()) {
            Object value = c.getAccessor().invoke(lite);
            if (stripped.contains(c.getName())) {
                assertThat(value).as(c.getName()).isNull();
            } else {
                assertThat(value).as(c.getName()).isEqualTo(c.getAccessor().invoke(full));
            }
        }
    }

    private static final Set<String> BADGE_STRIPPED = Set.of("detail", "eventTime");
    private static final Set<String> BADGE_KEPT = Set.of("type", "label", "facts", "rarityRank", "note",
            "rarityNote", "safetyNote");

    @Test
    @DisplayName("Every BriefingWindow.Badge component is classified; a tide badge loses detail and eventTime only")
    void badgeClassificationAndShaping() throws Exception {
        assertThat(names(BriefingWindow.Badge.class))
                .containsExactlyInAnyOrderElementsOf(union(BADGE_STRIPPED, BADGE_KEPT));

        BriefingWindow.Badge full = new BriefingWindow.Badge("KING_TIDE", "King tide", PRO_SENTENCE,
                List.of(new HotTopicFact(null, "5.8 m", null, false, true)), "06:41", 2, "note", "rarity", "safety");
        DailyBriefingResponse lite = shaper.forLite(briefing(List.of(fullKing()), List.of(full), null));

        BriefingWindow.Badge shaped = windowOf(lite).badges().get(0);
        for (RecordComponent c : BriefingWindow.Badge.class.getRecordComponents()) {
            Object value = c.getAccessor().invoke(shaped);
            if (c.getName().equals("detail")) {
                assertThat(value).as("a badge never disagrees with its topic").isEqualTo(LITE_SENTENCE);
            } else if (BADGE_STRIPPED.contains(c.getName())) {
                assertThat(value).as(c.getName()).isNull();
            } else {
                assertThat(value).as(c.getName()).isEqualTo(c.getAccessor().invoke(full));
            }
        }
    }

    private static final Set<String> BIG_SEAS_DAY_STRIPPED = Set.of("alignedEvent", "gapMinutes", "gapSide");
    private static final Set<String> BIG_SEAS_DAY_KEPT = Set.of("date", "windowStart", "windowEnd", "hwTime",
            "waveHeightM", "waveDirectionDeg", "wavePeriodS", "otherWindows", "daylightHighWater",
            "daylightStart", "daylightEnd");

    @Test
    @DisplayName("Every BigSeasDay component is classified; forLite nulls the light facts and keeps daylight HW")
    void bigSeasDayClassificationAndShaping() throws Exception {
        assertThat(names(BigSeasDay.class)).containsExactlyInAnyOrderElementsOf(
                union(union(BIG_SEAS_DAY_STRIPPED, BIG_SEAS_DAY_KEPT), Set.of("structures")));

        BigSeasDay full = bigSeasDay();
        BigSeasDay lite = full.forLite();
        for (RecordComponent c : BigSeasDay.class.getRecordComponents()) {
            Object value = c.getAccessor().invoke(lite);
            if (BIG_SEAS_DAY_STRIPPED.contains(c.getName())) {
                assertThat(value).as(c.getName()).isNull();
                assertThat(c.getAccessor().invoke(full)).as(c.getName() + " in the fixture").isNotNull();
            } else if (!c.getName().equals("structures")) {
                assertThat(value).as(c.getName()).isEqualTo(c.getAccessor().invoke(full));
            }
        }
        assertThat(lite.daylightHighWater()).isTrue();
        assertThat(lite.structures()).hasSize(1);
        assertThat(lite.structures().get(0).aligned()).isFalse();
    }

    @Test
    @DisplayName("Every BigSeasStructure component is classified; forLite sets aligned false and keeps the rest")
    void bigSeasStructureClassificationAndShaping() throws Exception {
        Set<String> kept = Set.of("locationId", "label", "structure", "waveHeightM", "waveDirectionDeg",
                "wavePeriodS", "facesDeg", "hwTime");
        assertThat(names(BigSeasStructure.class)).containsExactlyInAnyOrderElementsOf(union(Set.of("aligned"), kept));

        BigSeasStructure full = structure(true);
        BigSeasStructure lite = full.forLite();
        assertThat(lite.aligned()).isFalse();
        for (RecordComponent c : BigSeasStructure.class.getRecordComponents()) {
            if (kept.contains(c.getName())) {
                assertThat(c.getAccessor().invoke(lite)).as(c.getName()).isEqualTo(c.getAccessor().invoke(full));
            }
        }
    }

    // ── the shaper ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("LITE: hot topics and big seas are rebuilt; the topic list and every size fact stay")
    void shaperRebuildsTopicsAndBigSeas() {
        DailyBriefingResponse in = briefing(List.of(fullKing(), aurora()), List.of(), List.of(bigSeasDay()));

        DailyBriefingResponse lite = shaper.forLite(in);

        assertThat(lite.hotTopics()).hasSize(2);
        HotTopic king = lite.hotTopics().get(0);
        assertThat(king.type()).isEqualTo("KING_TIDE");
        assertThat(king.detail()).isEqualTo(LITE_SENTENCE);
        assertThat(king.tideRun()).isNull();
        assertThat(king.eventType()).isNull();
        assertThat(king.eventTime()).isNull();
        assertThat(king.expandedDetail().tideMetrics().sunriseAlignedCount()).isNull();
        assertThat(king.expandedDetail().tideMetrics().sunsetAlignedCount()).isNull();
        assertThat(king.expandedDetail().tideMetrics().tidalClassification()).isEqualTo("King tide");
        assertThat(king.facts()).hasSize(1);
        assertThat(king.locationNames()).containsExactly("Bamburgh");
        assertThat(lite.hotTopics().get(1)).as("an aurora topic is untouched").isEqualTo(aurora());
        BigSeasDay day = lite.bigSeas().get(0);
        assertThat(day.alignedEvent()).isNull();
        assertThat(day.gapMinutes()).isNull();
        assertThat(day.gapSide()).isNull();
        assertThat(day.structures().get(0).aligned()).isFalse();
        assertThat(day.daylightHighWater()).isTrue();
        assertThat(day.hwTime()).isEqualTo(AT);
    }

    @Test
    @DisplayName("LITE: null bigSeas stays null, and an empty topic list stays empty")
    void shaperToleratesNoBigSeasAndNoTopics() {
        DailyBriefingResponse lite = shaper.forLite(briefing(List.of(), List.of(), null));

        assertThat(lite.bigSeas()).isNull();
        assertThat(lite.hotTopics()).isEmpty();
    }

    @Test
    @DisplayName("LITE: the input briefing keeps its Pro topics, run rows and big-seas gap")
    void shaperDoesNotMutateTheInput() {
        HotTopic king = fullKing();
        DailyBriefingResponse in = briefing(List.of(king), List.of(badge("KING_TIDE", PRO_SENTENCE, "06:41")),
                List.of(bigSeasDay()));

        shaper.forLite(in);

        assertThat(in.hotTopics().get(0)).isSameAs(king);
        assertThat(in.hotTopics().get(0).tideRun()).isNotNull();
        assertThat(in.bigSeas().get(0).alignedEvent()).isEqualTo(BigSeasDay.AlignedEvent.SUNRISE);
        assertThat(windowOf(in).badges().get(0).detail()).isEqualTo(PRO_SENTENCE);
    }

    @Test
    @DisplayName("LITE: a spring badge takes the spring topic's sentence and a king badge the king's")
    void badgeAndTopicNeverDisagree() {
        DailyBriefingResponse in = briefing(
                List.of(fullKing().withLiteDetail("K"), fullSpring().withLiteDetail("S")),
                List.of(badge("SPRING_TIDE", PRO_SENTENCE, "16:55"), badge("KING_TIDE", PRO_SENTENCE, "06:41")),
                null);

        List<BriefingWindow.Badge> badges = windowOf(shaper.forLite(in)).badges();

        assertThat(badges.get(0).detail()).isEqualTo("S");
        assertThat(badges.get(1).detail()).isEqualTo("K");
        assertThat(badges.get(0).eventTime()).isNull();
        assertThat(badges.get(1).eventTime()).isNull();
    }

    @Test
    @DisplayName("LITE: a tide badge with no topic on its date, or whose topic has no liteDetail, has no detail")
    void badgeFailsClosedWithoutALiteDetail() {
        HotTopic otherDay = new HotTopic("KING_TIDE", "King tide", PRO_SENTENCE, DAY.plusDays(1), 1, null,
                List.of(), "d", null, null, null, null, null, null, null, null, null, null, null, null, null)
                .withLiteDetail(LITE_SENTENCE);
        HotTopic noCarrier = fullSpring().withLiteDetail(null);
        DailyBriefingResponse in = briefing(List.of(otherDay, noCarrier),
                List.of(badge("KING_TIDE", PRO_SENTENCE, "06:41"), badge("SPRING_TIDE", PRO_SENTENCE, "16:55")),
                null);

        List<BriefingWindow.Badge> badges = windowOf(shaper.forLite(in)).badges();

        assertThat(badges.get(0).detail()).isNull();
        assertThat(badges.get(1).detail()).isNull();
    }

    @Test
    @DisplayName("LITE: a badge of any other type is carried through verbatim")
    void nonTideBadgeIsUntouched() {
        BriefingWindow.Badge aurora = badge("AURORA", "Kp 6", "22:00");

        BriefingWindow.Badge shaped = windowOf(shaper.forLite(
                briefing(List.of(aurora()), List.of(aurora), null))).badges().get(0);

        assertThat(shaped).isEqualTo(aurora);
    }

    @Test
    @DisplayName("PRO and ADMIN are not shaped: the shaper copies, and forLite is the only edit")
    void proTopicsKeepTheirAlignment() {
        HotTopic king = fullKing();

        assertThat(king.detail()).isEqualTo(PRO_SENTENCE);
        assertThat(king.tideRun()).isNotNull();
        assertThat(king.expandedDetail().tideMetrics().sunriseAlignedCount()).isEqualTo(47);
    }
}
