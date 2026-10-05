package com.gregochr.goldenhour.service.ask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.comingup.ComingUpEntry;
import com.gregochr.goldenhour.service.DriveTimeResolver;
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
import java.util.Set;

import static com.gregochr.goldenhour.service.ask.AskFixtures.TODAY;
import static org.assertj.core.api.Assertions.assertThat;
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
        return new AskTools(snapshot, AskUserContext.userLess(), Set.of(), driveTimes, mapper);
    }

    private AskTools tools(AskSnapshot snapshot, AskUserContext user, Set<String> scope) {
        return new AskTools(snapshot, user, scope, driveTimes, mapper);
    }

    private static AskSnapshot snapshotOfRegions(BriefingRegion... regions) {
        return AskFixtures.snapshotOf(AskFixtures.briefing(
                List.of(AskFixtures.sunsetDay(TODAY, null, regions)), List.of()));
    }

    private static List<SpotInfo> spots(AskToolResult result) {
        assertThat(result.error()).as(result.content()).isFalse();
        return ((RankSpotsResult) result.payload()).spots();
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
    @DisplayName("list_windows names a weekday beyond tomorrow and omits a pick outside the question's scope")
    void listWindows_weekdayAndScope() {
        BriefingWindow.Pick best = AskFixtures.pick(BriefingWindow.PickKind.BEST, "Hills", "Cat Bells", 8L);
        BriefingRegion coast = AskFixtures.region("Coast", true, AskFixtures.slot(7L, "Whitby", 4));
        LocalDate saturday = LocalDate.of(2026, 10, 10);
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(
                List.of(AskFixtures.sunsetDay(saturday, best, coast)), List.of()));

        AskTools.WindowInfo info = ((ListWindowsResult) tools(snapshot, USER, Set.of("coast"))
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

    @Test
    @DisplayName("an unknown region name, a region outside the scope and a bad tide state are error results")
    void rankSpots_badRegionAndTideArguments() {
        BriefingRegion coast = AskFixtures.region("Coast", true, AskFixtures.slot(1L, "A", 4));
        BriefingRegion hills = AskFixtures.region("Hills", true, AskFixtures.slot(2L, "B", 4));
        AskSnapshot snapshot = snapshotOfRegions(coast, hills);

        AskToolResult unknown = tools(snapshot).rankSpots(
                new RankSpotsArgs(null, List.of("Atlantis"), null, null, null, 5));
        AskToolResult outside = tools(snapshot, USER, Set.of("Coast")).rankSpots(
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
        assertThat(spots(tools(snapshot, USER, Set.of("coast")).rankSpots(rank(5))))
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
        AskTools tools = tools(snapshotOfRegions(region), USER, Set.of());

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
        AskTools tools = tools(snapshotOfRegions(region), USER, Set.of());

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
        AskTools tools = tools(snapshotOfRegions(region), noTimes, Set.of());

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

        AskToolResult result = tools(snapshotOfRegions(region), USER, Set.of()).rankSpots(
                new RankSpotsArgs(null, null, null, null, 0, 5));

        assertThat(result.error()).isTrue();
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
    @DisplayName("tool output is capped at 6,000 characters a conversation; past it a tool errors and returns nothing")
    void resultCap_refusesPastSixThousandCharacters() {
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
    @DisplayName("the Coming up feed carries no safety note: its rows have none and offer none")
    void getComingUp_rowsCarryNoSafetyNote() {
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(List.of(), List.of()),
                List.of(AskSnapshotBuilderTest.almanacEntry("eclipse", "Partial solar eclipse",
                        TODAY.plusDays(40), TODAY.plusDays(40), "A partial eclipse")));
        AskTools tools = tools(snapshot);

        AskToolResult result = tools.getComingUp(null);

        assertThat(parse(result.content()).path("entries").get(0).has("safetyNote")).isFalse();
        assertThat(tools.evidence().events()).allSatisfy(e -> assertThat(e.safetyNote()).isNull());
    }

    @Test
    @DisplayName("get_hot_topics: a topic naming regions outside the scope is left out, a region-less one stays")
    void getHotTopics_scope() {
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(List.of(), List.of(
                AskFixtures.topic("KING_TIDE", "King tide", "Big", TOMORROW, List.of("Cornwall")),
                AskFixtures.topic("AURORA", "Aurora", "Kp 6", TODAY, List.of()),
                AskFixtures.topic("SNOW", "Snow", "Tops", TODAY, List.of("Hills", "Coast")))));

        HotTopicsResult result = (HotTopicsResult) tools(snapshot, USER, Set.of("coast"))
                .getHotTopics(null).payload();

        assertThat(result.topics()).extracting(AskTools.TopicInfo::type)
                .containsExactly("AURORA", "SNOW");
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
}
