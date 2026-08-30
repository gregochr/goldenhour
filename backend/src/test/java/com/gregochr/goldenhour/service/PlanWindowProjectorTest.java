package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingDayPeak;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.BriefingWindowTide;
import com.gregochr.goldenhour.model.Confidence;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.HotTopicFact;
import com.gregochr.goldenhour.model.PlanRenderedEvent;
import com.gregochr.goldenhour.model.TideRunDay;
import com.gregochr.goldenhour.model.Verdict;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private static final long BEACH_ID = 42L;
    private static final long WOOD_ID = 7L;

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

            assertThat(w.pick().regionName()).isEqualTo("Northumberland");
        }

        @Test
        @DisplayName("a tie on average breaks on scored coverage, not on payload order")
        void tieBreaksOnScoredCoverage() {
            // Averages are rounded to one decimal place before comparison and there are only a
            // handful of regions, so ties are routine rather than an edge case.
            BriefingWindow w = projectOne(
                    region("Dales", 4, 4),
                    region("Northumberland", 4, 4, 4));

            assertThat(w.pick().regionName()).isEqualTo("Northumberland");
        }

        @Test
        @DisplayName("a tie on average and coverage breaks on name, so the order is stable")
        void tieBreaksOnNameLast() {
            BriefingWindow w = projectOne(
                    region("Northumberland", 4, 4),
                    region("Dales", 4, 4));

            assertThat(w.pick().regionName()).isEqualTo("Dales");
        }

        @Test
        @DisplayName("a region's display name never decides the window's verdict")
        void renamingARegionCannotChangeTheVerdict() {
            // With nothing rated, a blanked region (no slots) and a real one both compute to
            // average 0.0 and count 0. Before the content tie-break, the tie fell through to the
            // region's display STRING — so renaming a region flipped the same forecast's badge
            // between WORTH_IT and STAND_DOWN and removed the Best Bet block entirely.
            BriefingRegion blanked = regionWithSlots("Mmm blanked", List.of(),
                    DisplayVerdict.STAND_DOWN, null, null);

            DisplayVerdict early = verdictWithContentRegionNamed("Aaa woods", blanked);
            DisplayVerdict late = verdictWithContentRegionNamed("Zzz woods", blanked);

            assertThat(early).isEqualTo(DisplayVerdict.MAYBE);
            assertThat(late).isEqualTo(early);
        }

        /** Projects a window holding one unrated region with slots plus the given blanked one. */
        private DisplayVerdict verdictWithContentRegionNamed(String name, BriefingRegion blanked) {
            BriefingRegion withContent = regionWithSlots(name, List.of(unratedSlot("Loc")),
                    DisplayVerdict.MAYBE, null, HEADLINE);
            return projectOne(withContent, blanked).verdict();
        }

        @Test
        @DisplayName("a region blanked by the honesty filter ranks last")
        void emptyRegionRanksLast() {
            // The shape BriefingHonestyFilter.fullRewrite leaves behind: no slots at all. It ranks
            // last by arithmetic (average 0.0) rather than by a rule, so pin it.
            BriefingWindow w = projectOne(
                    regionWithSlots("Aaa blanked", List.of()),
                    region("Zzz scored", 3, 3));

            assertThat(w.pick().regionName()).isEqualTo("Zzz scored");
        }
    }

    // ── Best rating and verdict ──────────────────────────────────────────────

    @Nested
    @DisplayName("best rating and verdict")
    class RatingAndVerdict {

        @Test
        @DisplayName("the best rating is still a max across every region — and no longer decides "
                + "the badge")
        void bestRatingIsTheMaximumAcrossEveryRegion() {
            BriefingWindow w = projectOne(region("Dales", 2, 4), region("Coast", 3));

            assertThat(w.bestRating()).isEqualTo(4);
            // The top region is Dales (mean 3.0, two scored locations beating Coast's one on the
            // coverage tie-break), and Dales reads MAYBE. The 4★ inside it no longer promotes the
            // window. Restore the max rule and this reads WORTH_IT above a grid of MAYBE cells —
            // exactly the disagreement this phase removes.
            assertThat(w.verdict()).isEqualTo(DisplayVerdict.MAYBE);
        }

        // Each of the three below takes at least two slots, so the region's mean and the window's
        // best rating land in DIFFERENT bands. A single-slot fixture makes mean == max, at which
        // point the deleted rating-led rule and the region-led one agree at every integer — the
        // test would pass under either and advertise a protection it does not have.

        @Test
        @DisplayName("a region averaging four is Worth it")
        void regionMeanOfFourIsWorthIt() {
            // 5 and 3 → mean 4.0. Both rules say WORTH_IT here, and no fixture can separate them
            // in this band: mean ≥ 3.5 forces some slot ≥ 4, so a max-led rule reaches WORTH_IT
            // too. Stated rather than papered over — the discrimination lives in the two below and
            // in the band-edge quartet.
            assertThat(projectOne(region("R", 5, 3)).verdict()).isEqualTo(DisplayVerdict.WORTH_IT);
        }

        @Test
        @DisplayName("a region averaging three is Maybe")
        void regionMeanOfThreeIsMaybe() {
            // 4 and 2 → mean 3.0, max 4. The deleted rule reads the 4 and says WORTH_IT.
            assertThat(projectOne(region("R", 4, 2)).verdict()).isEqualTo(DisplayVerdict.MAYBE);
        }

        @Test
        @DisplayName("a region averaging two is Poor")
        void regionMeanOfTwoIsStandDown() {
            // 3 and 1 → mean 2.0, max 3. The deleted rule reads the 3 and says MAYBE.
            assertThat(projectOne(region("R", 3, 1)).verdict()).isEqualTo(DisplayVerdict.STAND_DOWN);
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
            // And the BADGE agrees with it, which it did not until the canopy fix. The region's
            // rating rollup is now taken over its voting slots (BriefingSlot.votingSlots), so this
            // window reads Maybe from the 3★ beach alone — not Worth it from a canopy-inclusive
            // mean of 4.0 that no surface on the card could account for. The 5★ wood is excluded
            // from the star here and from the spot strip by `windowFirstSpots.js`, so a badge
            // reading Worth it was a band above every rating the card could render.
            //
            // The two assertions are one claim: whatever the badge says, the card can show a slot
            // that justifies it.
            assertThat(w.verdict()).isEqualTo(DisplayVerdict.MAYBE);
        }

        @Test
        @DisplayName("a wood cannot lift a window's badge past the sky it shares a region with")
        void aCanopySlotCannotLiftTheBadgeAboveTheSky() {
            // The defect this closes, at its widest. Under the canopy-inclusive rollup the mean of
            // {5 wood, 2, 2} is 3.0 → MAYBE, while the sky alone is 2.0 → Poor: a whole band, on
            // the badge, the grid cell and the day card, from a slot whose GO means heavy cloud and
            // mist. bestRating is held at 2 to show the badge is not merely tracking the star —
            // both now describe the sky, and the wood keeps its slot in the drill-down regardless.
            BriefingRegion mixed = regionWithSlots("Mixed", List.of(
                    ratedSlot("Wood", 5, true),
                    ratedSlot("Beach", 2, false),
                    ratedSlot("Bay", 2, false)));

            BriefingWindow w = projectOne(mixed);

            assertThat(w.verdict()).isEqualTo(DisplayVerdict.STAND_DOWN);
            assertThat(w.bestRating()).isEqualTo(2);
        }

        @Test
        @DisplayName("the ranking and the published average read the same voting slots as the "
                + "region's own band")
        void rankingAndPickAverageReadTheVotingSlots() {
            // The projector recomputes a region's statistics to rank regions and to publish
            // Pick.averageRating. If that recompute counts canopy slots while the region's own
            // displayVerdict does not, two things break at once, and this fixture is built so a
            // single revert shows both.
            //
            // "A woods and sky" is one 5★ wood and one 2★ sky slot: voting mean 2.0, but 3.5 if the
            // wood is counted. "B sky only" is a flat 3.0 either way. So the wood decides WHICH
            // REGION IS TOP — and therefore which verdict the window shows and which region the
            // Best Bet names — and it decides what number the pick publishes, which is the same
            // number the grid cell prints as its star.
            BriefingRegion woodsAndSky = regionWithSlots("A woods and sky", List.of(
                    ratedSlot("Wood", 5, true),
                    ratedSlot("Beach", 2, false)));
            BriefingRegion skyOnly = regionWithSlots("B sky only", List.of(
                    ratedSlot("Fell", 3, false),
                    ratedSlot("Tarn", 3, false)));

            BriefingWindow w = projectOne(woodsAndSky, skyOnly);

            assertThat(w.pick().regionName()).isEqualTo("B sky only");
            assertThat(w.pick().averageRating()).isEqualTo(3.0);
            assertThat(w.verdict()).isEqualTo(DisplayVerdict.MAYBE);
        }

        @Test
        @DisplayName("an all-canopy region still votes, rather than losing its verdict")
        void anAllCanopyRegionFallsBackToItsWoods() {
            // The fallback half of the rule, and the reason it is not a plain filter. A region with
            // nothing but woods genuinely has a forecast; silence there would be a worse answer
            // than an inverted-polarity one, so the woods vote for want of anyone else.
            //
            // The woods are rated 2, and that is the whole test. At 5 this passed with the fallback
            // DELETED: empty voting stats fall through to the fixture's triage Verdict.GO, which
            // resolves to the same WORTH_IT the woods give, and bestRating is carried by the
            // projector's own canopyCounts fallback pinned elsewhere. Two mechanisms agreeing by
            // accident is not a test. At 2 they disagree — fallback present STAND_DOWN, absent
            // WORTH_IT from the triage — so only the rule under test can produce this answer.
            BriefingRegion woods = regionWithSlots("Woods", List.of(
                    ratedSlot("Wood", 2, true),
                    ratedSlot("Copse", 2, true)));

            BriefingWindow w = projectOne(woods);

            assertThat(w.verdict()).isEqualTo(DisplayVerdict.STAND_DOWN);
            assertThat(w.bestRating()).isEqualTo(2);
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
            // The star refuses it, and the badge is now the region card's own verdict in every
            // case — rated or not — so this fixture's explicit MAYBE is what the window reads.
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
            // The destination must refuse the same row the star did — it was still naming it.
            assertThat(projectOne(r).pick().locationName()).isEqualTo("Ok");
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
            assertThat(w.pick()).isNull();
            assertThat(w.badges()).isEmpty();
            assertThat(w.topRarityRank()).isNull();
        }
    }

    // ── Region-led verdict (plan §2, invariants §7.1 and §7.5) ───────────────

    /**
     * The decided semantics: a window's verdict word IS its top region's, and the best-spot rating
     * is a separate, labelled channel that moves it not at all.
     *
     * <p>The <b>four band-edge fixtures hold the best rating constant at 5</b> and move only the
     * region mean, so within that quartet nothing but the mean can explain a change of band. The
     * other three vary a different thing on purpose — the identity test moves which region is top
     * (its window-wide max is 4), the five-star test reproduces the observed screenshot, and the
     * legacy test nulls the {@code displayVerdict} component rather than any rating.
     *
     * <p><b>Measured, not asserted:</b> restoring the deleted
     * {@code DisplayVerdict.resolve(bestRating, null)} branch turns <b>six of these seven</b> red.
     * The survivor is {@code meanOnTheWorthItEdgeIsWorthIt}, and it cannot be otherwise: a mean
     * ≥ 3.5 forces some slot ≥ 4, so the rating-led rule reaches WORTH_IT there too. That fixture
     * pins the band constant (loosening {@code >= 3.5} to {@code > 3.5} kills it), not the rule.
     */
    @Nested
    @DisplayName("the verdict is the top region's own")
    class RegionLedVerdict {

        @Test
        @DisplayName("§7.1 — the window verdict equals the top region's displayVerdict")
        void windowVerdictEqualsTopRegionDisplayVerdict() {
            // Two things this fixture has to do at once. The top region is deliberately NOT first
            // in payload order — nothing upstream orders regions, so a projection reading
            // regions[0] would pass a single-region fixture. And the top region's own band
            // (mean 3.0 → MAYBE) is one the window's best rating cannot produce (max 4 → WORTH_IT),
            // so the equality below is broken by the deleted max rule rather than satisfied by it.
            BriefingRegion weaker = region("A poor coast", 1, 1);
            BriefingRegion top = region("B better dales", 2, 4);

            BriefingWindow w = projectOne(weaker, top);

            assertThat(w.verdict()).isEqualTo(top.displayVerdict());
            assertThat(w.verdict()).isNotEqualTo(weaker.displayVerdict());
        }

        @Test
        @DisplayName("a mean of exactly 3.5 is Worth it, with a 5★ spot in the window")
        void meanOnTheWorthItEdgeIsWorthIt() {
            BriefingWindow w = projectOne(region("R", 5, 5, 5, 4, 4, 3, 3, 2, 2, 2));

            assertThat(w.bestRating()).isEqualTo(5);
            assertThat(w.verdict()).isEqualTo(DisplayVerdict.WORTH_IT);
        }

        @Test
        @DisplayName("one tenth below that edge is Maybe — same 5★ spot, same spread")
        void meanOneStepBelowTheWorthItEdgeIsMaybe() {
            // 3.4. Identical max (5), identical min (2), identical roster size — only the mean
            // moves, so a failure here can only be the band or the rule that reads it.
            BriefingWindow w = projectOne(region("R", 5, 5, 5, 4, 4, 3, 2, 2, 2, 2));

            assertThat(w.bestRating()).isEqualTo(5);
            assertThat(w.verdict()).isEqualTo(DisplayVerdict.MAYBE);
        }

        @Test
        @DisplayName("a mean of exactly 2.5 is Maybe, with a 5★ spot in the window")
        void meanOnTheMaybeEdgeIsMaybe() {
            BriefingWindow w = projectOne(region("R", 5, 4, 3, 3, 2, 2, 2, 2, 1, 1));

            assertThat(w.bestRating()).isEqualTo(5);
            assertThat(w.verdict()).isEqualTo(DisplayVerdict.MAYBE);
        }

        @Test
        @DisplayName("one tenth below that edge is Poor — same 5★ spot, same spread")
        void meanOneStepBelowTheMaybeEdgeIsStandDown() {
            // 2.4, with the same max (5) and min (1) as the 2.5 fixture above it.
            BriefingWindow w = projectOne(region("R", 5, 4, 3, 2, 2, 2, 2, 2, 1, 1));

            assertThat(w.bestRating()).isEqualTo(5);
            assertThat(w.verdict()).isEqualTo(DisplayVerdict.STAND_DOWN);
        }

        @Test
        @DisplayName("§7.5 — the best spot is published, and says nothing about the verdict")
        void aFiveStarSpotIsPublishedWithoutPromotingAPoorRegion() {
            // The 2026-08-16 screenshot in one fixture: the row read "Worth it · best 4★" over a
            // grid of Poor cells. It now reads Poor, and the 5 is still on the payload for the
            // card's labelled `best spot 5★` chip to render.
            BriefingWindow w = projectOne(region("R", 5, 1, 1, 1));

            assertThat(w.verdict()).isEqualTo(DisplayVerdict.STAND_DOWN);
            assertThat(w.bestRating()).isEqualTo(5);
        }

        @Test
        @DisplayName("a region carrying no verdict at all is AWAITING, not its ratings' verdict")
        void aRegionWithNoDisplayVerdictIsAwaiting() {
            // Only a payload cached before the field existed can produce this. AWAITING is the
            // honest answer; falling back to the ratings would resurrect the rating-led rule for
            // exactly the payloads least able to justify it.
            BriefingRegion legacy = regionWithSlots("R", ratedSlots(5, 5),
                    null, Confidence.HIGH, HEADLINE);

            BriefingWindow w = projectOne(legacy);

            assertThat(w.verdict()).isEqualTo(DisplayVerdict.AWAITING);
            assertThat(w.bestRating()).isEqualTo(5);
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
        @DisplayName("an unregioned slot can set the header time")
        void anUnregionedSlotCanSetTheHeaderTime() {
            // earliestEventTime walks region slots AND unregioned ones; bestRating walks only
            // region slots. Nothing populated `unregioned`, so that deliberate asymmetry was
            // invisible and swapping one helper for the other would have gone unnoticed. A
            // location with no region FK lands here, and the event still belongs to it.
            BriefingRegion regioned = regionWithSlots("R", List.of(
                    slotAt("Inland", LocalTime.of(21, 14))));

            DailyBriefingResponse out = project(
                    List.of(new BriefingDay(TODAY, List.of(new BriefingEventSummary(
                            TargetType.SUNSET, List.of(regioned),
                            List.of(slotAt("Orphan", LocalTime.of(21, 8))))))),
                    List.of());

            assertThat(out.days().get(0).eventSummaries().get(0).window().eventTime())
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

            assertThat(w.pick().kind()).isEqualTo(BriefingWindow.PickKind.BEST);
            assertThat(w.pick().regionName()).isEqualTo("Northumberland");
            assertThat(w.pick().headline()).isEqualTo(HEADLINE);
            assertThat(w.pick().detail()).isEqualTo(DETAIL);
            assertThat(w.pick().averageRating()).isEqualTo(4.0);
        }

        @Test
        void namesTheRegionsHighestRatedLocation() {
            BriefingRegion r = regionWithSlots("R", List.of(
                    ratedSlot("Bothal Weir", 3, false),
                    ratedSlot("Blyth Beach", 5, false)));

            assertThat(projectOne(r).pick().locationName()).isEqualTo("Blyth Beach");
        }

        @Test
        @DisplayName("never names the wood the header just refused")
        void doesNotNameACanopySlotExcludedFromTheHeader() {
            // The header star and the "where to drive" line must read the same slot population.
            // Naming the wood under sky prose sends someone to a location chosen by the opposite
            // measure of a good morning.
            BriefingRegion mixed = regionWithSlots("Mixed", List.of(
                    identifiedSlot("Wood", 5, true, WOOD_ID),
                    identifiedSlot("Beach", 3, false, BEACH_ID)));

            BriefingWindow w = projectOne(mixed);

            assertThat(w.bestRating()).isEqualTo(3);
            assertThat(w.pick().locationName()).isEqualTo("Beach");
            // Id and name come from ONE slot — never Wood's id under Beach's name.
            assertThat(w.pick().locationId()).isEqualTo(BEACH_ID);
        }

        @Test
        @DisplayName("a slot with no id still publishes its name")
        void nameSurvivesAMissingLocationId() {
            // Slots cached before BriefingSlot carried an id deserialise with a null one, and the
            // name is the documented fallback.
            BriefingRegion r = regionWithSlots("R", List.of(ratedSlot("Loc", 4, false)));

            assertThat(r.slots().get(0).locationId()).isNull();
            assertThat(projectOne(r).pick().locationName()).isEqualTo("Loc");
            assertThat(projectOne(r).pick().locationId()).isNull();
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

            assertThat(w.pick().regionName()).isEqualTo("B sky");
            assertThat(w.pick().locationName()).isEqualTo("Beach");
            assertThat(w.bestRating()).isEqualTo(3);
        }

        @Test
        @DisplayName("an all-canopy window still gets its own Best Bet")
        void anAllCanopyWindowKeepsItsRegion() {
            // The exclusion is two-tier, not absolute: with no sky slot anywhere there is nothing
            // to protect, and dropping every region would blank a window that has real content.
            BriefingRegion woods = regionWithSlots("Woods", List.of(ratedSlot("Wood", 5, true)));

            BriefingWindow w = projectOne(woods);

            assertThat(w.pick().regionName()).isEqualTo("Woods");
            assertThat(w.pick().locationName()).isEqualTo("Wood");
            assertThat(w.bestRating()).isEqualTo(5);
        }

        @Test
        void namesNoLocationWhenNothingIsRated() {
            BriefingRegion r = regionWithSlots("R", List.of(unratedSlot("Loc")),
                    DisplayVerdict.MAYBE, null, HEADLINE);

            assertThat(projectOne(r).pick().locationName()).isNull();
        }

        @Test
        @DisplayName("a null headline yields no Best Bet, and the rest of the window survives")
        void nullHeadlineYieldsNoBestBet() {
            // The plan's mandated case: serve-time re-enrichment nulls the gloss when a region
            // crosses a verdict band. The card must omit the block, not substitute anything.
            BriefingRegion r = regionWithSlots("R", ratedSlots(4, 4),
                    DisplayVerdict.WORTH_IT, Confidence.HIGH, null);

            BriefingWindow w = projectOne(r);

            assertThat(w.pick()).isNull();
            assertThat(w.bestRating()).isEqualTo(4);
            assertThat(w.verdict()).isEqualTo(DisplayVerdict.WORTH_IT);
            assertThat(w.confidence()).isEqualTo(Confidence.HIGH);
        }

        @Test
        void blankHeadlineYieldsNoBestBet() {
            assertThat(projectOne(regionWithSlots("R", ratedSlots(4),
                    DisplayVerdict.WORTH_IT, null, "   ")).pick()).isNull();
        }

        @Test
        @DisplayName("the literal string \"null\" yields no Best Bet")
        void literalNullStringYieldsNoBestBet() {
            // Reachable, not paranoia: the gloss parser accepts a JSON node whose value is an
            // explicit null, and reading that as text gives the four-character string. A bare
            // != null check ships a card headlined "null".
            assertThat(projectOne(regionWithSlots("R", ratedSlots(4),
                    DisplayVerdict.WORTH_IT, null, "null")).pick()).isNull();
            assertThat(projectOne(regionWithSlots("R", ratedSlots(4),
                    DisplayVerdict.WORTH_IT, null, "NULL")).pick()).isNull();
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

            assertThat(projectOne(r).pick().headline()).isEqualTo(HEADLINE);
            assertThat(projectOne(r).pick().detail()).isNull();
        }
    }

    // ── Also good ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("forecast-wide picks")
    class Picks {

        @Test
        @DisplayName("the two picks can fall on different days")
        void picksSpanDays() {
            // The whole point of the reversal: picks rank windows against each other, so a Best Bet
            // on one day and an Also good on another is the normal shape, not an anomaly.
            DailyBriefingResponse out = projectDays(
                    dayOf(TODAY, region("Strong", 5, 5)),
                    dayOf(TODAY.plusDays(1), region("Nearly", 5, 4)));

            BriefingWindow d0 = firstWindow(out, 0);
            BriefingWindow d1 = firstWindow(out, 1);
            assertThat(d0.pick().kind()).isEqualTo(BriefingWindow.PickKind.BEST);
            assertThat(d1.pick().kind()).isEqualTo(BriefingWindow.PickKind.ALSO);
        }

        @Test
        @DisplayName("exactly two picks, even when a third window would clear the floor")
        void onlyTwoWindowsCarryAPick() {
            // Every candidate here clears AlsoGoodFloor against the leader (5.0 vs 4.5 vs 4.5), so
            // the floor cannot be what refuses the third — only the two-pick cap can. An earlier
            // fixture had the third at 4.0, which the floor rejected on its own, and the cap could
            // then be deleted with the whole suite green.
            DailyBriefingResponse out = projectDays(
                    dayOf(TODAY, region("A", 5, 5)),
                    dayOf(TODAY.plusDays(1), region("B", 5, 4)),
                    dayOf(TODAY.plusDays(2), region("C", 5, 4)));

            assertThat(picksOf(out))
                    .extracting(BriefingWindow.Pick::kind)
                    .containsExactlyInAnyOrder(
                            BriefingWindow.PickKind.BEST, BriefingWindow.PickKind.ALSO);
            // A window without a pick is not thereby a poor window.
            assertThat(firstWindow(out, 2).pick()).isNull();
            assertThat(firstWindow(out, 2).bestRating()).isEqualTo(5);
        }

        @Test
        @DisplayName("the runner-up must still clear the floor")
        void runnerUpMustClearTheFloor() {
            // Same rule as before the reversal; only the comparands changed.
            DailyBriefingResponse out = projectDays(
                    dayOf(TODAY, region("Strong", 5, 5)),
                    dayOf(TODAY.plusDays(1), region("Weak", 3, 3)));

            assertThat(firstWindow(out, 0).pick().kind()).isEqualTo(BriefingWindow.PickKind.BEST);
            assertThat(firstWindow(out, 1).pick()).isNull();
        }

        @Test
        @DisplayName("a window beyond the rail's horizon can never be picked")
        void picksNeverLandOnAnUnrenderedWindow() {
            // The briefing carries more days than the rail renders. A pick bound to a window with
            // no tile is a recommendation nobody can reach. One event per day, so the horizon here
            // reads as 6 dates — horizonCountsSixEventsNotFourDates above is the fixture that
            // exercises the events-vs-dates distinction itself with two events per day.
            DailyBriefingResponse out = projectDays(
                    dayOf(TODAY, region("Modest", 3, 3)),
                    dayOf(TODAY.plusDays(1), region("Modest2", 3, 3)),
                    dayOf(TODAY.plusDays(2), region("Modest3", 3, 3)),
                    dayOf(TODAY.plusDays(3), region("Modest4", 3, 3)),
                    dayOf(TODAY.plusDays(4), region("Modest5", 3, 3)),
                    dayOf(TODAY.plusDays(5), region("Modest6", 3, 3)),
                    dayOf(TODAY.plusDays(6), region("Brilliant", 5, 5)));

            assertThat(firstWindow(out, 6).pick()).isNull();
            assertThat(firstWindow(out, 0).pick().kind()).isEqualTo(BriefingWindow.PickKind.BEST);
            assertThat(picksOf(out)).hasSize(2);
        }

        @Test
        @DisplayName("a window whose top region has no gloss is not a candidate")
        void noGlossMeansNoCandidate() {
            BriefingRegion mute = regionWithSlots("Mute", ratedSlots(5, 5),
                    DisplayVerdict.WORTH_IT, null, null);

            DailyBriefingResponse out = projectDays(
                    dayOf(TODAY, mute),
                    dayOf(TODAY.plusDays(1), region("Spoken", 4, 4)));

            assertThat(firstWindow(out, 0).pick()).isNull();
            assertThat(firstWindow(out, 1).pick().kind()).isEqualTo(BriefingWindow.PickKind.BEST);
        }


    }

    @Nested
    @DisplayName("picks and time")
    class PicksAndTime {

        @Test
        @DisplayName("an elapsed window cannot be picked")
        void anElapsedWindowIsNotEligible() {
            // Under the per-window model a client could just drop a past window and lose nothing.
            // Forecast-wide, the runner-up is discarded at selection, so spending BEST on a window
            // that has already happened cannot be recovered client-side.
            DailyBriefingResponse out = projectAt(
                    List.of(dayOf(TODAY, region("Gone", 5, 5)),
                            dayOf(TODAY.plusDays(1), region("Ahead", 4, 4))),
                    List.of(),
                    LocalDateTime.of(TODAY, LocalTime.of(23, 0)));

            assertThat(firstWindow(out, 0).pick()).isNull();
            assertThat(firstWindow(out, 1).pick().kind()).isEqualTo(BriefingWindow.PickKind.BEST);
        }

        @Test
        @DisplayName("a window is still pickable through its afterglow")
        void afterglowKeepsAWindowEligible() {
            // 21:11 fixture event, 30 minutes of afterglow: at 21:40 it is gone, at 21:39 it is not.
            assertThat(pickedAt(LocalTime.of(21, 41))).isNotNull();   // exactly +30, still current
            assertThat(pickedAt(LocalTime.of(21, 42))).isNull();      // one minute past
        }

        private BriefingWindow.Pick pickedAt(LocalTime at) {
            return projectAt(List.of(dayOf(TODAY, region("R", 4, 4))), List.of(),
                    LocalDateTime.of(TODAY, at))
                    .days().get(0).eventSummaries().get(0).window().pick();
        }

        @Test
        @DisplayName("a window with no time at all counts as current, not as past")
        void aTimelessWindowIsStillEligible() {
            // Reading a missing time as elapsed would publish no picks at all for a briefing whose
            // slots happen to carry no solar time — a silent, total failure.
            BriefingRegion timeless = new BriefingRegion("R", Verdict.GO, "summary", List.of(),
                    List.of(new BriefingSlot("Loc", null, Verdict.GO, null,
                            BriefingSlot.TideInfo.NONE, List.of(), null)
                            .withClaudeScores(4, 60, 55, "s")),
                    14.0, 13.0, 4.5, 3, HEADLINE, DETAIL,
                    DisplayVerdict.WORTH_IT, 1, null, false, null);

            DailyBriefingResponse out = projectAt(List.of(dayOf(TODAY, timeless)), List.of(),
                    LocalDateTime.of(TODAY, LocalTime.of(23, 59)));

            assertThat(firstWindow(out, 0).pick()).isNotNull();
        }

        @Test
        @DisplayName("the horizon counts events, not dates — two events per day diverge from one")
        void horizonCountsSixEventsNotFourDates() {
            // horizonCountsSurvivingDates below uses dayOf(), which builds exactly one event per
            // day — so "first 4 dates" and "first 4 events" are numerically identical there, and
            // an 8-vs-6 (dates-vs-events) horizon bug is invisible. With two events per day the
            // two horizons diverge: 4 dates is 8 events, the shared 6-event constant is 3 dates
            // plus one. D3am below is the 7th event overall — inside the old 4-DATE cap, outside
            // the shared 6-EVENT cap — which is exactly how a BEST landed on an unrendered window
            // in prod on 2026-08-16.
            DailyBriefingResponse out = projectDays(
                    twoEventDayOf(TODAY, region("D0am", 3, 3), region("D0pm", 3, 3)),
                    twoEventDayOf(TODAY.plusDays(1), region("D1am", 3, 3), region("D1pm", 3, 3)),
                    twoEventDayOf(TODAY.plusDays(2), region("D2am", 3, 3), region("D2pm", 4, 4)),
                    twoEventDayOf(TODAY.plusDays(3), region("D3am", 5, 5), region("D3pm", 3, 3)));

            BriefingWindow d2pm = out.days().get(2).eventSummaries().get(1).window();
            BriefingWindow d3am = out.days().get(3).eventSummaries().get(0).window();

            // D3am rates highest of everyone (5.0) but sits at event index 6 — the 7th event,
            // beyond the first 6. It must never win a pick despite the highest rating in the
            // payload. D2pm (4.0), the highest-rated window inside the first 6 events, must win
            // instead.
            assertThat(d3am.pick()).isNull();
            assertThat(d2pm.pick()).isNotNull();
            assertThat(d2pm.pick().kind()).isEqualTo(BriefingWindow.PickKind.BEST);
        }

        @Test
        @DisplayName("the horizon counts surviving dates, not payload positions")
        void horizonCountsSurvivingDates() {
            // The day list is built from the BUILD day, so on a next-day serve days[0] is
            // yesterday. Counting positions would admit the elapsed day and exclude a rendered one
            // at the far end — here, the day-4 window that the rail does show.
            DailyBriefingResponse out = projectAt(
                    List.of(dayOf(TODAY, region("Yesterday", 3, 3)),
                            dayOf(TODAY.plusDays(1), region("D1", 3, 3)),
                            dayOf(TODAY.plusDays(2), region("D2", 3, 3)),
                            dayOf(TODAY.plusDays(3), region("D3", 3, 3)),
                            dayOf(TODAY.plusDays(4), region("D4", 5, 5))),
                    List.of(),
                    LocalDateTime.of(TODAY, LocalTime.of(23, 59)));

            assertThat(firstWindow(out, 0).pick()).isNull();
            assertThat(firstWindow(out, 4).pick().kind()).isEqualTo(BriefingWindow.PickKind.BEST);
        }

        @Test
        @DisplayName("ties break on the sooner event time")
        void tiesBreakOnEventTime() {
            DailyBriefingResponse out = projectDays(
                    dayOf(TODAY.plusDays(1), region("Later", 4, 4)),
                    dayOf(TODAY, region("Sooner", 4, 4)));

            assertThat(firstWindow(out, 1).pick().kind()).isEqualTo(BriefingWindow.PickKind.BEST);
        }
    }

    // ── Badges ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("badges")
    class Badges {

        @Test
        @DisplayName("carries the topic's note, recurrence line and safety warning onto the badge")
        void carriesEveryNullableStringOntoTheBadge() {
            // These three are consecutive nullable Strings at the end of a positional constructor,
            // so a transposition compiles and every other test stays green. Three DISTINCT values,
            // asserted individually, is what makes a swap fail here.
            //
            // Without this, replacing `topic.safetyNote()` with `null` in the projector left the
            // entire backend suite green, the entire frontend suite green (its fixtures hand-build
            // badges), and the eye-safety warning gone from every v2 surface — the window card and
            // the promoted strip both render from badges and never join back to the topic list.
            HotTopic eclipse = topic("ECLIPSE", "SUNSET")
                    .withScience(List.of(), "look low to the west")
                    .withRarity("nothing comparable until 2081")
                    .withSafety("Certified solar filter on the lens");

            DailyBriefingResponse out = project(twoWindowDay(), List.of(eclipse));

            BriefingWindow.Badge badge = windowOf(out, TargetType.SUNSET).badges().get(0);
            assertThat(badge.note()).isEqualTo("look low to the west");
            assertThat(badge.rarityNote()).isEqualTo("nothing comparable until 2081");
            assertThat(badge.safetyNote()).isEqualTo("Certified solar filter on the lens");
        }

        @Test
        @DisplayName("leaves all three null for a topic that carries none")
        void leavesTheNullableStringsNullWhenTheTopicHasNone() {
            DailyBriefingResponse out = project(twoWindowDay(), List.of(topic("DUST", "SUNSET")));

            BriefingWindow.Badge badge = windowOf(out, TargetType.SUNSET).badges().get(0);
            assertThat(badge.note()).isNull();
            assertThat(badge.rarityNote()).isNull();
            assertThat(badge.safetyNote()).isNull();
        }

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
        @DisplayName("a window-scoped topic with no solar anchor is bucketed nowhere")
        void aTopicWithNoEventTypeIsDropped() {
            // Storm surge and clearance carry no anchor at all. NOT a tide: a null eventType there
            // means the alignment has passed or no water reached the light, which says nothing
            // about whether the day is tidal — see the day-scoped tests below.
            DailyBriefingResponse out =
                    project(twoWindowDay(), List.of(topic("STORM_SURGE", null)));

            assertThat(windowOf(out, TargetType.SUNSET).badges()).isEmpty();
            assertThat(windowOf(out, TargetType.SUNRISE).badges()).isEmpty();
        }

        // ── Day-scoped topics (tides) ────────────────────────────────────────

        @Test
        @DisplayName("a spring tide lands on BOTH of its day's windows")
        void aTideTopicSpansBothWindowsOfItsDay() {
            // "Spring tide" is a claim about the day. Placing it by `alignedEvent` labelled a
            // day-level fact with a window-level one, so an evening card on a spring run showed
            // no tide at all.
            HotTopic tide = topic("SPRING_TIDE", "SUNRISE")
                    .withTideRun(tideRun("high water 22m before sunrise",
                            "high water 1h49 before sunset"));

            DailyBriefingResponse out = project(twoWindowDay(), List.of(tide));

            assertThat(windowOf(out, TargetType.SUNRISE).badges()).hasSize(1);
            assertThat(windowOf(out, TargetType.SUNSET).badges()).hasSize(1);
        }

        @Test
        @DisplayName("a king tide spans both windows on the same rule as a spring tide")
        void aKingTideSpansBothWindowsToo() {
            // The two strategies share every piece of alignment machinery, so a fix that reached
            // only one of them would be a coincidence of type strings. Asserted separately because
            // that string is the only thing distinguishing them here.
            HotTopic tide = topic("KING_TIDE", "SUNSET")
                    .withTideRun(tideRun("low water 2h10 after sunrise",
                            "high water 29m before sunset"));

            DailyBriefingResponse out = project(twoWindowDay(), List.of(tide));

            assertThat(windowOf(out, TargetType.SUNRISE).badges()).hasSize(1);
            assertThat(windowOf(out, TargetType.SUNSET).badges()).hasSize(1);
        }

        @Test
        @DisplayName("the non-aligned window states its own water, not the alignment sentence")
        void theNonAlignedWindowCarriesItsOwnNeutralDetail() {
            // The whole reason the badge is built per key. One shared instance printed
            // "tide aligned with sunrise at 47 of 61 coastal locations" onto an evening card.
            HotTopic tide = topic("SPRING_TIDE", "SUNRISE")
                    .withTideRun(tideRun("high water 22m before sunrise",
                            "high water 1h49 before sunset"));

            DailyBriefingResponse out = project(twoWindowDay(), List.of(tide));

            assertThat(windowOf(out, TargetType.SUNRISE).badges().get(0).detail())
                    .isEqualTo("SPRING_TIDE detail");
            assertThat(windowOf(out, TargetType.SUNSET).badges().get(0).detail())
                    .isEqualTo("high water 1h49 before sunset");
        }

        @Test
        @DisplayName("the non-aligned window's eventTime moves to that window's own solar time")
        void theNonAlignedWindowCarriesItsOwnEventTime() {
            // Held to the same standard as the detail: a badge saying 06:09 on an evening card is
            // a falsehood on the wire even while nothing on the Plan surface reads it.
            HotTopic tide = topic("SPRING_TIDE", "SUNRISE")
                    .withTideRun(tideRun("high water 22m before sunrise",
                            "high water 1h49 before sunset"));

            DailyBriefingResponse out = project(twoWindowDay(), List.of(tide));

            assertThat(windowOf(out, TargetType.SUNRISE).badges().get(0).eventTime())
                    .isEqualTo("21:11");
            assertThat(windowOf(out, TargetType.SUNSET).badges().get(0).eventTime())
                    .isEqualTo("20:03");
        }

        @Test
        @DisplayName("a tide whose alignment has already passed still badges both windows")
        void aTideWithNoEventTypeStillSpansItsDay() {
            // The bug this fix is really about. `HotTopicEventEnricher` nulls eventType once the
            // aligned event is behind us, and the old guard dropped the whole topic — so a spring
            // day carried no tide indication anywhere from mid-morning onwards.
            HotTopic tide = topic("SPRING_TIDE", null)
                    .withTideRun(tideRun("high water 53m before sunrise",
                            "high water 2h24 before sunset"));

            DailyBriefingResponse out = project(twoWindowDay(), List.of(tide));

            assertThat(windowOf(out, TargetType.SUNRISE).badges()).hasSize(1);
            assertThat(windowOf(out, TargetType.SUNSET).badges()).hasSize(1);
        }

        @Test
        @DisplayName("with no aligned event, BOTH windows take the neutral wording")
        void aTideWithNoEventTypeNeutralisesBothWindows() {
            // There is no aligned window to protect, so neither window may keep the topic's own
            // "alignment already passed" sentence — on an evening card that denies an alignment
            // belonging to a morning that is gone.
            HotTopic tide = topic("SPRING_TIDE", null)
                    .withTideRun(tideRun("high water 53m before sunrise",
                            "high water 2h24 before sunset"));

            DailyBriefingResponse out = project(twoWindowDay(), List.of(tide));

            assertThat(windowOf(out, TargetType.SUNRISE).badges().get(0).detail())
                    .isEqualTo("high water 53m before sunrise");
            assertThat(windowOf(out, TargetType.SUNSET).badges().get(0).detail())
                    .isEqualTo("high water 2h24 before sunset");
        }

        @Test
        @DisplayName("a tide with no run row still spans, falling back to its own detail")
        void aTideWithNoRunRowFallsBackToTheTopicDetail() {
            // A tidal day whose tide could not be derived, and a payload cached before
            // sunriseWater existed. The day-level claim survives; only the per-window sentence is
            // lost, which over-states nothing.
            DailyBriefingResponse out =
                    project(twoWindowDay(), List.of(topic("SPRING_TIDE", "SUNRISE")));

            assertThat(windowOf(out, TargetType.SUNRISE).badges().get(0).detail())
                    .isEqualTo("SPRING_TIDE detail");
            assertThat(windowOf(out, TargetType.SUNSET).badges().get(0).detail())
                    .isEqualTo("SPRING_TIDE detail");
        }

        @Test
        @DisplayName("a run row with a null water phrase falls back rather than blanking the detail")
        void aNullWaterPhraseFallsBackToTheTopicDetail() {
            // waterAt returns null for a day carrying no extremes at all. A blank detail would be
            // a badge with a label and nothing under it.
            HotTopic tide = topic("SPRING_TIDE", "SUNRISE").withTideRun(tideRun(null, null));

            DailyBriefingResponse out = project(twoWindowDay(), List.of(tide));

            assertThat(windowOf(out, TargetType.SUNSET).badges().get(0).detail())
                    .isEqualTo("SPRING_TIDE detail");
        }

        @Test
        void aTopicWithNoDateIsDropped() {
            HotTopic undated = new HotTopic("DUST", "Sahara dust", "detail", null, 1,
                    null, List.of(), "desc", null, "SUNSET", "21:11", null, null, null,
                    null, null, null, null, null, null);

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
        @DisplayName("the topic's facts are copied verbatim — they decide badge or attribute row")
        void carriesTheTopicsOwnFacts() {
            // The Plan card promotes a topic to a full-width row only when it carries facts, so
            // dropping them here would silently demote every snow topic back to a header chip.
            // Verbatim, and never re-derived: a badge that reworded a fact could disagree with the
            // same topic's pill in the same response.
            HotTopic snow = topic("SNOW_TOPS", "SUNRISE")
                    .withScience(List.of(HotTopicFact.metric("snow line", "~650 m"),
                            new HotTopicFact(null, "240 m below the tops", null, false, true)),
                            "shoot from low ground");

            BriefingWindow.Badge badge =
                    windowOf(project(twoWindowDay(), List.of(snow)), TargetType.SUNRISE).badges()
                            .get(0);

            assertThat(badge.facts()).containsExactly(
                    HotTopicFact.metric("snow line", "~650 m"),
                    new HotTopicFact(null, "240 m below the tops", null, false, true));
        }

        @Test
        @DisplayName("a topic with no facts yields an empty list, never null")
        void aTopicWithNoScienceCarriesNoFacts() {
            // topic() never calls withScience, so its facts are null. The client's promotion rule
            // is a length check; a null here would make it a null check in two places instead.
            DailyBriefingResponse out = project(twoWindowDay(), List.of(topic("DUST", "SUNSET")));

            assertThat(windowOf(out, TargetType.SUNSET).badges().get(0).facts()).isEmpty();
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

            assertThat(w.pick()).isNull();
            assertThat(w.confidence()).isEqualTo(Confidence.LOW);
        }

        @Test
        void isNullWhenThereAreNoRegions() {
            assertThat(projectOne().confidence()).isNull();
        }
    }

    // ── The rendered list and the day peak (Phase 3: one owner per derivation) ──

    /**
     * The two derivations Phase 3 moved off the client, and the invariant that binds them.
     *
     * <p>The client used to keep its own six-event cap, its own pastness rule and its own day-card
     * roll-up, while the backend scoped the picks to a set derived separately. Two answers to
     * "which events exist" is how a BEST pick came to name a window with no card, so these tests
     * assert the three consumers of the horizon — the published list, the pick scope and the day
     * peak — against <em>one</em> fixture rather than each against its own.
     */
    @Nested
    @DisplayName("the rendered event list and the day peak")
    class RenderedListAndDayPeak {

        @Test
        @DisplayName("it publishes the leading six events, in payload order")
        void publishesTheLeadingSixEventsInOrder() {
            // Four two-event days is eight events. The 7th and 8th must not appear, and the six
            // that do must be in the order the payload carries them, not any ranking order — the
            // highest-rated window here is deliberately the last one published.
            DailyBriefingResponse out = projectDays(
                    twoEventDayOf(TODAY, region("D0am", 3, 3), region("D0pm", 3, 3)),
                    twoEventDayOf(TODAY.plusDays(1), region("D1am", 3, 3), region("D1pm", 3, 3)),
                    twoEventDayOf(TODAY.plusDays(2), region("D2am", 3, 3), region("D2pm", 5, 5)),
                    twoEventDayOf(TODAY.plusDays(3), region("D3am", 5, 5), region("D3pm", 3, 3)));

            assertThat(out.renderedEvents()).containsExactly(
                    new PlanRenderedEvent(TODAY, TargetType.SUNRISE),
                    new PlanRenderedEvent(TODAY, TargetType.SUNSET),
                    new PlanRenderedEvent(TODAY.plusDays(1), TargetType.SUNRISE),
                    new PlanRenderedEvent(TODAY.plusDays(1), TargetType.SUNSET),
                    new PlanRenderedEvent(TODAY.plusDays(2), TargetType.SUNRISE),
                    new PlanRenderedEvent(TODAY.plusDays(2), TargetType.SUNSET));
        }

        @Test
        @DisplayName("one elapsed event spends no slot, so the horizon reaches one event further")
        void anElapsedEventDoesNotSpendASlot() {
            // Eight events, served at 12:00 — after the 05:00 sunrise plus its 30-minute afterglow
            // and well before the 21:11 sunset. EXACTLY ONE event is past, which is what makes the
            // far end move by exactly one: without the pastness filter the list would end at D2pm.
            // The mixed-time fixture is load-bearing here; twoEventDayOf puts both events at 21:11,
            // so any clock past one is past both and the assertion would hold for the wrong reason.
            DailyBriefingResponse out = projectAt(List.of(
                    dawnAndDuskDayOf(TODAY, region("D0am", 3, 3), region("D0pm", 3, 3)),
                    dawnAndDuskDayOf(TODAY.plusDays(1), region("D1am", 3, 3), region("D1pm", 3, 3)),
                    dawnAndDuskDayOf(TODAY.plusDays(2), region("D2am", 3, 3), region("D2pm", 3, 3)),
                    dawnAndDuskDayOf(TODAY.plusDays(3), region("D3am", 3, 3), region("D3pm", 3, 3))),
                    List.of(), LocalDateTime.of(TODAY, LocalTime.NOON));

            assertThat(out.renderedEvents())
                    .doesNotContain(new PlanRenderedEvent(TODAY, TargetType.SUNRISE))
                    .startsWith(new PlanRenderedEvent(TODAY, TargetType.SUNSET))
                    .endsWith(new PlanRenderedEvent(TODAY.plusDays(3), TargetType.SUNRISE))
                    .hasSize(6);
        }

        @Test
        @DisplayName("§7.3 — no pick names a window outside the published list")
        void everyPickLandsOnAPublishedEvent() {
            // One fixture, both consumers. The projector could satisfy either alone by deriving the
            // horizon twice; asserting them against each other is what makes that impossible.
            DailyBriefingResponse out = projectDays(
                    twoEventDayOf(TODAY, region("D0am", 3, 3), region("D0pm", 3, 3)),
                    twoEventDayOf(TODAY.plusDays(1), region("D1am", 4, 4), region("D1pm", 3, 3)),
                    twoEventDayOf(TODAY.plusDays(2), region("D2am", 3, 3), region("D2pm", 4, 3)),
                    twoEventDayOf(TODAY.plusDays(3), region("D3am", 5, 5), region("D3pm", 5, 5)));

            List<PlanRenderedEvent> pickedAt = out.days().stream()
                    .flatMap(d -> d.eventSummaries().stream()
                            .filter(es -> es.window().pick() != null)
                            .map(es -> new PlanRenderedEvent(d.date(), es.targetType())))
                    .toList();

            assertThat(pickedAt).isNotEmpty();
            assertThat(out.renderedEvents()).containsAll(pickedAt);
        }

        @Test
        @DisplayName("a window whose slots were withdrawn is still timed, from the summary's own clock")
        void aSlotlessWindowUsesTheSummarysOwnEventTime() {
            // The honesty filter empties a zero-coverage region's slot list while leaving the
            // summary's solarEventTime intact. A scan of slots alone then reads that window as
            // timeless — and a timeless window counts as CURRENT, so it spends one of the six
            // rendered slots on an event that may be hours past, and the far end of the horizon
            // loses a day. Delete the fallback and this event is rendered.
            BriefingEventSummary blanked = new BriefingEventSummary(
                    TargetType.SUNRISE, List.of(regionWithSlots("Blanked", List.of())), List.of(),
                    LocalDateTime.of(TODAY, LocalTime.of(5, 0)), null);
            BriefingDay day = new BriefingDay(TODAY, List.of(
                    blanked,
                    new BriefingEventSummary(TargetType.SUNSET,
                            shiftRegions(TODAY, region("Dusk", 4, 4)), List.of())));

            DailyBriefingResponse out = projectAt(List.of(day), List.of(),
                    LocalDateTime.of(TODAY, LocalTime.NOON));

            assertThat(out.renderedEvents())
                    .containsExactly(new PlanRenderedEvent(TODAY, TargetType.SUNSET));
        }

        @Test
        @DisplayName("with every event elapsed the list is empty, never absent")
        void anAllPastForecastPublishesAnEmptyList() {
            // Empty and absent mean different things to the client — absent is "nothing projected"
            // and degrades to its own walk of the days, empty is "no live window" and draws
            // nothing. A forecast that has entirely passed must say the second.
            DailyBriefingResponse out = projectAt(
                    List.of(dayOf(TODAY, region("Gone", 4, 4))), List.of(),
                    LocalDateTime.of(TODAY, LocalTime.of(23, 59)));

            assertThat(out.renderedEvents()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("the day peak is Worth it when any rendered region reaches it")
        void dayPeakTakesTheBestBandAnyRegionReaches() {
            BriefingDay day = new BriefingDay(TODAY, List.of(new BriefingEventSummary(
                    TargetType.SUNSET,
                    List.of(region("Poor coast", 1, 1), region("Good dales", 4, 4)),
                    List.of())));

            BriefingDayPeak peak = projectDays(day).days().get(0).peak();

            assertThat(peak.verdict()).isEqualTo(DisplayVerdict.WORTH_IT);
            assertThat(peak.regions()).extracting(BriefingDayPeak.PeakRegion::regionName)
                    .containsExactly("Good dales");
            assertThat(peak.events()).containsExactly(TargetType.SUNSET);
        }

        @Test
        @DisplayName("a day whose best region is only Maybe reads Maybe, not Worth it")
        void dayPeakFallsToMaybe() {
            BriefingDayPeak peak = projectDays(dayOf(TODAY, region("Middling", 3, 3)))
                    .days().get(0).peak();

            assertThat(peak.verdict()).isEqualTo(DisplayVerdict.MAYBE);
            assertThat(peak.regions()).hasSize(1);
        }

        @Test
        @DisplayName("a day with nothing at either band is Poor and names no region")
        void dayPeakWithNothingRatedIsStandDown() {
            // The tile's "All poor". Empty regions rather than a STAND_DOWN entry: the chips render
            // what reached the peak, and on a poor day nothing did.
            BriefingDayPeak peak = projectDays(dayOf(TODAY, region("Dismal", 1, 1)))
                    .days().get(0).peak();

            assertThat(peak.verdict()).isEqualTo(DisplayVerdict.STAND_DOWN);
            assertThat(peak.regions()).isEmpty();
            assertThat(peak.events()).isEmpty();
        }

        @Test
        @DisplayName("it names both events only when the peak was reached on both")
        void dayPeakNamesTheEventsItsRegionsReachedItOn() {
            // Same band on both halves of the day → "both". The sunrise region is deliberately a
            // DIFFERENT region, so this cannot pass by one region being counted twice.
            BriefingDay bothWorthIt = twoEventDayOf(TODAY,
                    region("Dawn dales", 4, 4), region("Dusk coast", 4, 4));
            // And one half only → that event alone. The other half is present and Poor, so the
            // event set is doing the discriminating rather than the day simply having one event.
            BriefingDay sunsetOnly = twoEventDayOf(TODAY.plusDays(1),
                    region("Dawn dales", 1, 1), region("Dusk coast", 4, 4));

            DailyBriefingResponse out = projectDays(bothWorthIt, sunsetOnly);

            assertThat(out.days().get(0).peak().events())
                    .containsExactlyInAnyOrder(TargetType.SUNRISE, TargetType.SUNSET);
            assertThat(out.days().get(1).peak().events()).containsExactly(TargetType.SUNSET);
        }

        @Test
        @DisplayName("a region at both bands appears once, at its better one — either way round")
        void aRegionAtBothBandsAppearsOnceAtItsBetter() {
            // The tile names a region's best showing that day; listing it twice would put two chips
            // for one place on one tile, and taking whichever event came second would decide it on
            // payload order.
            //
            // BOTH orders, and the second is the one that does the work. A served payload always
            // carries sunrise first (BriefingHierarchyBuilder iterates SUNRISE then SUNSET), so a
            // "keep the first one seen" simplification passes the better-at-sunrise case for the
            // wrong reason — the outranking clause is only visible when the better band arrives
            // second. Everything else is held constant across the pair: same region name, same two
            // bands, same day shape.
            BriefingDayPeak betterFirst = projectDays(twoEventDayOf(TODAY,
                    region("Coast", 4, 4), region("Coast", 3, 3))).days().get(0).peak();
            BriefingDayPeak betterSecond = projectDays(twoEventDayOf(TODAY,
                    region("Coast", 3, 3), region("Coast", 4, 4))).days().get(0).peak();

            assertThat(betterFirst.verdict()).isEqualTo(DisplayVerdict.WORTH_IT);
            assertThat(betterFirst.regions()).extracting(
                            BriefingDayPeak.PeakRegion::regionName,
                            BriefingDayPeak.PeakRegion::targetType)
                    .containsExactly(tuple("Coast", TargetType.SUNRISE));

            assertThat(betterSecond.verdict()).isEqualTo(DisplayVerdict.WORTH_IT);
            assertThat(betterSecond.regions()).extracting(
                            BriefingDayPeak.PeakRegion::regionName,
                            BriefingDayPeak.PeakRegion::targetType)
                    .containsExactly(tuple("Coast", TargetType.SUNSET));
        }

        @Test
        @DisplayName("one region at the peak on both events names ONE of them — the known "
                + "under-report, pinned so it is not silently 'fixed'")
        void aSingleRegionAtBothEventsNamesOnlyTheFirst() {
            // A documented deliberate cost with no test is a cost the next reader corrects. The
            // day's only peak-band region is Worth it at BOTH events, so "· both" is arguably the
            // truer word — but a region appears once (the tile names its best showing, not one chip
            // per event), and the client roll-up this replaced behaved identically. The day card is
            // specified as an ownership move with no semantic change, so moving it here would move
            // v2 away from the v1 control mid-pilot. See BriefingDayPeak's Javadoc.
            BriefingDayPeak peak = projectDays(twoEventDayOf(TODAY,
                    region("Coast", 4, 4), region("Coast", 4, 4))).days().get(0).peak();

            assertThat(peak.verdict()).isEqualTo(DisplayVerdict.WORTH_IT);
            assertThat(peak.regions()).hasSize(1);
            assertThat(peak.events()).containsExactly(TargetType.SUNRISE);
        }

        @Test
        @DisplayName("it rolls up only the events the same response says are rendered")
        void dayPeakIgnoresAnEventTheHorizonDropped() {
            // The elapsed sunrise is the day's only good window. A roll-up over the DAY reads
            // "Worth it" for a window that has no card; over the RENDERED events it reads Poor,
            // which is what the reader can still act on. Same clock as the horizon test above.
            DailyBriefingResponse out = projectAt(List.of(dawnAndDuskDayOf(TODAY,
                    region("Brilliant dawn", 5, 5), region("Dismal dusk", 1, 1))),
                    List.of(), LocalDateTime.of(TODAY, LocalTime.NOON));

            assertThat(out.renderedEvents())
                    .containsExactly(new PlanRenderedEvent(TODAY, TargetType.SUNSET));
            assertThat(out.days().get(0).peak().verdict()).isEqualTo(DisplayVerdict.STAND_DOWN);
        }

        @Test
        @DisplayName("a day outside the horizon carries no peak at all")
        void anUnrenderedDayHasNoPeak() {
            // Null, not STAND_DOWN. The tile is not drawn for this day, and a Poor roll-up for a
            // day nothing renders is a claim about weather where the honest answer is "not shown".
            DailyBriefingResponse out = projectDays(
                    twoEventDayOf(TODAY, region("D0am", 4, 4), region("D0pm", 4, 4)),
                    twoEventDayOf(TODAY.plusDays(1), region("D1am", 4, 4), region("D1pm", 4, 4)),
                    twoEventDayOf(TODAY.plusDays(2), region("D2am", 4, 4), region("D2pm", 4, 4)),
                    twoEventDayOf(TODAY.plusDays(3), region("D3am", 4, 4), region("D3pm", 4, 4)));

            assertThat(out.days().get(2).peak()).isNotNull();
            assertThat(out.days().get(3).peak()).isNull();
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

        BriefingWindow wrongOrder = PlanWindowProjector.apply(raw, NOW, Map.of())
                .days().get(0).eventSummaries().get(0).window();
        BriefingWindow rightOrder =
                PlanWindowProjector.apply(BriefingHonestyFilter.apply(raw), NOW, Map.of())
                        .days().get(0).eventSummaries().get(0).window();

        assertThat(wrongOrder.pick()).isNotNull();
        assertThat(rightOrder.pick()).isNull();
    }

    // ── The tide rollup ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("the tide rollup")
    class Tide {

        @Test
        @DisplayName("each window carries the rollup keyed to its own date and event")
        void eachWindowCarriesItsOwnRollup() {
            // The rollup is derived by a component and handed in, so the projector stays a pure
            // function of the response. What must hold is that the two agree about which window is
            // which: a key built from a different notion of window identity would silently attach
            // the sunrise's tide to the sunset.
            BriefingWindowTide sunrise = tide("HW", "05:44");
            BriefingWindowTide sunset = tide("LW", "19:28");

            DailyBriefingResponse out = PlanWindowProjector.apply(
                    response(twoWindowDay(), List.of()), NOW,
                    Map.of(new PlanWindowProjector.WindowKey(TODAY, TargetType.SUNRISE), sunrise,
                            new PlanWindowProjector.WindowKey(TODAY, TargetType.SUNSET), sunset));

            List<BriefingEventSummary> summaries = out.days().get(0).eventSummaries();
            assertThat(summaries.get(0).targetType()).isEqualTo(TargetType.SUNRISE);
            assertThat(summaries.get(0).window().tide()).isEqualTo(sunrise);
            assertThat(summaries.get(1).window().tide()).isEqualTo(sunset);
        }

        @Test
        @DisplayName("a window with no rollup carries a null tide, and everything else")
        void aWindowWithoutARollupIsOtherwiseWhole() {
            // tide_extreme reaches months ahead but a single date can still be undrawable, and an inland
            // deployment has no coastal roster at all. Neither may cost the window its verdict,
            // its rating or its pick — the row simply falls back to its per-slot facts.
            BriefingWindow w = projectOne(region("Northumberland", 4, 4, 4));

            assertThat(w.tide()).isNull();
            assertThat(w.verdict()).isEqualTo(DisplayVerdict.WORTH_IT);
            assertThat(w.bestRating()).isEqualTo(4);
            assertThat(w.pick()).isNotNull();
        }

        @Test
        @DisplayName("a rollup keyed to a window the response does not contain is dropped")
        void aRollupForAnAbsentWindowIsIgnored() {
            // The map is built from the same day list the windows are projected from, so this
            // cannot arise on the serve path — but a projector that scattered unmatched entries
            // anywhere would be a hard defect to see, since every rendered window would look fine.
            DailyBriefingResponse out = PlanWindowProjector.apply(
                    response(twoWindowDay(), List.of()), NOW,
                    Map.of(new PlanWindowProjector.WindowKey(TODAY.plusDays(9), TargetType.SUNSET),
                            tide("LW", "19:28")));

            assertThat(out.days().get(0).eventSummaries())
                    .allSatisfy(es -> assertThat(es.window().tide()).isNull());
        }

        private BriefingWindowTide tide(String nearestType, String nearestTime) {
            return new BriefingWindowTide("Whitby", TideState.MID,
                    BriefingWindowTide.Direction.FALLING, nearestType, nearestTime,
                    "1h43 before sunset", "4.9 m", "1.2 m above an average tide", null,
                    List.of(0.0, 0.5, 1.0), 0.88, 0.42);
        }
    }

    @Test
    void aNullResponsePassesThrough() {
        assertThat(PlanWindowProjector.apply(null, NOW, Map.of())).isNull();
    }

    @Test
    void everyOtherComponentOfTheResponseSurvives() {
        DailyBriefingResponse in = response(twoWindowDay(), List.of(topic("DUST", "SUNSET")));

        DailyBriefingResponse out = PlanWindowProjector.apply(in, NOW, Map.of());

        assertThat(out.headline()).isEqualTo(in.headline());
        assertThat(out.generatedAt()).isEqualTo(in.generatedAt());
        assertThat(out.hotTopics()).isEqualTo(in.hotTopics());
        assertThat(out.days()).hasSameSizeAs(in.days());
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    /** Projects a single sunset window. A lone window with a gloss wins BEST by default. */
    private static BriefingWindow projectOne(BriefingRegion... regions) {
        DailyBriefingResponse out = project(
                List.of(new BriefingDay(TODAY, List.of(
                        new BriefingEventSummary(TargetType.SUNSET, List.of(regions), List.of())))),
                List.of());
        return out.days().get(0).eventSummaries().get(0).window();
    }

    /** Dawn on the fixture day — before every fixture event, so only tests that opt in see the gate. */
    private static final LocalDateTime NOW = LocalDateTime.of(TODAY, LocalTime.of(4, 0));

    private static DailyBriefingResponse project(List<BriefingDay> days, List<HotTopic> topics) {
        return projectAt(days, topics, NOW);
    }

    private static DailyBriefingResponse projectAt(List<BriefingDay> days, List<HotTopic> topics,
            LocalDateTime now) {
        return PlanWindowProjector.apply(response(days, topics), now, Map.of());
    }

    /** Every pick on the response, so an invariant about their number can be stated as one. */
    private static List<BriefingWindow.Pick> picksOf(DailyBriefingResponse r) {
        return r.days().stream()
                .flatMap(d -> d.eventSummaries().stream())
                .map(es -> es.window().pick())
                .filter(java.util.Objects::nonNull)
                .toList();
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

    /**
     * One day holding a single sunset window built from the given regions.
     *
     * <p>Slot times are remapped onto this day's own date. The slot helpers stamp {@link #TODAY},
     * so without this every fixture across every day claimed to happen on day zero — invisible
     * while pick eligibility was positional, and wrong the moment it became temporal.
     */
    private static BriefingDay dayOf(LocalDate date, BriefingRegion... regions) {
        return new BriefingDay(date, List.of(
                new BriefingEventSummary(TargetType.SUNSET, shiftRegions(date, regions), List.of())));
    }

    /**
     * One day holding both a sunrise and a sunset window, each built from its own regions and
     * re-stamped onto the given date — for fixtures where a two-events-per-day shape matters (the
     * events-vs-dates horizon; see {@code horizonCountsSixEventsNotFourDates}).
     */
    private static BriefingDay twoEventDayOf(LocalDate date, BriefingRegion sunrise, BriefingRegion sunset) {
        return new BriefingDay(date, List.of(
                new BriefingEventSummary(TargetType.SUNRISE, shiftRegions(date, sunrise), List.of()),
                new BriefingEventSummary(TargetType.SUNSET, shiftRegions(date, sunset), List.of())));
    }

    /**
     * One day carrying a <b>05:00 sunrise and a 21:11 sunset</b>, each built from its own regions.
     *
     * <p>{@link #twoEventDayOf} stamps every slot at 21:11 — its fixtures only ever needed the two
     * events ordered, never separable in time — so no clock can put one of them in the past and
     * leave the other live. Anything asserting that exactly ONE event elapsed needs this instead,
     * or it passes while dropping both and says nothing about the rule under test.
     */
    private static BriefingDay dawnAndDuskDayOf(LocalDate date, BriefingRegion sunrise,
            BriefingRegion sunset) {
        return new BriefingDay(date, List.of(
                new BriefingEventSummary(TargetType.SUNRISE,
                        retimed(shiftRegions(date, sunrise), LocalDateTime.of(date, LocalTime.of(5, 0))),
                        List.of()),
                new BriefingEventSummary(TargetType.SUNSET, shiftRegions(date, sunset), List.of())));
    }

    /** Puts every slot in the given regions at one instant, so an event has a single clock time. */
    private static List<BriefingRegion> retimed(List<BriefingRegion> regions, LocalDateTime when) {
        return regions.stream()
                .map(r -> new BriefingRegion(r.regionName(), r.verdict(), r.summary(),
                        r.tideHighlights(),
                        r.slots().stream().map(sl -> {
                            BriefingSlot moved = new BriefingSlot(sl.locationId(), sl.locationName(),
                                    when, sl.verdict(), sl.weather(), sl.tide(), sl.flags(),
                                    sl.standdownReason());
                            return sl.claudeRating() == null ? moved
                                    : moved.withClaudeScores(sl.claudeRating(),
                                            sl.fierySkyPotential(), sl.goldenHourPotential(),
                                            sl.claudeSummary());
                        }).toList(),
                        r.regionTemperatureCelsius(), r.regionApparentTemperatureCelsius(),
                        r.regionWindSpeedMs(), r.regionWeatherCode(), r.glossHeadline(),
                        r.glossDetail(), r.displayVerdict(), r.scoredLocationCount(),
                        r.verdictLabel(), r.lightlyEvaluated(), r.confidence(), r.meanRating())
                        .withBestRating(r.bestRating()))
                .toList();
    }

    /** Re-stamps every slot in the given regions onto the given date, keeping clock times. */
    private static List<BriefingRegion> shiftRegions(LocalDate date, BriefingRegion... regions) {
        return java.util.Arrays.stream(regions)
                .map(r -> new BriefingRegion(r.regionName(), r.verdict(), r.summary(),
                        r.tideHighlights(),
                        r.slots().stream().map(sl -> shiftTo(sl, date)).toList(),
                        r.regionTemperatureCelsius(), r.regionApparentTemperatureCelsius(),
                        r.regionWindSpeedMs(), r.regionWeatherCode(), r.glossHeadline(),
                        r.glossDetail(), r.displayVerdict(), r.scoredLocationCount(),
                        r.verdictLabel(), r.lightlyEvaluated(), r.confidence(), r.meanRating())
                        .withBestRating(r.bestRating()))
                .toList();
    }

    /** Re-stamps a slot's solar time onto the given date, keeping its clock time. */
    private static BriefingSlot shiftTo(BriefingSlot slot, LocalDate date) {
        if (slot.solarEventTime() == null) {
            return slot;
        }
        BriefingSlot moved = new BriefingSlot(slot.locationId(), slot.locationName(),
                LocalDateTime.of(date, slot.solarEventTime().toLocalTime()), slot.verdict(),
                slot.weather(), slot.tide(), slot.flags(), slot.standdownReason());
        return slot.claudeRating() == null ? moved
                : moved.withClaudeScores(slot.claudeRating(), slot.fierySkyPotential(),
                        slot.goldenHourPotential(), slot.claudeSummary());
    }

    private static DailyBriefingResponse projectDays(BriefingDay... days) {
        return project(List.of(days), List.of());
    }

    private static BriefingWindow firstWindow(DailyBriefingResponse r, int dayIndex) {
        return r.days().get(dayIndex).eventSummaries().get(0).window();
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

    /**
     * A region whose {@code displayVerdict} is derived from its own slots by the production rule,
     * exactly as {@code BriefingService.enrichWithCachedScores} derives it.
     *
     * <p>It used to default to a flat {@code WORTH_IT}. That was harmless while the window verdict
     * came from the best rating and ignored this field; since the verdict became the top region's
     * own, a fixture pairing a 2★ slot with a WORTH_IT region is a payload production cannot
     * produce, and every window built from one would carry an incoherent badge. Derived here so a
     * fixture cannot state a verdict its own ratings contradict.
     *
     * <p>Over the region's <b>voting</b> slots, which is the population the enrichment path
     * averages. It was canopy-inclusive until the canopy fix; leaving it there would have kept
     * every mixed-region fixture asserting the old band while production produced the new one —
     * a fixture staying green being precisely how the defect survived in the first place.
     */
    private static BriefingRegion regionWithSlots(String name, List<BriefingSlot> slots) {
        BriefingRatingStats.Stats stats = BriefingRatingStats.compute(
                BriefingSlot.votingSlots(slots).stream()
                        .map(s -> new BriefingRatingStats.Entry(s.locationName(), s.claudeRating()))
                        .toList(),
                name, TODAY, TargetType.SUNSET);
        return regionWithSlots(name, slots,
                BriefingRatingStats.resolveRegionDisplayVerdict(stats, Verdict.GO),
                Confidence.HIGH, HEADLINE);
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

    private static BriefingSlot identifiedSlot(String name, int rating, boolean canopy, long id) {
        BriefingSlot base = canopy
                ? BriefingSlot.canopySlot(id, name, at(LocalTime.of(21, 11)), Verdict.GO, null,
                        List.of(), null)
                : new BriefingSlot(id, name, at(LocalTime.of(21, 11)), Verdict.GO, null,
                        BriefingSlot.TideInfo.NONE, List.of(), null);
        return base.withClaudeScores(rating, 60, 55, "summary");
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
                null, null, null, null, null, null);
    }

    /**
     * A tide run row carrying only what the badge derivation reads: the two neutral water phrases
     * and the two solar times. Everything else is the shape the record demands, not the fixture's
     * subject.
     */
    private static TideRunDay tideRun(String sunriseWater, String sunsetWater) {
        return new TideRunDay("SPRING RUN 2/4", 2, 4, "MON 31", "St Mary's Lighthouse",
                "2.4 m", null, null, null, null, "06:09", "20:03", null, List.of(),
                "verdict", true, false, "sunrise", "HW 05:47 · 22m before sunrise", null,
                sunriseWater, sunsetWater, false, null);
    }
}
