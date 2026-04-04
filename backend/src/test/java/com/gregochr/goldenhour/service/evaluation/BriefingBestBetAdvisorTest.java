package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Usage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.model.BestBet;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.Confidence;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BriefingBestBetAdvisor}.
 */
@ExtendWith(MockitoExtension.class)
class BriefingBestBetAdvisorTest {

    @Mock private AnthropicApiClient anthropicApiClient;
    @Mock private JobRunService jobRunService;
    @Mock private ModelSelectionService modelSelectionService;
    @Mock private AuroraStateCache auroraStateCache;

    private BriefingBestBetAdvisor advisor;

    @BeforeEach
    void setUp() {
        lenient().when(modelSelectionService.getActiveModel(RunType.BRIEFING_BEST_BET))
                .thenReturn(EvaluationModel.OPUS);
        advisor = new BriefingBestBetAdvisor(
                anthropicApiClient, new ObjectMapper().findAndRegisterModules(),
                jobRunService, modelSelectionService, auroraStateCache);
    }

    // ── parseBestBets ──

    @Nested
    @DisplayName("parseBestBets")
    class ParseTests {

        @Test
        @DisplayName("Two picks returned from valid JSON")
        void twoPicks() {
            String raw = """
                    {
                      "picks": [
                        {
                          "rank": 1,
                          "headline": "King tide sunset at Northumberland",
                          "detail": "Rare king tide with clear skies.",
                          "event": "tomorrow_sunset",
                          "region": "Northumberland",
                          "confidence": "high"
                        },
                        {
                          "rank": 2,
                          "headline": "Lake District also good",
                          "detail": "Clear but no tide magic.",
                          "event": "tomorrow_sunset",
                          "region": "Lake District",
                          "confidence": "high"
                        }
                      ]
                    }
                    """;
            List<BestBet> picks = advisor.parseBestBets(raw);
            assertThat(picks).hasSize(2);
            assertThat(picks.get(0).rank()).isEqualTo(1);
            assertThat(picks.get(0).headline()).isEqualTo("King tide sunset at Northumberland");
            assertThat(picks.get(0).event()).isEqualTo("tomorrow_sunset");
            assertThat(picks.get(0).region()).isEqualTo("Northumberland");
            assertThat(picks.get(0).confidence()).isEqualTo(Confidence.HIGH);
            assertThat(picks.get(1).rank()).isEqualTo(2);
        }

        @Test
        @DisplayName("Stay-home pick has null event and region")
        void stayHomePick() {
            String raw = """
                    {
                      "picks": [
                        {
                          "rank": 1,
                          "headline": "Stay in — nothing worth cold fingers today",
                          "detail": "Heavy cloud and rain everywhere.",
                          "event": null,
                          "region": null,
                          "confidence": "high"
                        }
                      ]
                    }
                    """;
            List<BestBet> picks = advisor.parseBestBets(raw);
            assertThat(picks).hasSize(1);
            assertThat(picks.get(0).event()).isNull();
            assertThat(picks.get(0).region()).isNull();
        }

        @Test
        @DisplayName("Code fences stripped before parsing")
        void codeFencesStripped() {
            String raw = "```json\n{\"picks\":[{\"rank\":1,\"headline\":\"Go shoot\","
                    + "\"detail\":\"Clear.\",\"event\":\"today_sunset\","
                    + "\"region\":\"Northumberland\",\"confidence\":\"high\"}]}\n```";
            List<BestBet> picks = advisor.parseBestBets(raw);
            assertThat(picks).hasSize(1);
            assertThat(picks.get(0).event()).isEqualTo("today_sunset");
        }

        @Test
        @DisplayName("Empty response returns empty list")
        void emptyResponse() {
            assertThat(advisor.parseBestBets("")).isEmpty();
            assertThat(advisor.parseBestBets(null)).isEmpty();
            assertThat(advisor.parseBestBets("   ")).isEmpty();
        }

        @Test
        @DisplayName("Invalid JSON returns empty list")
        void invalidJson() {
            assertThat(advisor.parseBestBets("{not valid json}")).isEmpty();
        }

        @Test
        @DisplayName("Missing picks array returns empty list")
        void missingPicksArray() {
            assertThat(advisor.parseBestBets("{\"other\": \"field\"}")).isEmpty();
        }

        @Test
        @DisplayName("Single pick aurora event")
        void auroraEvent() {
            String raw = """
                    {
                      "picks": [
                        {
                          "rank": 1,
                          "headline": "Aurora alert over Northumberland tonight",
                          "detail": "Kp 5.2 and 3 clear locations.",
                          "event": "2026-04-01_aurora",
                          "region": "Northumberland",
                          "confidence": "high"
                        }
                      ]
                    }
                    """;
            List<BestBet> picks = advisor.parseBestBets(raw);
            assertThat(picks).hasSize(1);
            assertThat(picks.get(0).event()).isEqualTo("2026-04-01_aurora");
        }
    }

    // ── buildRollupJson ──

    @Nested
    @DisplayName("buildRollupJson")
    class RollupJsonTests {

