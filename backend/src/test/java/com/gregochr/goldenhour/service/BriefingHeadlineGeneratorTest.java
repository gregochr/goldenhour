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
import java.time.ZoneId;
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

    @Nested
    @DisplayName("Sorting and format selection")
    class HeadlineSortingAndFormatTests {

        @Test
        @DisplayName("Today beats tomorrow even when tomorrow is listed first in days")
        void todayGoBeats_tomorrowGo_whenTomorrowListedFirst() {
            // tomorrow passed FIRST in the list — without the sort, tomorrow's event
            // would be goOpps.get(0) and the headline would say "Tomorrow"
            // Kills: sort removal on line 62
            LocalDate today = LocalDate.now(ZoneId.of("Europe/London"));
            LocalDate tomorrow = today.plusDays(1);
            BriefingDay tomorrowDay = new BriefingDay(tomorrow, List.of(
                    new BriefingEventSummary(TargetType.SUNSET,
                            List.of(region("Tomorrow Region", Verdict.GO)), List.of())));
            BriefingDay todayDay = new BriefingDay(today, List.of(
                    new BriefingEventSummary(TargetType.SUNSET,
                            List.of(region("Today Region", Verdict.GO)), List.of())));

            String headline = generator.generateHeadline(List.of(tomorrowDay, todayDay));

            assertThat(headline).contains("Today");
            assertThat(headline).doesNotContain("Tomorrow");
        }

        @Test
        @DisplayName("More GO regions on the same day wins tiebreaker — descending sort")
        void moreGoRegionsWins_onSameDay() {
            // sunrise (2 GO) listed first, sunset (4 GO) listed second — same day
            // With negation: -4 < -2 → 4-GO event is index 0 → "sunset" wins
            // Without negation (line 64 mutant): 2 < 4 → 2-GO event wins → "sunrise"
            LocalDate today = LocalDate.now(ZoneId.of("Europe/London"));
            BriefingEventSummary sunriseWith2Go = new BriefingEventSummary(TargetType.SUNRISE,
                    List.of(region("Region A", Verdict.GO), region("Region B", Verdict.GO)), List.of());
            BriefingEventSummary sunsetWith4Go = new BriefingEventSummary(TargetType.SUNSET,
                    List.of(region("Region C", Verdict.GO), region("Region D", Verdict.GO),
                            region("Region E", Verdict.GO), region("Region F", Verdict.GO)), List.of());
            BriefingDay day = new BriefingDay(today, List.of(sunriseWith2Go, sunsetWith4Go));

            String headline = generator.generateHeadline(List.of(day));

            assertThat(headline).contains("sunset");
            assertThat(headline).doesNotContain("sunrise");
        }

        @Test
        @DisplayName("SUNRISE event uses sunrise emoji \uD83C\uDF05")
        void sunriseUsesCorrectEmoji() {
            // Kills: line 68 replacing equality check with false (always uses sunset emoji)
            LocalDate today = LocalDate.now(ZoneId.of("Europe/London"));
            BriefingDay day = new BriefingDay(today, List.of(
                    new BriefingEventSummary(TargetType.SUNRISE,
                            List.of(region("Northumberland", Verdict.GO)), List.of())));

            String headline = generator.generateHeadline(List.of(day));

            assertThat(headline).startsWith("\uD83C\uDF05");
        }

        @Test
        @DisplayName("SUNSET event uses sunset emoji \uD83C\uDF07")
        void sunsetUsesCorrectEmoji() {
            LocalDate today = LocalDate.now(ZoneId.of("Europe/London"));
            BriefingDay day = new BriefingDay(today, List.of(
                    new BriefingEventSummary(TargetType.SUNSET,
                            List.of(region("Northumberland", Verdict.GO)), List.of())));

            String headline = generator.generateHeadline(List.of(day));

            assertThat(headline).startsWith("\uD83C\uDF07");
        }

        @Test
        @DisplayName("Exactly 5 GO regions uses 'looking excellent' format — strict >= 5 boundary")
        void exactly5GoRegions_usesExcellentFormat() {
            // 5 >= 5 → excellent path; mutant (> 5): 5 > 5 = false → falls to ">= 3" path
            // Kills: line 74 boundary mutant
            LocalDate tomorrow = LocalDate.now(ZoneId.of("Europe/London")).plusDays(1);
            List<BriefingRegion> regions = List.of(
                    region("A", Verdict.GO), region("B", Verdict.GO), region("C", Verdict.GO),
                    region("D", Verdict.GO), region("E", Verdict.GO));
            BriefingDay day = new BriefingDay(tomorrow, List.of(
                    new BriefingEventSummary(TargetType.SUNRISE, regions, List.of())));

            String headline = generator.generateHeadline(List.of(day));

            assertThat(headline).containsIgnoringCase("excellent");
            assertThat(headline).contains("5 regions GO");
        }

        @Test
        @DisplayName("Exactly 4 GO regions does NOT use 'excellent' — just below >= 5 threshold")
        void exactly4GoRegions_doesNotUseExcellentFormat() {
            // 4 >= 5 = false → falls to ">= 3" path
            LocalDate tomorrow = LocalDate.now(ZoneId.of("Europe/London")).plusDays(1);
            List<BriefingRegion> regions = List.of(
                    region("A", Verdict.GO), region("B", Verdict.GO),
                    region("C", Verdict.GO), region("D", Verdict.GO));
            BriefingDay day = new BriefingDay(tomorrow, List.of(
                    new BriefingEventSummary(TargetType.SUNRISE, regions, List.of())));

            String headline = generator.generateHeadline(List.of(day));

            assertThat(headline).doesNotContainIgnoringCase("excellent");
            assertThat(headline).contains("and 3 more");
        }
    }

    @Nested
    @DisplayName("Past event filtering (isEventPast)")
    class IsEventPastTests {

        @Test
        @DisplayName("Slot with null solarEventTime is not treated as past — null filter is safe")
        void eventWithNullSolarTime_isNotConsideredPast() {
            // If null filter (line 101) is removed, null slot passes through:
            // map(::solarEventTime) → null → Optional.empty() → orElse(false) → same result.
            // This documents the behaviour; the mutation is equivalent but the test
            // verifies no NPE and the event is included, not skipped.
            LocalDate today = LocalDate.now(ZoneId.of("Europe/London"));
            BriefingSlot nullTimeSlot = new BriefingSlot("Location", null, Verdict.GO,
                    new BriefingSlot.WeatherConditions(20, BigDecimal.ZERO, 15000, 70,
                            8.0, null, null, BigDecimal.ONE, 0, 0),
                    BriefingSlot.TideInfo.NONE, List.of(), null);
            BriefingRegion region = new BriefingRegion("Northumberland", Verdict.GO, "Clear",
                    List.of(), List.of(nullTimeSlot), null, null, null, null, null, null);
            BriefingDay day = new BriefingDay(today, List.of(
                    new BriefingEventSummary(TargetType.SUNRISE, List.of(region), List.of())));

            String headline = generator.generateHeadline(List.of(day));

            assertThat(headline).contains("Today");
            assertThat(headline).contains("Northumberland");
        }
    }

    @Nested
    @DisplayName("Marginal headline path (findMarginalHeadline)")
    class MarginalHeadlineTests {

        @Test
        @DisplayName("Marginal event today uses 'today' label")
        void noGoRegions_marginalToday_saysToday() {
            // Kills: line 125 replacing equals(today) with false → always "tomorrow"
            LocalDate today = LocalDate.now(ZoneId.of("Europe/London"));
            BriefingDay day = new BriefingDay(today, List.of(
                    new BriefingEventSummary(TargetType.SUNSET,
                            List.of(region("Northumberland", Verdict.MARGINAL)), List.of())));

            String headline = generator.generateHeadline(List.of(day));

            assertThat(headline).contains("today");
            assertThat(headline).doesNotContain("tomorrow");
        }

        @Test
        @DisplayName("Marginal event tomorrow uses 'tomorrow' label")
        void noGoRegions_marginalTomorrow_saysTomorrow() {
            LocalDate tomorrow = LocalDate.now(ZoneId.of("Europe/London")).plusDays(1);
            BriefingDay day = new BriefingDay(tomorrow, List.of(
                    new BriefingEventSummary(TargetType.SUNSET,
                            List.of(region("Lake District", Verdict.MARGINAL)), List.of())));

            String headline = generator.generateHeadline(List.of(day));

            assertThat(headline).contains("tomorrow");
            assertThat(headline).doesNotContain("today");
        }

        @Test
        @DisplayName("Marginal SUNRISE event uses 'sunrise' label")
        void marginalSunrise_saysSunrise() {
            // Kills: line 126 replacing SUNRISE equality with false → always "sunset"
            LocalDate tomorrow = LocalDate.now(ZoneId.of("Europe/London")).plusDays(1);
            BriefingDay day = new BriefingDay(tomorrow, List.of(
                    new BriefingEventSummary(TargetType.SUNRISE,
                            List.of(region("Lake District", Verdict.MARGINAL)), List.of())));

            String headline = generator.generateHeadline(List.of(day));

            assertThat(headline).contains("sunrise");
            assertThat(headline).doesNotContain("sunset");
        }

        @Test
        @DisplayName("Marginal SUNSET event uses 'sunset' label")
        void marginalSunset_saysSunset() {
            LocalDate tomorrow = LocalDate.now(ZoneId.of("Europe/London")).plusDays(1);
            BriefingDay day = new BriefingDay(tomorrow, List.of(
                    new BriefingEventSummary(TargetType.SUNSET,
                            List.of(region("Lake District", Verdict.MARGINAL)), List.of())));

            String headline = generator.generateHeadline(List.of(day));

            assertThat(headline).contains("sunset");
            assertThat(headline).doesNotContain("sunrise");
        }

        @Test
        @DisplayName("Marginal headline includes the specific region name")
        void marginalHeadline_includesRegionName() {
            LocalDate tomorrow = LocalDate.now(ZoneId.of("Europe/London")).plusDays(1);
            BriefingDay day = new BriefingDay(tomorrow, List.of(
                    new BriefingEventSummary(TargetType.SUNSET,
                            List.of(region("Yorkshire Dales", Verdict.MARGINAL)), List.of())));

            String headline = generator.generateHeadline(List.of(day));

            assertThat(headline).contains("Yorkshire Dales");
        }

        @Test
        @DisplayName("Past marginal event today is skipped — next future marginal event used")
        void pastMarginalEvent_skippedForNextFutureEvent() {
            // today sunrise is MARGINAL + past → skipped; today sunset is MARGINAL + future → used
            // Kills: line 120 replacing equals(today) with false → past event not skipped,
            //        "Past Region" at sunrise would be returned instead
            LocalDate today = LocalDate.now(ZoneId.of("Europe/London"));
            LocalDateTime pastTime = LocalDateTime.now(ZoneOffset.UTC).minusHours(2);
            LocalDateTime futureTime = LocalDateTime.now(ZoneOffset.UTC).plusHours(6);

            BriefingRegion pastRegion = new BriefingRegion("Past Region", Verdict.MARGINAL, "OK",
                    List.of(), List.of(slotAt("Spot", Verdict.MARGINAL, pastTime)),
                    null, null, null, null, null, null);
            BriefingRegion futureRegion = new BriefingRegion("Future Region", Verdict.MARGINAL, "OK",
                    List.of(), List.of(slotAt("Spot2", Verdict.MARGINAL, futureTime)),
                    null, null, null, null, null, null);

            BriefingDay todayDay = new BriefingDay(today, List.of(
                    new BriefingEventSummary(TargetType.SUNRISE, List.of(pastRegion), List.of()),
                    new BriefingEventSummary(TargetType.SUNSET, List.of(futureRegion), List.of())));

            String headline = generator.generateHeadline(List.of(todayDay));

            assertThat(headline).contains("sunset");
            assertThat(headline).contains("Future Region");
            assertThat(headline).doesNotContain("Past Region");
        }
    }

    @Nested
    @DisplayName("Verdict breakdown and suffix content")
    class VerdictBreakdownAndSuffixTests {

        @Test
        @DisplayName("5 GO + 1 MARGINAL — singular 'region MARGINAL' in breakdown")
        void fiveGoWithOneMarginal_singularMarginalInBreakdown() {
            // Kills: line 147 (appendVerdictCounts removed from buildVerdictBreakdown),
            //        line 172 (MARGINAL filter → false), line 175 (marginal > 0 → false),
            //        line 177 (marginal == 1 → false gives plural "regions MARGINAL")
            LocalDate tomorrow = LocalDate.now(ZoneId.of("Europe/London")).plusDays(1);
            List<BriefingRegion> regions = List.of(
                    region("A", Verdict.GO), region("B", Verdict.GO), region("C", Verdict.GO),
                    region("D", Verdict.GO), region("E", Verdict.GO),
                    region("Borderlands", Verdict.MARGINAL));
            BriefingDay day = new BriefingDay(tomorrow, List.of(
                    new BriefingEventSummary(TargetType.SUNRISE, regions, List.of())));

            String headline = generator.generateHeadline(List.of(day));

            assertThat(headline).contains("1 region MARGINAL");
            assertThat(headline).doesNotContain("regions MARGINAL");
        }

        @Test
        @DisplayName("5 GO + 2 MARGINAL — plural 'regions MARGINAL' in breakdown")
        void fiveGoWithTwoMarginal_pluralMarginalInBreakdown() {
            // Kills: line 177 changing plural branch (marginal == 1 → false uses plural always)
            LocalDate tomorrow = LocalDate.now(ZoneId.of("Europe/London")).plusDays(1);
            List<BriefingRegion> regions = List.of(
                    region("A", Verdict.GO), region("B", Verdict.GO), region("C", Verdict.GO),
                    region("D", Verdict.GO), region("E", Verdict.GO),
                    region("Borderlands", Verdict.MARGINAL), region("Midlands", Verdict.MARGINAL));
            BriefingDay day = new BriefingDay(tomorrow, List.of(
                    new BriefingEventSummary(TargetType.SUNRISE, regions, List.of())));

            String headline = generator.generateHeadline(List.of(day));

            assertThat(headline).contains("2 regions MARGINAL");
        }

        @Test
        @DisplayName("5 GO + 0 MARGINAL — no MARGINAL text in breakdown")
        void fiveGoWithZeroMarginal_noMarginalText() {
            // Kills: line 175 (marginal > 0 guard removed → MARGINAL text always appended)
            LocalDate tomorrow = LocalDate.now(ZoneId.of("Europe/London")).plusDays(1);
            List<BriefingRegion> regions = List.of(
                    region("A", Verdict.GO), region("B", Verdict.GO), region("C", Verdict.GO),
                    region("D", Verdict.GO), region("E", Verdict.GO),
                    region("Rain Region", Verdict.STANDDOWN));
            BriefingDay day = new BriefingDay(tomorrow, List.of(
                    new BriefingEventSummary(TargetType.SUNRISE, regions, List.of())));

            String headline = generator.generateHeadline(List.of(day));

            assertThat(headline).doesNotContain("MARGINAL");
            assertThat(headline).contains("STANDDOWN");
        }

        @Test
        @DisplayName("5 GO + 1 STANDDOWN — singular 'region STANDDOWN' in breakdown")
        void fiveGoWithOneStanddown_singularStanddownInBreakdown() {
            // Kills: line 174 (STANDDOWN filter → true → all 6 regions counted),
            //        line 179 (standdown > 0 → false), line 181 (standdown == 1 → false)
            LocalDate tomorrow = LocalDate.now(ZoneId.of("Europe/London")).plusDays(1);
            List<BriefingRegion> regions = List.of(
                    region("A", Verdict.GO), region("B", Verdict.GO), region("C", Verdict.GO),
                    region("D", Verdict.GO), region("E", Verdict.GO),
                    region("Rain Region", Verdict.STANDDOWN));
            BriefingDay day = new BriefingDay(tomorrow, List.of(
                    new BriefingEventSummary(TargetType.SUNRISE, regions, List.of())));

            String headline = generator.generateHeadline(List.of(day));

            assertThat(headline).contains("1 region STANDDOWN");
            assertThat(headline).doesNotContain("regions STANDDOWN");
        }

        @Test
        @DisplayName("5 GO + 2 STANDDOWN — plural 'regions STANDDOWN' in breakdown")
        void fiveGoWithTwoStanddown_pluralStanddownInBreakdown() {
            // Kills: line 181 (standdown == 1 → false always uses plural)
            LocalDate tomorrow = LocalDate.now(ZoneId.of("Europe/London")).plusDays(1);
            List<BriefingRegion> regions = List.of(
                    region("A", Verdict.GO), region("B", Verdict.GO), region("C", Verdict.GO),
                    region("D", Verdict.GO), region("E", Verdict.GO),
                    region("Rain1", Verdict.STANDDOWN), region("Rain2", Verdict.STANDDOWN));
            BriefingDay day = new BriefingDay(tomorrow, List.of(
                    new BriefingEventSummary(TargetType.SUNRISE, regions, List.of())));

            String headline = generator.generateHeadline(List.of(day));

            assertThat(headline).contains("2 regions STANDDOWN");
        }

        @Test
        @DisplayName("5 GO + 0 STANDDOWN — no STANDDOWN text in breakdown")
        void fiveGoWithZeroStanddown_noStanddownText() {
            // Kills: line 179 guard removed — STANDDOWN text would appear even for count=0
            LocalDate tomorrow = LocalDate.now(ZoneId.of("Europe/London")).plusDays(1);
            List<BriefingRegion> regions = List.of(
                    region("A", Verdict.GO), region("B", Verdict.GO), region("C", Verdict.GO),
                    region("D", Verdict.GO), region("E", Verdict.GO),
                    region("Borderlands", Verdict.MARGINAL));
            BriefingDay day = new BriefingDay(tomorrow, List.of(
                    new BriefingEventSummary(TargetType.SUNRISE, regions, List.of())));

            String headline = generator.generateHeadline(List.of(day));

            assertThat(headline).doesNotContain("STANDDOWN");
            assertThat(headline).contains("MARGINAL");
        }

        @Test
        @DisplayName("1 GO + 1 MARGINAL — MARGINAL count appears in suffix")
        void oneGoWithMarginal_marginalAppearsInSuffix() {
            // Kills: line 160 (appendVerdictCounts removed from buildNonGoSuffix),
            //        line 161 (buildNonGoSuffix returns "")
            LocalDate today = LocalDate.now(ZoneId.of("Europe/London"));
            List<BriefingRegion> regions = List.of(
                    region("Go Region", Verdict.GO),
                    region("Marginal Region", Verdict.MARGINAL));
            BriefingDay day = new BriefingDay(today, List.of(
                    new BriefingEventSummary(TargetType.SUNSET, regions, List.of())));

            String headline = generator.generateHeadline(List.of(day));

            assertThat(headline).contains("MARGINAL");
        }

        @Test
        @DisplayName("1 GO + 1 STANDDOWN — STANDDOWN count appears in suffix")
        void oneGoWithStanddown_standdownAppearsInSuffix() {
            // Kills: line 160, 161 (buildNonGoSuffix empty/call removed)
            LocalDate today = LocalDate.now(ZoneId.of("Europe/London"));
            List<BriefingRegion> regions = List.of(
                    region("Go Region", Verdict.GO),
                    region("Standdown Region", Verdict.STANDDOWN));
            BriefingDay day = new BriefingDay(today, List.of(
                    new BriefingEventSummary(TargetType.SUNSET, regions, List.of())));

            String headline = generator.generateHeadline(List.of(day));

            assertThat(headline).contains("STANDDOWN");
        }

        @Test
        @DisplayName("2 GO + 1 MARGINAL — MARGINAL count appears in suffix (2-GO path)")
        void twoGoWithMarginal_marginalAppearsInSuffix() {
            LocalDate today = LocalDate.now(ZoneId.of("Europe/London"));
            List<BriefingRegion> regions = List.of(
                    region("Go A", Verdict.GO), region("Go B", Verdict.GO),
                    region("Marginal Region", Verdict.MARGINAL));
            BriefingDay day = new BriefingDay(today, List.of(
                    new BriefingEventSummary(TargetType.SUNSET, regions, List.of())));

            String headline = generator.generateHeadline(List.of(day));

            assertThat(headline).contains("MARGINAL");
        }

        @Test
        @DisplayName("All regions GO — no MARGINAL or STANDDOWN in headline")
        void allGoRegions_noNonGoSuffix() {
            LocalDate today = LocalDate.now(ZoneId.of("Europe/London"));
            List<BriefingRegion> regions = List.of(
                    region("A", Verdict.GO), region("B", Verdict.GO));
            BriefingDay day = new BriefingDay(today, List.of(
                    new BriefingEventSummary(TargetType.SUNSET, regions, List.of())));

            String headline = generator.generateHeadline(List.of(day));

            assertThat(headline).doesNotContain("MARGINAL");
            assertThat(headline).doesNotContain("STANDDOWN");
        }

        @Test
        @DisplayName("Single GO region uses singular 'region GO' format in headline")
        void singleGoRegion_usesSingularGoFormat() {
            // goCount=1 in buildVerdictBreakdown: "1 region GO" (singular)
            // NOTE: the breakdown is computed but not used in goCount=1 output,
            // so L146 (goCount==1 → false) is an equivalent mutant — documented here.
            // The singleGoRegion test in HeadlineTests already covers the core output.
            LocalDate today = LocalDate.now(ZoneId.of("Europe/London"));
            List<BriefingRegion> regions = List.of(
                    region("Only Region", Verdict.GO),
                    region("Rain Region", Verdict.STANDDOWN));
            BriefingDay day = new BriefingDay(today, List.of(
                    new BriefingEventSummary(TargetType.SUNSET, regions, List.of())));

            String headline = generator.generateHeadline(List.of(day));

            // goCount=1 path includes buildNonGoSuffix; "1 region GO" from breakdown is unused
            assertThat(headline).contains("Only Region");
            assertThat(headline).contains("1 region STANDDOWN");
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
