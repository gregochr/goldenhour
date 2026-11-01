package com.gregochr.goldenhour.service.ask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.CoverageReason;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.service.DriveTimeResolver;
import com.gregochr.goldenhour.service.evaluation.ForecastResultHandler;
import com.gregochr.goldenhour.service.ask.AskTools.LookUpPlaceArgs;
import com.gregochr.goldenhour.service.ask.AskTools.LookUpPlaceResult;
import com.gregochr.goldenhour.service.ask.AskTools.PlaceDetail;
import com.gregochr.goldenhour.service.ask.AskTools.PlaceInfo;
import com.gregochr.goldenhour.service.ask.AskTools.PlaceStatus;
import com.gregochr.goldenhour.service.ask.AskTools.RankSpotsArgs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.gregochr.goldenhour.service.ask.AskFixtures.TODAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@code look_up_place} (look-up plan §4): the matcher, each status, the agreement with
 * {@link AskSnapshot#isPickEligible}, the scope, the windows and the rule that a lookup is never evidence.
 */
class AskToolsLookUpPlaceTest {

    private static final String TODAY_SUNSET = "2026-10-05_sunset";
    private static final String TOMORROW_SUNSET = "2026-10-06_sunset";
    /** Half the conversation's cap, the standard list_windows is held to; one lookup must leave the rest to it. */
    private static final int WORST_CASE_CHARS = 4_500;
    private static final AskUserContext USER = new AskUserContext(7L, UserRole.PRO_USER, true);

    private final ObjectMapper mapper = new ObjectMapper();
    private final DriveTimeResolver driveTimes = mock(DriveTimeResolver.class);

    private AskTools tools(AskSnapshot snapshot) {
        return tools(snapshot, AskScope.ALL);
    }

    private AskTools tools(AskSnapshot snapshot, AskScope scope) {
        return new AskTools(snapshot, USER, scope, driveTimes, mapper);
    }

    private static AskSnapshot snapshotOf(BriefingRegion... regions) {
        return AskFixtures.snapshotOf(AskFixtures.briefing(
                List.of(AskFixtures.sunsetDay(TODAY, null, regions)), List.of()));
    }

    /** Today's and tomorrow's sunset, both holding these regions. */
    private static AskSnapshot twoDays(BriefingRegion... regions) {
        return AskFixtures.snapshotOf(AskFixtures.briefing(List.of(
                AskFixtures.sunsetDay(TODAY, null, regions),
                AskFixtures.sunsetDay(TODAY.plusDays(1), null, regions)), List.of()));
    }

    private static LookUpPlaceArgs name(String name) {
        return new LookUpPlaceArgs(name, null);
    }

    private static LookUpPlaceResult ok(AskToolResult<LookUpPlaceResult> result) {
        assertThat(result.error()).as(result.content()).isFalse();
        return result.payload();
    }

    private static List<String> names(LookUpPlaceResult result) {
        return result.places().stream().map(PlaceInfo::name).toList();
    }

    private static PlaceInfo only(LookUpPlaceResult result) {
        assertThat(result.places()).hasSize(1);
        return result.places().getFirst();
    }

    // -- the matcher ------------------------------------------------------------------------

    @Test
    @DisplayName("an exact name comes before a partial one, and every place carrying the word is found")
    void exactBeforePartial() {
        BriefingRegion coast = AskFixtures.region("Coast", true,
                AskFixtures.slot(2L, "St Cuthbert's Cross, Alnmouth", 3),
                AskFixtures.slot(3L, "Alnmouth Bay", 3),
                AskFixtures.slot(1L, "Alnmouth", 4));

        LookUpPlaceResult result = ok(tools(snapshotOf(coast)).lookUpPlace(name("alnmouth")));

        assertThat(names(result)).containsExactly("Alnmouth", "Alnmouth Bay", "St Cuthbert's Cross, Alnmouth");
        assertThat(result.note()).isNull();
    }

    @Test
    @DisplayName("whole words only: 'mouth' finds neither Alnmouth nor Yarmouth")
    void wholeWordsOnly() {
        BriefingRegion coast = AskFixtures.region("Coast", true,
                AskFixtures.slot(1L, "Alnmouth", 4), AskFixtures.slot(2L, "Yarmouth Pier", 4));
        AskTools tools = tools(snapshotOf(coast));

        LookUpPlaceResult result = ok(tools.lookUpPlace(name("mouth")));

        assertThat(result.places()).isEmpty();
        assertThat(result.note()).isEqualTo(AskTools.NO_SUCH_PLACE_NOTE);
    }

    @Test
    @DisplayName("the query's words must be a contiguous run of the name's words, in order")
    void contiguousRunInOrder() {
        BriefingRegion coast = AskFixtures.region("Coast", true,
                AskFixtures.slot(2L, "St Cuthbert's Cross, Alnmouth", 3));
        AskTools tools = tools(snapshotOf(coast));

        assertThat(names(ok(tools.lookUpPlace(name("cross alnmouth")))))
                .containsExactly("St Cuthbert's Cross, Alnmouth");
        assertThat(ok(tools.lookUpPlace(name("alnmouth cross"))).places()).isEmpty();
        assertThat(ok(tools.lookUpPlace(name("st alnmouth"))).places()).isEmpty();
    }

    @Test
    @DisplayName("apostrophes are removed on both sides, straight or curly: 'st cuthberts' = \"St Cuthbert's\"")
    void apostrophes() {
        BriefingRegion coast = AskFixtures.region("Coast", true,
                AskFixtures.slot(2L, "St Cuthbert's Cross", 3));
        AskTools tools = tools(snapshotOf(coast));

        assertThat(names(ok(tools.lookUpPlace(name("st cuthberts"))))).containsExactly("St Cuthbert's Cross");
        assertThat(names(ok(tools.lookUpPlace(name("St Cuthbert's"))))).containsExactly("St Cuthbert's Cross");
        assertThat(names(ok(tools.lookUpPlace(name("St Cuthbert’s"))))).containsExactly("St Cuthbert's Cross");
    }

    @Test
    @DisplayName("case and punctuation do not matter, and a place name's own 'the' is kept")
    void caseAndFillerWords() {
        BriefingRegion coast = AskFixtures.region("Coast", true,
                AskFixtures.slot(1L, "The Nab", 3), AskFixtures.slot(2L, "Bamburgh, Castle", 4));
        AskTools tools = tools(snapshotOf(coast));

        assertThat(names(ok(tools.lookUpPlace(name("THE NAB"))))).containsExactly("The Nab");
        assertThat(names(ok(tools.lookUpPlace(name("nab"))))).containsExactly("The Nab");
        assertThat(ok(tools.lookUpPlace(name("a nab"))).places()).isEmpty();
        assertThat(names(ok(tools.lookUpPlace(name("  bamburgh castle! "))))).containsExactly("Bamburgh, Castle");
    }

    @Test
    @DisplayName("places are capped at four and rows at six, each place's windows in order, both cuts in the note")
    void capAndNote() {
        List<BriefingSlot> slots = new ArrayList<>();
        for (long id = 1; id <= 6; id++) {
            slots.add(AskFixtures.slot(id, "Beach " + (char) ('A' + id - 1), 4));
        }
        BriefingRegion coast = AskFixtures.region("Coast", true, slots.toArray(BriefingSlot[]::new));

        LookUpPlaceResult result = ok(tools(twoDays(coast)).lookUpPlace(name("beach")));

        assertThat(result.places()).extracting(PlaceInfo::locationId).containsExactly(1L, 1L, 2L, 2L, 3L, 3L);
        assertThat(result.places()).extracting(PlaceInfo::windowId)
                .containsExactly(TODAY_SUNSET, TOMORROW_SUNSET, TODAY_SUNSET, TOMORROW_SUNSET,
                        TODAY_SUNSET, TOMORROW_SUNSET);
        assertThat(result.note()).isEqualTo("2 more places match; use a longer name. "
                + "Showing 6 of 8 place-windows; pass windowIds to narrow.");
    }

    @Test
    @DisplayName("exactly the cap is no 'more' note")
    void exactlyTheCapHasNoNote() {
        BriefingRegion coast = AskFixtures.region("Coast", true,
                AskFixtures.slot(1L, "Beach A", 4), AskFixtures.slot(2L, "Beach B", 4),
                AskFixtures.slot(3L, "Beach C", 4), AskFixtures.slot(4L, "Beach D", 4));

        LookUpPlaceResult result = ok(tools(snapshotOf(coast)).lookUpPlace(name("beach")));

        assertThat(result.places()).hasSize(AskTools.MAX_PLACE_MATCHES);
        assertThat(result.note()).isNull();
    }

    @Test
    @DisplayName("the exact match survives the cap even when five other places sort before it by name")
    void exactMatchIsNotCappedOut() {
        BriefingRegion coast = AskFixtures.region("Coast", true,
                AskFixtures.slot(1L, "Alnmouth Bay", 4), AskFixtures.slot(2L, "Alnmouth Beach", 4),
                AskFixtures.slot(3L, "Alnmouth Cliff", 4), AskFixtures.slot(4L, "Alnmouth Dunes", 4),
                AskFixtures.slot(5L, "Alnmouth Estuary", 4), AskFixtures.slot(9L, "Alnmouth", 4));

        LookUpPlaceResult result = ok(tools(snapshotOf(coast)).lookUpPlace(name("alnmouth")));

        assertThat(names(result).getFirst()).isEqualTo("Alnmouth");
        assertThat(result.places()).hasSize(AskTools.MAX_PLACE_MATCHES);
        assertThat(result.note()).isEqualTo("2 more places match; use a longer name.");
    }

    @Test
    @DisplayName("no match at all: no places and the 'not in these windows' note")
    void noMatch() {
        LookUpPlaceResult result = ok(tools(snapshotOf(AskFixtures.region("Coast", true,
                AskFixtures.slot(1L, "Bamburgh", 4)))).lookUpPlace(name("Atlantis")));

        assertThat(result.places()).isEmpty();
        assertThat(result.note()).isEqualTo(AskTools.NO_SUCH_PLACE_NOTE);
    }

    @Test
    @DisplayName("a missing, blank or punctuation-only name is an error, and the call is traced as one")
    void nameIsRequired() {
        AskTools tools = tools(snapshotOf(AskFixtures.region("Coast", true, AskFixtures.slot(1L, "Bamburgh", 4))));

        assertThat(tools.lookUpPlace(null).error()).isTrue();
        assertThat(tools.lookUpPlace(new LookUpPlaceArgs(null, null)).error()).isTrue();
        assertThat(tools.lookUpPlace(name("   ")).error()).isTrue();
        AskToolResult<LookUpPlaceResult> punctuation = tools.lookUpPlace(name("!?"));
        assertThat(punctuation.error()).isTrue();
        assertThat(punctuation.content()).isEqualTo("name is required.");
        assertThat(tools.trace()).hasSize(4).allSatisfy(call -> assertThat(call.error()).isTrue());
    }

    // -- status -----------------------------------------------------------------------------

    /** The reasons the last {@link #lookUp} returned, so a test reads a place's reason as the model does. */
    private Map<String, String> lastReasons;

    private PlaceInfo lookUp(BriefingRegion region, String placeName) {
        LookUpPlaceResult result = ok(tools(snapshotOf(region)).lookUpPlace(name(placeName)));
        lastReasons = result.reasons();
        return only(result);
    }

    /** The reason said once for the status of {@code info}, from the last {@link #lookUp}; null when it has none. */
    private String reason(PlaceInfo info) {
        return lastReasons == null ? null : lastReasons.get(info.status());
    }

    @Test
    @DisplayName("a pick-eligible place is PICK with its served figures and no reason")
    void statusPick() {
        BriefingSlot slot = AskFixtures.withHeadline(AskFixtures.coastal(1L, "Alnmouth", 4, "HIGH", true),
                "Clear east, high water");

        PlaceInfo info = lookUp(AskFixtures.region("Coast", true, slot), "Alnmouth");

        assertThat(info.status()).isEqualTo("PICK");
        assertThat(reason(info)).isNull();
        assertThat(info.rating()).isEqualTo(4);
        assertThat(info.verdict()).isEqualTo(DisplayVerdict.resolve(4, Verdict.GO).name());
        assertThat(info.headline()).isEqualTo("Clear east, high water");
        assertThat(info.tideState()).isEqualTo("HIGH");
        assertThat(info.tideAligned()).isTrue();
        assertThat(info.region()).isEqualTo("Coast");
        assertThat(info.windowId()).isEqualTo(TODAY_SUNSET);
        assertThat(info.day()).isEqualTo("Today");
        assertThat(info.event()).isEqualTo("sunset");
        assertThat(info.locationId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("an inland place carries no tide fields")
    void inlandPlaceHasNoTide() {
        PlaceInfo info = lookUp(AskFixtures.region("Hills", true, AskFixtures.slot(1L, "Hawk Crag", 4)),
                "Hawk Crag");

        assertThat(info.tideState()).isNull();
        assertThat(info.tideAligned()).isNull();
    }

    @Test
    @DisplayName("below the game-on floor is BELOW_GAME_ON, with the star shown and no reason")
    void statusBelowGameOn() {
        PlaceInfo info = lookUp(AskFixtures.region("Coast", true, AskFixtures.slot(1L, "Alnmouth", 2)), "Alnmouth");

        assertThat(info.status()).isEqualTo("BELOW_GAME_ON");
        assertThat(info.rating()).isEqualTo(2);
        assertThat(reason(info)).isNull();
    }

    @Test
    @DisplayName("the floor is inclusive: 3 stars is PICK, 2 is BELOW_GAME_ON")
    void floorBoundary() {
        BriefingRegion region = AskFixtures.region("Coast", true,
                AskFixtures.slot(1L, "Three", 3), AskFixtures.slot(2L, "Two", 2));
        AskTools tools = tools(snapshotOf(region));

        assertThat(only(ok(tools.lookUpPlace(name("three")))).status()).isEqualTo("PICK");
        assertThat(only(ok(tools.lookUpPlace(name("two")))).status()).isEqualTo("BELOW_GAME_ON");
    }

    @Test
    @DisplayName("a 4-star place in a thin-sample region is THIN_SAMPLE: the Alnmouth shape")
    void statusThinSample() {
        PlaceInfo info = lookUp(AskFixtures.region("Northumberland", false, AskFixtures.slot(1L, "Alnmouth", 4)),
                "Alnmouth");

        assertThat(info.status()).isEqualTo("THIN_SAMPLE");
        assertThat(info.rating()).isEqualTo(4);
        assertThat(reason(info)).isEqualTo("Too few places in this region were rated to recommend one.");
    }

    @Test
    @DisplayName("a neighbour outlier is NEIGHBOUR_OUTLIER, keeps its star, and says it is not being suggested")
    void statusNeighbourOutlier() {
        PlaceInfo info = lookUp(AskFixtures.region("Wall", true,
                AskFixtures.outlier(AskFixtures.slot(1L, "Caw Gap", 4)), AskFixtures.slot(2L, "Cawfields", 1)),
                "Caw Gap");

        assertThat(info.status()).isEqualTo("NEIGHBOUR_OUTLIER");
        assertThat(info.rating()).isEqualTo(4);
        assertThat(reason(info))
                .isEqualTo("Rated well above the places beside it, so PhotoCast is not suggesting it.");
    }

    @Test
    @DisplayName("order: an outlier in a thin-sample region reads NEIGHBOUR_OUTLIER, not THIN_SAMPLE")
    void outlierBeatsThinSample() {
        PlaceInfo info = lookUp(AskFixtures.region("Wall", false,
                AskFixtures.outlier(AskFixtures.slot(1L, "Caw Gap", 4))), "Caw Gap");

        assertThat(info.status()).isEqualTo("NEIGHBOUR_OUTLIER");
    }

    @Test
    @DisplayName("order: a wood marked as an outlier reads CANOPY, and a wood in a thin region reads CANOPY")
    void canopyBeatsOutlierAndThinSample() {
        BriefingSlot markedWood = AskFixtures.outlier(AskFixtures.wood(1L, "Hollin Wood", 5));
        BriefingSlot thinWood = AskFixtures.wood(2L, "Cragside Wood", 5);

        assertThat(lookUp(AskFixtures.region("Wall", true, markedWood), "Hollin Wood").status())
                .isEqualTo("CANOPY");
        PlaceInfo thin = lookUp(AskFixtures.region("Wall", false, thinWood), "Cragside Wood");
        assertThat(thin.status()).isEqualTo("CANOPY");
        assertThat(thin.rating()).isEqualTo(5);
        assertThat(reason(thin))
                .isEqualTo("A woodland canopy rating: how good the light under the trees is, not the sky.");
    }

    @Test
    @DisplayName("order: a canopy slot with a null rating is UNRATED, not CANOPY, and carries no star")
    void unratedBeatsCanopy() {
        PlaceInfo info = lookUp(AskFixtures.region("Wall", true, AskFixtures.wood(1L, "Hollin Wood", null)),
                "Hollin Wood");

        assertThat(info.status()).isEqualTo("UNRATED");
        assertThat(info.rating()).isNull();
        assertThat(reason(info)).isEqualTo("Not rated for this window.");
    }

    @Test
    @DisplayName("a rating off Claude's 1-5 scale is UNRATED and the figure is not shown")
    void outOfRangeRatingIsUnrated() {
        BriefingRegion region = AskFixtures.region("Coast", true,
                AskFixtures.slot(1L, "Malformed", 491), AskFixtures.slot(2L, "Zero", 0));
        AskTools tools = tools(snapshotOf(region));

        PlaceInfo malformed = only(ok(tools.lookUpPlace(name("malformed"))));
        assertThat(malformed.status()).isEqualTo("UNRATED");
        assertThat(malformed.rating()).isNull();
        assertThat(only(ok(tools.lookUpPlace(name("zero")))).status()).isEqualTo("UNRATED");
    }

    @Test
    @DisplayName("an unrated place in a region the filter blanked carries that region's reason in the words the "
            + "spread uses: stood down for the weather, or not scored")
    void unratedReasonNamesTheCoverage() {
        PlaceInfo stood = lookUp(withCoverage("Coast", CoverageReason.STOOD_DOWN), "Alnmouth");
        String stoodReason = reason(stood);
        PlaceInfo away = lookUp(withCoverage("Coast", CoverageReason.AWAY), "Alnmouth");

        assertThat(stood.status()).isEqualTo("UNRATED");
        assertThat(stoodReason)
                .isEqualTo("Not rated for this window. Its region was stood down for the weather.");
        assertThat(reason(away)).isEqualTo("Not rated for this window. Its region was not scored.");
    }

    private static BriefingRegion withCoverage(String regionName, CoverageReason reason) {
        BriefingSlot slot = AskFixtures.slot(1L, "Alnmouth", null);
        return new BriefingRegion(regionName, Verdict.STANDDOWN, "summary", List.of(), List.of(slot), null, null,
                null, null, null, "detail", DisplayVerdict.STAND_DOWN, 1, "label", null, null, null, null,
                null, null, reason);
    }

    // -- the status agrees with the pick rule -----------------------------------------------

    @Test
    @DisplayName("every slot is PICK exactly when AskSnapshot.isPickEligible says so, over every combination")
    void pickStatusAgreesWithIsPickEligible() {
        Integer[] ratings = {null, -1, 0, 1, 2, 3, 4, 5, 6, 491};
        boolean[] both = {true, false};
        int checked = 0;
        for (boolean regionEligible : both) {
            for (boolean outlier : both) {
                for (boolean canopy : both) {
                    for (Integer rating : ratings) {
                        AskSnapshot.Slot slot = new AskSnapshot.Slot(1L, "Place", rating, DisplayVerdict.WORTH_IT,
                                null, canopy, null, false, null, outlier);
                        AskSnapshot.Region region = new AskSnapshot.Region("R", DisplayVerdict.WORTH_IT, 4.0,
                                regionEligible, regionEligible, List.of(slot), null);

                        PlaceStatus status = AskTools.classify(region, slot);

                        assertThat(status == PlaceStatus.PICK)
                                .as("region %s outlier %s canopy %s rating %s -> %s", regionEligible, outlier,
                                        canopy, rating, status)
                                .isEqualTo(AskSnapshot.isPickEligible(region, slot));
                        checked++;
                    }
                }
            }
        }
        assertThat(checked).isEqualTo(80);
    }

    @Test
    @DisplayName("a verdict-eligible but pick-ineligible region (the forced-sample exemption) is THIN_SAMPLE")
    void forcedSampleRegionIsThinSample() {
        AskSnapshot.Slot slot = new AskSnapshot.Slot(1L, "Alnmouth", 4, DisplayVerdict.WORTH_IT, null, false, null,
                false, null, false);
        AskSnapshot.Region forced = new AskSnapshot.Region("R", DisplayVerdict.WORTH_IT, 4.0, true, false,
                List.of(slot), null);

        assertThat(AskTools.classify(forced, slot)).isEqualTo(PlaceStatus.THIN_SAMPLE);
    }

    // -- scope ------------------------------------------------------------------------------

    @Test
    @DisplayName("only places in the question's regions are returned")
    void scopeKeepsInScopePlacesOnly() {
        BriefingRegion wall = AskFixtures.region("Wall", true, AskFixtures.slot(1L, "Gap Beach", 4));
        BriefingRegion tees = AskFixtures.region("Teesdale", true, AskFixtures.slot(2L, "Gap Falls", 4));

        LookUpPlaceResult result = ok(tools(snapshotOf(wall, tees), TestScopes.of("Wall")).lookUpPlace(name("gap")));

        assertThat(names(result)).containsExactly("Gap Beach");
        assertThat(result.note()).isNull();
    }

    @Test
    @DisplayName("a name found only outside the scope returns no places, the scope note and no rating")
    void outOfScopeOnlyMatchGivesNoRating() {
        BriefingRegion wall = AskFixtures.region("Wall", true, AskFixtures.slot(1L, "Caw Gap", 3));
        BriefingRegion tees = AskFixtures.region("Teesdale", true, AskFixtures.slot(2L, "High Force", 5));
        AskTools tools = tools(snapshotOf(wall, tees), TestScopes.of("Wall"));

        AskToolResult<LookUpPlaceResult> result = tools.lookUpPlace(name("high force"));

        assertThat(ok(result).places()).isEmpty();
        assertThat(result.payload().note()).isEqualTo(AskTools.PLACE_OUT_OF_SCOPE_NOTE);
        assertThat(result.content()).doesNotContain("High Force").doesNotContain("\"rating\"");
    }

    // -- windows ----------------------------------------------------------------------------

    @Test
    @DisplayName("no windowIds means every solar window in the snapshot, in window order")
    void defaultsToEveryWindow() {
        BriefingRegion coast = AskFixtures.region("Coast", true, AskFixtures.slot(1L, "Alnmouth", 4));

        LookUpPlaceResult result = ok(tools(twoDays(coast)).lookUpPlace(name("alnmouth")));
        LookUpPlaceResult emptyList = ok(tools(twoDays(coast)).lookUpPlace(new LookUpPlaceArgs("alnmouth", List.of())));

        assertThat(result.places()).extracting(PlaceInfo::windowId).containsExactly(TODAY_SUNSET, TOMORROW_SUNSET);
        assertThat(result.places()).extracting(PlaceInfo::day).containsExactly("Today", "Tomorrow");
        assertThat(emptyList.places()).isEqualTo(result.places());
    }

    @Test
    @DisplayName("windowIds narrows the lookup to those windows")
    void windowIdsNarrow() {
        BriefingRegion coast = AskFixtures.region("Coast", true, AskFixtures.slot(1L, "Alnmouth", 4));

        LookUpPlaceResult result = ok(tools(twoDays(coast))
                .lookUpPlace(new LookUpPlaceArgs("alnmouth", List.of(TOMORROW_SUNSET, TOMORROW_SUNSET))));

        assertThat(result.places()).extracting(PlaceInfo::windowId).containsExactly(TOMORROW_SUNSET);
    }

    @Test
    @DisplayName("an unknown window id is the same error rank_spots gives, even beside a valid one")
    void unknownWindowId() {
        BriefingRegion coast = AskFixtures.region("Coast", true, AskFixtures.slot(1L, "Alnmouth", 4));
        AskTools tools = tools(twoDays(coast));

        AskToolResult<LookUpPlaceResult> result = tools.lookUpPlace(
                new LookUpPlaceArgs("alnmouth", List.of(TODAY_SUNSET, "2030-01-01_sunrise")));

        assertThat(result.error()).isTrue();
        assertThat(result.content()).isEqualTo("Unknown window id '2030-01-01_sunrise'. "
                + "Call list_windows for the valid ids.");
        assertThat(tools.trace()).hasSize(1);
        assertThat(tools.trace().getFirst().error()).isTrue();
    }

    // -- evidence ---------------------------------------------------------------------------

    @Test
    @DisplayName("a lookup adds no evidence pair, for a PICK place or a held-back one, but it is a tool call")
    void lookupIsNeverEvidence() {
        BriefingRegion coast = AskFixtures.region("Coast", true,
                AskFixtures.slot(1L, "Alnmouth", 4),
                AskFixtures.outlier(AskFixtures.slot(2L, "Alnmouth Rocks", 5)),
                AskFixtures.slot(3L, "Cresswell", 3));
        AskTools tools = tools(snapshotOf(coast));

        LookUpPlaceResult result = ok(tools.lookUpPlace(name("alnmouth")));

        assertThat(result.places()).extracting(PlaceInfo::status).containsExactly("PICK", "NEIGHBOUR_OUTLIER");
        assertThat(tools.evidence().pairs()).isEmpty();
        assertThat(tools.evidence().events()).isEmpty();
        assertThat(tools.evidence().anyToolCalled()).isTrue();
        assertThat(tools.evidence().offers(new AskEvidence.Pair(1L, TODAY_SUNSET))).isFalse();
        assertThat(tools.trace()).extracting(AskTools.ToolCall::tool).containsExactly("look_up_place");
        assertThat(tools.personal()).isFalse();
        assertThat(tools.uncacheable()).isFalse();
    }

    @Test
    @DisplayName("rank_spots after a lookup still records its own pairs, and a lookup never changes what it offers")
    void rankSpotsUnchangedByLookup() {
        BriefingRegion coast = AskFixtures.region("Coast", true,
                AskFixtures.slot(1L, "Alnmouth", 4), AskFixtures.outlier(AskFixtures.slot(2L, "Rocks", 5)));
        AskTools tools = tools(snapshotOf(coast));

        tools.lookUpPlace(name("rocks"));
        AskToolResult<AskTools.RankSpotsResult> ranked = tools.rankSpots(
                new RankSpotsArgs(null, null, null, null, null, null));

        assertThat(ranked.payload().spots()).extracting(AskTools.SpotInfo::name).containsExactly("Alnmouth");
        assertThat(tools.evidence().pairs()).containsExactly(new AskEvidence.Pair(1L, TODAY_SUNSET));
    }

    @Test
    @DisplayName("the result is counted against the conversation's character cap like any other")
    void countsAgainstTheCharCap() {
        AskTools tools = tools(snapshotOf(AskFixtures.region("Coast", true, AskFixtures.slot(1L, "Alnmouth", 4))));

        AskToolResult<LookUpPlaceResult> result = tools.lookUpPlace(name("alnmouth"));

        assertThat(tools.charsUsed()).isEqualTo(result.content().length()).isPositive();
        assertThat(result.content()).contains("\"status\":\"PICK\"").doesNotContain("reasons")
                .doesNotContain("\"note\"");
    }

    // -- the second review: status order and the payload's size -------------------------------

    @Test
    @DisplayName("a 2-star place is BELOW_GAME_ON whatever its region or mark: the rating is the honest reason")
    void lowRatingBeatsThinSampleAndOutlier() {
        assertThat(lookUp(AskFixtures.region("Wall", false, AskFixtures.slot(1L, "Alnmouth", 2)), "Alnmouth")
                .status()).isEqualTo("BELOW_GAME_ON");
        assertThat(lookUp(AskFixtures.region("Wall", true,
                AskFixtures.outlier(AskFixtures.slot(1L, "Alnmouth", 2))), "Alnmouth").status())
                .isEqualTo("BELOW_GAME_ON");
        assertThat(lookUp(AskFixtures.region("Wall", false,
                AskFixtures.outlier(AskFixtures.slot(1L, "Alnmouth", 1))), "Alnmouth").status())
                .isEqualTo("BELOW_GAME_ON");
        assertThat(reason(lookUp(AskFixtures.region("Wall", false, AskFixtures.slot(1L, "Alnmouth", 2)),
                "Alnmouth"))).isNull();
    }

    @Test
    @DisplayName("a 3-star outlier in a thin region is NEIGHBOUR_OUTLIER, and a 3-star place in one is THIN_SAMPLE")
    void floorBoundaryKeepsTheGuardOrder() {
        assertThat(lookUp(AskFixtures.region("Wall", false,
                AskFixtures.outlier(AskFixtures.slot(1L, "Alnmouth", 3))), "Alnmouth").status())
                .isEqualTo("NEIGHBOUR_OUTLIER");
        assertThat(lookUp(AskFixtures.region("Wall", false, AskFixtures.slot(1L, "Alnmouth", 3)), "Alnmouth")
                .status()).isEqualTo("THIN_SAMPLE");
    }

    /** One place per name, in a region with every kind of status in play, long text everywhere. */
    private static BriefingSlot longSlot(long id, String placeName, Integer rating, boolean wood, boolean outlier) {
        BriefingSlot base = wood ? AskFixtures.wood(id, placeName, rating)
                : AskFixtures.coastal(id, placeName, rating, "HIGH", true, "T".repeat(160));
        BriefingSlot withHeadline = AskFixtures.withHeadline(base, "H".repeat(200));
        return outlier ? AskFixtures.outlier(withHeadline) : withHeadline;
    }

    /** The given number of sunsets, each holding four matching places of four different statuses. */
    private static AskSnapshot worstCaseSnapshot(int windows) {
        String place = "Alnmouth Estuary And Saltmarsh Nature Reserve Car Park ";
        List<com.gregochr.goldenhour.model.BriefingDay> days = new ArrayList<>();
        for (int d = 0; d < windows; d++) {
            // Four matching places in four differently-statused regions, so every reason can be present.
            BriefingRegion unrated = AskFixtures.region("The North Yorkshire Moors And Coast Region", true,
                    longSlot(4L, place + "D", null, false, false));
            BriefingRegion wood = AskFixtures.region("Northumberland Coast And Tyneside Region", true,
                    longSlot(3L, place + "C", 5, true, false));
            BriefingRegion thin = AskFixtures.region("The Lake District National Park Region", false,
                    longSlot(1L, place + "A", 4, false, false));
            BriefingRegion held = AskFixtures.region("The Yorkshire Dales National Park Region", true,
                    longSlot(2L, place + "B", 5, false, true));
            days.add(AskFixtures.sunsetDay(TODAY.plusDays(d), null, unrated, wood, thin, held));
        }
        return AskFixtures.snapshotOf(AskFixtures.briefing(days, List.of()));
    }

    @Test
    @DisplayName("worst case: four long-named places over many windows are cut to MAX_PLACE_ROWS rows, say so in "
            + "the note, and serialise to at most 4,500 characters (half the cap, as list_windows is held)")
    void worstCasePayloadFitsHalfTheCap() {
        AskToolResult<LookUpPlaceResult> result = tools(worstCaseSnapshot(6)).lookUpPlace(name("alnmouth estuary"));

        LookUpPlaceResult payload = ok(result);
        assertThat(AskTools.MAX_PLACE_ROWS).isEqualTo(6);
        assertThat(payload.places()).hasSize(AskTools.MAX_PLACE_ROWS);
        assertThat(payload.note()).isEqualTo("Showing 6 of 24 place-windows; pass windowIds to narrow.");
        assertThat(payload.places()).extracting(PlaceInfo::headline)
                .allMatch(h -> h.length() == AskTools.HEADLINE_CAP);
        System.out.println("[look_up_place] worst-case payload chars (6 windows): " + result.content().length());
        assertThat(result.content().length()).isLessThanOrEqualTo(WORST_CASE_CHARS);
    }

    @Test
    @DisplayName("worst case with every reason-bearing status present: each reason is said once in 'reasons', "
            + "not on every row, and the payload still fits half the cap")
    void reasonsAreSaidOncePerStatus() {
        AskToolResult<LookUpPlaceResult> result = tools(worstCaseSnapshot(1)).lookUpPlace(name("alnmouth estuary"));
        LookUpPlaceResult payload = ok(result);

        assertThat(payload.places()).hasSize(4);
        assertThat(payload.places()).extracting(PlaceInfo::status).containsExactly(
                "THIN_SAMPLE", "NEIGHBOUR_OUTLIER", "CANOPY", "UNRATED");
        assertThat(payload.reasons()).containsOnlyKeys("UNRATED", "CANOPY", "THIN_SAMPLE", "NEIGHBOUR_OUTLIER");
        assertThat(payload.note()).isNull();
        assertThat(result.content().split("Too few places in this region", -1)).hasSize(2);
        System.out.println("[look_up_place] worst-case payload chars (all reasons): " + result.content().length());
        assertThat(result.content().length()).isLessThanOrEqualTo(WORST_CASE_CHARS);
    }

    @Test
    @DisplayName("rows are cut in order: the exact match's windows first, then the others', each in window order")
    void rowCapKeepsTheExactMatchFirst() {
        BriefingRegion coast = AskFixtures.region("Coast", true, AskFixtures.slot(1L, "Alnmouth Bay", 4),
                AskFixtures.slot(2L, "Alnmouth", 4));
        List<com.gregochr.goldenhour.model.BriefingDay> days = new ArrayList<>();
        for (int d = 0; d < 6; d++) {
            days.add(AskFixtures.sunsetDay(TODAY.plusDays(d), null, coast));
        }

        LookUpPlaceResult payload = ok(tools(AskFixtures.snapshotOf(AskFixtures.briefing(days, List.of())))
                .lookUpPlace(name("alnmouth")));

        assertThat(payload.places()).extracting(PlaceInfo::locationId)
                .containsExactly(2L, 2L, 2L, 2L, 2L, 2L);
        assertThat(payload.places().subList(0, 6)).extracting(PlaceInfo::windowId).isSorted();
        assertThat(payload.note()).isEqualTo("Showing 6 of 12 place-windows; pass windowIds to narrow.");
    }

    @Test
    @DisplayName("both notes together when places and rows are cut")
    void bothNotesCombine() {
        List<BriefingSlot> slots = new ArrayList<>();
        for (long id = 1; id <= 6; id++) {
            slots.add(AskFixtures.slot(id, "Beach " + (char) ('A' + id - 1), 4));
        }
        BriefingRegion coast = AskFixtures.region("Coast", true, slots.toArray(BriefingSlot[]::new));
        List<com.gregochr.goldenhour.model.BriefingDay> days = new ArrayList<>();
        for (int d = 0; d < 3; d++) {
            days.add(AskFixtures.sunsetDay(TODAY.plusDays(d), null, coast));
        }

        LookUpPlaceResult payload = ok(tools(AskFixtures.snapshotOf(AskFixtures.briefing(days, List.of())))
                .lookUpPlace(name("beach")));

        assertThat(payload.note()).isEqualTo("2 more places match; use a longer name. "
                + "Showing 6 of 12 place-windows; pass windowIds to narrow.");
    }

    // -- E1: the forecast's own reason (ask-place-explain plan §4) -----------------------------

    /** The slot with the evaluation's reason fields served; its combined rating and headline are kept. */
    private static BriefingSlot explained(BriefingSlot base, Integer sky, Integer fiery, Integer golden,
            String summary) {
        return base.withClaudeScores(base.claudeRating(), sky, fiery, golden, summary, base.claudeHeadline());
    }

    private static AskSnapshot daysOf(int days, BriefingRegion... regions) {
        List<com.gregochr.goldenhour.model.BriefingDay> list = new ArrayList<>();
        for (int d = 0; d < days; d++) {
            list.add(AskFixtures.sunsetDay(TODAY.plusDays(d), null, regions));
        }
        return AskFixtures.snapshotOf(AskFixtures.briefing(list, List.of()));
    }

    @Test
    @DisplayName("one place in one window carries the full detail; the sky score may sit above the combined star")
    void detailFullAndSkyAboveTheStar() {
        BriefingSlot slot = explained(AskFixtures.coastal(1L, "Bamburgh", 3, "LOW", false, "Wrong water"),
                4, 62, 71, "Thick low cloud at the western horizon with a narrow strip of clear sky above it.");

        PlaceInfo info = only(ok(tools(snapshotOf(AskFixtures.region("Coast", true, slot)))
                .lookUpPlace(name("bamburgh"))));

        assertThat(info.rating()).isEqualTo(3);
        assertThat(info.detail()).isEqualTo(new PlaceDetail(
                "Thick low cloud at the western horizon with a narrow strip of clear sky above it.", 4, 62, 71));
    }

    @Test
    @DisplayName("the summary has its quotes swapped for apostrophes, its backslashes dropped, and is capped")
    void detailSummaryIsQuoteSafeAndCapped() {
        String raw = "He said \"go\" at C:\\east " + "x".repeat(300);
        BriefingSlot slot = explained(AskFixtures.slot(1L, "Bamburgh", 4), 4, 50, 50, raw);

        PlaceDetail detail = only(ok(tools(snapshotOf(AskFixtures.region("Coast", true, slot)))
                .lookUpPlace(name("bamburgh")))).detail();

        assertThat(detail.summary()).hasSize(AskTools.SUMMARY_CAP).startsWith("He said 'go' at C:east x")
                .endsWith("…").doesNotContain("\"").doesNotContain("\\");
    }

    @Test
    @DisplayName("five windows of one place: four rows, all with detail, and the note says 4 of 5")
    void detailRowsAreCappedAtFour() {
        BriefingSlot slot = explained(AskFixtures.slot(1L, "Bamburgh", 4), 4, 50, 60, "Broken high cloud.");

        LookUpPlaceResult result = ok(tools(daysOf(5, AskFixtures.region("Coast", true, slot)))
                .lookUpPlace(name("bamburgh")));

        assertThat(AskTools.MAX_PLACE_ROWS_WITH_DETAIL).isEqualTo(4);
        assertThat(result.places()).hasSize(4).extracting(PlaceInfo::detail).doesNotContainNull();
        assertThat(result.places()).extracting(PlaceInfo::windowId).isSorted();
        assertThat(result.note()).isEqualTo("Showing 4 of 5 place-windows; pass windowIds to narrow.");
    }

    @Test
    @DisplayName("without detail the row cap stays 6 (nothing served to explain)")
    void noDetailKeepsTheSixRowCap() {
        LookUpPlaceResult result = ok(tools(daysOf(7,
                AskFixtures.region("Coast", true, AskFixtures.slot(1L, "Bamburgh", 4))))
                .lookUpPlace(name("bamburgh")));

        assertThat(result.places()).hasSize(6).extracting(PlaceInfo::detail).containsOnlyNulls();
        assertThat(result.note()).isEqualTo("Showing 6 of 7 place-windows; pass windowIds to narrow.");
    }

    @Test
    @DisplayName("two places and no exact name: neither carries detail, since the answer is not about one of them")
    void twoNonExactPlacesGetNoDetail() {
        BriefingRegion coast = AskFixtures.region("Coast", true,
                explained(AskFixtures.slot(1L, "Alnmouth Bay", 4), 4, 50, 50, "Clear east."),
                explained(AskFixtures.slot(2L, "Alnmouth Cove", 4), 4, 50, 50, "Clear east."));

        LookUpPlaceResult result = ok(tools(snapshotOf(coast)).lookUpPlace(name("alnmouth")));

        assertThat(result.places()).hasSize(2).extracting(PlaceInfo::detail).containsOnlyNulls();
    }

    @Test
    @DisplayName("an exact place beside a partial one: detail on the exact place only")
    void exactPlusPartialDetailsTheExactOnly() {
        BriefingRegion coast = AskFixtures.region("Coast", true,
                explained(AskFixtures.slot(1L, "Alnmouth Bay", 4), 4, 50, 50, "Cove reason."),
                explained(AskFixtures.slot(2L, "Alnmouth", 4), 4, 50, 50, "Exact reason."));

        LookUpPlaceResult result = ok(tools(snapshotOf(coast)).lookUpPlace(name("alnmouth")));

        assertThat(names(result)).containsExactly("Alnmouth", "Alnmouth Bay");
        assertThat(result.places().get(0).detail().summary()).isEqualTo("Exact reason.");
        assertThat(result.places().get(1).detail()).isNull();
    }

    @Test
    @DisplayName("a single non-exact place is the place the answer is about, so it carries detail")
    void aloneIsAboutThatPlace() {
        BriefingRegion coast = AskFixtures.region("Coast", true,
                explained(AskFixtures.slot(1L, "Alnmouth Bay", 4), 4, 50, 50, "Cove reason."));

        PlaceInfo info = only(ok(tools(snapshotOf(coast)).lookUpPlace(name("alnmouth"))));

        assertThat(info.detail()).isNotNull();
    }

    @Test
    @DisplayName("UNRATED, NEIGHBOUR_OUTLIER and the 'not forecast' placeholder carry no detail")
    void noDetailForDisownedRows() {
        BriefingSlot unrated = explained(AskFixtures.slot(1L, "Bamburgh", null), 4, 50, 50, "Some prose.");
        BriefingSlot outlier = AskFixtures.outlier(
                explained(AskFixtures.slot(2L, "Alnmouth", 5), 5, 50, 50, "Some prose."));
        BriefingSlot placeholder = explained(AskFixtures.slot(3L, "Seaham", 1), 1, 0, 0,
                ForecastResultHandler.SKY_NOT_FORECAST_SUMMARY);
        BriefingRegion coast = AskFixtures.region("Coast", true, unrated, outlier, placeholder);

        for (String place : List.of("bamburgh", "alnmouth", "seaham")) {
            AskToolResult<LookUpPlaceResult> result = tools(snapshotOf(coast)).lookUpPlace(name(place));
            assertThat(only(ok(result)).detail()).as(place).isNull();
            assertThat(result.content()).as(place).doesNotContain("\"detail\"").doesNotContain("Claude did not");
        }
        assertThat(only(ok(tools(snapshotOf(coast)).lookUpPlace(name("alnmouth")))).status())
                .isEqualTo("NEIGHBOUR_OUTLIER");
    }

    @Test
    @DisplayName("a wood keeps its summary only: no sky score and no potentials")
    void canopyGetsTheSummaryOnly() {
        BriefingSlot wood = explained(AskFixtures.wood(1L, "Hamsterley Forest", 5), 5, 80, 80,
                "Soft light under the canopy.");

        PlaceInfo info = only(ok(tools(snapshotOf(AskFixtures.region("Forest", true, wood)))
                .lookUpPlace(name("hamsterley"))));

        assertThat(info.status()).isEqualTo("CANOPY");
        assertThat(info.detail()).isEqualTo(new PlaceDetail("Soft light under the canopy.", null, null, null));
    }

    @Test
    @DisplayName("a slot with nothing served has no detail key at all in the content")
    void allNullFieldsMeansNoDetailKey() {
        AskToolResult<LookUpPlaceResult> result = tools(snapshotOf(
                AskFixtures.region("Coast", true, AskFixtures.withHeadline(AskFixtures.slot(1L, "Bamburgh", 4), "h"))))
                .lookUpPlace(name("bamburgh"));

        assertThat(only(ok(result)).detail()).isNull();
        assertThat(result.content()).doesNotContain("detail");
    }

    @Test
    @DisplayName("rank_spots is byte-identical for a slot with and without the forecast's reason fields")
    void rankSpotsIgnoresTheReasonFields() {
        BriefingSlot plain = AskFixtures.coastal(1L, "Bamburgh", 4, "HIGH", true);
        BriefingSlot rich = explained(plain, 5, 60, 70, "Clear east.");
        RankSpotsArgs args = new RankSpotsArgs(null, null, null, null, null, null);

        String without = tools(snapshotOf(AskFixtures.region("Coast", true, plain))).rankSpots(args).content();
        String with = tools(snapshotOf(AskFixtures.region("Coast", true, rich))).rankSpots(args).content();

        assertThat(with).isEqualTo(without).doesNotContain("detail").doesNotContain("Clear east");
    }

    /** A worst-case slot: long tide fit and headline, and a summary of quotes and backslashes at twice its cap. */
    private static BriefingSlot worstExplained(long id, String placeName) {
        String nasty = "\"\\".repeat(200);
        return explained(AskFixtures.withHeadline(
                AskFixtures.coastal(id, placeName, 4, "HIGH", true, "T".repeat(160)), "H".repeat(200)),
                5, 100, 100, nasty + "abc".repeat(100));
    }

    @Test
    @DisplayName("worst case with detail, six windows of one long-named place: four rows with 200-character "
            + "summaries still serialise to at most 4,500 characters")
    void detailWorstCaseSixWindowsFitsHalfTheCap() {
        String place = "Alnmouth Estuary And Saltmarsh Nature Reserve Car Park";
        // Thin sample: the status reason is on the payload as well.
        AskSnapshot snapshot = daysOf(6, AskFixtures.region("The North Yorkshire Moors And Coast Region", false,
                worstExplained(1L, place)));

        AskToolResult<LookUpPlaceResult> result = tools(snapshot).lookUpPlace(name("alnmouth estuary"));
        LookUpPlaceResult payload = ok(result);

        assertThat(payload.places()).hasSize(AskTools.MAX_PLACE_ROWS_WITH_DETAIL);
        assertThat(payload.places()).extracting(p -> p.detail().summary().length())
                .containsOnly(AskTools.SUMMARY_CAP);
        assertThat(payload.note()).isEqualTo("Showing 4 of 6 place-windows; pass windowIds to narrow.");
        System.out.println("[look_up_place] detail worst-case payload chars (6 windows): "
                + result.content().length());
        assertThat(result.content().length()).isLessThanOrEqualTo(WORST_CASE_CHARS);
    }

    @Test
    @DisplayName("worst case with detail for a place found by a 40-character alias: four rows still fit 4,500")
    void detailWorstCaseAliasFitsHalfTheCap() {
        String place = "Hartlepool Headland And The Old Town Wall Nature Reserve Car Park";
        AskSnapshot base = daysOf(6, AskFixtures.region("The North Yorkshire Moors And Coast Region", false,
                worstExplained(1L, place)));
        String alias = "Heugh " + "x".repeat(34);
        AskSnapshot snapshot = base.withNames(List.of(), Map.of(1L, List.of(alias)));

        AskToolResult<LookUpPlaceResult> result = tools(snapshot).lookUpPlace(new LookUpPlaceArgs("heugh", null));
        LookUpPlaceResult payload = ok(result);

        assertThat(payload.places()).hasSize(AskTools.MAX_PLACE_ROWS_WITH_DETAIL);
        assertThat(payload.places()).extracting(PlaceInfo::matchedAlias).containsOnly(alias);
        System.out.println("[look_up_place] detail alias worst-case payload chars: " + result.content().length());
        assertThat(result.content().length()).isLessThanOrEqualTo(WORST_CASE_CHARS);
    }
}
