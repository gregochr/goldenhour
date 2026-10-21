package com.gregochr.goldenhour.service.ask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.CoverageReason;
import com.gregochr.goldenhour.model.DisplayVerdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.gregochr.goldenhour.service.ask.AskFixtures.TODAY;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pure fold behind {@code list_windows}' spread (ask-window-context plan §2.1): which regions and
 * slots are in the population, how the bands, the derived figures and the pockets come out, how a
 * scope sums, and the set test built on the same candidates.
 */
class AskSpreadTest {

    private static final String SUNSET_TODAY = "2026-10-05_sunset";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // -- fixtures ---------------------------------------------------------------------------

    private static AskSnapshot snapshotOf(BriefingRegion... regions) {
        return AskFixtures.snapshotOf(AskFixtures.briefing(
                List.of(AskFixtures.sunsetDay(TODAY, null, regions)), List.of()));
    }

    private static AskSnapshot.Spread spreadOf(AskScope scope, BriefingRegion... regions) {
        return snapshotOf(regions).spread(SUNSET_TODAY, scope).orElseThrow();
    }

    private static AskSnapshot.Spread spreadOf(BriefingRegion... regions) {
        return spreadOf(AskScope.ALL, regions);
    }

    // -- bands and derived figures ----------------------------------------------------------

    @Test
    @DisplayName("the bands count the rated places at each star and the derived figures follow from them")
    void bandsAndDerivedFigures() {
        AskSnapshot.Spread spread = spreadOf(AskFixtures.bandedRegion("Coast", 1L, 0, 56, 122, 2, 0));

        assertThat(spread.over()).isEqualTo("every region");
        assertThat(spread.rated()).isEqualTo(180);
        assertThat(spread.bands()).containsExactly(0, 56, 122, 2, 0);
        assertThat(spread.gameOn()).isEqualTo(2);
        assertThat(spread.gameOnPct()).isEqualTo(1);
        assertThat(spread.go()).isZero();
        assertThat(spread.below4()).isEqualTo(178);
        assertThat(spread.stars1to2()).isEqualTo(56);
    }

    @Test
    @DisplayName("a 5-star place counts as game on AND as go, and the percentage rounds to the nearest whole")
    void goAndRounding() {
        AskSnapshot.Spread spread = spreadOf(AskFixtures.bandedRegion("Coast", 1L, 1, 2, 3, 1, 2));

        assertThat(spread.rated()).isEqualTo(9);
        assertThat(spread.gameOn()).isEqualTo(3);
        assertThat(spread.go()).isEqualTo(2);
        assertThat(spread.gameOnPct()).as("3 of 9 is 33.3%").isEqualTo(33);
        assertThat(spreadOf(AskFixtures.bandedRegion("Coast", 1L, 0, 0, 1, 1, 1)).gameOnPct())
                .as("2 of 3 is 66.7%, rounded up").isEqualTo(67);
    }

    @Test
    @DisplayName("nothing rated is rated 0, five zero bands, 0% and no pocket, never an absent spread")
    void nothingRated() {
        AskSnapshot.Spread spread = spreadOf(AskFixtures.blankedRegion("Coast", CoverageReason.STOOD_DOWN));

        assertThat(spread.rated()).isZero();
        assertThat(spread.bands()).containsExactly(0, 0, 0, 0, 0);
        assertThat(spread.gameOnPct()).isZero();
        assertThat(spread.pockets()).isEmpty();
        assertThat(spread.regions().counted()).isZero();
    }

    // -- the population: regions by served kind ---------------------------------------------

    @Test
    @DisplayName("a region the briefing stood down whole is a stood-down region, and an all-blue window says so")
    void allBlueWindow() {
        AskSnapshot.Spread spread = spreadOf(
                AskFixtures.blankedRegion("Coast", CoverageReason.STOOD_DOWN),
                AskFixtures.blankedRegion("Hills", CoverageReason.STOOD_DOWN),
                AskFixtures.blankedRegion("Dales", CoverageReason.STOOD_DOWN),
                AskFixtures.blankedRegion("Moors", CoverageReason.STOOD_DOWN));

        assertThat(spread.regions()).isEqualTo(new AskSnapshot.Spread.Regions(0, null, 4, null));
        assertThat(spread.rated()).isZero();
    }