        @Test
        @DisplayName("Future event is included with date-based ID and dayName")
        void futureEventIncluded() throws Exception {
            when(auroraStateCache.isActive()).thenReturn(false);
            LocalDate tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1);
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            BriefingDay day = new BriefingDay(tomorrow, List.of(
                    new BriefingEventSummary(TargetType.SUNSET, List.of(
                            region("Northumberland", Verdict.GO, 3, 0, 0)
                    ), List.of())
            ));

            BriefingBestBetAdvisor.RollupResult result = advisor.buildRollupJson(List.of(day), now);
            String expectedEventId = tomorrow.toString() + "_sunset";
            assertThat(result.json()).contains(expectedEventId);
            assertThat(result.json()).contains("Northumberland");
            assertThat(result.json()).contains("dayName");
            assertThat(result.validEvents()).contains(expectedEventId);
            assertThat(result.validRegions()).contains("Northumberland");
        }

        @Test
        @DisplayName("Past today event is skipped and not in validEvents")
        void pastTodayEventSkipped() throws Exception {
            when(auroraStateCache.isActive()).thenReturn(false);
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            LocalDateTime pastTime = now.minusHours(2);
            BriefingDay day = new BriefingDay(today, List.of(
                    new BriefingEventSummary(TargetType.SUNRISE, List.of(
                            regionWithTime("Northumberland", Verdict.GO, 2, 0, 0, pastTime)
                    ), List.of())
            ));

            BriefingBestBetAdvisor.RollupResult result = advisor.buildRollupJson(List.of(day), now);
            String skippedId = today.toString() + "_sunrise";
            assertThat(result.json()).doesNotContain(skippedId);
            assertThat(result.validEvents()).doesNotContain(skippedId);
        }

