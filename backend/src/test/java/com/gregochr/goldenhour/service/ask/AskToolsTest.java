package com.gregochr.goldenhour.service.ask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.model.comingup.ComingUpEntry;
import com.gregochr.goldenhour.service.DriveTimeResolver;
import com.gregochr.goldenhour.service.EclipseHotTopicStrategy;
import com.gregochr.goldenhour.service.ask.AskTools.ComingUpArgs;
import com.gregochr.goldenhour.service.ask.AskTools.ComingUpResult;
import com.gregochr.goldenhour.service.ask.AskTools.HotTopicsArgs;
import com.gregochr.goldenhour.service.ask.AskTools.HotTopicsResult;
import com.gregochr.goldenhour.service.ask.AskTools.ListWindowsResult;
import com.gregochr.goldenhour.service.ask.AskTools.RankSpotsArgs;
import com.gregochr.goldenhour.service.ask.AskTools.RankSpotsResult;
import com.gregochr.goldenhour.service.ask.AskTools.SpotInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.gregochr.goldenhour.service.ask.AskFixtures.TODAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link AskTools}: what each tool may return, and the conversation's limits. */
class AskToolsTest {

    private static final LocalDate TOMORROW = TODAY.plusDays(1);
    private static final AskUserContext USER = new AskUserContext(7L, UserRole.PRO_USER, true);

    private final ObjectMapper mapper = new ObjectMapper();
    private final DriveTimeResolver driveTimes = mock(DriveTimeResolver.class);

    private AskTools tools(AskSnapshot snapshot) {
        return new AskTools(snapshot, AskUserContext.userLess(), TestScopes.of(), driveTimes, mapper);
    }

    private AskTools tools(AskSnapshot snapshot, AskUserContext user, AskScope scope) {
        return new AskTools(snapshot, user, scope, driveTimes, mapper);
    }

    private static AskSnapshot snapshotOfRegions(BriefingRegion... regions) {
        return AskFixtures.snapshotOf(AskFixtures.briefing(
                List.of(AskFixtures.sunsetDay(TODAY, null, regions)), List.of()));
    }

    private static List<SpotInfo> spots(AskToolResult<RankSpotsResult> result) {
        assertThat(result.error()).as(result.content()).isFalse();
        return result.payload().spots();
    }

    private static RankSpotsArgs rank(Integer limit) {
        return new RankSpotsArgs(null, null, null, null, null, limit);
    }

    // -- eligibility ------------------------------------------------------------------------

    @Test
    @DisplayName("the 2026-09-29 shape: three hand-run 4-star slots in an ineligible region of 40 are never returned")
    void rankSpots_neverReturnsRatingsFromAnIneligibleRegion() {
        List<BriefingSlot> slots = new ArrayList<>();
        for (long i = 1; i <= 37; i++) {
            slots.add(AskFixtures.slot(i, "Unrated " + i, null));
        }
        slots.add(AskFixtures.slot(38L, "Forced A", 4));
        slots.add(AskFixtures.slot(39L, "Forced B", 4));
        slots.add(AskFixtures.slot(40L, "Forced C", 4));
        BriefingRegion thin = AskFixtures.region("Wide Region", false, slots.toArray(BriefingSlot[]::new));
        BriefingRegion sound = AskFixtures.region("Sound Region", true,
                AskFixtures.slot(101L, "Steady", 3));

        List<SpotInfo> found = spots(tools(snapshotOfRegions(thin, sound)).rankSpots(rank(8)));

        assertThat(found).extracting(SpotInfo::name).containsExactly("Steady");
    }

    @Test
    @DisplayName("a 5-star wood beside a 3-star headland: the wood is never returned")
    void rankSpots_neverReturnsAWood() {
        BriefingRegion region = AskFixtures.region("Coast", true,
                AskFixtures.wood(1L, "Hollin Wood", 5), AskFixtures.slot(2L, "Headland", 3));

        List<SpotInfo> found = spots(tools(snapshotOfRegions(region)).rankSpots(rank(8)));

        assertThat(found).extracting(SpotInfo::name).containsExactly("Headland");
    }

    @Test
    @DisplayName("2 stars is not offered, 3 stars is: the floor is inclusive")
    void rankSpots_ratingFloorIsThree() {
        BriefingRegion region = AskFixtures.region("Coast", true,
                AskFixtures.slot(1L, "Two", 2), AskFixtures.slot(2L, "Three", 3),
                AskFixtures.slot(3L, "Unrated", null));

        List<SpotInfo> found = spots(tools(snapshotOfRegions(region)).rankSpots(rank(8)));

        assertThat(found).extracting(SpotInfo::name).containsExactly("Three");
    }

    @Test
    @DisplayName("a slot with no location id is skipped")
    void rankSpots_skipsASlotWithNoLocationId() {
        BriefingRegion region = AskFixtures.region("Coast", true,
                AskFixtures.slot(null, "Legacy", 5), AskFixtures.slot(2L, "Known", 4));

        List<SpotInfo> found = spots(tools(snapshotOfRegions(region)).rankSpots(rank(8)));

        assertThat(found).extracting(SpotInfo::name).containsExactly("Known");
    }

    @Test
    @DisplayName("nothing eligible: an empty list with a note, so 'nothing is worth it' is an answer")
    void rankSpots_nothingEligible_hasNote() {
        BriefingRegion region = AskFixtures.region("Coast", true, AskFixtures.slot(1L, "Poor", 2));
        AskTools tools = tools(snapshotOfRegions(region));

        AskToolResult result = tools.rankSpots(rank(5));

        RankSpotsResult payload = (RankSpotsResult) result.payload();
        assertThat(payload.spots()).isEmpty();
        assertThat(payload.note()).isEqualTo(AskTools.NOTHING_ELIGIBLE_NOTE);
        assertThat(tools.evidence().pairs()).isEmpty();
        assertThat(tools.evidence().anyToolCalled()).isTrue();
    }

    @Test
    @DisplayName("filters that leave nothing say so with a different note")
    void rankSpots_filtersLeaveNothing_hasFilterNote() {
        BriefingRegion region = AskFixtures.region("Coast", true, AskFixtures.slot(1L, "Inland", 4));

        RankSpotsResult payload = (RankSpotsResult) tools(snapshotOfRegions(region))
                .rankSpots(new RankSpotsArgs(null, null, true, null, null, 5)).payload();

        assertThat(payload.spots()).isEmpty();
        assertThat(payload.note()).isEqualTo(AskTools.NOTHING_MATCHES_NOTE);
    }

    // -- tide -------------------------------------------------------------------------------

    @Test
    @DisplayName("a HIGH-state slot whose location wants LOW: tideState HIGH, tideAligned false, both present")
    void rankSpots_keepsTideStateAndPreferenceSeparate() {
        BriefingRegion region = AskFixtures.region("Coast", true,
                AskFixtures.coastal(1L, "Wants Low", 4, "HIGH", false),
                AskFixtures.coastal(2L, "Wants High", 4, "HIGH", true));

        AskToolResult result = tools(snapshotOfRegions(region)).rankSpots(rank(5));

        List<SpotInfo> found = spots(result);
        SpotInfo wantsLow = found.stream().filter(s -> s.locationId() == 1L).findFirst().orElseThrow();
        assertThat(wantsLow.tideState()).isEqualTo("HIGH");
        assertThat(wantsLow.tideAligned()).isFalse();
        JsonNode json = parse(result.content()).path("spots");
        assertThat(json.findValues("tideAligned")).extracting(JsonNode::asBoolean)
                .containsExactlyInAnyOrder(false, true);
        assertThat(json.findValues("tideState")).extracting(JsonNode::asText)
                .containsExactly("HIGH", "HIGH");
    }

    @Test
    @DisplayName("an aligned coastal spot sorts before an unaligned one of equal rating")
    void rankSpots_tideAlignedBreaksATie() {
        BriefingRegion region = AskFixtures.region("Coast", true,
                AskFixtures.coastal(1L, "Aaa Unaligned", 4, "HIGH", false),
                AskFixtures.coastal(2L, "Zzz Aligned", 4, "HIGH", true));

        List<SpotInfo> found = spots(tools(snapshotOfRegions(region)).rankSpots(rank(5)));

        assertThat(found).extracting(SpotInfo::name).containsExactly("Zzz Aligned", "Aaa Unaligned");
    }

    @Test
    @DisplayName("an inland spot has neither a tide state nor a tide preference field")
    void rankSpots_inlandSpotHasNoTideFields() {
        BriefingRegion region = AskFixtures.region("Coast", true, AskFixtures.slot(1L, "Inland", 4));

        AskToolResult result = tools(snapshotOfRegions(region)).rankSpots(rank(5));

        JsonNode spot = parse(result.content()).path("spots").get(0);
        assertThat(spot.has("tideState")).isFalse();
        assertThat(spot.has("tideAligned")).isFalse();
    }

    @Test
    @DisplayName("coastalOnly and tideState filter on the served state, case-insensitively")
    void rankSpots_tideFilters() {
        BriefingRegion region = AskFixtures.region("Coast", true,
                AskFixtures.slot(1L, "Inland", 5),
                AskFixtures.coastal(2L, "High", 4, "HIGH", true),
                AskFixtures.coastal(3L, "Low", 4, "LOW", true));
        AskSnapshot snapshot = snapshotOfRegions(region);

        assertThat(spots(tools(snapshot).rankSpots(
                new RankSpotsArgs(null, null, true, null, null, 8))))
                .extracting(SpotInfo::name).containsExactly("High", "Low");
        assertThat(spots(tools(snapshot).rankSpots(
                new RankSpotsArgs(null, null, null, "low", null, 8))))
                .extracting(SpotInfo::name).containsExactly("Low");
    }

    // -- ordering and the forecast's own pick -----------------------------------------------

    @Test
    @DisplayName("the BEST BET location sorts first among equal ratings, then ALSO GOOD, then the rest by name")
    void rankSpots_pickLocationSortsFirstAmongEqualRatings() {
        BriefingWindow.Pick best = AskFixtures.pick(BriefingWindow.PickKind.BEST, "Coast", "Zulu", 3L);
        BriefingRegion region = AskFixtures.region("Coast", true,
                AskFixtures.slot(1L, "Alpha", 4), AskFixtures.slot(2L, "Bravo", 4),
                AskFixtures.slot(3L, "Zulu", 4), AskFixtures.slot(4L, "Top", 5));
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(
                List.of(AskFixtures.sunsetDay(TODAY, best, region)), List.of()));

        List<SpotInfo> found = spots(tools(snapshot).rankSpots(rank(8)));

