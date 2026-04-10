package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BriefingHeadlineGenerator}.
 */
class BriefingHeadlineGeneratorTest {

    private final BriefingHeadlineGenerator generator = new BriefingHeadlineGenerator();

    @Nested
    @DisplayName("Headline generation")
    class HeadlineTests {

        // --- past-event filtering ---

        @Test
        @DisplayName("Past today event is skipped; tomorrow shown instead")
        void pastTodayEventSkipped() {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            LocalDate tomorrow = today.plusDays(1);
            LocalDateTime pastTime = LocalDateTime.now(ZoneOffset.UTC).minusHours(1);
            LocalDateTime futureTime = LocalDateTime.now(ZoneOffset.UTC).plusHours(8);

            BriefingRegion todayRegion = new BriefingRegion("Northumberland",
                    Verdict.GO, "Clear", List.of(),
                    List.of(slotAt("Bamburgh", Verdict.GO, pastTime)), null, null, null, null, null, null);
            BriefingRegion tomorrowRegion = new BriefingRegion("Lake District",
                    Verdict.GO, "Clear", List.of(),
                    List.of(slotAt("Keswick", Verdict.GO, futureTime)), null, null, null, null, null, null);

            BriefingDay todayDay = new BriefingDay(today, List.of(
                    new BriefingEventSummary(TargetType.SUNRISE, List.of(todayRegion), List.of())));
            BriefingDay tomorrowDay = new BriefingDay(tomorrow, List.of(
                    new BriefingEventSummary(TargetType.SUNRISE, List.of(tomorrowRegion), List.of())));

            String headline = generator.generateHeadline(List.of(todayDay, tomorrowDay));
            assertThat(headline).contains("Tomorrow");
            assertThat(headline).contains("Lake District");
            assertThat(headline).doesNotContain("Northumberland");
        }

        @Test
        @DisplayName("Future today event is not skipped")
        void futureTodayEventNotSkipped() {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            LocalDateTime futureTime = LocalDateTime.now(ZoneOffset.UTC).plusHours(2);

            BriefingRegion region = new BriefingRegion("Northumberland",
                    Verdict.GO, "Clear", List.of(),
                    List.of(slotAt("Bamburgh", Verdict.GO, futureTime)), null, null, null, null, null, null);
            BriefingDay day = new BriefingDay(today, List.of(
                    new BriefingEventSummary(TargetType.SUNSET, List.of(region), List.of())));

            String headline = generator.generateHeadline(List.of(day));
            assertThat(headline).contains("Today");
            assertThat(headline).contains("Northumberland");
        }

        // --- headline content by GO region count ---

        @Test
        @DisplayName("Single GO region — names region in headline")
        void singleGoRegion() {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            BriefingRegion region = new BriefingRegion("Lake District",
                    Verdict.GO, "Clear skies", List.of(), List.of(), null, null, null, null, null, null);
            BriefingRegion standdown = new BriefingRegion("Northumberland",
                    Verdict.STANDDOWN, "Rain", List.of(), List.of(), null, null, null, null, null, null);
            BriefingDay day = new BriefingDay(today, List.of(
                    new BriefingEventSummary(TargetType.SUNSET,
                            List.of(region, standdown), List.of())));

            String headline = generator.generateHeadline(List.of(day));
            assertThat(headline).contains("Lake District");
            assertThat(headline).contains("sunset");
        }

        @Test
        @DisplayName("Two GO regions — both named in headline")
        void twoGoRegions() {
            LocalDate tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1);
            BriefingDay day = new BriefingDay(tomorrow, List.of(
                    new BriefingEventSummary(TargetType.SUNRISE, List.of(
                            new BriefingRegion("Northumberland", Verdict.GO, "Clear", List.of(), List.of(),
                                    null, null, null, null, null, null),
                            new BriefingRegion("Lake District", Verdict.GO, "Clear", List.of(), List.of(),
                                    null, null, null, null, null, null)
                    ), List.of())));

            String headline = generator.generateHeadline(List.of(day));
            assertThat(headline).contains("Northumberland");
            assertThat(headline).contains("Lake District");
            assertThat(headline).contains("sunrise");
        }

        @Test
        @DisplayName("Three GO regions — top region + count shown")
        void threeGoRegions() {
            LocalDate tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1);
            BriefingDay day = new BriefingDay(tomorrow, List.of(
                    new BriefingEventSummary(TargetType.SUNRISE, List.of(
                            new BriefingRegion("Northumberland", Verdict.GO, "Clear", List.of(), List.of(),
                                    null, null, null, null, null, null),
                            new BriefingRegion("Lake District", Verdict.GO, "Clear", List.of(), List.of(),
                                    null, null, null, null, null, null),
                            new BriefingRegion("Yorkshire Dales", Verdict.GO, "Clear", List.of(), List.of(),
                                    null, null, null, null, null, null)
                    ), List.of())));