    @Test
    @DisplayName("a slotless region is 'not scored' for every reason but STOOD_DOWN: awaiting, away, UNKNOWN "
            + "(which the filter words AWAITING too) and no reason at all")
    void slotlessRegionIsStoodDownOnlyForAWeatherReason() {
        AskSnapshot.Spread spread = spreadOf(
                AskFixtures.blankedRegion("A", CoverageReason.STOOD_DOWN),
                AskFixtures.blankedRegion("B", CoverageReason.UNKNOWN),
                AskFixtures.blankedRegion("C", CoverageReason.NOT_ATTEMPTED),
                AskFixtures.blankedRegion("D", CoverageReason.FAILED),
                AskFixtures.blankedRegion("E", null));

        assertThat(spread.regions().stoodDown()).isEqualTo(1);
        assertThat(spread.regions().notScored()).as("UNKNOWN and null are unknown, never poor").isEqualTo(4);
    }

    @Test
    @DisplayName("a thin region's slots are NOT in the bands: a 4-star rank_spots would never return is not game on")
    void thinRegionIsOutOfTheBands() {
        AskSnapshot.Spread spread = spreadOf(
                AskFixtures.bandedRegion("Coast", 1L, 0, 0, 5, 1, 0),
                AskFixtures.bandedRegion("Thin", false, 100L, 0, 0, 0, 4, 2));

        assertThat(spread.rated()).isEqualTo(6);
        assertThat(spread.gameOn()).isEqualTo(1);
        assertThat(spread.regions()).isEqualTo(new AskSnapshot.Spread.Regions(1, 1, null, null));
        assertThat(spread.pockets()).extracting(AskSnapshot.Spread.Pocket::region).containsExactly("Coast");
    }

    @Test
    @DisplayName("the population is exactly the places rank_spots draws from at 4 stars and above")
    void gameOnIsTheSamePlacesRankSpotsReturns() {
        AskSnapshot snapshot = snapshotOf(
                AskFixtures.bandedRegion("Coast", 1L, 0, 3, 5, 2, 1),
                AskFixtures.bandedRegion("Thin", false, 100L, 0, 0, 0, 4, 2));

        long fourPlus = snapshot.candidates(snapshot.window(SUNSET_TODAY).orElseThrow(), AskScope.ALL).stream()
                .filter(c -> c.slot().rating() >= 4).count();

        assertThat(snapshot.spread(SUNSET_TODAY, AskScope.ALL).orElseThrow().gameOn()).isEqualTo((int) fourPlus);
    }

    // -- the population: slots --------------------------------------------------------------

    @Test
    @DisplayName("a wood is never in the population, and an all-canopy region is counted but never a pocket")
    void canopyIsOut() {
        BriefingRegion woods = AskFixtures.region("Woods", true,
                AskFixtures.wood(1L, "Hollin Wood", 5), AskFixtures.wood(2L, "Beech Wood", 5));
        AskSnapshot.Spread spread = spreadOf(woods, AskFixtures.bandedRegion("Coast", 10L, 0, 0, 4, 0, 0));

        assertThat(spread.rated()).isEqualTo(4);
        assertThat(spread.go()).isZero();
        assertThat(spread.regions().counted()).isEqualTo(2);
        assertThat(spread.pockets()).isEmpty();
    }

    @Test
    @DisplayName("a slot the neighbour guard withholds is out of the bands, gameOn and the pockets, and counted")
    void heldBackSlotIsOutAndCounted() {
        BriefingRegion coast = AskFixtures.region("Coast", true,
                AskFixtures.outlier(AskFixtures.slot(1L, "Rogue", 5)),
                AskFixtures.slot(2L, "Whitby", 4), AskFixtures.slot(3L, "Staithes", 3));
        AskSnapshot.Spread spread = spreadOf(coast);

        assertThat(spread.bands()).containsExactly(0, 0, 1, 1, 0);
        assertThat(spread.gameOn()).isEqualTo(1);
        assertThat(spread.go()).isZero();
        assertThat(spread.heldBack()).isEqualTo(1);
        assertThat(spread.pockets()).singleElement().satisfies(p -> {
            assertThat(p.gameOn()).isEqualTo(1);
            assertThat(p.mean()).isEqualTo(3.5);
        });
    }

