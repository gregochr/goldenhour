package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.Confidence;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.Verdict;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The serve-time projection that turns each solar event into the window the Plan tab renders.
 *
 * <p>Grouped by rule. Each degrade path is its own named test rather than a branch inside a
 * happy-path one, because the degrades are what a later refactor drops silently.
 */
class PlanWindowProjectorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 5, 23);
    private static final String HEADLINE = "Breaking clear with the antisolar canvas alight";
    private static final String DETAIL = "Low cloud clears from the west on cue.";

    // ── Ranking ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("region ranking")
    class Ranking {

        @Test
        void theHigherAverageRanksFirstRegardlessOfPayloadOrder() {
            // Payload order traces to whichever grid cell Open-Meteo answered first, so the weaker
            // region is deliberately placed first here.
            BriefingWindow w = projectOne(
                    region("Dales", 3, 3, 3),
                    region("Northumberland", 4, 4, 4));

            assertThat(w.bestBet().regionName()).isEqualTo("Northumberland");
        }

        @Test
        @DisplayName("a tie on average breaks on scored coverage, not on payload order")
        void tieBreaksOnScoredCoverage() {
            // Averages are rounded to one decimal place before comparison and there are only a
            // handful of regions, so ties are routine rather than an edge case.
            BriefingWindow w = projectOne(
                    region("Dales", 4, 4),
                    region("Northumberland", 4, 4, 4));

            assertThat(w.bestBet().regionName()).isEqualTo("Northumberland");
        }

        @Test
        @DisplayName("a tie on average and coverage breaks on name, so the order is stable")
        void tieBreaksOnNameLast() {
            BriefingWindow w = projectOne(
                    region("Northumberland", 4, 4),
                    region("Dales", 4, 4));

            assertThat(w.bestBet().regionName()).isEqualTo("Dales");
        }

        @Test
        @DisplayName("a region blanked by the honesty filter ranks last")
        void emptyRegionRanksLast() {
            // The shape BriefingHonestyFilter.fullRewrite leaves behind: no slots at all. It ranks
            // last by arithmetic (average 0.0) rather than by a rule, so pin it.
            BriefingWindow w = projectOne(
                    regionWithSlots("Aaa blanked", List.of()),
                    region("Zzz scored", 3, 3));

            assertThat(w.bestBet().regionName()).isEqualTo("Zzz scored");
        }
    }

    // ── Best rating and verdict ──────────────────────────────────────────────

    @Nested
    @DisplayName("best rating and verdict")
    class RatingAndVerdict {

        @Test
        void bestRatingIsTheMaximumAcrossEveryRegion() {
            BriefingWindow w = projectOne(region("Dales", 2, 4), region("Coast", 3));

            assertThat(w.bestRating()).isEqualTo(4);
            assertThat(w.verdict()).isEqualTo(DisplayVerdict.WORTH_IT);
        }

        @Test
        void ratingOfFourIsWorthIt() {
            assertThat(projectOne(region("R", 4)).verdict()).isEqualTo(DisplayVerdict.WORTH_IT);
        }

        @Test
        void ratingOfThreeIsMaybe() {
            assertThat(projectOne(region("R", 3)).verdict()).isEqualTo(DisplayVerdict.MAYBE);
        }

        @Test
        void ratingOfTwoIsStandDown() {
            assertThat(projectOne(region("R", 2)).verdict()).isEqualTo(DisplayVerdict.STAND_DOWN);
        }

        @Test
        @DisplayName("a canopy slot cannot headline a sky window")
        void canopySlotsAreExcludedFromBestRating() {
            // A woodland GO means heavy cloud and mist — the opposite of what it means for the sky.
            // Letting a bluebell 5 headline a sunset window is the failure this prevents.
            BriefingRegion mixed = regionWithSlots("Mixed", List.of(
                    ratedSlot("Wood", 5, true),
                    ratedSlot("Beach", 3, false)));

            BriefingWindow w = projectOne(mixed);

            assertThat(w.bestRating()).isEqualTo(3);
            assertThat(w.verdict()).isEqualTo(DisplayVerdict.MAYBE);
        }

        @Test
        @DisplayName("an unrated sky slot still keeps a wood out of the header")
        void unratedSkySlotDoesNotHandTheWindowToACanopySlot() {
            // The fallback must key on whether a sky slot EXISTS, not on whether one is rated.
            // This is an ordinary misty sunrise: the woodland lane is deliberately exempt from the
            // sky triage that stands the sky slots down, so the fog that leaves every sky slot
            // unrated is the same fog that scores the wood well.
            BriefingRegion mixed = regionWithSlots("Mixed", List.of(
                    unratedSlot("Beach"),
                    ratedSlot("Wood", 5, true)),
                    DisplayVerdict.MAYBE, null, HEADLINE);

            BriefingWindow w = projectOne(mixed);

            // Unfixed, the wood's 5 became the header star and resolved the badge to WORTH_IT.
            // The badge now follows the region card instead — which is canopy-INCLUSIVE upstream,
            // so a wood can still influence it. That is deliberate and out of P1's scope: see
            // BriefingWindow's contract. What P1 guarantees is the star.
            assertThat(w.bestRating()).isNull();
            assertThat(w.verdict()).isEqualTo(DisplayVerdict.MAYBE);
        }

        @Test
        @DisplayName("the sky-slot test is window-wide, not per region")
        void anUnratedSkyRegionKeepsAnotherRegionsWoodOutOfTheHeader() {
            // bestRating flattens across regions, so a per-region fallback would let a wood in one
            // region headline a window whose sky slots live in another.
            BriefingRegion sky = regionWithSlots("A sky", List.of(unratedSlot("Beach")));
            BriefingRegion woods = regionWithSlots("B woods", List.of(ratedSlot("Wood", 5, true)));

            assertThat(projectOne(sky, woods).bestRating()).isNull();
        }

        @Test
        @DisplayName("a rating outside 1-5 never reaches the header")
        void outOfRangeRatingsAreRejected() {
            // The ranking already discards these via RatingValidator. Accepting one here would let
            // the same bad row be refused for the ranking and printed in the header.
            BriefingRegion r = regionWithSlots("R", List.of(
                    ratedSlot("Loc", 491, false), ratedSlot("Ok", 3, false)));

            assertThat(projectOne(r).bestRating()).isEqualTo(3);
        }

        @Test
        @DisplayName("an all-canopy window reports its own rating rather than nothing")
        void allCanopyWindowFallsBackToCanopySlots() {
            BriefingRegion woods = regionWithSlots("Woods", List.of(ratedSlot("Wood", 5, true)));

            assertThat(projectOne(woods).bestRating()).isEqualTo(5);
        }

        @Test
        @DisplayName("with nothing rated the verdict falls back to the top region's own")
        void unratedWindowUsesRegionVerdict() {
            BriefingRegion unrated = regionWithSlots("R", List.of(unratedSlot("Loc")),
                    DisplayVerdict.MAYBE, null, HEADLINE);

            BriefingWindow w = projectOne(unrated);

            assertThat(w.bestRating()).isNull();
            assertThat(w.verdict()).isEqualTo(DisplayVerdict.MAYBE);
        }

        @Test
        @DisplayName("a window with no regions is AWAITING, not poor")
        void emptyWindowIsAwaiting() {
            BriefingWindow w = projectOne();

            assertThat(w.verdict()).isEqualTo(DisplayVerdict.AWAITING);
            assertThat(w.bestRating()).isNull();
            assertThat(w.bestBet()).isNull();
            assertThat(w.badges()).isEmpty();
            assertThat(w.topRarityRank()).isNull();
        }
    }

    // ── Event time ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("event time")
    class EventTime {

        @Test
        @DisplayName("the earliest slot time wins, not the first in payload order")
        void earliestSlotTimeWins() {
            BriefingRegion late = regionWithSlots("Late", List.of(
                    slotAt("A", LocalTime.of(21, 14))));
            BriefingRegion early = regionWithSlots("Early", List.of(
                    slotAt("B", LocalTime.of(21, 8))));

            assertThat(projectOne(late, early).eventTime())
                    .isEqualTo(LocalDateTime.of(TODAY, LocalTime.of(21, 8)));
        }

        @Test
        void noSlotCarriesATimeYieldsNull() {
            BriefingRegion timeless = regionWithSlots("R", List.of(
                    new BriefingSlot("Loc", null, Verdict.GO, null,
                            BriefingSlot.TideInfo.NONE, List.of(), null)));

            assertThat(projectOne(timeless).eventTime()).isNull();
        }
    }

    // ── Best Bet ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Best Bet")
    class BestBet {

        @Test
        void carriesTheTopRegionsGlossAndItsRankedAverage() {
            BriefingWindow w = projectOne(region("Northumberland", 4, 4));

            assertThat(w.bestBet().regionName()).isEqualTo("Northumberland");
            assertThat(w.bestBet().headline()).isEqualTo(HEADLINE);
            assertThat(w.bestBet().detail()).isEqualTo(DETAIL);
            assertThat(w.bestBet().averageRating()).isEqualTo(4.0);
        }

        @Test
        void namesTheRegionsHighestRatedLocation() {
            BriefingRegion r = regionWithSlots("R", List.of(
                    ratedSlot("Bothal Weir", 3, false),
                    ratedSlot("Blyth Beach", 5, false)));

            assertThat(projectOne(r).bestBet().locationName()).isEqualTo("Blyth Beach");
        }

        @Test
        @DisplayName("never names the wood the header just refused")
        void doesNotNameACanopySlotExcludedFromTheHeader() {
            // The header star and the "where to drive" line must read the same slot population.
            // Naming the wood under sky prose sends someone to a location chosen by the opposite
            // measure of a good morning.
            BriefingRegion mixed = regionWithSlots("Mixed", List.of(
                    ratedSlot("Wood", 5, true),
                    ratedSlot("Beach", 3, false)));

            BriefingWindow w = projectOne(mixed);

            assertThat(w.bestRating()).isEqualTo(3);
            assertThat(w.bestBet().locationName()).isEqualTo("Beach");
        }

        @Test
        @DisplayName("an all-canopy region is not the Best Bet of a window that has sky slots")
        void anAllCanopyRegionDoesNotWinAMixedWindow() {
            // "A woods" sorts first alphabetically and has the higher average, so on ranking alone
            // it would take the Best Bet — and bring woodland prose and a woodland destination to
            // a sky window. The ranking excludes it while any region has a sky slot.
            BriefingRegion woods = regionWithSlots("A woods", List.of(ratedSlot("Wood", 5, true)));
            BriefingRegion sky = regionWithSlots("B sky", List.of(ratedSlot("Beach", 3, false)));

            BriefingWindow w = projectOne(woods, sky);

            assertThat(w.bestBet().regionName()).isEqualTo("B sky");
            assertThat(w.bestBet().locationName()).isEqualTo("Beach");
            assertThat(w.bestRating()).isEqualTo(3);
        }

        @Test
        @DisplayName("an all-canopy window still gets its own Best Bet")
        void anAllCanopyWindowKeepsItsRegion() {
            // The exclusion is two-tier, not absolute: with no sky slot anywhere there is nothing
            // to protect, and dropping every region would blank a window that has real content.
            BriefingRegion woods = regionWithSlots("Woods", List.of(ratedSlot("Wood", 5, true)));

            BriefingWindow w = projectOne(woods);

            assertThat(w.bestBet().regionName()).isEqualTo("Woods");
            assertThat(w.bestBet().locationName()).isEqualTo("Wood");
            assertThat(w.bestRating()).isEqualTo(5);
        }

        @Test
        void namesNoLocationWhenNothingIsRated() {
            BriefingRegion r = regionWithSlots("R", List.of(unratedSlot("Loc")),
                    DisplayVerdict.MAYBE, null, HEADLINE);

            assertThat(projectOne(r).bestBet().locationName()).isNull();
        }

        @Test
        @DisplayName("a null headline yields no Best Bet, and the rest of the window survives")
        void nullHeadlineYieldsNoBestBet() {
            // The plan's mandated case: serve-time re-enrichment nulls the gloss when a region
            // crosses a verdict band. The card must omit the block, not substitute anything.
            BriefingRegion r = regionWithSlots("R", ratedSlots(4, 4),
                    DisplayVerdict.WORTH_IT, Confidence.HIGH, null);

            BriefingWindow w = projectOne(r);

            assertThat(w.bestBet()).isNull();
            assertThat(w.bestRating()).isEqualTo(4);
            assertThat(w.verdict()).isEqualTo(DisplayVerdict.WORTH_IT);
            assertThat(w.confidence()).isEqualTo(Confidence.HIGH);
        }

        @Test
        void blankHeadlineYieldsNoBestBet() {
            assertThat(projectOne(regionWithSlots("R", ratedSlots(4),
                    DisplayVerdict.WORTH_IT, null, "   ")).bestBet()).isNull();
        }

        @Test
        @DisplayName("the literal string \"null\" yields no Best Bet")
        void literalNullStringYieldsNoBestBet() {
            // Reachable, not paranoia: the gloss parser accepts a JSON node whose value is an
            // explicit null, and reading that as text gives the four-character string. A bare
            // != null check ships a card headlined "null".
            assertThat(projectOne(regionWithSlots("R", ratedSlots(4),
                    DisplayVerdict.WORTH_IT, null, "null")).bestBet()).isNull();
            assertThat(projectOne(regionWithSlots("R", ratedSlots(4),
                    DisplayVerdict.WORTH_IT, null, "NULL")).bestBet()).isNull();
        }

        @Test
        @DisplayName("a headline with no detail still yields a Best Bet")
        void headlinePresentDetailAbsent() {
            // A shipped state: the honesty filter writes a null headline with a detail, and the
            // mirror is reachable too. Keying on the headline is what keeps the canned failure
            // copy out of the recommendation slot.
            BriefingRegion r = new BriefingRegion("R", Verdict.GO, "summary", List.of(),
                    ratedSlots(4), 14.0, 13.0, 4.5, 3, HEADLINE, null,
                    DisplayVerdict.WORTH_IT, 1, null, false, null);

            assertThat(projectOne(r).bestBet().headline()).isEqualTo(HEADLINE);
            assertThat(projectOne(r).bestBet().detail()).isNull();
        }
    }

    // ── Also good ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Also good")
    class AlsoGood {

        @Test
        void isOfferedWhenTheSecondRegionClearsTheFloor() {
            BriefingWindow w = projectOne(region("Top", 4, 4), region("Second", 4, 3));

            assertThat(w.alsoGood()).isNotNull();
            assertThat(w.alsoGood().regionName()).isEqualTo("Second");
        }

        @Test
        void isRefusedWhenTheSecondRegionIsTooFarBehind() {
            BriefingWindow w = projectOne(region("Top", 5, 5), region("Second", 3, 3));

            assertThat(w.bestBet()).isNotNull();
            assertThat(w.alsoGood()).isNull();
        }

        @Test
        void isRefusedWhenTheSecondRegionIsBelowTheAbsoluteFloor() {
            BriefingWindow w = projectOne(region("Top", 3, 3), region("Second", 2, 3));

            assertThat(w.alsoGood()).isNull();
        }

        @Test
        @DisplayName("is refused when the second region has no usable gloss")
        void isRefusedWithoutAGloss() {
            // Names chosen so the alphabetical tie-break agrees with the intended rank: these two
            // tie on both average and coverage, so the name is what orders them.
            BriefingRegion second = regionWithSlots("B second", ratedSlots(4),
                    DisplayVerdict.WORTH_IT, null, null);

            BriefingWindow w = projectOne(region("A top", 4), second);

            assertThat(w.bestBet()).isNotNull();
            assertThat(w.alsoGood()).isNull();
        }

        @Test
        @DisplayName("is refused when there is no Best Bet — an alternative to nothing is incoherent")
        void isRefusedWithoutABestBet() {
            BriefingRegion top = regionWithSlots("A top", ratedSlots(4, 4),
                    DisplayVerdict.WORTH_IT, null, null);

            BriefingWindow w = projectOne(top, region("B second", 4, 4));

            assertThat(w.bestBet()).isNull();
            assertThat(w.alsoGood()).isNull();
        }

        @Test
        void isNeverAThirdRegion() {
            BriefingWindow w = projectOne(
                    region("A top", 4, 4), region("B second", 4, 4), region("C third", 4, 4));

            assertThat(w.alsoGood().regionName()).isEqualTo("B second");
        }

        @Test
        void isAbsentWhenOnlyOneRegionExists() {
            assertThat(projectOne(region("Only", 4)).alsoGood()).isNull();
        }
    }

    // ── Badges ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("badges")
    class Badges {

        @Test
        void aSunsetTopicLandsOnThatDaysSunsetOnly() {
            DailyBriefingResponse out = project(twoWindowDay(), List.of(topic("DUST", "SUNSET")));

            assertThat(windowOf(out, TargetType.SUNSET).badges()).hasSize(1);
            assertThat(windowOf(out, TargetType.SUNRISE).badges()).isEmpty();
        }

        @Test
        void aSunriseTopicLandsOnThatDaysSunriseOnly() {
            DailyBriefingResponse out =
                    project(twoWindowDay(), List.of(topic("INVERSION", "SUNRISE")));

            assertThat(windowOf(out, TargetType.SUNRISE).badges()).hasSize(1);
            assertThat(windowOf(out, TargetType.SUNSET).badges()).isEmpty();
        }

        @Test
        @DisplayName("a night topic lands on the evening's sunset and the next morning's sunrise")
        void aNightTopicSpansTheDateBoundary() {
            // A night runs across midnight — the topic itself carries an evening half and a
            // morning half — so anchoring it to one date alone loses half the display.
            List<BriefingDay> days = List.of(
                    new BriefingDay(TODAY, List.of(summary(TargetType.SUNSET))),
                    new BriefingDay(TODAY.plusDays(1), List.of(summary(TargetType.SUNRISE))));

            DailyBriefingResponse out = project(days, List.of(topic("NLC", "NIGHT")));

            assertThat(out.days().get(0).eventSummaries().get(0).window().badges()).hasSize(1);
            assertThat(out.days().get(1).eventSummaries().get(0).window().badges()).hasSize(1);
        }

        @Test
        @DisplayName("a night topic on the last day loses its morning half without error")
        void aNightTopicOnTheLastDayDropsTheMissingWindow() {
            DailyBriefingResponse out = project(
                    List.of(new BriefingDay(TODAY, List.of(summary(TargetType.SUNSET)))),
                    List.of(topic("AURORA", "NIGHT")));

            assertThat(out.days().get(0).eventSummaries().get(0).window().badges()).hasSize(1);
        }

        @Test
        @DisplayName("a topic with no solar anchor is bucketed nowhere")
        void aTopicWithNoEventTypeIsDropped() {
            // Storm surge and clearance carry no anchor at all, and a tide topic resolves one only
            // when its metrics say which event it aligns with.
            DailyBriefingResponse out =
                    project(twoWindowDay(), List.of(topic("STORM_SURGE", null)));

            assertThat(windowOf(out, TargetType.SUNSET).badges()).isEmpty();
            assertThat(windowOf(out, TargetType.SUNRISE).badges()).isEmpty();
        }

        @Test
        void aTopicWithNoDateIsDropped() {
            HotTopic undated = new HotTopic("DUST", "Sahara dust", "detail", null, 1,
                    null, List.of(), "desc", null, "SUNSET", "21:11", null, null, null,
                    null, null, null, null);

            DailyBriefingResponse out = project(twoWindowDay(), List.of(undated));

            assertThat(windowOf(out, TargetType.SUNSET).badges()).isEmpty();
        }

        @Test
        void carriesTheTopicsOwnLabelDetailAndTime() {
            DailyBriefingResponse out = project(twoWindowDay(), List.of(topic("DUST", "SUNSET")));

            BriefingWindow.Badge badge = windowOf(out, TargetType.SUNSET).badges().get(0);
            assertThat(badge.type()).isEqualTo("DUST");
            assertThat(badge.label()).isEqualTo("DUST label");
            assertThat(badge.detail()).isEqualTo("DUST detail");
            assertThat(badge.eventTime()).isEqualTo("21:11");
        }

        @Test
        void theRarestBadgeSetsTheWindowsRank() {
            DailyBriefingResponse out = project(twoWindowDay(),
                    List.of(topic("SPRING_TIDE", "SUNSET"), topic("SUPERMOON", "SUNSET")));

            BriefingWindow w = windowOf(out, TargetType.SUNSET);
            assertThat(w.badges()).hasSize(2);
            assertThat(w.topRarityRank()).isEqualTo(TopicRarity.rankOf("SUPERMOON"));
        }

        @Test
        void noBadgesMeansNoRank() {
            assertThat(projectOne(region("R", 4)).topRarityRank()).isNull();
        }
    }

    // ── Confidence ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("confidence")
    class ConfidenceChannel {

        @Test
        void isTheTopRegionsOwnConfidence() {
            BriefingRegion top = regionWithSlots("Top", ratedSlots(4, 4),
                    DisplayVerdict.WORTH_IT, Confidence.MEDIUM, HEADLINE);

            assertThat(projectOne(top).confidence()).isEqualTo(Confidence.MEDIUM);
        }

        @Test
        @DisplayName("it follows rank 1, never rank 2")
        void neverFallsThroughToTheSecondRegion() {
            // One field, one source. Falling through would report a confidence that describes a
            // region the card does not name.
            BriefingRegion top = regionWithSlots("A top", ratedSlots(4, 4),
                    DisplayVerdict.WORTH_IT, null, HEADLINE);
            BriefingRegion second = regionWithSlots("B second", ratedSlots(4, 4),
                    DisplayVerdict.WORTH_IT, Confidence.HIGH, HEADLINE);

            assertThat(projectOne(top, second).confidence()).isNull();
        }

        @Test
        @DisplayName("it survives the top region having no Best Bet")
        void isPublishedEvenWhenTheGlossIsMissing() {
            // The degraded case is exactly the one the channel exists for, so keying confidence on
            // the Best Bet rather than on rank 1 would drop it precisely when it matters.
            BriefingRegion top = regionWithSlots("Top", ratedSlots(4),
                    DisplayVerdict.WORTH_IT, Confidence.LOW, null);

            BriefingWindow w = projectOne(top);

            assertThat(w.bestBet()).isNull();
            assertThat(w.confidence()).isEqualTo(Confidence.LOW);
        }

        @Test
        void isNullWhenThereAreNoRegions() {
            assertThat(projectOne().confidence()).isNull();
        }
    }

    // ── Carriage ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("every event summary on the response gets a window")
    void everySummaryIsProjected() {
        // The ordering tripwire. Upstream construction sites legitimately produce a null window;
        // if the projector ever stops being last, this is what says so.
        DailyBriefingResponse out = project(twoWindowDay(), List.of());

        assertThat(out.days())
                .flatMap(BriefingDay::eventSummaries)
                .allSatisfy(es -> assertThat(es.window()).isNotNull());
    }

    @Test
    @DisplayName("projecting after the honesty filter describes the blanked region, not the raw one")
    void orderingRelativeToTheHonestyFilterIsLoadBearing() {
        // The class Javadoc says the projector must run last. This is the test that says so.
        //
        // A zero-coverage region reaches the serve path carrying a build-time gloss. The honesty
        // filter blanks it — empty slots, null headline — because there is nothing behind it. Run
        // in the right order, the window omits the Best Bet. Run in the wrong order, the window
        // confidently recommends a region the very same response reports as unevaluable, and it
        // renders perfectly: the only way to see it is to open the drill-down.
        BriefingRegion zeroCoverage = new BriefingRegion(
                "North East", Verdict.GO, "summary", List.of(),
                List.of(unratedSlot("Bamburgh"), unratedSlot("Alnmouth")),
                14.0, 13.0, 4.5, 3, HEADLINE, DETAIL,
                DisplayVerdict.WORTH_IT, 0, null, false, Confidence.HIGH);
        DailyBriefingResponse raw = response(
                List.of(new BriefingDay(TODAY, List.of(
                        new BriefingEventSummary(TargetType.SUNSET, List.of(zeroCoverage),
                                List.of())))),
                List.of());

        BriefingWindow wrongOrder = PlanWindowProjector.apply(raw)
                .days().get(0).eventSummaries().get(0).window();
        BriefingWindow rightOrder = PlanWindowProjector.apply(BriefingHonestyFilter.apply(raw))
                .days().get(0).eventSummaries().get(0).window();

        assertThat(wrongOrder.bestBet()).isNotNull();
        assertThat(rightOrder.bestBet()).isNull();
    }

    @Test
    void aNullResponsePassesThrough() {
        assertThat(PlanWindowProjector.apply(null)).isNull();
    }

    @Test
    void everyOtherComponentOfTheResponseSurvives() {
        DailyBriefingResponse in = response(twoWindowDay(), List.of(topic("DUST", "SUNSET")));

        DailyBriefingResponse out = PlanWindowProjector.apply(in);

        assertThat(out.headline()).isEqualTo(in.headline());
        assertThat(out.generatedAt()).isEqualTo(in.generatedAt());
        assertThat(out.hotTopics()).isEqualTo(in.hotTopics());
        assertThat(out.days()).hasSameSizeAs(in.days());
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private static BriefingWindow projectOne(BriefingRegion... regions) {
        DailyBriefingResponse out = project(
                List.of(new BriefingDay(TODAY, List.of(
                        new BriefingEventSummary(TargetType.SUNSET, List.of(regions), List.of())))),
                List.of());
        return out.days().get(0).eventSummaries().get(0).window();
    }

    private static DailyBriefingResponse project(List<BriefingDay> days, List<HotTopic> topics) {
        return PlanWindowProjector.apply(response(days, topics));
    }

    private static DailyBriefingResponse response(List<BriefingDay> days, List<HotTopic> topics) {
        return new DailyBriefingResponse(
                LocalDateTime.of(TODAY, LocalTime.NOON), "headline", days,
                List.of(), null, null, false, false, 0, null, topics, List.of());
    }

    private static List<BriefingDay> twoWindowDay() {
        return List.of(new BriefingDay(TODAY,
                List.of(summary(TargetType.SUNRISE), summary(TargetType.SUNSET))));
    }

    private static BriefingEventSummary summary(TargetType type) {
        return new BriefingEventSummary(type, List.of(region("R", 4)), List.of());
    }

    private static BriefingWindow windowOf(DailyBriefingResponse r, TargetType type) {
        return r.days().get(0).eventSummaries().stream()
                .filter(es -> es.targetType() == type)
                .findFirst().orElseThrow().window();
    }

    /** A glossed region whose slots carry the given ratings. */
    private static BriefingRegion region(String name, int... ratings) {
        return regionWithSlots(name, ratedSlots(ratings));
    }

    private static BriefingRegion regionWithSlots(String name, List<BriefingSlot> slots) {
        return regionWithSlots(name, slots, DisplayVerdict.WORTH_IT, Confidence.HIGH, HEADLINE);
    }

    private static BriefingRegion regionWithSlots(String name, List<BriefingSlot> slots,
            DisplayVerdict dv, Confidence confidence, String headline) {
        return new BriefingRegion(name, Verdict.GO, "summary", List.of(), slots,
                14.0, 13.0, 4.5, 3, headline, headline == null ? null : DETAIL,
                dv, slots.size(), null, false, confidence);
    }

    private static List<BriefingSlot> ratedSlots(int... ratings) {
        List<BriefingSlot> slots = new ArrayList<>();
        for (int i = 0; i < ratings.length; i++) {
            slots.add(ratedSlot("Loc" + i, ratings[i], false));
        }
        return slots;
    }

    private static BriefingSlot ratedSlot(String name, int rating, boolean canopy) {
        BriefingSlot base = canopy
                ? BriefingSlot.canopySlot(name, at(LocalTime.of(21, 11)), Verdict.GO, null,
                        List.of(), null)
                : new BriefingSlot(name, at(LocalTime.of(21, 11)), Verdict.GO, null,
                        BriefingSlot.TideInfo.NONE, List.of(), null);
        return base.withClaudeScores(rating, 60, 55, "summary");
    }

    private static BriefingSlot unratedSlot(String name) {
        return new BriefingSlot(name, at(LocalTime.of(21, 11)), Verdict.GO, null,
                BriefingSlot.TideInfo.NONE, List.of(), null);
    }

    private static BriefingSlot slotAt(String name, LocalTime time) {
        return new BriefingSlot(name, at(time), Verdict.GO, null,
                BriefingSlot.TideInfo.NONE, List.of(), null).withClaudeScores(4, 60, 55, "s");
    }

    private static LocalDateTime at(LocalTime time) {
        return LocalDateTime.of(TODAY, time);
    }

    private static HotTopic topic(String type, String eventType) {
        return new HotTopic(type, type + " label", type + " detail", TODAY, 1,
                null, List.of(), "desc", null, eventType, "21:11", null, null, null,
                null, null, null, null);
    }
}