        @Test
        @DisplayName("Aurora event included when cache is active and added to validEvents")
        void auroraIncludedWhenActive() throws Exception {
            when(auroraStateCache.isActive()).thenReturn(true);
            when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.MODERATE);
            when(auroraStateCache.getLastTriggerKp()).thenReturn(5.2);
            when(auroraStateCache.getDarkSkyLocationCount()).thenReturn(45);
            when(auroraStateCache.getClearLocationCount()).thenReturn(12);
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            BriefingBestBetAdvisor.RollupResult result = advisor.buildRollupJson(List.of(), now);
            String expectedAuroraId = LocalDate.now(ZoneId.of("Europe/London")) + "_aurora";
            assertThat(result.json()).contains(expectedAuroraId);
            assertThat(result.json()).contains("MODERATE");
            assertThat(result.json()).contains("5.2");
            assertThat(result.json()).contains("\"darkSkyLocationCount\":45");
            assertThat(result.json()).contains("\"clearLocationCount\":12");
            assertThat(result.validEvents()).contains(expectedAuroraId);
        }

        @Test
        @DisplayName("Aurora excluded when cache is inactive")
        void auroraExcludedWhenInactive() throws Exception {
            when(auroraStateCache.isActive()).thenReturn(false);
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            BriefingBestBetAdvisor.RollupResult result = advisor.buildRollupJson(List.of(), now);
            assertThat(result.json()).doesNotContain("_aurora");
            assertThat(result.validEvents().stream().noneMatch(e -> e.endsWith("_aurora"))).isTrue();
        }

        @Test
        @DisplayName("King tide locations listed in rollup")
        void kingTideInRollup() throws Exception {
            when(auroraStateCache.isActive()).thenReturn(false);
            LocalDate tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1);
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

            BriefingSlot kingSlot = new BriefingSlot(
                    "Bamburgh", null, Verdict.GO,
                    new BriefingSlot.WeatherConditions(20, BigDecimal.ZERO, 15000, 70,
                            8.0, null, null, BigDecimal.ONE),
                    new BriefingSlot.TideInfo("HIGH", true, null,
                            new BigDecimal("1.9"), true, false, null, null, null),
                    List.of("King tide"));
            BriefingRegion region = new BriefingRegion(
                    "Northumberland", Verdict.GO, "Clear", List.of("King tide at Bamburgh"),
                    List.of(kingSlot), 7.0, 5.0, 1.5, 1);
            BriefingDay day = new BriefingDay(tomorrow, List.of(
                    new BriefingEventSummary(TargetType.SUNSET, List.of(region), List.of())
            ));

            BriefingBestBetAdvisor.RollupResult result = advisor.buildRollupJson(List.of(day), now);
            assertThat(result.json()).contains("hasKingTide");
            assertThat(result.json()).contains("Bamburgh");
            assertThat(result.json()).contains("kingTideLocations");
        }

        @Test
        @DisplayName("Lunar king tide count included in rollup when slot has KING_TIDE")
        void lunarKingTideCountInRollup() throws Exception {
            when(auroraStateCache.isActive()).thenReturn(false);
            LocalDate tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1);
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

            BriefingSlot lunarKingSlot = new BriefingSlot(
                    "Bamburgh", null, Verdict.GO,
                    new BriefingSlot.WeatherConditions(20, BigDecimal.ZERO, 15000, 70,
                            8.0, null, null, BigDecimal.ONE),
                    new BriefingSlot.TideInfo("HIGH", true, null,
                            new BigDecimal("6.2"), true, false,
                            LunarTideType.KING_TIDE, "New Moon", true),
                    List.of("King tide"));
            BriefingRegion region = new BriefingRegion(
                    "Northumberland", Verdict.GO, "Clear", List.of(),
                    List.of(lunarKingSlot), 7.0, 5.0, 1.5, 1);
            BriefingDay day = new BriefingDay(tomorrow, List.of(
                    new BriefingEventSummary(TargetType.SUNSET, List.of(region), List.of())
            ));

            BriefingBestBetAdvisor.RollupResult result = advisor.buildRollupJson(
                    List.of(day), now);
            assertThat(result.json()).contains("\"lunarKingTideCount\":1");
            assertThat(result.json()).contains("\"lunarSpringTideCount\":0");
            assertThat(result.json()).contains("\"hasSurgeBoost\":true");
        }

        @Test
        @DisplayName("Lunar spring tide count included in rollup when slot has SPRING_TIDE")
        void lunarSpringTideCountInRollup() throws Exception {
            when(auroraStateCache.isActive()).thenReturn(false);
            LocalDate tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1);
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

            BriefingSlot springSlot = new BriefingSlot(
                    "Dunstanburgh", null, Verdict.GO,
                    new BriefingSlot.WeatherConditions(15, BigDecimal.ZERO, 20000, 65,
                            9.0, null, null, BigDecimal.ONE),
                    new BriefingSlot.TideInfo("HIGH", true, null,
                            new BigDecimal("5.1"), false, true,
                            LunarTideType.SPRING_TIDE, "Full Moon", false),
                    List.of());
            BriefingRegion region = new BriefingRegion(
                    "Northumberland", Verdict.GO, "Clear", List.of(),
                    List.of(springSlot), 7.0, 5.0, 1.5, 1);
            BriefingDay day = new BriefingDay(tomorrow, List.of(
                    new BriefingEventSummary(TargetType.SUNSET, List.of(region), List.of())
            ));

            BriefingBestBetAdvisor.RollupResult result = advisor.buildRollupJson(
                    List.of(day), now);
            assertThat(result.json()).contains("\"lunarSpringTideCount\":1");
            assertThat(result.json()).contains("\"lunarKingTideCount\":0");
            assertThat(result.json()).contains("\"extraHighCount\":1");
            assertThat(result.json()).contains("\"hasSurgeBoost\":true");
        }

        @Test
        @DisplayName("Statistical size counts reflect TideInfo.statisticalSize() derivation")
        void statisticalSizeCountsInRollup() throws Exception {
            when(auroraStateCache.isActive()).thenReturn(false);
            LocalDate tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1);
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

            // isKingTide=true → statisticalSize()=EXTRA_EXTRA_HIGH
            BriefingSlot extraExtraHighSlot = new BriefingSlot(
                    "Bamburgh", null, Verdict.GO,
                    new BriefingSlot.WeatherConditions(20, BigDecimal.ZERO, 15000, 70,
                            8.0, null, null, BigDecimal.ONE),
                    new BriefingSlot.TideInfo("HIGH", true, null,
                            new BigDecimal("6.5"), true, false,
                            LunarTideType.KING_TIDE, "New Moon", true),
                    List.of());
            // isSpringTide=true → statisticalSize()=EXTRA_HIGH
            BriefingSlot extraHighSlot = new BriefingSlot(
                    "Dunstanburgh", null, Verdict.GO,
                    new BriefingSlot.WeatherConditions(15, BigDecimal.ZERO, 20000, 65,
                            9.0, null, null, BigDecimal.ONE),
                    new BriefingSlot.TideInfo("HIGH", true, null,
                            new BigDecimal("5.3"), false, true,
                            LunarTideType.SPRING_TIDE, "Full Moon", false),
                    List.of());
            BriefingRegion region = new BriefingRegion(
                    "Northumberland", Verdict.GO, "Clear", List.of(),
                    List.of(extraExtraHighSlot, extraHighSlot), 7.0, 5.0, 1.5, 1);
            BriefingDay day = new BriefingDay(tomorrow, List.of(
                    new BriefingEventSummary(TargetType.SUNSET, List.of(region), List.of())
            ));

            BriefingBestBetAdvisor.RollupResult result = advisor.buildRollupJson(
                    List.of(day), now);
            assertThat(result.json()).contains("\"extraExtraHighCount\":1");
            assertThat(result.json()).contains("\"extraHighCount\":1");
        }

        @Test
        @DisplayName("Inland slots produce zero counts for all tide fields")
        void inlandSlotsZeroTideCounts() throws Exception {
            when(auroraStateCache.isActive()).thenReturn(false);
            LocalDate tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1);
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

            BriefingDay day = new BriefingDay(tomorrow, List.of(
                    new BriefingEventSummary(TargetType.SUNSET, List.of(
                            region("Lake District", Verdict.GO, 3, 0, 0)
                    ), List.of())
            ));

            BriefingBestBetAdvisor.RollupResult result = advisor.buildRollupJson(
                    List.of(day), now);
            assertThat(result.json()).contains("\"lunarKingTideCount\":0");
            assertThat(result.json()).contains("\"lunarSpringTideCount\":0");
            assertThat(result.json()).contains("\"extraExtraHighCount\":0");
            assertThat(result.json()).contains("\"extraHighCount\":0");
            assertThat(result.json()).contains("\"tideAlignedCount\":0");
            assertThat(result.json()).contains("\"coastalLocationCount\":0");
            assertThat(result.json()).contains("\"hasSurgeBoost\":false");
        }

        @Test
        @DisplayName("dayOfWeek and isWeekday not included in event node")
        void dayOfWeekNotIncluded() throws Exception {
            when(auroraStateCache.isActive()).thenReturn(false);
            LocalDate saturday = LocalDate.now(ZoneOffset.UTC).with(
                    java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.SATURDAY));
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            BriefingDay day = new BriefingDay(saturday, List.of(
                    new BriefingEventSummary(TargetType.SUNSET, List.of(
                            region("Northumberland", Verdict.GO, 2, 0, 0)
                    ), List.of())
            ));

            BriefingBestBetAdvisor.RollupResult result = advisor.buildRollupJson(List.of(day), now);
            assertThat(result.json()).doesNotContain("dayOfWeek");
            assertThat(result.json()).doesNotContain("isWeekday");
        }

        @Test
        @DisplayName("forecastWindow and validEvents/validRegions included in JSON")
        void forecastWindowAndValidSetsIncluded() throws Exception {
            when(auroraStateCache.isActive()).thenReturn(false);
            LocalDate tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1);
            LocalDate dayAfter = LocalDate.now(ZoneOffset.UTC).plusDays(2);
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            BriefingDay day1 = new BriefingDay(tomorrow, List.of(
                    new BriefingEventSummary(TargetType.SUNSET, List.of(
                            region("Northumberland", Verdict.GO, 2, 0, 0)), List.of())));
            BriefingDay day2 = new BriefingDay(dayAfter, List.of(
                    new BriefingEventSummary(TargetType.SUNRISE, List.of(
                            region("The Lake District", Verdict.MARGINAL, 0, 1, 0)), List.of())));

            BriefingBestBetAdvisor.RollupResult result = advisor.buildRollupJson(
                    List.of(day1, day2), now);
            assertThat(result.json()).contains("forecastWindow");
            assertThat(result.json()).contains("startDate");
            assertThat(result.json()).contains("availableDates");
            assertThat(result.json()).contains("validEvents");
            assertThat(result.json()).contains("validRegions");
            assertThat(result.validEvents()).containsExactly(
                    tomorrow.toString() + "_sunset", dayAfter.toString() + "_sunrise");
            assertThat(result.validRegions()).containsExactly("Northumberland", "The Lake District");
        }

        @Test
        @DisplayName("Limits rollup to 6 non-past events")
        void limitsToSixEvents() throws Exception {
            when(auroraStateCache.isActive()).thenReturn(false);
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            // 4 days × 2 events = 8 total → should be limited to 6
            List<BriefingDay> days = new java.util.ArrayList<>();
            for (int i = 1; i <= 4; i++) {
                LocalDate date = LocalDate.now(ZoneOffset.UTC).plusDays(i);
                days.add(new BriefingDay(date, List.of(
                        new BriefingEventSummary(TargetType.SUNRISE, List.of(
                                region("Region" + i, Verdict.GO, 1, 0, 0)), List.of()),
                        new BriefingEventSummary(TargetType.SUNSET, List.of(
                                region("Region" + i, Verdict.GO, 1, 0, 0)), List.of())
                )));
            }

            BriefingBestBetAdvisor.RollupResult result = advisor.buildRollupJson(days, now);
            assertThat(result.validEvents()).hasSize(6);
            // Events from day 4 sunset should not be included
            LocalDate day4 = LocalDate.now(ZoneOffset.UTC).plusDays(4);
            assertThat(result.validEvents()).doesNotContain(day4.toString() + "_sunset");
        }

        @Test
        @DisplayName("Past events on today are skipped before counting the 6-event limit")
        void pastEventsSkippedBeforeCounting() throws Exception {
            when(auroraStateCache.isActive()).thenReturn(false);
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            LocalDateTime pastTime = now.minusHours(2);
            // Today has 1 past event + 1 future event, then 3 more days × 2
            List<BriefingDay> days = new java.util.ArrayList<>();
            days.add(new BriefingDay(today, List.of(
                    new BriefingEventSummary(TargetType.SUNRISE, List.of(
                            regionWithTime("PastRegion", Verdict.GO, 1, 0, 0, pastTime)), List.of()),
                    new BriefingEventSummary(TargetType.SUNSET, List.of(
                            region("TodayRegion", Verdict.GO, 1, 0, 0)), List.of())
            )));
            for (int i = 1; i <= 3; i++) {
                LocalDate date = LocalDate.now(ZoneOffset.UTC).plusDays(i);
                days.add(new BriefingDay(date, List.of(
                        new BriefingEventSummary(TargetType.SUNRISE, List.of(
                                region("Region" + i, Verdict.GO, 1, 0, 0)), List.of()),
                        new BriefingEventSummary(TargetType.SUNSET, List.of(
                                region("Region" + i, Verdict.GO, 1, 0, 0)), List.of())
                )));
            }

            BriefingBestBetAdvisor.RollupResult result = advisor.buildRollupJson(days, now);
            // 1 past (skipped) + 1 today + 6 from future = 7, capped at 6
            assertThat(result.validEvents()).hasSize(6);
            // The past sunrise should be excluded
            assertThat(result.validEvents()).doesNotContain(today.toString() + "_sunrise");
            // Today sunset should be included
            assertThat(result.validEvents()).contains(today.toString() + "_sunset");
        }

    }

    // ── advise (integration path) ──

    @Nested
    @DisplayName("advise fallback")
    class AdviseFallbackTests {

        @Test
        @DisplayName("Returns empty list when Claude call throws")
        void claudeThrowsReturnsEmpty() {
            when(anthropicApiClient.createMessage(any()))
                    .thenThrow(new RuntimeException("overloaded — simulated failure"));
            when(auroraStateCache.isActive()).thenReturn(false);

            List<BestBet> picks = advisor.advise(List.of(), 42L, Map.of());
            assertThat(picks).isEmpty();
        }

        @Test
        @DisplayName("Returns picks from valid Claude response matching validEvents/validRegions")
        void returnsPicksFromValidResponse() {
            when(auroraStateCache.isActive()).thenReturn(false);
            LocalDate tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1);
            String eventId = tomorrow.toString() + "_sunset";

            TextBlock textBlock = mock(TextBlock.class);
            when(textBlock.text()).thenReturn(
                    "{\"picks\":[{\"rank\":1,\"headline\":\"Go shoot\",\"detail\":\"Clear skies.\","
                    + "\"event\":\"" + eventId + "\",\"region\":\"Northumberland\","
                    + "\"confidence\":\"high\"}]}");
            ContentBlock contentBlock = mock(ContentBlock.class);
            when(contentBlock.isText()).thenReturn(true);
            when(contentBlock.asText()).thenReturn(textBlock);
            Message message = mock(Message.class);
            when(message.content()).thenReturn(List.of(contentBlock));
            when(anthropicApiClient.createMessage(any())).thenReturn(message);

            BriefingDay day = new BriefingDay(tomorrow, List.of(
                    new BriefingEventSummary(TargetType.SUNSET, List.of(
                            region("Northumberland", Verdict.GO, 3, 0, 0)), List.of())));
            List<BestBet> picks = advisor.advise(List.of(day), 42L, Map.of());
            assertThat(picks).hasSize(1);
            assertThat(picks.get(0).region()).isEqualTo("Northumberland");
        }

        @Test
        @DisplayName("Enriches picks with structured event data from triage")
        void enrichesWithStructuredEventData() {
            when(auroraStateCache.isActive()).thenReturn(false);
            LocalDate tomorrow = LocalDate.now(ZoneId.of("Europe/London")).plusDays(1);
            String eventId = tomorrow.toString() + "_sunset";
            LocalDateTime eventTime = tomorrow.atTime(18, 48);

            TextBlock textBlock = mock(TextBlock.class);
            when(textBlock.text()).thenReturn(
                    "{\"picks\":[{\"rank\":1,\"headline\":\"Best light\",\"detail\":\"Clear.\","
                    + "\"event\":\"" + eventId + "\",\"region\":\"Northumberland\","
                    + "\"confidence\":\"high\"}]}");
            ContentBlock contentBlock = mock(ContentBlock.class);
            when(contentBlock.isText()).thenReturn(true);
            when(contentBlock.asText()).thenReturn(textBlock);
            Message message = mock(Message.class);
            when(message.content()).thenReturn(List.of(contentBlock));
            when(anthropicApiClient.createMessage(any())).thenReturn(message);

            BriefingDay day = new BriefingDay(tomorrow, List.of(
                    new BriefingEventSummary(TargetType.SUNSET, List.of(
                            regionWithTime("Northumberland", Verdict.GO, 3, 0, 0, eventTime)
                    ), List.of())));
            List<BestBet> picks = advisor.advise(List.of(day), 42L, Map.of());
            assertThat(picks).hasSize(1);
            BestBet pick = picks.get(0);
            assertThat(pick.dayName()).isEqualTo("Tomorrow");
            assertThat(pick.eventType()).isEqualTo("sunset");
            assertThat(pick.eventTime()).isNotNull();
        }

        @Test
        @DisplayName("Stay-home pick has null structured fields")
        void stayHomePickNullStructuredFields() {
            when(auroraStateCache.isActive()).thenReturn(false);

            TextBlock textBlock = mock(TextBlock.class);
            when(textBlock.text()).thenReturn(
                    "{\"picks\":[{\"rank\":1,\"headline\":\"Stay in\","
                    + "\"detail\":\"Dreadful.\",\"event\":null,\"region\":null,"
                    + "\"confidence\":\"high\"}]}");
            ContentBlock contentBlock = mock(ContentBlock.class);
            when(contentBlock.isText()).thenReturn(true);
            when(contentBlock.asText()).thenReturn(textBlock);
            Message message = mock(Message.class);
            when(message.content()).thenReturn(List.of(contentBlock));
            when(anthropicApiClient.createMessage(any())).thenReturn(message);

            List<BestBet> picks = advisor.advise(List.of(), 42L, Map.of());
            assertThat(picks).hasSize(1);
            BestBet pick = picks.get(0);
            assertThat(pick.dayName()).isNull();
            assertThat(pick.eventType()).isNull();
            assertThat(pick.eventTime()).isNull();
        }
    }

    // ── validateAndFilterPicks ──

    @Nested
    @DisplayName("validateAndFilterPicks")
    class ValidationTests {

        private static final Set<String> VALID_EVENTS = Set.of(
                "2026-03-29_sunset", "2026-03-30_sunrise", "2026-03-30_sunset");
        private static final Set<String> VALID_REGIONS = Set.of(
                "Northumberland", "The Lake District", "The North York Moors");
        private static final Set<String> VALID_DAY_NAMES = Set.of("Saturday", "Sunday");

        private BestBet pick(int rank, String event, String region,
                String headline, String detail) {
            return new BestBet(rank, headline, detail, event, region,
                    Confidence.HIGH, null, null, null, null);
        }

        @Test
        @DisplayName("Valid picks pass — both returned")
        void validPicksPass() {
            List<BestBet> picks = List.of(
                    pick(1, "2026-03-30_sunset", "Northumberland", "Go shoot", "Clear."),
                    pick(2, "2026-03-30_sunrise", "The Lake District", "Also good", "Nice."));
            List<BestBet> result = advisor.validateAndFilterPicks(
                    picks, VALID_EVENTS, VALID_REGIONS, VALID_DAY_NAMES);
            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("Invalid event rejected")
        void invalidEventRejected() {
            List<BestBet> picks = List.of(
                    pick(1, "thursday_sunrise", "Northumberland", "Go shoot", "Clear."));
            List<BestBet> result = advisor.validateAndFilterPicks(
                    picks, VALID_EVENTS, VALID_REGIONS, VALID_DAY_NAMES);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Invalid region rejected")
        void invalidRegionRejected() {
            List<BestBet> picks = List.of(
                    pick(1, "2026-03-30_sunset", "The Highlands", "Go shoot", "Clear."));
            List<BestBet> result = advisor.validateAndFilterPicks(
                    picks, VALID_EVENTS, VALID_REGIONS, VALID_DAY_NAMES);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Stay-home pick (null event and region) always valid")
        void stayHomePickValid() {
            List<BestBet> picks = List.of(
                    pick(1, null, null, "Stay in — dreadful out there", "Charge your batteries."));
            List<BestBet> result = advisor.validateAndFilterPicks(
                    picks, VALID_EVENTS, VALID_REGIONS, VALID_DAY_NAMES);
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("Narrative day outside forecast window rejects pick")
        void narrativeInvalidDayRejectsPick() {
            List<BestBet> picks = List.of(
                    pick(1, "2026-03-30_sunset", "Northumberland",
                            "Great Thursday sunset", "Head north on Thursday."));
            List<BestBet> result = advisor.validateAndFilterPicks(
                    picks, VALID_EVENTS, VALID_REGIONS, VALID_DAY_NAMES);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Narrative with valid day name passes")
        void narrativeValidDayPasses() {
            List<BestBet> picks = List.of(
                    pick(1, "2026-03-30_sunset", "Northumberland",
                            "Brilliant Sunday sunset", "Head north on Sunday."));
            List<BestBet> result = advisor.validateAndFilterPicks(
                    picks, VALID_EVENTS, VALID_REGIONS, VALID_DAY_NAMES);
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("One valid pick survives and is re-ranked to 1")
        void oneValidPickReranked() {
            List<BestBet> picks = List.of(
                    pick(1, "thursday_sunrise", "Northumberland", "Invalid", "Bad."),
                    pick(2, "2026-03-30_sunrise", "The Lake District", "Also good", "Nice."));
            List<BestBet> result = advisor.validateAndFilterPicks(
                    picks, VALID_EVENTS, VALID_REGIONS, VALID_DAY_NAMES);
            assertThat(result).hasSize(1);
            assertThat(result.get(0).rank()).isEqualTo(1);
            assertThat(result.get(0).region()).isEqualTo("The Lake District");
        }

        @Test
        @DisplayName("Both picks invalid returns empty list")
        void bothInvalidReturnsEmpty() {
            List<BestBet> picks = List.of(
                    pick(1, "thursday_sunset", "Northumberland", "Bad", "Nope."),
                    pick(2, "friday_sunrise", "The Highlands", "Also bad", "Nope."));
            List<BestBet> result = advisor.validateAndFilterPicks(
                    picks, VALID_EVENTS, VALID_REGIONS, VALID_DAY_NAMES);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Re-ranking: pick 1 invalid, pick 2 survives as rank 1")
        void reRankingAfterDiscard() {
            List<BestBet> picks = List.of(
                    pick(1, "thursday_sunset", "Northumberland", "Bad", "Nope."),
                    pick(2, "2026-03-29_sunset", "The North York Moors", "Good one", "Clear."));
            List<BestBet> result = advisor.validateAndFilterPicks(
                    picks, VALID_EVENTS, VALID_REGIONS, VALID_DAY_NAMES);
            assertThat(result).hasSize(1);
            assertThat(result.get(0).rank()).isEqualTo(1);
            assertThat(result.get(0).event()).isEqualTo("2026-03-29_sunset");
        }

        @Test
        @DisplayName("Empty validEvents set skips event validation (no-day-data fallback)")
        void emptyValidEventsSetsSkipValidation() {
            List<BestBet> picks = List.of(
                    pick(1, null, null, "Stay home", "Nothing to see."));
            List<BestBet> result = advisor.validateAndFilterPicks(
                    picks, Set.of(), Set.of(), Set.of());
            assertThat(result).hasSize(1);
        }
    }

    // ── compareModels ──

    @Nested
    @DisplayName("compareModels")
    class CompareModelsTests {

        @Test
        @DisplayName("Fans out to 3 models with the same rollup JSON")
        void fansOutToThreeModels() throws Exception {
            when(auroraStateCache.isActive()).thenReturn(false);
            LocalDate tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1);
            String eventId = tomorrow.toString() + "_sunset";

            Message message = stubMessage(
                    "{\"picks\":[{\"rank\":1,\"headline\":\"Go\",\"detail\":\"Clear.\","
                    + "\"event\":\"" + eventId + "\",\"region\":\"Northumberland\","
                    + "\"confidence\":\"high\"}]}");
            when(anthropicApiClient.createMessage(any())).thenReturn(message);

            BriefingDay day = new BriefingDay(tomorrow, List.of(
                    new BriefingEventSummary(TargetType.SUNSET, List.of(
                            region("Northumberland", Verdict.GO, 3, 0, 0)), List.of())));

            BriefingBestBetAdvisor.ComparisonRun result = advisor.compareModels(
                    List.of(day), Map.of());

            assertThat(result.results()).hasSize(3);
            assertThat(result.results().get(0).model()).isEqualTo(EvaluationModel.HAIKU);
            assertThat(result.results().get(1).model()).isEqualTo(EvaluationModel.SONNET);
            assertThat(result.results().get(2).model()).isEqualTo(EvaluationModel.OPUS);
            assertThat(result.rollupJson()).isNotBlank();

            // All 3 should have the same parsed picks
            for (BriefingBestBetAdvisor.ModelComparisonResult r : result.results()) {
                assertThat(r.rawResponse()).isNotNull();
                assertThat(r.validatedPicks()).hasSize(1);
                assertThat(r.validatedPicks().get(0).region()).isEqualTo("Northumberland");
            }
        }

        @Test
        @DisplayName("Per-model failure is isolated — other models still succeed")
        void perModelFailureIsolated() throws Exception {
            when(auroraStateCache.isActive()).thenReturn(false);
            LocalDate tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1);
            String eventId = tomorrow.toString() + "_sunset";

            Message goodMessage = stubMessage(
                    "{\"picks\":[{\"rank\":1,\"headline\":\"Go\",\"detail\":\"Clear.\","
                    + "\"event\":\"" + eventId + "\",\"region\":\"Northumberland\","
                    + "\"confidence\":\"high\"}]}");

            // First call succeeds, second fails, third succeeds
            when(anthropicApiClient.createMessage(any()))
                    .thenReturn(goodMessage)
                    .thenThrow(new RuntimeException("overloaded"))
                    .thenReturn(goodMessage);

            BriefingDay day = new BriefingDay(tomorrow, List.of(
                    new BriefingEventSummary(TargetType.SUNSET, List.of(
                            region("Northumberland", Verdict.GO, 3, 0, 0)), List.of())));

            BriefingBestBetAdvisor.ComparisonRun result = advisor.compareModels(
                    List.of(day), Map.of());

            assertThat(result.results()).hasSize(3);
            assertThat(result.results().get(0).rawResponse()).isNotNull(); // HAIKU ok
            assertThat(result.results().get(1).rawResponse()).isNull();    // SONNET failed
            assertThat(result.results().get(2).rawResponse()).isNotNull(); // OPUS ok
            assertThat(result.results().get(1).tokenUsage()).isEqualTo(TokenUsage.EMPTY);
        }

        @Test
        @DisplayName("Correct validation counts returned")
        void correctValidationCounts() throws Exception {
            when(auroraStateCache.isActive()).thenReturn(false);
            LocalDate tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1);
            String eventId = tomorrow.toString() + "_sunset";

            // Two picks: one valid (event in rollup), one invalid (bad event)
            Message message = stubMessage(
                    "{\"picks\":["
                    + "{\"rank\":1,\"headline\":\"Go\",\"detail\":\"Clear.\","
                    + "\"event\":\"" + eventId + "\",\"region\":\"Northumberland\","
                    + "\"confidence\":\"high\"},"
                    + "{\"rank\":2,\"headline\":\"Also\",\"detail\":\"Nice.\","
                    + "\"event\":\"bogus_event\",\"region\":\"Northumberland\","
                    + "\"confidence\":\"medium\"}"
                    + "]}");
            when(anthropicApiClient.createMessage(any())).thenReturn(message);

            BriefingDay day = new BriefingDay(tomorrow, List.of(
                    new BriefingEventSummary(TargetType.SUNSET, List.of(
                            region("Northumberland", Verdict.GO, 3, 0, 0)), List.of())));

            BriefingBestBetAdvisor.ComparisonRun result = advisor.compareModels(
                    List.of(day), Map.of());

            for (BriefingBestBetAdvisor.ModelComparisonResult r : result.results()) {
                assertThat(r.parsedPicks()).hasSize(2);
                assertThat(r.validatedPicks()).hasSize(1);
            }
        }

        private Message stubMessage(String text) {
            TextBlock textBlock = mock(TextBlock.class);
            when(textBlock.text()).thenReturn(text);
            ContentBlock contentBlock = mock(ContentBlock.class);
            when(contentBlock.isText()).thenReturn(true);
            when(contentBlock.asText()).thenReturn(textBlock);

            Usage usage = mock(Usage.class);
            when(usage.inputTokens()).thenReturn(500L);
            when(usage.outputTokens()).thenReturn(100L);
            when(usage.cacheCreationInputTokens()).thenReturn(java.util.Optional.of(0L));
            when(usage.cacheReadInputTokens()).thenReturn(java.util.Optional.of(0L));

            Message message = mock(Message.class);
            when(message.content()).thenReturn(List.of(contentBlock));
            when(message.usage()).thenReturn(usage);
            return message;
        }
    }

    // ── getModelDisplayName ──

    @Nested
    @DisplayName("getModelDisplayName")
    class ModelDisplayNameTests {

        @Test
        @DisplayName("Returns 'Opus' when OPUS is configured")
        void returnsOpusDisplayName() {
            when(modelSelectionService.getActiveModel(RunType.BRIEFING_BEST_BET))
                    .thenReturn(EvaluationModel.OPUS);
            assertThat(advisor.getModelDisplayName()).isEqualTo("Opus");
        }

        @Test
        @DisplayName("Returns 'Haiku' when HAIKU is configured")
        void returnsHaikuDisplayName() {
            when(modelSelectionService.getActiveModel(RunType.BRIEFING_BEST_BET))
                    .thenReturn(EvaluationModel.HAIKU);
            assertThat(advisor.getModelDisplayName()).isEqualTo("Haiku");
        }

        @Test
        @DisplayName("Returns 'Sonnet' when SONNET is configured")
        void returnsSonnetDisplayName() {
            when(modelSelectionService.getActiveModel(RunType.BRIEFING_BEST_BET))
                    .thenReturn(EvaluationModel.SONNET);
            assertThat(advisor.getModelDisplayName()).isEqualTo("Sonnet");
        }
    }

    // ── Helpers ──

    private static BriefingRegion region(String name, Verdict verdict,
            int go, int marginal, int standdown) {
        List<BriefingSlot> slots = new java.util.ArrayList<>();
        for (int i = 0; i < go; i++) {
            slots.add(slot("Loc" + i, Verdict.GO, null));
        }
        for (int i = 0; i < marginal; i++) {
            slots.add(slot("M" + i, Verdict.MARGINAL, null));
        }
        for (int i = 0; i < standdown; i++) {
            slots.add(slot("S" + i, Verdict.STANDDOWN, null));
        }
        return new BriefingRegion(name, verdict, "Summary", List.of(), slots,
                null, null, null, null);
    }

    private static BriefingRegion regionWithTime(String name, Verdict verdict,
            int go, int marginal, int standdown, LocalDateTime time) {
        List<BriefingSlot> slots = new java.util.ArrayList<>();
        for (int i = 0; i < go; i++) {
            slots.add(slot("Loc" + i, Verdict.GO, time));
        }
        for (int i = 0; i < marginal; i++) {
            slots.add(slot("M" + i, Verdict.MARGINAL, time));
        }
        for (int i = 0; i < standdown; i++) {
            slots.add(slot("S" + i, Verdict.STANDDOWN, time));
        }
        return new BriefingRegion(name, verdict, "Summary", List.of(), slots,
                null, null, null, null);
    }

    private static BriefingSlot slot(String name, Verdict verdict, LocalDateTime time) {
        return new BriefingSlot(name, time, verdict,
                new BriefingSlot.WeatherConditions(20, BigDecimal.ZERO, 15000, 70,
                        8.0, null, null, BigDecimal.ONE),
                BriefingSlot.TideInfo.NONE, List.of());
    }
}