    @Test
    @DisplayName("a marked slot that would not have been a pick anyway (under the floor) stays in its band")
    void markedSlotUnderTheFloorStaysInItsBand() {
        BriefingRegion coast = AskFixtures.region("Coast", true,
                AskFixtures.outlier(AskFixtures.slot(1L, "Odd", 2)), AskFixtures.slot(2L, "Whitby", 4));
        AskSnapshot.Spread spread = spreadOf(coast);

        assertThat(spread.bands()).containsExactly(0, 1, 0, 1, 0);
        assertThat(spread.heldBack()).isNull();
    }

    @Test
    @DisplayName("an unrated slot is counted unrated; one the briefing's own verdict stood down is also stoodDown; "
            + "a malformed star is unrated, not a band")
    void unratedSlots() {
        BriefingSlot noRating = AskFixtures.slot(1L, "Unscored", null);
        BriefingSlot triaged = AskFixtures.standDownSlot(2L, "Triaged");
        BriefingSlot malformed = AskFixtures.slot(3L, "Malformed", 491);
        BriefingRegion coast = AskFixtures.region("Coast", true, noRating, triaged, malformed,
                AskFixtures.slot(4L, "Whitby", 4));
        AskSnapshot.Spread spread = spreadOf(coast);

        assertThat(spread.rated()).isEqualTo(1);
        assertThat(spread.unrated()).isEqualTo(3);
        assertThat(spread.stoodDown()).isEqualTo(1);
    }

    // -- pockets ----------------------------------------------------------------------------

    @Test
    @DisplayName("pockets are the counted regions holding a game-on place, most game-on first, then the higher "
            + "mean, then the name; the mean is to one decimal over the spread's own population")
    void pocketOrderAndMean() {
        AskSnapshot.Spread spread = spreadOf(
                AskFixtures.bandedRegion("Zeta", 1L, 0, 0, 5, 2, 0),
                AskFixtures.bandedRegion("Alpha", 100L, 0, 0, 6, 2, 0),
                AskFixtures.bandedRegion("Beta", 200L, 0, 0, 1, 2, 0),
                AskFixtures.bandedRegion("Gamma", 300L, 0, 0, 9, 0, 0),
                AskFixtures.bandedRegion("Delta", 400L, 0, 0, 0, 5, 0));

        assertThat(spread.pockets()).extracting(AskSnapshot.Spread.Pocket::region)
                .as("Delta has the most game-on; Beta's mean beats Alpha's and Zeta's; Gamma has none")
                .containsExactly("Delta", "Beta", "Alpha", "Zeta");
        assertThat(spread.pockets()).extracting(AskSnapshot.Spread.Pocket::mean)
                .containsExactly(4.0, 3.7, 3.3, 3.3);
        assertThat(spread.pockets()).extracting(AskSnapshot.Spread.Pocket::gameOn)
                .containsExactly(5, 2, 2, 2);
        assertThat(spread.morePockets()).isNull();
    }

    @Test
    @DisplayName("at most four pockets are listed and morePockets says how many were cut")
    void pocketsAreCappedAtFour() {
        List<BriefingRegion> regions = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            regions.add(AskFixtures.bandedRegion("Region " + (char) ('A' + i), 100L * (i + 1), 0, 0, 1, i + 1, 0));
        }
        AskSnapshot.Spread spread = spreadOf(regions.toArray(BriefingRegion[]::new));