            String headline = generator.generateHeadline(List.of(day));
            assertThat(headline).contains("Northumberland");
            assertThat(headline).contains("2 more");
        }

        @Test
        @DisplayName("Five or more GO regions — excellent headline with count")
        void fiveOrMoreGoRegions() {
            LocalDate tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1);
            List<BriefingRegion> regions = List.of(
                    region("Northumberland", Verdict.GO),
                    region("Lake District", Verdict.GO),
                    region("Yorkshire Dales", Verdict.GO),
                    region("Tyne and Wear", Verdict.GO),
                    region("Teesdale", Verdict.GO),
                    region("North York Moors", Verdict.GO)
            );
            BriefingDay day = new BriefingDay(tomorrow, List.of(
                    new BriefingEventSummary(TargetType.SUNRISE, regions, List.of())));

            String headline = generator.generateHeadline(List.of(day));
            assertThat(headline).contains("6");
            assertThat(headline).contains("sunrise");
            assertThat(headline).containsIgnoringCase("excellent");
        }

        @Test
        @DisplayName("Today preferred over tomorrow even with fewer GO regions")
        void todayPreferredOverTomorrow() {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            LocalDate tomorrow = today.plusDays(1);

            BriefingDay todayDay = new BriefingDay(today, List.of(
                    new BriefingEventSummary(TargetType.SUNSET, List.of(
                            new BriefingRegion("Tyne and Wear", Verdict.GO, "Clear", List.of(), List.of(),
                                    null, null, null, null, null, null)
                    ), List.of())));
            BriefingDay tomorrowDay = new BriefingDay(tomorrow, List.of(
                    new BriefingEventSummary(TargetType.SUNRISE, List.of(
                            new BriefingRegion("Northumberland", Verdict.GO, "Clear", List.of(), List.of(),
                                    null, null, null, null, null, null),
                            new BriefingRegion("Lake District", Verdict.GO, "Clear", List.of(), List.of(),
                                    null, null, null, null, null, null),
                            new BriefingRegion("Yorkshire Dales", Verdict.GO, "Clear", List.of(), List.of(),
                                    null, null, null, null, null, null)
                    ), List.of())));

            String headline = generator.generateHeadline(List.of(todayDay, tomorrowDay));
            assertThat(headline).contains("Today");
        }

        // --- marginal / standdown ---

        @Test
        @DisplayName("Marginal only — marginal headline returned")
        void marginalOnlyHeadline() {
            LocalDate tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1);
            BriefingRegion region = new BriefingRegion("Northumberland",
                    Verdict.MARGINAL, "Patchy cloud", List.of(), List.of(), null, null, null, null, null, null);
            BriefingDay day = new BriefingDay(tomorrow, List.of(
                    new BriefingEventSummary(TargetType.SUNSET, List.of(region), List.of())));

            String headline = generator.generateHeadline(List.of(day));
            assertThat(headline).containsIgnoringCase("marginal");
            assertThat(headline).contains("Northumberland");
        }

        @Test
        @DisplayName("Standdown headline when no good conditions anywhere")
        void standdownHeadline() {
            BriefingRegion region = new BriefingRegion("Northumberland",
                    Verdict.STANDDOWN, "Rain everywhere", List.of(), List.of(), null, null, null, null, null, null);
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            BriefingDay day = new BriefingDay(today, List.of(
                    new BriefingEventSummary(TargetType.SUNSET, List.of(region), List.of())));

            String headline = generator.generateHeadline(List.of(day));
            assertThat(headline).isEqualTo("No promising conditions in the next two days");
        }
    }

    // ── Helpers ──

    private static BriefingRegion region(String name, Verdict verdict) {
        return new BriefingRegion(name, verdict, "Summary", List.of(), List.of(),
                null, null, null, null, null, null);
    }

    private static BriefingSlot slotAt(String name, Verdict verdict, LocalDateTime time) {
        return new BriefingSlot(name, time, verdict,
                new BriefingSlot.WeatherConditions(20, BigDecimal.ZERO, 15000, 70,
                        8.0, null, null, BigDecimal.ONE, 0, 0),
                BriefingSlot.TideInfo.NONE, List.of(), null);
    }
}
