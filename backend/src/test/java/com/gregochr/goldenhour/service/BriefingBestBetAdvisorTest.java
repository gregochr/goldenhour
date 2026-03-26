package com.gregochr.goldenhour.service;

import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.TextBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AuroraForecastScore;
import com.gregochr.goldenhour.model.BestBet;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import com.gregochr.goldenhour.service.evaluation.AnthropicApiClient;
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
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BriefingBestBetAdvisor}.
 */
@ExtendWith(MockitoExtension.class)
class BriefingBestBetAdvisorTest {

    @Mock private AnthropicApiClient anthropicApiClient;
    @Mock private JobRunService jobRunService;
    @Mock private AuroraStateCache auroraStateCache;

    private BriefingBestBetAdvisor advisor;

    @BeforeEach
    void setUp() {
        advisor = new BriefingBestBetAdvisor(
                anthropicApiClient, new ObjectMapper().findAndRegisterModules(),
                jobRunService, auroraStateCache);
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
            assertThat(picks.get(0).confidence()).isEqualTo("high");
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
                          "event": "aurora_tonight",
                          "region": "Northumberland",
                          "confidence": "high"
                        }
                      ]
                    }
                    """;
            List<BestBet> picks = advisor.parseBestBets(raw);
            assertThat(picks).hasSize(1);
            assertThat(picks.get(0).event()).isEqualTo("aurora_tonight");
        }
    }

    // ── buildRollupJson ──

    @Nested
    @DisplayName("buildRollupJson")
    class RollupJsonTests {

        @Test
        @DisplayName("Future event is included in rollup")
        void futureEventIncluded() throws Exception {
            when(auroraStateCache.isActive()).thenReturn(false);
            LocalDate tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1);
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            BriefingDay day = new BriefingDay(tomorrow, List.of(
                    new BriefingEventSummary(TargetType.SUNSET, List.of(
                            region("Northumberland", Verdict.GO, 3, 0, 0)
                    ), List.of())
            ));

            String json = advisor.buildRollupJson(List.of(day), now);
            assertThat(json).contains("tomorrow_sunset");
            assertThat(json).contains("Northumberland");
        }

        @Test
        @DisplayName("Past today event is skipped")
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

            String json = advisor.buildRollupJson(List.of(day), now);
            assertThat(json).doesNotContain("today_sunrise");
        }

        @Test
        @DisplayName("Aurora event included when cache is active")
        void auroraIncludedWhenActive() throws Exception {
            when(auroraStateCache.isActive()).thenReturn(true);
            when(auroraStateCache.getCurrentLevel()).thenReturn(AlertLevel.MODERATE);
            when(auroraStateCache.getLastTriggerKp()).thenReturn(5.2);
            when(auroraStateCache.getCachedScores()).thenReturn(List.of(
                    mock(AuroraForecastScore.class)
            ));
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            String json = advisor.buildRollupJson(List.of(), now);
            assertThat(json).contains("aurora_tonight");
            assertThat(json).contains("MODERATE");
            assertThat(json).contains("5.2");
        }

        @Test
        @DisplayName("Aurora excluded when cache is inactive")
        void auroraExcludedWhenInactive() throws Exception {
            when(auroraStateCache.isActive()).thenReturn(false);
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            String json = advisor.buildRollupJson(List.of(), now);
            assertThat(json).doesNotContain("aurora_tonight");
        }

        @Test
        @DisplayName("King tide locations listed in rollup")
        void kingTideInRollup() throws Exception {
            when(auroraStateCache.isActive()).thenReturn(false);
            LocalDate tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1);
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

            BriefingSlot kingSlot = new BriefingSlot(
                    "Bamburgh", null, Verdict.GO,
                    20, BigDecimal.ZERO, 15000, 70, 8.0, null, null, BigDecimal.ONE,
                    "HIGH", true, null, new BigDecimal("1.9"),
                    true, false, List.of("King tide"));
            BriefingRegion region = new BriefingRegion(
                    "Northumberland", Verdict.GO, "Clear", List.of("King tide at Bamburgh"),
                    List.of(kingSlot), 7.0, 5.0, 1.5, 1);
            BriefingDay day = new BriefingDay(tomorrow, List.of(
                    new BriefingEventSummary(TargetType.SUNSET, List.of(region), List.of())
            ));

            String json = advisor.buildRollupJson(List.of(day), now);
            assertThat(json).contains("hasKingTide");
            assertThat(json).contains("Bamburgh");
            assertThat(json).contains("kingTideLocations");
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

            List<BestBet> picks = advisor.advise(List.of(), 42L);
            assertThat(picks).isEmpty();
        }

        @Test
        @DisplayName("Returns picks from valid Claude response")
        void returnsPicksFromValidResponse() {
            when(auroraStateCache.isActive()).thenReturn(false);

            TextBlock textBlock = mock(TextBlock.class);
            when(textBlock.text()).thenReturn("""
                    {"picks":[{"rank":1,"headline":"Go shoot","detail":"Clear.",
                    "event":"tomorrow_sunset","region":"Northumberland","confidence":"high"}]}
                    """);
            ContentBlock contentBlock = mock(ContentBlock.class);
            when(contentBlock.isText()).thenReturn(true);
            when(contentBlock.asText()).thenReturn(textBlock);
            Message message = mock(Message.class);
            when(message.content()).thenReturn(List.of(contentBlock));
            when(anthropicApiClient.createMessage(any())).thenReturn(message);

            List<BestBet> picks = advisor.advise(List.of(), 42L);
            assertThat(picks).hasSize(1);
            assertThat(picks.get(0).region()).isEqualTo("Northumberland");
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
                20, BigDecimal.ZERO, 15000, 70, 8.0, null, null, BigDecimal.ONE,
                null, false, null, null, false, false, List.of());
    }
}