        assertThat(spread.pockets()).hasSize(AskSnapshot.Spread.MAX_POCKETS);
        assertThat(spread.pockets()).extracting(AskSnapshot.Spread.Pocket::region)
                .containsExactly("Region G", "Region F", "Region E", "Region D");
        assertThat(spread.morePockets()).isEqualTo(3);
        assertThat(spread.gameOn()).as("the totals still include the cut pockets").isEqualTo(28);
    }

    // -- scope ------------------------------------------------------------------------------

    @Test
    @DisplayName("a scope sums only the regions it contains, and its names are the population's name")
    void scopeSumsWholeRegions() {
        BriefingRegion[] regions = {
            AskFixtures.bandedRegion("Northumberland", 1L, 0, 10, 20, 2, 0),
            AskFixtures.bandedRegion("Lake District", 100L, 0, 5, 5, 1, 1),
            AskFixtures.bandedRegion("Yorkshire Dales", 200L, 0, 1, 1, 7, 0),
        };

        AskSnapshot.Spread one = spreadOf(TestScopes.of("Northumberland"), regions);
        AskSnapshot.Spread two = spreadOf(TestScopes.of("Northumberland", "Lake District"), regions);
        AskSnapshot.Spread three = spreadOf(
                TestScopes.of("Yorkshire Dales", "Lake District", "Northumberland"), regions);
        AskSnapshot.Spread all = spreadOf(regions);

        assertThat(one.over()).isEqualTo("Northumberland");
        assertThat(one.rated()).isEqualTo(32);
        assertThat(two.over()).as("sorted, joined").isEqualTo("Lake District and Northumberland");
        assertThat(two.rated()).isEqualTo(44);
        assertThat(two.bands()).containsExactly(0, 15, 25, 3, 1);
        assertThat(three.over()).isEqualTo("Lake District, Northumberland and Yorkshire Dales");
        assertThat(all.over()).isEqualTo("every region");
        assertThat(all.rated()).isEqualTo(three.rated());
        assertThat(all.rated()).isEqualTo(one.rated() + 12 + 9);
    }

    @Test
    @DisplayName("a scope names its regions case-insensitively, as every scope check does")
    void scopeMatchIsCaseInsensitive() {
        AskSnapshot.Spread spread = spreadOf(TestScopes.of("COAST"),
                AskFixtures.bandedRegion("Coast", 1L, 0, 0, 3, 1, 0),
                AskFixtures.bandedRegion("Hills", 50L, 0, 0, 9, 0, 0));

        assertThat(spread.rated()).isEqualTo(4);
    }

    @Test
    @DisplayName("a night window has no spread")
    void nightHasNoSpread() {
        assertThat(snapshotOf(AskFixtures.bandedRegion("Coast", 1L, 0, 0, 3, 1, 0))
                .spread("2026-10-05_astro", AskScope.ALL)).isEmpty();
    }

    // -- the wire shape ---------------------------------------------------------------------

    @Test
    @DisplayName("on the wire the optional tallies are omitted when zero, the regions keep 'counted', and "
            + "the pockets are always present")
    void wireShape() throws Exception {
        String json = MAPPER.writeValueAsString(
                spreadOf(AskFixtures.bandedRegion("Coast", 1L, 0, 56, 122, 2, 0)));

        assertThat(json).contains("\"over\":\"every region\"", "\"bands\":[0,56,122,2,0]", "\"gameOn\":2",
                "\"gameOnPct\":1", "\"go\":0", "\"below4\":178", "\"stars1to2\":56",
                "\"regions\":{\"counted\":1}", "\"pockets\":[{\"region\":\"Coast\",\"gameOn\":2,\"mean\":2.7}]");
        assertThat(json).doesNotContain("unrated", "heldBack", "morePockets", "thin", "notScored");
    }

    @Test
    @DisplayName("the whole spread survives a JSON round trip, so it can be stored with an answer and compared")
    void roundTrip() throws Exception {
        AskSnapshot.Spread spread = spreadOf(
                AskFixtures.bandedRegion("Coast", 1L, 1, 4, 7, 3, 1),
                AskFixtures.bandedRegion("Thin", false, 100L, 0, 0, 0, 2, 0),
                AskFixtures.blankedRegion("Hills", CoverageReason.STOOD_DOWN));

        AskSnapshot.Spread back = MAPPER.readValue(MAPPER.writeValueAsString(spread), AskSnapshot.Spread.class);

        assertThat(back).isEqualTo(spread);
    }

    // -- the set test built on the same candidates ------------------------------------------

    private static AskSnapshot twoWindowSnapshot(BriefingRegion saturday, BriefingRegion sunday) {
        LocalDate sat = LocalDate.of(2026, 10, 10);
        LocalDate sun = sat.plusDays(1);
        BriefingWindow satWindow = AskFixtures.window(sat.atTime(18, 0), DisplayVerdict.MAYBE, 3, null);
        BriefingWindow sunWindow = AskFixtures.window(sun.atTime(18, 0), DisplayVerdict.MAYBE, 4, null);
        return AskFixtures.snapshotOf(AskFixtures.briefing(List.of(
                AskFixtures.day(sat, AskFixtures.summary(TargetType.SUNSET, satWindow, saturday)),
                AskFixtures.day(sun, AskFixtures.summary(TargetType.SUNSET, sunWindow, sunday))), List.of()));
    }

    @Test
    @DisplayName("game-on candidates are the pick-eligible slots at 4 stars and up on the given windows, in scope, "
            + "that the question admits; a held-back place and a thin region's 4-star are not candidates")
    void gameOnCandidates() {
        AskSnapshot snapshot = twoWindowSnapshot(
                AskFixtures.bandedRegion("Coast", 1L, 0, 0, 4, 0, 0),
                AskFixtures.region("Coast", true, AskFixtures.outlier(AskFixtures.slot(50L, "Rogue", 5)),
                        AskFixtures.slot(51L, "Whitby", 4), AskFixtures.slot(52L, "Cat Bells", 3)));

        assertThat(snapshot.gameOnCandidates(List.of("2026-10-10_sunset"), AskScope.ALL, s -> true)).isEmpty();
        assertThat(snapshot.gameOnCandidates(List.of("2026-10-11_sunset"), AskScope.ALL, s -> true))
                .extracting(c -> c.slot().name()).containsExactly("Whitby");
        assertThat(snapshot.hasGameOn(List.of("2026-10-10_sunset", "2026-10-11_sunset"), AskScope.ALL, s -> true))
                .isTrue();
        assertThat(snapshot.hasGameOn(List.of("2026-10-11_sunset"), AskScope.ALL, s -> false))
                .as("the question's own predicate decides what counts").isFalse();
        assertThat(snapshot.hasGameOn(List.of("2026-10-11_sunset"), TestScopes.of("Hills"), s -> true))
                .as("out of scope").isFalse();
        assertThat(snapshot.hasGameOn(List.of("2031-01-01_sunset"), AskScope.ALL, s -> true))
                .as("a window the snapshot does not hold contributes nothing").isFalse();
    }

    @Test
    @DisplayName("a thin region's 4-star is not game on, so a window holding only that is a no-game-on window")
    void thinRegionIsNotGameOn() {
        AskSnapshot snapshot = twoWindowSnapshot(AskFixtures.bandedRegion("Thin", false, 1L, 0, 0, 0, 3, 0),
                AskFixtures.bandedRegion("Thin", false, 10L, 0, 0, 0, 3, 0));

        assertThat(snapshot.hasGameOn(List.of("2026-10-10_sunset", "2026-10-11_sunset"), AskScope.ALL, s -> true))
                .isFalse();
    }

    @Test
    @DisplayName("under a narrowed scope the thin, stood-down and not-scored tallies count only in-scope regions")
    void scopeNarrowsTheRegionTallies() {
        BriefingRegion[] regions = {
            AskFixtures.bandedRegion("Coast", 1L, 0, 0, 3, 1, 0),
            AskFixtures.bandedRegion("Thin", false, 100L, 0, 0, 0, 2, 0),
            AskFixtures.blankedRegion("Blue", CoverageReason.STOOD_DOWN),
            AskFixtures.blankedRegion("Unlooked", CoverageReason.NOT_ATTEMPTED),
        };

        AskSnapshot.Spread all = spreadOf(regions);
        AskSnapshot.Spread coastAndBlue = spreadOf(TestScopes.of("Coast", "Blue"), regions);

        assertThat(all.regions()).isEqualTo(new AskSnapshot.Spread.Regions(1, 1, 1, 1));
        assertThat(coastAndBlue.regions()).isEqualTo(new AskSnapshot.Spread.Regions(1, null, 1, null));
    }
}