        assertThat(found).extracting(SpotInfo::name).containsExactly("Top", "Zulu", "Alpha", "Bravo");
        assertThat(found.get(1).pick()).isEqualTo("BEST");
        assertThat(found.get(0).pick()).isNull();
    }

    @Test
    @DisplayName("an ALSO GOOD pick with no id is matched by name and sorts after BEST BET")
    void rankSpots_alsoGoodMatchedByName() {
        BriefingWindow.Pick also = AskFixtures.pick(BriefingWindow.PickKind.ALSO, "Coast", "Bravo", null);
        BriefingRegion region = AskFixtures.region("Coast", true,
                AskFixtures.slot(1L, "Alpha", 4), AskFixtures.slot(2L, "Bravo", 4));
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(
                List.of(AskFixtures.sunsetDay(TODAY, also, region)), List.of()));

        List<SpotInfo> found = spots(tools(snapshot).rankSpots(rank(8)));

        assertThat(found).extracting(SpotInfo::name).containsExactly("Bravo", "Alpha");
        assertThat(found.getFirst().pick()).isEqualTo("ALSO");
    }

    @Test
    @DisplayName("rating outranks the forecast's pick")
    void rankSpots_ratingOutranksThePick() {
        BriefingWindow.Pick best = AskFixtures.pick(BriefingWindow.PickKind.BEST, "Coast", "Low", 1L);
        BriefingRegion region = AskFixtures.region("Coast", true,
                AskFixtures.slot(1L, "Low", 3), AskFixtures.slot(2L, "High", 5));
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(
                List.of(AskFixtures.sunsetDay(TODAY, best, region)), List.of()));

        assertThat(spots(tools(snapshot).rankSpots(rank(8))))
                .extracting(SpotInfo::name).containsExactly("High", "Low");
    }

    @Test
    @DisplayName("list_windows exposes the served verdict, best rating and the pick with its location")
    void listWindows_exposesThePick() {
        BriefingWindow.Pick best = AskFixtures.pick(BriefingWindow.PickKind.BEST, "Coast", "Whitby", 7L);
        BriefingWindow.Pick also = AskFixtures.pick(BriefingWindow.PickKind.ALSO, "Hills", "Cat Bells", 8L);
        BriefingRegion region = AskFixtures.region("Coast", true, AskFixtures.slot(7L, "Whitby", 4));
        BriefingWindow sunsetWindow = AskFixtures.window(TODAY.atTime(18, 0), DisplayVerdict.WORTH_IT, 4, best);
        BriefingWindow sunriseWindow = AskFixtures.window(TOMORROW.atTime(5, 40), DisplayVerdict.MAYBE, 3, also);
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(List.of(
                AskFixtures.day(TODAY, AskFixtures.summary(TargetType.SUNSET, sunsetWindow, region)),
                AskFixtures.day(TOMORROW, AskFixtures.summary(TargetType.SUNRISE, sunriseWindow, region))),
                List.of()));

        ListWindowsResult result = (ListWindowsResult) tools(snapshot).listWindows().payload();

        assertThat(result.windows()).hasSize(2);
        AskTools.WindowInfo first = result.windows().getFirst();
        assertThat(first.id()).isEqualTo("2026-10-05_sunset");
        assertThat(first.day()).isEqualTo("Today");
        assertThat(first.event()).isEqualTo("sunset");
        assertThat(first.time()).as("18:00 UTC is 19:00 BST").isEqualTo("19:00");
        assertThat(first.verdict()).isEqualTo("WORTH_IT");
        assertThat(first.bestRating()).isEqualTo(4);
        assertThat(first.bestBet()).isEqualTo(new AskTools.PickInfo("Coast", "Whitby", 7L));
        assertThat(first.alsoGood()).isNull();
        AskTools.WindowInfo second = result.windows().get(1);
        assertThat(second.day()).isEqualTo("Tomorrow");
        assertThat(second.time()).isEqualTo("06:40");
        assertThat(second.alsoGood()).isEqualTo(new AskTools.PickInfo("Hills", "Cat Bells", 8L));
        assertThat(second.bestBet()).isNull();
    }

    @Test
    @DisplayName("list_windows serialises an UNRATED window by name, with no pick (unrated-verdict-plan.md U1)")
    void listWindows_servesUnratedByName() {
        BriefingRegion region = AskFixtures.region("Coast", true, AskFixtures.slot(7L, "Whitby", 4));
        BriefingWindow unrated = AskFixtures.window(TODAY.atTime(18, 0), DisplayVerdict.UNRATED, 4, null);
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(List.of(
                AskFixtures.day(TODAY, AskFixtures.summary(TargetType.SUNSET, unrated, region))),
                List.of()));

        ListWindowsResult result = (ListWindowsResult) tools(snapshot).listWindows().payload();

        AskTools.WindowInfo only = result.windows().getFirst();
        assertThat(only.verdict()).isEqualTo("UNRATED");
        assertThat(only.bestBet()).isNull();
        assertThat(only.alsoGood()).isNull();
    }

    @Test
    @DisplayName("list_windows names a weekday beyond tomorrow and omits a pick outside the question's scope")
    void listWindows_weekdayAndScope() {
        BriefingWindow.Pick best = AskFixtures.pick(BriefingWindow.PickKind.BEST, "Hills", "Cat Bells", 8L);
        BriefingRegion coast = AskFixtures.region("Coast", true, AskFixtures.slot(7L, "Whitby", 4));
        LocalDate saturday = LocalDate.of(2026, 10, 10);
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(
                List.of(AskFixtures.sunsetDay(saturday, best, coast)), List.of()));

        AskTools.WindowInfo info = ((ListWindowsResult) tools(snapshot, USER, TestScopes.of("coast"))
                .listWindows().payload()).windows().getFirst();

        assertThat(info.day()).isEqualTo("Saturday");
        assertThat(info.bestBet()).as("the pick's region is outside the scope").isNull();
    }

    // -- arguments and errors ---------------------------------------------------------------

    @Test
    @DisplayName("an unknown window id is an error result, not an exception, and counts as no evidence")
    void rankSpots_unknownWindowId_isAnErrorResult() {
        BriefingRegion region = AskFixtures.region("Coast", true, AskFixtures.slot(1L, "A", 4));
        AskTools tools = tools(snapshotOfRegions(region));

        AskToolResult result = tools.rankSpots(new RankSpotsArgs(List.of("2026-10-05_sunrise"),
                null, null, null, null, 5));

        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("Unknown window id '2026-10-05_sunrise'");
        assertThat(tools.evidence().pairs()).isEmpty();
        assertThat(tools.charsUsed()).isZero();
        assertThat(tools.evidence().toolCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("a malformed window id is an error result")
    void rankSpots_malformedWindowId_isAnErrorResult() {
        BriefingRegion region = AskFixtures.region("Coast", true, AskFixtures.slot(1L, "A", 4));

        AskToolResult result = tools(snapshotOfRegions(region)).rankSpots(
                new RankSpotsArgs(List.of("tonight"), null, null, null, null, 5));

        assertThat(result.error()).isTrue();
    }

    @Test
    @DisplayName("a named window restricts the ranking to that window")
    void rankSpots_windowFilter() {
        BriefingRegion region = AskFixtures.region("Coast", true, AskFixtures.slot(1L, "A", 4));
        BriefingWindow w1 = AskFixtures.window(TODAY.atTime(18, 0), DisplayVerdict.WORTH_IT, 4, null);
        BriefingWindow w2 = AskFixtures.window(TOMORROW.atTime(5, 40), DisplayVerdict.WORTH_IT, 4, null);
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(List.of(
                AskFixtures.day(TODAY, AskFixtures.summary(TargetType.SUNSET, w1, region)),
                AskFixtures.day(TOMORROW, AskFixtures.summary(TargetType.SUNRISE, w2, region))),
                List.of()));

        List<SpotInfo> found = spots(tools(snapshot).rankSpots(new RankSpotsArgs(
                List.of("2026-10-06_sunrise"), null, null, null, null, 5)));

        assertThat(found).extracting(SpotInfo::windowId).containsExactly("2026-10-06_sunrise");
    }

    // -- a Ready pick question's window bound -----------------------------------------------

    private static final String TODAY_SUNSET = "2026-10-05_sunset";
    private static final String TOMORROW_SUNRISE = "2026-10-06_sunrise";

    private AskTools boundedTools(AskSnapshot snapshot, List<String> bound) {
        return new AskTools(snapshot, AskUserContext.userLess(), TestScopes.of(), driveTimes, mapper,
                AskLocationNames.NONE, bound);
    }

    /** Two solar windows, today's sunset and tomorrow's sunrise, each holding one slot. */
    private static AskSnapshot twoWindowSnapshot(BriefingSlot todaySlot, BriefingSlot tomorrowSlot) {
        BriefingWindow w1 = AskFixtures.window(TODAY.atTime(18, 0), DisplayVerdict.WORTH_IT, 4, null);
        BriefingWindow w2 = AskFixtures.window(TOMORROW.atTime(5, 40), DisplayVerdict.WORTH_IT, 4, null);
        return AskFixtures.snapshotOf(AskFixtures.briefing(List.of(
                AskFixtures.day(TODAY, AskFixtures.summary(TargetType.SUNSET, w1,
                        AskFixtures.region("Coast", true, todaySlot))),
                AskFixtures.day(TOMORROW, AskFixtures.summary(TargetType.SUNRISE, w2,
                        AskFixtures.region("Coast", true, tomorrowSlot)))),
                List.of()));
    }

    private static AskSnapshot twoWindowSnapshot() {
        return twoWindowSnapshot(AskFixtures.slot(1L, "Today Spot", 4), AskFixtures.slot(2L, "Tomorrow Spot", 5));
    }

    @Test
    @DisplayName("a bounded rank_spots with no windowIds ranks only the bound windows, though a better "
            + "place sits in a window outside it")
    void rankSpots_bounded_noWindowIds_ranksOnlyTheBound() {
        List<SpotInfo> found = spots(boundedTools(twoWindowSnapshot(), List.of(TODAY_SUNSET)).rankSpots(rank(5)));

        assertThat(found).extracting(SpotInfo::windowId).containsOnly(TODAY_SUNSET);
        assertThat(found).extracting(SpotInfo::locationId).containsExactly(1L);
    }

    @Test
    @DisplayName("an unbounded rank_spots with no windowIds still ranks every window")
    void rankSpots_unbounded_ranksEveryWindow() {
        List<SpotInfo> found = spots(tools(twoWindowSnapshot()).rankSpots(rank(5)));

        assertThat(found).extracting(SpotInfo::windowId).containsExactlyInAnyOrder(TODAY_SUNSET, TOMORROW_SUNRISE);
    }

    @Test
    @DisplayName("a bounded rank_spots intersects named windowIds with the bound: the out-of-bound id is "
            + "dropped silently, with no error")
    void rankSpots_bounded_mixedIds_ranksOnlyTheInBoundOnes() {
        AskToolResult<RankSpotsResult> result = boundedTools(twoWindowSnapshot(), List.of(TODAY_SUNSET))
                .rankSpots(new RankSpotsArgs(List.of(TODAY_SUNSET, TOMORROW_SUNRISE), null, null, null, null, 5));

        assertThat(spots(result)).extracting(SpotInfo::windowId).containsOnly(TODAY_SUNSET);
    }

    @Test
    @DisplayName("a bounded rank_spots naming only out-of-bound windows is an error that names the allowed ids")
    void rankSpots_bounded_onlyOutOfBoundIds_isAnErrorNamingTheAllowedIds() {
        AskTools tools = boundedTools(twoWindowSnapshot(), List.of(TODAY_SUNSET));

        AskToolResult<RankSpotsResult> result = tools.rankSpots(
                new RankSpotsArgs(List.of(TOMORROW_SUNRISE), null, null, null, null, 5));

        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("None of those windows has a place this question can pick")
                .contains("Rank within: " + TODAY_SUNSET + ".");
        assertThat(tools.evidence().pairs()).isEmpty();
    }

    @Test
    @DisplayName("a bounded rank_spots still reports an unknown window id with the unknown-id message")
    void rankSpots_bounded_unknownId_keepsTheUnknownIdError() {
        AskToolResult<RankSpotsResult> result = boundedTools(twoWindowSnapshot(), List.of(TODAY_SUNSET))
                .rankSpots(new RankSpotsArgs(List.of("2026-10-09_sunset"), null, null, null, null, 5));

        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("Unknown window id '2026-10-09_sunset'");
    }

    @Test
    @DisplayName("COASTAL_HIGH: passing every covered window id ranks only the high-water window and does "
            + "not error, though the covered set is wider than the bound")
    void rankSpots_coastalHigh_coveredIdsRankOnlyTheHighWaterWindow() {
        AskSnapshot snapshot = twoWindowSnapshot(AskFixtures.coastal(1L, "Bamburgh", 4, "HIGH", true),
                AskFixtures.coastal(2L, "Seahouses", 5, "LOW", false));
        ReadyQuestion.Offer offer = ReadyQuestion.COASTAL_HIGH.offer(snapshot, AskScope.ALL).orElseThrow();
        assertThat(offer.windowIds()).containsExactly(TODAY_SUNSET);
        assertThat(offer.coveredWindowIds()).containsExactly(TODAY_SUNSET, TOMORROW_SUNRISE);

        AskToolResult<RankSpotsResult> result = boundedTools(snapshot, offer.windowIds()).rankSpots(
                new RankSpotsArgs(offer.coveredWindowIds(), null, null, null, null, 5));

        assertThat(spots(result)).extracting(SpotInfo::windowId, SpotInfo::locationId)
                .containsExactly(tuple(TODAY_SUNSET, 1L));
    }

    @Test
    @DisplayName("an unknown region name, a region outside the scope and a bad tide state are error results")
    void rankSpots_badRegionAndTideArguments() {
        BriefingRegion coast = AskFixtures.region("Coast", true, AskFixtures.slot(1L, "A", 4));
        BriefingRegion hills = AskFixtures.region("Hills", true, AskFixtures.slot(2L, "B", 4));
        AskSnapshot snapshot = snapshotOfRegions(coast, hills);

        AskToolResult unknown = tools(snapshot).rankSpots(
                new RankSpotsArgs(null, List.of("Atlantis"), null, null, null, 5));
        AskToolResult outside = tools(snapshot, USER, TestScopes.of("Coast")).rankSpots(
                new RankSpotsArgs(null, List.of("hills"), null, null, null, 5));
        AskToolResult badTide = tools(snapshot).rankSpots(
                new RankSpotsArgs(null, null, null, "FLOOD", null, 5));

        assertThat(unknown.error()).isTrue();
        assertThat(unknown.content()).contains("Unknown region 'Atlantis'");
        assertThat(outside.error()).isTrue();
        assertThat(outside.content()).contains("outside the scope");
        assertThat(badTide.error()).isTrue();
        assertThat(badTide.content()).contains("HIGH, MID or LOW");
    }

    @Test
    @DisplayName("a region filter matches case-insensitively and the question's scope narrows every call")
    void rankSpots_regionFilterAndScope() {
        BriefingRegion coast = AskFixtures.region("Coast", true, AskFixtures.slot(1L, "A", 4));
        BriefingRegion hills = AskFixtures.region("Hills", true, AskFixtures.slot(2L, "B", 5));
        AskSnapshot snapshot = snapshotOfRegions(coast, hills);

        assertThat(spots(tools(snapshot).rankSpots(
                new RankSpotsArgs(null, List.of("COAST"), null, null, null, 5))))
                .extracting(SpotInfo::name).containsExactly("A");
        assertThat(spots(tools(snapshot, USER, TestScopes.of("coast")).rankSpots(rank(5))))
                .extracting(SpotInfo::name).containsExactly("A");
    }

    @Test
    @DisplayName("limit defaults to 5, is capped at 8 and floored at 1")
    void rankSpots_limitIsClamped() {
        List<BriefingSlot> slots = new ArrayList<>();
        for (long i = 1; i <= 10; i++) {
            slots.add(AskFixtures.slot(i, "Spot " + i, 4));
        }
        AskSnapshot snapshot = snapshotOfRegions(
                AskFixtures.region("Coast", true, slots.toArray(BriefingSlot[]::new)));

        assertThat(spots(tools(snapshot).rankSpots(null))).hasSize(5);
        assertThat(spots(tools(snapshot).rankSpots(rank(99)))).hasSize(8);
        assertThat(spots(tools(snapshot).rankSpots(rank(0)))).hasSize(1);
        assertThat(spots(tools(snapshot).rankSpots(rank(-3)))).hasSize(1);
    }

    @Test
    @DisplayName("a headline is cut to 120 characters")
    void rankSpots_headlineIsCapped() {
        BriefingRegion region = AskFixtures.region("Coast", true,
                AskFixtures.withHeadline(AskFixtures.slot(1L, "A", 4), "x".repeat(300)));

        SpotInfo spot = spots(tools(snapshotOfRegions(region)).rankSpots(rank(1))).getFirst();

        assertThat(spot.headline()).hasSize(AskTools.HEADLINE_CAP).endsWith("…");
    }

    // -- drive times and personal answers ---------------------------------------------------

    @Test
    @DisplayName("maxDriveMinutes is refused in a user-less conversation and leaves it non-personal")
    void rankSpots_driveLimitRefusedWithoutAUser() {
        BriefingRegion region = AskFixtures.region("Coast", true, AskFixtures.slot(1L, "A", 4));
        AskTools tools = tools(snapshotOfRegions(region));

        AskToolResult result = tools.rankSpots(
                new RankSpotsArgs(null, null, null, null, 60, 5));

        assertThat(result.error()).isTrue();
        assertThat(tools.personal()).isFalse();
        verify(driveTimes, never()).getAllMinutes(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("maxDriveMinutes filters on the asker's own times, reports them and makes the answer personal")
    void rankSpots_driveLimitFiltersAndMarksPersonal() {
        BriefingRegion region = AskFixtures.region("Coast", true,
                AskFixtures.slot(1L, "Near", 4), AskFixtures.slot(2L, "Far", 5),
                AskFixtures.slot(3L, "Unknown", 5), AskFixtures.slot(4L, "Edge", 3));
        when(driveTimes.getAllMinutes(7L)).thenReturn(Map.of(1L, 30, 2L, 90, 4L, 60));
        AskTools tools = tools(snapshotOfRegions(region), USER, TestScopes.of());

        List<SpotInfo> found = spots(tools.rankSpots(
                new RankSpotsArgs(null, null, null, null, 60, 5)));

        assertThat(found).extracting(SpotInfo::name).containsExactly("Near", "Edge");
        assertThat(found).extracting(SpotInfo::driveMinutes).containsExactly(30, 60);
        assertThat(tools.personal()).isTrue();
    }

    @Test
    @DisplayName("without maxDriveMinutes no drive time is returned and the answer is not personal")
    void rankSpots_noDriveLimit_noDriveFieldAndNotPersonal() {
        BriefingRegion region = AskFixtures.region("Coast", true, AskFixtures.slot(1L, "A", 4));
        AskTools tools = tools(snapshotOfRegions(region), USER, TestScopes.of());

        AskToolResult result = tools.rankSpots(rank(5));

        assertThat(parse(result.content()).path("spots").get(0).has("driveMinutes")).isFalse();
        assertThat(tools.personal()).isFalse();
        verify(driveTimes, never()).getAllMinutes(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("an asker with no drive times gets an explanatory empty result, still personal")
    void rankSpots_askerWithNoDriveTimes() {
        BriefingRegion region = AskFixtures.region("Coast", true, AskFixtures.slot(1L, "A", 4));
        AskUserContext noTimes = new AskUserContext(9L, UserRole.LITE_USER, false);
        AskTools tools = tools(snapshotOfRegions(region), noTimes, TestScopes.of());

        RankSpotsResult payload = (RankSpotsResult) tools.rankSpots(
                new RankSpotsArgs(null, null, null, null, 60, 5)).payload();

        assertThat(payload.spots()).isEmpty();
        assertThat(payload.note()).isEqualTo(AskTools.NO_DRIVE_TIMES_NOTE);
        assertThat(tools.personal()).isTrue();
    }

    @Test
    @DisplayName("a drive limit below 1 minute is an error result")
    void rankSpots_driveLimitBelowOne() {
        BriefingRegion region = AskFixtures.region("Coast", true, AskFixtures.slot(1L, "A", 4));

        AskToolResult result = tools(snapshotOfRegions(region), USER, TestScopes.of()).rankSpots(
                new RankSpotsArgs(null, null, null, null, 0, 5));

        assertThat(result.error()).isTrue();
    }

    // -- nearHome: the saved local radius, straight-line --------------------------------------

    /** Home at 55.0 N, 1.5 W with a 25-mile radius: Near is about 7 miles away, Far about 35. */
    private static final AskUserContext HOMED = new AskUserContext(7L, UserRole.PRO_USER, false,
            new AskUserContext.Home(55.0, -1.5, 25));

    private static final Map<Long, AskLocationNames.Place> PLACES = Map.of(
            1L, new AskLocationNames.Place("Near", 55.1, -1.5),
            2L, new AskLocationNames.Place("Far", 55.5, -1.5),
            3L, new AskLocationNames.Place("Edge", 55.0, -1.5 + 0.2));

    private AskTools nearHomeTools(AskSnapshot snapshot, AskUserContext user) {
        return new AskTools(snapshot, user, TestScopes.of(), driveTimes, mapper, () -> PLACES);
    }

    private static RankSpotsArgs nearHome() {
        return new RankSpotsArgs(null, null, null, null, null, true, 5);
    }

    @Test
    @DisplayName("nearHome keeps the spots within the asker's radius as the crow flies, drops the rest and any "
            + "place the lookup does not hold, and says the radius")
    void rankSpots_nearHomeFiltersByStraightLineRadius() {
        BriefingRegion region = AskFixtures.region("Coast", true,
                AskFixtures.slot(1L, "Near", 4), AskFixtures.slot(2L, "Far", 5),
                AskFixtures.slot(3L, "Edge", 3), AskFixtures.slot(4L, "Unlisted", 5));
        AskTools tools = nearHomeTools(snapshotOfRegions(region), HOMED);

        AskToolResult<RankSpotsResult> result = tools.rankSpots(nearHome());

        assertThat(spots(result)).extracting(SpotInfo::name).containsExactly("Near", "Edge");
        assertThat(result.payload().withinMilesOfHome()).isEqualTo(25);
        assertThat(parse(result.content()).path("withinMilesOfHome").asInt()).isEqualTo(25);
        assertThat(tools.personal()).isTrue();
    }

    @Test
    @DisplayName("the radius is the asker's own: a larger radius admits the place a smaller one refuses")
    void rankSpots_nearHomeUsesTheContextRadius() {
        BriefingRegion region = AskFixtures.region("Coast", true,
                AskFixtures.slot(1L, "Near", 4), AskFixtures.slot(2L, "Far", 5));
        AskUserContext wide = new AskUserContext(7L, UserRole.PRO_USER, false,
                new AskUserContext.Home(55.0, -1.5, 50));

        List<SpotInfo> found = spots(nearHomeTools(snapshotOfRegions(region), wide).rankSpots(nearHome()));

        assertThat(found).extracting(SpotInfo::name).containsExactly("Far", "Near");
    }

    @Test
    @DisplayName("nothing within the radius is an empty result whose note names the radius, still personal")
    void rankSpots_nearHomeNothingInRadiusNamesIt() {
        BriefingRegion region = AskFixtures.region("Coast", true, AskFixtures.slot(2L, "Far", 5));
        AskTools tools = nearHomeTools(snapshotOfRegions(region), HOMED);

        RankSpotsResult payload = tools.rankSpots(nearHome()).payload();

        assertThat(payload.spots()).isEmpty();
        assertThat(payload.note()).contains("within 25 miles of home");
        assertThat(payload.withinMilesOfHome()).isEqualTo(25);
        assertThat(tools.personal()).isTrue();
    }

    @Test
    @DisplayName("nearHome with no home saved answers that it is not available, still personal, and reads no places")
    void rankSpots_nearHomeWithoutAHome() {
        BriefingRegion region = AskFixtures.region("Coast", true, AskFixtures.slot(1L, "Near", 4));
        AskLocationNames lookup = mock(AskLocationNames.class);
        AskUserContext noHome = new AskUserContext(7L, UserRole.PRO_USER, true);
        AskTools tools = new AskTools(snapshotOfRegions(region), noHome, TestScopes.of(), driveTimes, mapper,
                lookup);

        RankSpotsResult payload = tools.rankSpots(nearHome()).payload();

        assertThat(payload.spots()).isEmpty();
        assertThat(payload.note()).isEqualTo(AskTools.NO_HOME_NOTE);
        assertThat(payload.withinMilesOfHome()).isNull();
        assertThat(tools.personal()).isTrue();
        verify(lookup, never()).byId();
    }

    @Test
    @DisplayName("a LITE account with a home and no drive times gets nearHome: distance needs no drive time")
    void rankSpots_nearHomeNeedsNoDriveTimes() {
        BriefingRegion region = AskFixtures.region("Coast", true, AskFixtures.slot(1L, "Near", 4));
        AskUserContext lite = new AskUserContext(9L, UserRole.LITE_USER, false,
                new AskUserContext.Home(55.0, -1.5, 22));

        List<SpotInfo> found = spots(nearHomeTools(snapshotOfRegions(region), lite).rankSpots(nearHome()));

        assertThat(found).extracting(SpotInfo::name).containsExactly("Near");
        verify(driveTimes, never()).getAllMinutes(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("nearHome is refused in a user-less (Ready) conversation, leaves it non-personal and reads no places")
    void rankSpots_nearHomeRefusedWithoutAUser() {
        BriefingRegion region = AskFixtures.region("Coast", true, AskFixtures.slot(1L, "Near", 4));
        AskLocationNames lookup = mock(AskLocationNames.class);
        AskTools tools = new AskTools(snapshotOfRegions(region), AskUserContext.userLess(), TestScopes.of(),
                driveTimes, mapper, lookup);

        AskToolResult<RankSpotsResult> result = tools.rankSpots(nearHome());

        assertThat(result.error()).isTrue();
        assertThat(tools.personal()).isFalse();
        verify(lookup, never()).byId();
    }

    @Test
    @DisplayName("nearHome false or absent applies no distance filter and reports no radius")
    void rankSpots_nearHomeOffIsNotApplied() {
        BriefingRegion region = AskFixtures.region("Coast", true,
                AskFixtures.slot(1L, "Near", 4), AskFixtures.slot(2L, "Far", 5));
        AskTools tools = nearHomeTools(snapshotOfRegions(region), HOMED);

        AskToolResult<RankSpotsResult> result = tools.rankSpots(
                new RankSpotsArgs(null, null, null, null, null, false, 5));

        assertThat(spots(result)).extracting(SpotInfo::name).containsExactly("Far", "Near");
        assertThat(result.payload().withinMilesOfHome()).isNull();
        assertThat(parse(result.content()).has("withinMilesOfHome")).isFalse();
        assertThat(tools.personal()).isFalse();
    }

    @Test
    @DisplayName("nearHome and maxDriveMinutes together apply both filters")
    void rankSpots_nearHomeAndDriveLimitCombine() {
        BriefingRegion region = AskFixtures.region("Coast", true,
                AskFixtures.slot(1L, "Near", 4), AskFixtures.slot(3L, "Edge", 4));
        AskUserContext both = new AskUserContext(7L, UserRole.PRO_USER, true,
                new AskUserContext.Home(55.0, -1.5, 25));
        when(driveTimes.getAllMinutes(7L)).thenReturn(Map.of(1L, 20, 3L, 80));

        List<SpotInfo> found = spots(nearHomeTools(snapshotOfRegions(region), both).rankSpots(
                new RankSpotsArgs(null, null, null, null, 45, true, 5)));

        assertThat(found).extracting(SpotInfo::name).containsExactly("Near");
    }

    // -- evidence and the character cap -----------------------------------------------------

    @Test
    @DisplayName("only the pairs a successful rank_spots returned become evidence")
    void evidence_recordsReturnedPairs() {
        BriefingRegion region = AskFixtures.region("Coast", true,
                AskFixtures.slot(1L, "A", 4), AskFixtures.slot(2L, "B", 4), AskFixtures.slot(3L, "C", 4));
        AskTools tools = tools(snapshotOfRegions(region));

        tools.rankSpots(rank(2));

        assertThat(tools.evidence().pairs()).containsExactlyInAnyOrder(
                new AskEvidence.Pair(1L, "2026-10-05_sunset"),
                new AskEvidence.Pair(2L, "2026-10-05_sunset"));
    }

    @Test
    @DisplayName("tool output is capped at 9,000 characters a conversation; past it a tool errors and returns nothing")
    void resultCap_refusesPastNineThousandCharacters() {
        List<BriefingSlot> slots = new ArrayList<>();
        for (long i = 1; i <= 8; i++) {
            slots.add(AskFixtures.withHeadline(AskFixtures.slot(i, "Spot number " + i, 4),
                    "h".repeat(200)));
        }
        AskTools tools = tools(snapshotOfRegions(
                AskFixtures.region("Coast", true, slots.toArray(BriefingSlot[]::new))));

        AskToolResult last = null;
        int accepted = 0;
        for (int i = 0; i < 10 && (last == null || !last.error()); i++) {
            last = tools.rankSpots(rank(8));
            if (!last.error()) {
                accepted++;
            }
        }

        assertThat(last.error()).isTrue();
        assertThat(last.content()).contains("Answer now");
        assertThat(accepted).isGreaterThanOrEqualTo(1);
        assertThat(tools.charsUsed()).isLessThanOrEqualTo(AskTools.RESULT_CHAR_CAP);
        assertThat(tools.trace()).hasSize(accepted + 1);
        assertThat(tools.trace().getLast().error()).isTrue();
    }

    @Test
    @DisplayName("a result the cap refuses leaves no evidence behind")
    void resultCap_refusedResultRecordsNoEvidence() {
        List<BriefingSlot> slots = new ArrayList<>();
        for (long i = 1; i <= 8; i++) {
            slots.add(AskFixtures.withHeadline(AskFixtures.slot(i, "Spot number " + i, 4),
                    "h".repeat(200)));
        }
        AskTools tools = tools(snapshotOfRegions(
                AskFixtures.region("Coast", true, slots.toArray(BriefingSlot[]::new))));
        AskToolResult first = tools.rankSpots(rank(8));
        // Fill the budget with further identical calls until one is refused.
        while (!tools.rankSpots(rank(8)).error()) {
            assertThat(tools.charsUsed()).isLessThanOrEqualTo(AskTools.RESULT_CHAR_CAP);
        }

        assertThat(first.error()).isFalse();
        assertThat(tools.evidence().pairs()).hasSize(8);
    }

    // -- hot topics and Coming up -----------------------------------------------------------

    @Test
    @DisplayName("get_hot_topics filters by type, caps the detail, and records what it returned")
    void getHotTopics_filtersCapsAndRecords() {
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(List.of(), List.of(
                AskFixtures.topic("AURORA", "Aurora", "a".repeat(500), TODAY, List.of()),
                AskFixtures.topic("KING_TIDE", "King tide", "Big", TOMORROW, List.of("Coast")))));
        AskTools tools = tools(snapshot);

        HotTopicsResult result = (HotTopicsResult) tools.getHotTopics(
                new HotTopicsArgs(List.of("aurora"), 5)).payload();

        assertThat(result.topics()).hasSize(1);
        assertThat(result.topics().getFirst().detail()).hasSize(AskTools.DETAIL_CAP);
        assertThat(result.topics().getFirst().date()).isEqualTo("2026-10-05");
        assertThat(tools.evidence().events()).containsExactly(
                new AskEvidence.EventFact("AURORA", "Aurora", TODAY));
    }

    @Test
    @DisplayName("get_hot_topics reads a type the way every other site does: 'lunar-eclipse' asks for "
            + "LUNAR_ECLIPSE, and the card still carries the served type")
    void getHotTopics_typeFilterFoldsDashes() {
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(List.of(), List.of(
                AskFixtures.topic("LUNAR_ECLIPSE", "Lunar eclipse", "d", TOMORROW, List.of()),
                AskFixtures.topic("AURORA", "Aurora", "d", TODAY, List.of()))));
        AskTools tools = tools(snapshot);

        HotTopicsResult result = tools.getHotTopics(new HotTopicsArgs(List.of(" lunar-eclipse "), 5)).payload();

        assertThat(result.topics()).extracting(AskTools.TopicInfo::type).containsExactly("LUNAR_ECLIPSE");
        assertThat(tools.evidence().events()).containsExactly(
                new AskEvidence.EventFact("LUNAR_ECLIPSE", "Lunar eclipse", TOMORROW));
    }

    @Test
    @DisplayName("get_coming_up is the snapshot's timeline cut to the limit, nothing more and nothing less")
    void getComingUp_isTheSnapshotsTimeline() {
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(List.of(), List.of(
                AskFixtures.topic("AURORA", "Aurora", "d", TODAY, List.of()))),
                List.of(AskSnapshotBuilderTest.almanacEntry("SUPERMOON", "Supermoon", TODAY.plusDays(3),
                        TODAY.plusDays(3), "d")));
        AskScope scope = TestScopes.of();

        List<AskTools.ComingUpInfo> served = tools(snapshot).getComingUp(new ComingUpArgs(30, 10))
                .payload().entries();

        assertThat(served).extracting(AskTools.ComingUpInfo::title)
                .containsExactlyElementsOf(snapshot.timeline(scope, 30).stream()
                        .map(AskSnapshot.ComingUp::title).toList());
        assertThat(served).hasSize(2);
    }

    @Test
    @DisplayName("a solar eclipse topic's safety note is on the tool row, whole, and in the evidence")
    void getHotTopics_carriesTheSafetyNote() {
        String warning = "Certified solar filter on the lens — not only over your eye. " + "x".repeat(250);
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(List.of(), List.of(
                AskFixtures.topic("ECLIPSE", "Partial solar eclipse", "62% covered", TOMORROW,
                        List.of()).withSafety(warning),
                AskFixtures.topic("AURORA", "Aurora", "Kp 6", TODAY, List.of()))));
        AskTools tools = tools(snapshot);

        AskToolResult result = tools.getHotTopics(null);

        List<AskTools.TopicInfo> topics = ((HotTopicsResult) result.payload()).topics();
        assertThat(topics.getFirst().safetyNote()).as("served whole, never cut to 200").isEqualTo(warning);
        assertThat(topics.get(1).safetyNote()).isNull();
        JsonNode json = parse(result.content()).path("topics");
        assertThat(json.get(0).path("safetyNote").asText()).isEqualTo(warning);
        assertThat(json.get(1).has("safetyNote")).isFalse();
        assertThat(tools.evidence().events()).contains(
                new AskEvidence.EventFact("ECLIPSE", "Partial solar eclipse", TOMORROW, warning),
                new AskEvidence.EventFact("AURORA", "Aurora", TODAY, null));
    }

    @Test
    @DisplayName("a solar eclipse from get_coming_up carries the shared lens-filter note, a lunar one none")
    void getComingUp_solarEclipseCarriesTheNote() {
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(List.of(), List.of()),
                List.of(AskSnapshotBuilderTest.almanacEntry("eclipse", "Partial solar eclipse",
                                TODAY.plusDays(40), TODAY.plusDays(40), "A partial eclipse"),
                        AskSnapshotBuilderTest.almanacEntry("lunar-eclipse", "Total lunar eclipse",
                                TODAY.plusDays(50), TODAY.plusDays(50), "Moon in shadow"),
                        AskSnapshotBuilderTest.almanacEntry("SUPERMOON", "Supermoon",
                                TODAY.plusDays(60), TODAY.plusDays(60), "Big moon")));
        AskTools tools = tools(snapshot);

        AskToolResult result = tools.getComingUp(null);

        JsonNode entries = parse(result.content()).path("entries");
        assertThat(entries.get(0).path("safetyNote").asText())
                .isEqualTo(EclipseHotTopicStrategy.SAFETY_NOTE);
        assertThat(entries.get(1).has("safetyNote")).as("lunar eclipse").isFalse();
        assertThat(entries.get(2).has("safetyNote")).as("supermoon").isFalse();
        assertThat(tools.evidence().events()).containsExactlyInAnyOrder(
                new AskEvidence.EventFact("ECLIPSE", "Partial solar eclipse", TODAY.plusDays(40),
                        EclipseHotTopicStrategy.SAFETY_NOTE),
                new AskEvidence.EventFact("LUNAR-ECLIPSE", "Total lunar eclipse", TODAY.plusDays(50), null),
                new AskEvidence.EventFact("SUPERMOON", "Supermoon", TODAY.plusDays(60), null));
    }

    @Test
    @DisplayName("get_hot_topics: a topic naming regions outside the scope is left out, a region-less one stays")
    void getHotTopics_scope() {
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(List.of(), List.of(
                AskFixtures.topic("KING_TIDE", "King tide", "Big", TOMORROW, List.of("Cornwall")),
                AskFixtures.topic("AURORA", "Aurora", "Kp 6", TODAY, List.of()),
                AskFixtures.topic("SNOW", "Snow", "Tops", TODAY, List.of("Hills", "Coast")))));

        HotTopicsResult result = (HotTopicsResult) tools(snapshot, USER, TestScopes.of("coast"))
                .getHotTopics(null).payload();

        assertThat(result.topics()).extracting(AskTools.TopicInfo::type)
                .containsExactly("AURORA", "SNOW");
    }

    /**
     * Three days of a surge at sunrise and at sunset, as {@code StormSurgeHotTopicStrategy} emits
     * them since #1300 (one topic per date and event), plus an aurora after them.
     */
    private static AskSnapshot splitSurgeSnapshot() {
        List<com.gregochr.goldenhour.model.HotTopic> topics = new ArrayList<>();
        for (int day = 0; day < 3; day++) {
            topics.add(AskFixtures.topic("STORM_SURGE", "Storm surge", "dawn tide", TODAY.plusDays(day),
                    List.of()).withEvent("SUNRISE", null));
            topics.add(AskFixtures.topic("STORM_SURGE", "Storm surge", "evening tide", TODAY.plusDays(day),
                    List.of()).withEvent("SUNSET", null));
        }
        topics.add(AskFixtures.topic("AURORA", "Aurora", "Kp 6", TODAY.plusDays(2), List.of()));
        return AskFixtures.snapshotOf(AskFixtures.briefing(List.of(), topics));
    }

    @Test
    @DisplayName("get_hot_topics: a kind split by event is listed once per day, so it cannot crowd out the rest")
    void getHotTopics_onePerKindAndDay() {
        AskTools tools = tools(splitSurgeSnapshot());

        HotTopicsResult result = (HotTopicsResult) tools.getHotTopics(null).payload();

        assertThat(result.topics()).extracting(AskTools.TopicInfo::type, AskTools.TopicInfo::date)
                .containsExactlyInAnyOrder(
                        tuple("STORM_SURGE", TODAY.toString()),
                        tuple("STORM_SURGE", TODAY.plusDays(1).toString()),
                        tuple("STORM_SURGE", TODAY.plusDays(2).toString()),
                        tuple("AURORA", TODAY.plusDays(2).toString()));
        assertThat(result.topics()).filteredOn(t -> t.type().equals("STORM_SURGE"))
                .extracting(AskTools.TopicInfo::detail).containsOnly("dawn tide");
    }

    @Test
    @DisplayName("get_hot_topics: the kept topic of a split pair is one in scope")
    void getHotTopics_onePerKindAndDay_keepsTheInScopeHalf() {
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(List.of(), List.of(
                AskFixtures.topic("STORM_SURGE", "Storm surge", "dawn tide", TODAY, List.of("Cornwall"))
                        .withEvent("SUNRISE", null),
                AskFixtures.topic("STORM_SURGE", "Storm surge", "evening tide", TODAY, List.of("Coast"))
                        .withEvent("SUNSET", null))));

        HotTopicsResult result = (HotTopicsResult) tools(snapshot, USER, TestScopes.of("coast"))
                .getHotTopics(null).payload();

        assertThat(result.topics()).extracting(AskTools.TopicInfo::detail).containsExactly("evening tide");
    }

    @Test
    @DisplayName("get_hot_topics: the folded topic names every region either half covered")
    void getHotTopics_onePerKindAndDay_unionsRegions() {
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(List.of(), List.of(
                AskFixtures.topic("STORM_SURGE", "Storm surge", "dawn tide", TODAY,
                        List.of("Cornwall", "Coast")).withEvent("SUNRISE", null),
                AskFixtures.topic("STORM_SURGE", "Storm surge", "evening tide", TODAY,
                        List.of("Coast", "Northumberland")).withEvent("SUNSET", null),
                AskFixtures.topic("MIST", "Valley mist", "dawn", TODAY, List.of("Dales"))
                        .withEvent("SUNRISE", null),
                AskFixtures.topic("MIST", "Valley mist", "dusk", TODAY, List.of())
                        .withEvent("SUNSET", null))));

        HotTopicsResult result = (HotTopicsResult) tools(snapshot).getHotTopics(null).payload();

        assertThat(result.topics()).extracting(AskTools.TopicInfo::type, AskTools.TopicInfo::detail,
                        AskTools.TopicInfo::regions)
                .containsExactlyInAnyOrder(
                        tuple("STORM_SURGE", "dawn tide", List.of("Cornwall", "Coast", "Northumberland")),
                        // A region-less half is true of everywhere, so the fold is region-less too.
                        tuple("MIST", "dawn", List.of()));
    }

    @Test
    @DisplayName("get_coming_up: a kind split by event stands in the timeline once per day")
    void getComingUp_onePerKindAndDay() {
        List<AskTools.ComingUpInfo> served = tools(splitSurgeSnapshot()).getComingUp(null).payload().entries();

        assertThat(served).extracting(AskTools.ComingUpInfo::type, AskTools.ComingUpInfo::start)
                .containsExactly(
                        tuple("STORM_SURGE", TODAY.toString()),
                        tuple("STORM_SURGE", TODAY.plusDays(1).toString()),
                        tuple("AURORA", TODAY.plusDays(2).toString()),
                        tuple("STORM_SURGE", TODAY.plusDays(2).toString()));
    }

    @Test
    @DisplayName("get_hot_topics limit defaults to 5 and is capped at 10")
    void getHotTopics_limitClamp() {
        List<com.gregochr.goldenhour.model.HotTopic> topics = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            topics.add(AskFixtures.topic("T" + i, "Topic " + i, "d", TODAY, List.of()));
        }
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(List.of(), topics));

        assertThat(((HotTopicsResult) tools(snapshot).getHotTopics(null).payload()).topics())
                .hasSize(5);
        assertThat(((HotTopicsResult) tools(snapshot).getHotTopics(
                new HotTopicsArgs(null, 50)).payload()).topics()).hasSize(10);
    }

    @Test
    @DisplayName("get_coming_up returns entries overlapping the next N days, soonest first, detail capped")
    void getComingUp_windowOrderAndCap() {
        List<ComingUpEntry> entries = List.of(
                AskSnapshotBuilderTest.almanacEntry("EQUINOX", "Equinox", TODAY.plusDays(80),
                        TODAY.plusDays(80), "Day and night equal"),
                AskSnapshotBuilderTest.almanacEntry("SUPERMOON", "Supermoon", TODAY.plusDays(3),
                        TODAY.plusDays(3), "s".repeat(400)),
                AskSnapshotBuilderTest.almanacEntry("OLD", "Over", TODAY.minusDays(9),
                        TODAY.minusDays(1), "gone"),
                AskSnapshotBuilderTest.almanacEntry("RUN", "Under way", TODAY.minusDays(2),
                        TODAY.plusDays(2), "ongoing"),
                AskSnapshotBuilderTest.almanacEntry("FAR", "Far", TODAY.plusDays(120),
                        TODAY.plusDays(120), "too far"));
        AskSnapshot snapshot = AskFixtures.snapshotOf(
                AskFixtures.briefing(List.of(), List.of()), entries);
        AskTools tools = tools(snapshot);

        ComingUpResult all = (ComingUpResult) tools.getComingUp(null).payload();
        ComingUpResult soon = (ComingUpResult) tools(snapshot).getComingUp(
                new ComingUpArgs(7, 10)).payload();

        assertThat(all.entries()).extracting(AskTools.ComingUpInfo::type)
                .containsExactly("RUN", "SUPERMOON", "EQUINOX");
        assertThat(soon.entries()).extracting(AskTools.ComingUpInfo::type)
                .containsExactly("RUN", "SUPERMOON");
        assertThat(all.entries().get(1).detail()).hasSize(AskTools.DETAIL_CAP);
        assertThat(tools.evidence().events()).contains(
                new AskEvidence.EventFact("SUPERMOON", "Supermoon", TODAY.plusDays(3)));
    }

    @Test
    @DisplayName("get_coming_up: days is capped at 90 and limit at 10")
    void getComingUp_clamps() {
        List<ComingUpEntry> entries = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            entries.add(AskSnapshotBuilderTest.almanacEntry("E" + i, "Entry " + i,
                    TODAY.plusDays(i + 1), TODAY.plusDays(i + 1), "d"));
        }
        entries.add(AskSnapshotBuilderTest.almanacEntry("LATE", "Late", TODAY.plusDays(91),
                TODAY.plusDays(91), "d"));
        AskSnapshot snapshot = AskFixtures.snapshotOf(
                AskFixtures.briefing(List.of(), List.of()), entries);

        ComingUpResult result = (ComingUpResult) tools(snapshot).getComingUp(
                new ComingUpArgs(500, 500)).payload();

        assertThat(result.entries()).hasSize(10);
        assertThat(result.entries()).extracting(AskTools.ComingUpInfo::type).doesNotContain("LATE");
    }

    // -- the trace --------------------------------------------------------------------------

    @Test
    @DisplayName("the trace lists every call in order, with errors flagged")
    void trace_listsEveryCall() {
        BriefingRegion region = AskFixtures.region("Coast", true, AskFixtures.slot(1L, "A", 4));
        AskTools tools = tools(snapshotOfRegions(region));

        tools.listWindows();
        tools.rankSpots(new RankSpotsArgs(List.of("nope"), null, null, null, null, 5));
        tools.getHotTopics(null);

        assertThat(tools.trace()).extracting(AskTools.ToolCall::tool)
                .containsExactly("list_windows", "rank_spots", "get_hot_topics");
        assertThat(tools.trace()).extracting(AskTools.ToolCall::error)
                .containsExactly(false, true, false);
        assertThat(tools.evidence().toolCalls()).isEqualTo(3);
    }

    // -- agreement with the Plan path's guards ----------------------------------------------

    @Test
    @DisplayName("a rated slot that also carries a stand-down reason is offered: the rating wins, as on the Plan path")
    void rankSpots_ratingWinsOverAStandDownReason() {
        BriefingSlot base = AskFixtures.slot(1L, "Rated", 4);
        BriefingSlot withReason = new BriefingSlot(1L, "Rated", base.solarEventTime(), base.verdict(),
                null, base.tide(), List.of(), 4, 4, null, null, null,
                base.displayVerdict(), null, false);
        BriefingRegion region = AskFixtures.region("Coast", true, withReason);

        List<SpotInfo> found = spots(tools(snapshotOfRegions(region)).rankSpots(rank(5)));

        assertThat(found).extracting(SpotInfo::name).containsExactly("Rated");
    }

    @Test
    @DisplayName("slots with no region are never offered, as the Plan path excludes them from its best rating")
    void rankSpots_neverOffersUnregionedSlots() {
        BriefingRegion region = AskFixtures.region("Coast", true, AskFixtures.slot(1L, "Regioned", 3));
        com.gregochr.goldenhour.model.BriefingEventSummary summary =
                new com.gregochr.goldenhour.model.BriefingEventSummary(TargetType.SUNSET,
                        List.of(region), List.of(AskFixtures.slot(2L, "Orphan", 5)), null,
                        AskFixtures.window(TODAY.atTime(18, 0), DisplayVerdict.WORTH_IT, 3, null));
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(
                List.of(AskFixtures.day(TODAY, summary)), List.of()));

        assertThat(spots(tools(snapshot).rankSpots(rank(5)))).extracting(SpotInfo::name)
                .containsExactly("Regioned");
    }

    // -- boundary sweep ---------------------------------------------------------------------

    private AskSnapshot almanacSnapshot(int... dayOffsets) {
        List<ComingUpEntry> entries = new ArrayList<>();
        for (int offset : dayOffsets) {
            entries.add(AskSnapshotBuilderTest.almanacEntry("E" + offset, "Entry " + offset,
                    TODAY.plusDays(offset), TODAY.plusDays(offset), "d"));
        }
        return AskFixtures.snapshotOf(AskFixtures.briefing(List.of(), List.of()), entries);
    }

    private List<String> comingUpTypes(AskSnapshot snapshot, Integer days) {
        return ((ComingUpResult) tools(snapshot).getComingUp(new ComingUpArgs(days, 10)).payload())
                .entries().stream().map(AskTools.ComingUpInfo::type).toList();
    }

    @Test
    @DisplayName("get_coming_up: at the 90-day cap day 89 is in and day 90 is out, whatever days is asked")
    void getComingUp_ninetyDayCap() {
        AskSnapshot snapshot = almanacSnapshot(89, 90);

        assertThat(comingUpTypes(snapshot, 90)).containsExactly("E89");
        assertThat(comingUpTypes(snapshot, 91)).containsExactly("E89");
        assertThat(comingUpTypes(snapshot, 500)).containsExactly("E89");
        assertThat(comingUpTypes(snapshot, null)).as("default is the cap").containsExactly("E89");
    }

    @Test
    @DisplayName("get_coming_up: zero and negative days read as one day; an entry ending today is in, yesterday out")
    void getComingUp_floorAndSpanEdges() {
        List<ComingUpEntry> entries = List.of(
                AskSnapshotBuilderTest.almanacEntry("ENDS_TODAY", "Ends today", TODAY.minusDays(3),
                        TODAY, "d"),
                AskSnapshotBuilderTest.almanacEntry("ENDED", "Ended yesterday", TODAY.minusDays(3),
                        TODAY.minusDays(1), "d"),
                AskSnapshotBuilderTest.almanacEntry("LATER", "Later", TODAY.plusDays(1),
                        TODAY.plusDays(1), "d"));
        AskSnapshot snapshot = AskFixtures.snapshotOf(
                AskFixtures.briefing(List.of(), List.of()), entries);

        assertThat(comingUpTypes(snapshot, 0)).containsExactly("ENDS_TODAY");
        assertThat(comingUpTypes(snapshot, -5)).containsExactly("ENDS_TODAY");
        assertThat(comingUpTypes(snapshot, 2)).containsExactly("ENDS_TODAY", "LATER");
    }

    @Test
    @DisplayName("get_coming_up: limit is null -> 5, 0 or negative -> 1, over 10 -> 10")
    void getComingUp_limitEdges() {
        List<ComingUpEntry> entries = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            entries.add(AskSnapshotBuilderTest.almanacEntry("E" + i, "Entry " + i,
                    TODAY.plusDays(i), TODAY.plusDays(i), "d"));
        }
        AskSnapshot snapshot = AskFixtures.snapshotOf(
                AskFixtures.briefing(List.of(), List.of()), entries);

        assertThat(((ComingUpResult) tools(snapshot).getComingUp(null).payload()).entries()).hasSize(5);
        assertThat(((ComingUpResult) tools(snapshot).getComingUp(new ComingUpArgs(30, 0)).payload())
                .entries()).hasSize(1);
        assertThat(((ComingUpResult) tools(snapshot).getComingUp(new ComingUpArgs(30, -4)).payload())
                .entries()).hasSize(1);
        assertThat(((ComingUpResult) tools(snapshot).getComingUp(new ComingUpArgs(30, 11)).payload())
                .entries()).hasSize(10);
    }

    @Test
    @DisplayName("get_hot_topics: limit is null -> 5, 0 or negative -> 1; the limit applies after the scope filter")
    void getHotTopics_limitEdgesAndOrderOfFilters() {
        List<com.gregochr.goldenhour.model.HotTopic> topics = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            topics.add(AskFixtures.topic("OUT" + i, "Out " + i, "d", TODAY, List.of("Cornwall")));
        }
        for (int i = 0; i < 6; i++) {
            topics.add(AskFixtures.topic("IN" + i, "In " + i, "d", TODAY, List.of("Coast")));
        }
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(List.of(), topics));

        List<AskTools.TopicInfo> scoped = ((HotTopicsResult) tools(snapshot, USER, TestScopes.of("Coast"))
                .getHotTopics(new HotTopicsArgs(null, 3)).payload()).topics();

        assertThat(scoped).extracting(AskTools.TopicInfo::type).containsExactly("IN0", "IN1", "IN2");
        assertThat(((HotTopicsResult) tools(snapshot).getHotTopics(new HotTopicsArgs(null, 0))
                .payload()).topics()).hasSize(1);
        assertThat(((HotTopicsResult) tools(snapshot).getHotTopics(new HotTopicsArgs(null, -2))
                .payload()).topics()).hasSize(1);
        assertThat(((HotTopicsResult) tools(snapshot).getHotTopics(null).payload()).topics())
                .hasSize(5);
    }

    @Test
    @DisplayName("rank_spots: equal rating, name and window order by location id, whatever order they arrive in")
    void rankSpots_sortIsTotal() {
        BriefingRegion forward = AskFixtures.region("Coast", true,
                AskFixtures.slot(9L, "Twin", 4), AskFixtures.slot(3L, "TWIN", 4));
        BriefingRegion reverse = AskFixtures.region("Coast", true,
                AskFixtures.slot(3L, "TWIN", 4), AskFixtures.slot(9L, "Twin", 4));

        List<SpotInfo> a = spots(tools(snapshotOfRegions(forward)).rankSpots(rank(8)));
        List<SpotInfo> b = spots(tools(snapshotOfRegions(reverse)).rankSpots(rank(8)));

        assertThat(a).extracting(SpotInfo::locationId).containsExactly(3L, 9L);
        assertThat(b).extracting(SpotInfo::locationId).isEqualTo(
                a.stream().map(SpotInfo::locationId).toList());
    }

    @Test
    @DisplayName("rank_spots: a drive limit is inclusive: 60 minutes is within 60, 61 is not")
    void rankSpots_driveLimitIsInclusive() {
        BriefingRegion region = AskFixtures.region("Coast", true,
                AskFixtures.slot(1L, "Sixty", 4), AskFixtures.slot(2L, "SixtyOne", 4));
        when(driveTimes.getAllMinutes(7L)).thenReturn(Map.of(1L, 60, 2L, 61));

        List<SpotInfo> found = spots(tools(snapshotOfRegions(region), USER, TestScopes.of()).rankSpots(
                new RankSpotsArgs(null, null, null, null, 60, 5)));

        assertThat(found).extracting(SpotInfo::name).containsExactly("Sixty");
    }

    private AskTools topicToolWithNoteOfLength(int noteLength) {
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(List.of(), List.of(
                AskFixtures.topic("ECLIPSE", "Eclipse", "d", TODAY, List.of())
                        .withSafety("n".repeat(noteLength)))));
        return tools(snapshot);
    }

    @Test
    @DisplayName("the 9,000-character cap: exactly 9,000 is allowed, 8,999 is allowed, 9,001 is refused")
    void resultCap_boundary() {
        int base = topicToolWithNoteOfLength(1).getHotTopics(null).content().length() - 1;
        int exact = AskTools.RESULT_CHAR_CAP - base;

        AskTools atCap = topicToolWithNoteOfLength(exact);
        AskToolResult ok = atCap.getHotTopics(null);
        AskTools oneUnder = topicToolWithNoteOfLength(exact - 1);
        AskToolResult underOk = oneUnder.getHotTopics(null);
        AskTools oneOver = topicToolWithNoteOfLength(exact + 1);
        AskToolResult refused = oneOver.getHotTopics(null);

        assertThat(ok.error()).isFalse();
        assertThat(atCap.charsUsed()).isEqualTo(9_000);
        assertThat(underOk.error()).isFalse();
        assertThat(oneUnder.charsUsed()).isEqualTo(8_999);
        assertThat(refused.error()).isTrue();
        assertThat(oneOver.charsUsed()).isZero();
        assertThat(oneOver.evidence().events()).as("a refused result is not evidence").isEmpty();
        assertThat(refused.content()).contains("Tool output limit reached").contains("Answer now");
        assertThat(atCap.getHotTopics(null).error()).as("nothing more fits once full").isTrue();
    }

    @Test
    @DisplayName("cap(): exactly 120 characters is left alone, 121 is cut to 120 ending in an ellipsis")
    void cap_lengthBoundary() {
        assertThat(AskTools.cap("a".repeat(120), AskTools.HEADLINE_CAP)).isEqualTo("a".repeat(120));
        String cut = AskTools.cap("a".repeat(121), AskTools.HEADLINE_CAP);
        assertThat(cut).hasSize(120).endsWith("…");
        assertThat(AskTools.cap("a".repeat(200), AskTools.DETAIL_CAP)).hasSize(200);
        assertThat(AskTools.cap("a".repeat(201), AskTools.DETAIL_CAP)).hasSize(200).endsWith("…");
    }

    @Test
    @DisplayName("cap(): a cut never splits a surrogate pair, and multi-byte text under the limit is untouched")
    void cap_multiByteText() {
        String emoji = "🌅";
        String atBoundary = "a".repeat(118) + emoji + "b";
        String cut = AskTools.cap(atBoundary, AskTools.HEADLINE_CAP);

        assertThat(atBoundary).hasSize(121);
        assertThat(cut).isEqualTo("a".repeat(118) + "…");
        for (int i = 0; i < cut.length(); i++) {
            if (Character.isHighSurrogate(cut.charAt(i))) {
                assertThat(Character.isLowSurrogate(cut.charAt(i + 1))).isTrue();
            }
        }
        String accented = "é".repeat(120);
        assertThat(AskTools.cap(accented, AskTools.HEADLINE_CAP)).isEqualTo(accented);
        String keptPair = "a".repeat(116) + emoji + "bb";
        assertThat(AskTools.cap(keptPair, AskTools.HEADLINE_CAP)).isEqualTo(keptPair);
    }

    @Test
    @DisplayName("cap() leaves short text alone and marks a cut")
    void cap_behaviour() {
        assertThat(AskTools.cap(null, 5)).isNull();
        assertThat(AskTools.cap("abcde", 5)).isEqualTo("abcde");
        assertThat(AskTools.cap("abcdef", 5)).isEqualTo("abcd…");
    }

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("get_coming_up also returns a live hot topic dated within its horizon, once, with its "
            + "served safety note: the eclipse the almanac does not list is no longer 'nothing'")
    void getComingUp_includesTheLiveHotTopics() {
        String warning = EclipseHotTopicStrategy.SAFETY_NOTE;
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(List.of(), List.of(
                AskFixtures.topic("ECLIPSE", "Partial solar eclipse", "Low in the south-west",
                        TODAY.plusDays(4), List.of("Coast")).withSafety(warning))));
        AskTools tools = tools(snapshot);

        ComingUpResult result = (ComingUpResult) tools.getComingUp(new ComingUpArgs(90, 10)).payload();

        assertThat(result.entries()).singleElement().satisfies(e -> {
            assertThat(e.type()).isEqualTo("ECLIPSE");
            assertThat(e.title()).isEqualTo("Partial solar eclipse");
            assertThat(e.start()).isEqualTo(TODAY.plusDays(4).toString());
            assertThat(e.end()).isEqualTo(TODAY.plusDays(4).toString());
            assertThat(e.safetyNote()).isEqualTo(warning);
        });
        assertThat(tools.evidence().events()).containsExactly(new AskEvidence.EventFact("ECLIPSE",
                "Partial solar eclipse", TODAY.plusDays(4), warning));
    }

    // -- helpers the thread's prompt lines share with rank_spots -----------------------------

    @Test
    @DisplayName("driveMinutesOf reads the asker's own drive times lazily, once, without making the answer "
            + "personal, and never for a user-less or drive-less asker")
    void driveMinutesOf() {
        AskSnapshot snapshot = snapshotOfRegions(AskFixtures.region("Coast", true,
                AskFixtures.slot(1L, "Whitby", 4)));
        when(driveTimes.getAllMinutes(7L)).thenReturn(Map.of(1L, 55));
        AskTools pro = tools(snapshot, USER, AskScope.ALL);
        verify(driveTimes, never()).getAllMinutes(7L);

        assertThat(pro.driveMinutesOf(1L)).isEqualTo(55);
        assertThat(pro.driveMinutesOf(2L)).isNull();
        assertThat(pro.personal()).isFalse();
        verify(driveTimes).getAllMinutes(7L);

        assertThat(tools(snapshot).driveMinutesOf(1L)).isNull();
        assertThat(tools(snapshot, new AskUserContext(8L, UserRole.LITE_USER, false), AskScope.ALL)
                .driveMinutesOf(1L)).isNull();
        verify(driveTimes, never()).getAllMinutes(8L);
    }

    @Test
    @DisplayName("spotInfo renders a slot as rank_spots does, whichever caller asks; dayWord is the one day word")
    void spotInfoAndDayWordAreShared() {
        AskSnapshot snapshot = snapshotOfRegions(AskFixtures.region("Coast", true,
                AskFixtures.coastal(1L, "Whitby", 4, "HIGH", true)));
        AskSnapshot.Candidate candidate = snapshot.candidate("2026-10-05_sunset", 1L).orElseThrow();
        AskTools tools = tools(snapshot);

        SpotInfo direct = tools.spotInfo(candidate, 12);
        SpotInfo viaTool = spots(tools.rankSpots(rank(8))).getFirst();

        assertThat(direct).isEqualTo(new SpotInfo(viaTool.locationId(), viaTool.name(), viaTool.region(),
                viaTool.windowId(), viaTool.rating(), viaTool.verdict(), viaTool.tideState(),
                viaTool.tideAligned(), viaTool.tideFitPhrase(), viaTool.headline(), viaTool.pick(), 12,
                null, null));
        assertThat(AskTools.dayWord(TODAY, TODAY)).isEqualTo("Today");
        assertThat(AskTools.dayWord(TODAY.plusDays(1), TODAY)).isEqualTo("Tomorrow");
        assertThat(AskTools.dayWord(TODAY.plusDays(2), TODAY)).isEqualTo("Wednesday");
    }

    @Test
    @DisplayName("slotAt finds a slot whether or not it is pick-eligible, and only in a window that exists")
    void slotAtFindsIneligibleSlotsToo() {
        AskSnapshot snapshot = snapshotOfRegions(AskFixtures.region("Coast", true,
                AskFixtures.slot(1L, "Whitby", 4), AskFixtures.slot(2L, "Poor Moor", 2),
                AskFixtures.wood(3L, "Hollin Wood", 5)));

        assertThat(snapshot.slotAt("2026-10-05_sunset", 2L)).get()
                .extracting(c -> c.slot().name()).isEqualTo("Poor Moor");
        assertThat(snapshot.candidate("2026-10-05_sunset", 2L)).as("not eligible").isEmpty();
        assertThat(snapshot.slotAt("2026-10-05_sunset", 3L)).isPresent();
        assertThat(snapshot.slotAt("2026-10-05_sunset", 9L)).isEmpty();
        assertThat(snapshot.slotAt("2026-10-04_sunset", 1L)).isEmpty();
    }
    // -- list_windows' spread (ask-window-context plan §2.1, §2.2) ---------------------------

    private static final String SATURDAY_SUNSET = "2026-10-10_sunset";

    private static AskSnapshot weekendSnapshot() {
        LocalDate saturday = LocalDate.of(2026, 10, 10);
        BriefingRegion[] regions = {
            AskFixtures.bandedRegion("Northumberland", 1L, 0, 20, 60, 2, 0),
            AskFixtures.bandedRegion("Lake District", 500L, 0, 36, 62, 0, 0),
        };
        return AskFixtures.snapshotOf(AskFixtures.briefing(
                List.of(AskFixtures.sunsetDay(saturday, null, regions)), List.of()));
    }

    @Test
    @DisplayName("list_windows serves each solar window's spread over the question's scope, beside the "
            + "window-wide best rating")
    void listWindows_carriesTheSpread() {
        AskToolResult result = tools(weekendSnapshot()).listWindows();

        AskTools.WindowInfo info = ((ListWindowsResult) result.payload()).windows().getFirst();
        assertThat(info.spread().over()).isEqualTo("every region");
        assertThat(info.spread().rated()).isEqualTo(180);
        assertThat(info.spread().bands()).containsExactly(0, 56, 122, 2, 0);
        assertThat(info.spread().pockets()).singleElement()
                .extracting(AskSnapshot.Spread.Pocket::region).isEqualTo("Northumberland");
        assertThat(result.content()).contains("\"spread\":{\"over\":\"every region\",\"rated\":180");
    }

    @Test
    @DisplayName("the spread follows the scope while bestRating stays the one window-wide served figure")
    void listWindows_spreadFollowsScope() {
        AskSnapshot snapshot = weekendSnapshot();

        AskTools.WindowInfo coast = ((ListWindowsResult) tools(snapshot, USER, TestScopes.of("Lake District"))
                .listWindows().payload()).windows().getFirst();
        AskTools.WindowInfo both = ((ListWindowsResult) tools(snapshot, USER,
                TestScopes.of("Lake District", "Northumberland")).listWindows().payload()).windows().getFirst();

        assertThat(coast.spread().over()).isEqualTo("Lake District");
        assertThat(coast.spread().rated()).isEqualTo(98);
        assertThat(coast.spread().gameOn()).isZero();
        assertThat(coast.spread().pockets()).isEmpty();
        assertThat(both.spread().over()).isEqualTo("Lake District and Northumberland");
        assertThat(both.spread().rated()).isEqualTo(180);
        assertThat(coast.bestRating()).as("window-wide: the one figure that ignores the scope")
                .isEqualTo(both.bestRating()).isEqualTo(4);
    }

    @Test
    @DisplayName("a night row carries no spread: the figures come only from rank_night_spots")
    void listWindows_nightRowHasNoSpread() {
        LocalDate tonight = TODAY;
        AskSnapshot solar = AskFixtures.snapshotOf(AskFixtures.briefing(List.of(AskFixtures.sunsetDay(TODAY, null,
                AskFixtures.bandedRegion("Coast", 1L, 0, 0, 3, 1, 0))), List.of()));
        AskSnapshot.Night night = new AskSnapshot.Night("2026-10-05_astro", tonight, AskWindowKind.ASTRO,
                tonight.atTime(19, 0), tonight.plusDays(1).atTime(5, 0), null, List.of(new AskSnapshot.NightSpot(
                        9L, "Kielder", "Coast", 55.2, -2.5, false, 2, 21.5, 4, "Clear", "Clear skies", null,
                        null, null, null, null, null, false, null, null, null)));
        AskSnapshot withNight = new AskSnapshot(solar.generatedAt(), solar.runLabel(), solar.today(),
                solar.windows(), solar.hotTopics(), solar.comingUp(), false,
                new AskSnapshot.Nights(tonight, List.of(night), null));

        List<AskTools.WindowInfo> windows = ((ListWindowsResult) tools(withNight).listWindows().payload()).windows();

        assertThat(windows).extracting(AskTools.WindowInfo::event).containsExactly("sunset", "astro");
        assertThat(windows.get(0).spread()).isNotNull();
        assertThat(windows.get(1).spread()).isNull();
    }

    /** A busy four-region day: six windows, every region holding game-on places. */
    private static AskSnapshot busyDaySnapshot() {
        List<com.gregochr.goldenhour.model.BriefingDay> days = new ArrayList<>();
        for (int d = 0; d < 6; d++) {
            LocalDate date = TODAY.plusDays(d);
            BriefingRegion[] regions = new BriefingRegion[4];
            String[] names = {"Northumberland", "North York Moors & Coast", "Lake District", "Yorkshire Dales"};
            for (int r = 0; r < 4; r++) {
                regions[r] = AskFixtures.bandedRegion(names[r], 1_000L * (d * 4 + r + 1), 2, 8, 20, 25 + r, 6);
            }
            days.add(AskFixtures.sunsetDay(date, null, regions));
        }
        return AskFixtures.snapshotOf(AskFixtures.briefing(days, List.of()));
    }

    @Test
    @DisplayName("on one conversation: the busy-day list_windows first, then look_up_place for a name matching two "
            + "places in every window with no windowIds, is not cut off by the shared character cap")
    void lookUpPlace_afterABusyDayListWindowsIsNotCutOff() {
        List<com.gregochr.goldenhour.model.BriefingDay> days = new ArrayList<>();
        String[] names = {"Northumberland", "North York Moors & Coast", "Lake District", "Yorkshire Dales"};
        for (int d = 0; d < 6; d++) {
            BriefingRegion[] regions = new BriefingRegion[5];
            for (int r = 0; r < 4; r++) {
                regions[r] = AskFixtures.bandedRegion(names[r], 1_000L * (d * 4 + r + 1), 2, 8, 20, 25 + r, 6);
            }
            regions[4] = AskFixtures.region("Northumberland Coast", false,
                    AskFixtures.withHeadline(AskFixtures.coastal(1L, "Alnmouth", 4, "HIGH", true,
                            "T".repeat(160)), "H".repeat(200)),
                    AskFixtures.withHeadline(AskFixtures.coastal(2L, "St Cuthbert's Cross, Alnmouth", 3, "MID",
                            false, "T".repeat(160)), "H".repeat(200)));
            days.add(AskFixtures.sunsetDay(TODAY.plusDays(d), null, regions));
        }
        AskTools tools = tools(AskFixtures.snapshotOf(AskFixtures.briefing(days, List.of())));

        AskToolResult listed = tools.listWindows();
        AskToolResult<AskTools.LookUpPlaceResult> lookup = tools.lookUpPlace(
                new AskTools.LookUpPlaceArgs("Alnmouth", null));

        assertThat(listed.error()).isFalse();
        assertThat(listed.content().length()).isLessThan(AskTools.RESULT_CHAR_CAP / 2);
        assertThat(lookup.error()).as(lookup.content()).isFalse();
        assertThat(lookup.payload().places()).hasSize(AskTools.MAX_PLACE_ROWS);
        assertThat(lookup.payload().places()).extracting(AskTools.PlaceInfo::locationId).containsOnly(1L);
        assertThat(tools.charsUsed()).isLessThan(AskTools.RESULT_CHAR_CAP);
    }

    // -- light character (F1) ---------------------------------------------------------------

    private static BriefingRegion lightRegion(Integer fiery, Integer golden) {
        return AskFixtures.region("Coast", true,
                AskFixtures.withPotentials(AskFixtures.coastal(1L, "Bamburgh", 4, "HIGH", true), fiery, golden),
                AskFixtures.withPotentials(AskFixtures.coastal(2L, "Whitby", 3, "MID", true),
                        fiery == null ? null : 40, golden == null ? null : 55));
    }

    @Test
    @DisplayName("a typed rank_spots carries each slot's served Fiery Sky and Golden Hour")
    void rankSpots_typedCarriesBothScores() {
        List<SpotInfo> found = spots(tools(snapshotOfRegions(lightRegion(78, 60)), USER, TestScopes.of())
                .rankSpots(rank(8)));

        assertThat(found).extracting(SpotInfo::name, SpotInfo::fierySky, SpotInfo::goldenHour)
                .containsExactly(tuple("Bamburgh", 78, 60),
                        tuple("Whitby", 40, 55));
    }

    @Test
    @DisplayName("Ready: a slot with both scores set puts neither key in the user-less rank_spots content")
    void rankSpots_userLessNeverCarriesTheScores() {
        AskToolResult<RankSpotsResult> result = tools(snapshotOfRegions(lightRegion(78, 60))).rankSpots(rank(8));

        assertThat(result.error()).isFalse();
        assertThat(result.content()).doesNotContain("fierySky").doesNotContain("goldenHour");
        assertThat(result.payload().spots()).allSatisfy(s -> {
            assertThat(s.fierySky()).isNull();
            assertThat(s.goldenHour()).isNull();
        });
    }

    @Test
    @DisplayName("null scores mean no keys, typed or not")
    void rankSpots_nullScoresMeanNoKeys() {
        AskToolResult<RankSpotsResult> typed = tools(snapshotOfRegions(lightRegion(null, null)), USER,
                TestScopes.of()).rankSpots(rank(8));

        assertThat(typed.content()).doesNotContain("fierySky").doesNotContain("goldenHour");
    }

    @Test
    @DisplayName("typed and user-less rank_spots return the same places in the same order with the same picks")
    void rankSpots_orderingAndPicksIdenticalTypedAndUserLess() {
        AskSnapshot snapshot = snapshotOfRegions(lightRegion(78, 60));

        List<SpotInfo> typed = spots(tools(snapshot, USER, TestScopes.of()).rankSpots(rank(8)));
        List<SpotInfo> userLess = spots(tools(snapshot).rankSpots(rank(8)));

        assertThat(typed).extracting(SpotInfo::locationId, SpotInfo::windowId, SpotInfo::rating, SpotInfo::pick)
                .isEqualTo(userLess.stream().map(s -> tuple(
                        s.locationId(), s.windowId(), s.rating(), s.pick())).toList());
    }

    private static BriefingRegion worstCaseLightRegion(Integer fiery, Integer golden) {
        List<BriefingSlot> slots = new ArrayList<>();
        for (long i = 1; i <= 8; i++) {
            slots.add(AskFixtures.withPotentials(AskFixtures.withHeadline(
                    AskFixtures.coastal(i, "Place number " + i, 5, "HIGH", true), "H".repeat(120)), fiery, golden));
        }
        return AskFixtures.region("Coast", true, slots.toArray(new BriefingSlot[0]));
    }

    @Test
    @DisplayName("budget: a typed 8-row rank_spots at 120-char headlines and both scores at 100 stays under "
            + "half the cap and costs at most 8 x 32 characters more than the same call without scores")
    void rankSpots_typedWorstCaseBudget() {
        String with = tools(snapshotOfRegions(worstCaseLightRegion(100, 100)), USER, TestScopes.of())
                .rankSpots(rank(8)).content();
        String without = tools(snapshotOfRegions(worstCaseLightRegion(null, null)), USER, TestScopes.of())
                .rankSpots(rank(8)).content();

        System.out.println("F1 budget: typed rank_spots(8) with scores = " + with.length()
                + " chars, without = " + without.length() + ", delta = " + (with.length() - without.length())
                + ", cap/2 = " + AskTools.RESULT_CHAR_CAP / 2);
        assertThat(with.length()).isLessThan(AskTools.RESULT_CHAR_CAP / 2);
        assertThat(with.length() - without.length()).isLessThanOrEqualTo(8 * 32);
    }

    @Test
    @DisplayName("budget: a typed list_windows, rank_spots(8) and look_up_place with detail fit the conversation cap")
    void typedConversationWithLightFiguresFitsTheCap() {
        BriefingSlot detailed = new BriefingSlot(1L, "Place number 1", AskFixtures.NOW.plusHours(6),
                Verdict.GO, null, BriefingSlot.TideInfo.NONE, List.of(), 5, 5,
                100, 100, "S ".repeat(200), DisplayVerdict.resolve(5,
                        Verdict.GO), "H".repeat(120), false);
        List<BriefingSlot> slots = new ArrayList<>();
        slots.add(detailed);
        for (long i = 2; i <= 8; i++) {
            slots.add(AskFixtures.withPotentials(AskFixtures.withHeadline(
                    AskFixtures.coastal(i, "Place number " + i, 5, "HIGH", true), "H".repeat(120)), 100, 100));
        }
        AskTools tools = tools(snapshotOfRegions(AskFixtures.region("Coast", true,
                slots.toArray(new BriefingSlot[0]))), USER, TestScopes.of());

        AskToolResult listed = tools.listWindows();
        AskToolResult ranked = tools.rankSpots(rank(8));
        AskToolResult<AskTools.LookUpPlaceResult> looked = tools.lookUpPlace(
                new AskTools.LookUpPlaceArgs("Place number 1", null));

        System.out.println("F1 budget: typed list_windows + rank_spots(8) + look_up_place total = "
                + tools.charsUsed() + " of " + AskTools.RESULT_CHAR_CAP);
        assertThat(listed.error()).isFalse();
        assertThat(ranked.error()).isFalse();
        assertThat(looked.error()).as(looked.content()).isFalse();
        assertThat(tools.charsUsed()).isLessThan(AskTools.RESULT_CHAR_CAP);
    }

    @Test
    @DisplayName("the busy-day list_windows (six windows, four regions, every one a pocket) fits well under "
            + "half the cap, and with a five-spot rank_spots still fits the conversation")
    void listWindows_busyDayStaysUnderHalfTheCap() {
        AskTools tools = tools(busyDaySnapshot());

        AskToolResult listed = tools.listWindows();
        AskToolResult ranked = tools.rankSpots(rank(5));

        assertThat(listed.error()).isFalse();
        assertThat(listed.content().length()).isLessThan(AskTools.RESULT_CHAR_CAP / 2);
        assertThat(ranked.error()).as("the second look the owner wants on a busy day").isFalse();
        assertThat(tools.charsUsed()).isLessThan(AskTools.RESULT_CHAR_CAP);
    }

    @Test
    @DisplayName("every solar window an ACCEPTED list_windows returned is recorded as listed; one the cap "
            + "refused lists nothing")
    void listWindows_recordsWhatTheModelWasShown() {
        AskTools accepted = tools(weekendSnapshot());
        accepted.listWindows();

        AskSnapshot busy = busyDaySnapshot();
        AskSnapshot withBigNote = new AskSnapshot(busy.generatedAt(), busy.runLabel(), busy.today(),
                busy.windows(), List.of(new AskSnapshot.Topic("ECLIPSE", "Eclipse", "d", TODAY, List.of(),
                        "n".repeat(AskTools.RESULT_CHAR_CAP - 1_000))), busy.comingUp());
        AskTools capped = tools(withBigNote);
        assertThat(capped.getHotTopics(null).error()).isFalse();
        AskToolResult late = capped.listWindows();

        assertThat(accepted.evidence().listedWindows()).containsExactly(SATURDAY_SUNSET);
        assertThat(late.error()).as("the cap refused it").isTrue();
        assertThat(capped.evidence().listedWindows()).as("a result the model never saw is not carried").isEmpty();
        assertThat(tools(weekendSnapshot()).evidence().listedWindows()).as("not called").isEmpty();
    }
}
