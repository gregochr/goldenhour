package com.gregochr.goldenhour.service.ask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.service.DriveTimeResolver;
import com.gregochr.goldenhour.service.ask.AskTools.AreaInfo;
import com.gregochr.goldenhour.service.ask.AskTools.AreaPlace;
import com.gregochr.goldenhour.service.ask.AskTools.AreaWindow;
import com.gregochr.goldenhour.service.ask.AskTools.LookUpPlaceArgs;
import com.gregochr.goldenhour.service.ask.AskTools.LookUpPlaceResult;
import com.gregochr.goldenhour.service.ask.AskTools.PlaceInfo;
import com.gregochr.goldenhour.service.ask.AskTools.RankSpotsArgs;
import com.gregochr.goldenhour.service.ask.AskTools.RankSpotsResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.gregochr.goldenhour.service.ask.AskFixtures.TODAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Named areas and location aliases (named-areas plan §4.6 to §4.8): the matchers, {@code look_up_place}'s area
 * branch and its precedence rule (D10), the caps and counts, the budget pins, the rule that a lookup is never
 * evidence, and {@code rank_spots} with {@code areaName}.
 */
class AskToolsNamedAreaTest {

    private static final String TODAY_SUNSET = "2026-10-05_sunset";
    private static final int WORST_CASE_CHARS = 4_500;
    private static final AskUserContext USER = new AskUserContext(7L, UserRole.PRO_USER, true);
    private static final String WALL = "Hadrian's Wall";
    private static final String NOTHING_FOUND_NOTE = AskTools.NO_SUCH_PLACE_NOTE;

    private final ObjectMapper mapper = new ObjectMapper();
    private final DriveTimeResolver driveTimes = mock(DriveTimeResolver.class);

    private AskTools tools(AskSnapshot snapshot) {
        return new AskTools(snapshot, USER, AskScope.ALL, driveTimes, mapper);
    }

    private static AskSnapshot.Area wall(Long... ids) {
        return new AskSnapshot.Area(WALL, List.of("Hadrian Wall", "Roman Wall"), Set.of(ids));
    }

    private static AskSnapshot withWall(AskSnapshot snapshot, Long... ids) {
        return snapshot.withNames(List.of(wall(ids)), Map.of());
    }

    private static AskSnapshot snapshotOf(BriefingRegion... regions) {
        return AskFixtures.snapshotOf(AskFixtures.briefing(
                List.of(AskFixtures.sunsetDay(TODAY, null, regions)), List.of()));
    }

    private static LookUpPlaceResult ok(AskToolResult<LookUpPlaceResult> result) {
        assertThat(result.error()).as(result.content()).isFalse();
        return result.payload();
    }

    private static List<String> words(String text) {
        return AskTools.placeWords(text);
    }

    private static List<String> placeNames(AreaWindow window) {
        return window.places().stream().map(AreaPlace::name).toList();
    }

    @Test
    @DisplayName("the older constructors stay: PlaceInfo without an alias, the result without an area or reasons, "
            + "and RankSpotsArgs with seven or six arguments")
    void olderConstructorsStay() {
        PlaceInfo place = new PlaceInfo(1L, "Alnmouth", "Coast", TODAY_SUNSET, "Today", "sunset", 4, null, null,
                null, null, null, "PICK");
        LookUpPlaceResult plain = new LookUpPlaceResult(List.of(place), "a note");
        LookUpPlaceResult withReasons = new LookUpPlaceResult(List.of(place), Map.of("X", "y"), null);

        assertThat(place.matchedAlias()).isNull();
        assertThat(plain.area()).isNull();
        assertThat(plain.reasons()).isNull();
        assertThat(withReasons.area()).isNull();
        assertThat(new RankSpotsArgs(null, null, null, null, null, null, 3).areaName()).isNull();
        assertThat(new RankSpotsArgs(null, null, null, null, null, 3).areaName()).isNull();
    }

    // -- the place matcher: aliases ---------------------------------------------------------

    private static final List<String> HEUGH_ALIASES = List.of("Heugh Lighthouse", "The Heugh", "Heugh Battery");

    @Test
    @DisplayName("'heugh' finds Hartlepool Headland and reports the shortest alias, 'The Heugh'")
    void heughReportsTheShortestAlias() {
        AskTools.NameMatch match = AskTools.matchPlace("Hartlepool Headland", HEUGH_ALIASES, words("heugh"));

        assertThat(match.matchedAlias()).isEqualTo("The Heugh");
        assertThat(match.exact()).isFalse();
    }

    @Test
    @DisplayName("the reported alias does not depend on the order the aliases arrive in")
    void matchedAliasIsDeterministic() {
        List<String> shuffled = new ArrayList<>(List.of("Heugh Lighthouse", "heugh battery", "The Heugh",
                "Heugh Ness"));
        Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 12; i++) {
            Collections.shuffle(shuffled, new java.util.Random(i));
            seen.add(AskTools.matchPlace("Hartlepool Headland", shuffled, words("heugh")).matchedAlias());
        }

        assertThat(seen).containsExactly("The Heugh");
        // Equal length: case-insensitive alphabetical, whatever the order.
        assertThat(AskTools.matchPlace("X", List.of("Heugh Ness", "Heugh Bay"), words("heugh")).matchedAlias())
                .isEqualTo("Heugh Bay");
        assertThat(AskTools.matchPlace("X", List.of("Heugh Bay", "Heugh Ness"), words("heugh")).matchedAlias())
                .isEqualTo("Heugh Bay");
    }

    @Test
    @DisplayName("'heugh lighthouse' is an exact match, and its matchedAlias is that alias")
    void heughLighthouseIsExact() {
        AskTools.NameMatch match = AskTools.matchPlace("Hartlepool Headland", HEUGH_ALIASES,
                words("heugh lighthouse"));

        assertThat(match.exact()).isTrue();
        assertThat(match.matchedAlias()).isEqualTo("Heugh Lighthouse");
    }

    @Test
    @DisplayName("'hartlepool' finds it by name with no matchedAlias, and a place with no aliases is as before")
    void nameMatchHasNoAlias() {
        AskTools.NameMatch byName = AskTools.matchPlace("Hartlepool Headland", HEUGH_ALIASES, words("hartlepool"));
        AskTools.NameMatch plain = AskTools.matchPlace("Alnmouth Bay", List.of(), words("alnmouth"));

        assertThat(byName.matchedAlias()).isNull();
        assertThat(byName.exact()).isFalse();
        assertThat(plain.exact()).isFalse();
        assertThat(plain.matchedAlias()).isNull();
        assertThat(AskTools.matchPlace("Alnmouth", List.of(), words("alnmouth")).exact()).isTrue();
        assertThat(AskTools.matchPlace("Alnmouth", List.of(), words("bamburgh"))).isNull();
    }

    // -- the area matcher -------------------------------------------------------------------

    @Test
    @DisplayName("an area matches by name and by alias, ignoring case and apostrophes")
    void areaMatchesByNameAndAlias() {
        List<AskSnapshot.Area> areas = List.of(wall(1L));

        assertThat(AskTools.matchArea(areas, words("hadrians wall"))).isNotNull();
        assertThat(AskTools.matchArea(areas, words("HADRIAN’S WALL"))).isNotNull();
        assertThat(AskTools.matchArea(areas, words("roman wall"))).isNotNull();
        assertThat(AskTools.matchArea(areas, words("hadrian wall"))).isNotNull();
    }

    @Test
    @DisplayName("a run inside a longer query matches the area; 'wall' alone, or the Great Wall, matches nothing")
    void areaRunInsideALongerQuery() {
        List<AskSnapshot.Area> areas = List.of(wall(1L));

        assertThat(AskTools.matchArea(areas, words("how is steel rigg on hadrians wall tomorrow"))).isNotNull();
        assertThat(AskTools.matchArea(areas, words("along hadrians wall"))).isNotNull();
        assertThat(AskTools.matchArea(areas, words("wall"))).isNull();
        assertThat(AskTools.matchArea(areas, words("hadrians"))).isNull();
        assertThat(AskTools.matchArea(areas, words("the great wall of china"))).isNull();
    }

    @Test
    @DisplayName("when two areas match, the longest matched run wins, then name order")
    void longestRunWins() {
        AskSnapshot.Area wallArea = new AskSnapshot.Area(WALL, List.of(), Set.of(1L));
        AskSnapshot.Area path = new AskSnapshot.Area("Hadrian's Wall Path", List.of(), Set.of(2L));
        AskSnapshot.Area twinA = new AskSnapshot.Area("Alpha Coast", List.of(), Set.of(3L));
        AskSnapshot.Area twinB = new AskSnapshot.Area("Beta Coast", List.of("Alpha Coast"), Set.of(4L));

        assertThat(AskTools.matchArea(List.of(wallArea, path), words("hadrians wall path in the morning")).name())
                .isEqualTo("Hadrian's Wall Path");
        assertThat(AskTools.matchArea(List.of(path, wallArea), words("hadrians wall")).name()).isEqualTo(WALL);
        assertThat(AskTools.matchArea(List.of(twinB, twinA), words("alpha coast")).name()).isEqualTo("Alpha Coast");
    }

    // -- look_up_place: the area branch -----------------------------------------------------

    private static AskSnapshot wallSnapshot() {
        BriefingRegion north = AskFixtures.region("Northumberland", true,
                AskFixtures.slot(1L, "Walltown Crags", 4), AskFixtures.slot(2L, "Cawfields", 5),
                AskFixtures.slot(3L, "Steel Rigg", 3), AskFixtures.slot(9L, "Alnmouth", 5));
        return withWall(snapshotOf(north), 1L, 2L, 3L);
    }

    @Test
    @DisplayName("the screenshot question: 'Hadrian's Wall' returns the named area's places with their stars")
    void areaQueryReturnsMembers() {
        AskToolResult<LookUpPlaceResult> result = tools(wallSnapshot()).lookUpPlace(
                new LookUpPlaceArgs("Does Hadrian's Wall look good this weekend?", null));
        LookUpPlaceResult payload = ok(result);

        assertThat(payload.places()).isEmpty();
        AreaInfo area = payload.area();
        assertThat(area.name()).isEqualTo(WALL);
        assertThat(area.places()).isEqualTo(3);
        assertThat(area.windows()).hasSize(1);
        AreaWindow window = area.windows().getFirst();
        assertThat(window.windowId()).isEqualTo(TODAY_SUNSET);
        assertThat(window.event()).isEqualTo("sunset");
        assertThat(placeNames(window)).containsExactly("Cawfields", "Walltown Crags", "Steel Rigg");
        assertThat(window.places()).extracting(AreaPlace::rating).containsExactly(5, 4, 3);
        assertThat(window.places()).extracting(AreaPlace::status).containsOnly("PICK");
        assertThat(window.rated()).isEqualTo(3);
        assertThat(window.fourStarOrBetter()).isEqualTo(2);
        assertThat(payload.note()).isEqualTo(AskTools.AREA_SUGGEST_NOTE);
        assertThat(result.content()).doesNotContain("Alnmouth");
    }

    @Test
    @DisplayName("an alias of the named area finds it")
    void areaAliasFindsIt() {
        LookUpPlaceResult payload = ok(tools(wallSnapshot()).lookUpPlace(new LookUpPlaceArgs("Roman Wall", null)));

        assertThat(payload.area().name()).isEqualTo(WALL);
    }

    @Test
    @DisplayName("a name that matches no named area and no place is the old 'no place by that name'")
    void unknownNameIsAsBefore() {
        LookUpPlaceResult payload = ok(tools(wallSnapshot()).lookUpPlace(
                new LookUpPlaceArgs("Great Wall of China", null)));

        assertThat(payload.area()).isNull();
        assertThat(payload.places()).isEmpty();
        assertThat(payload.note()).isEqualTo(NOTHING_FOUND_NOTE);
    }

    // D10 ------------------------------------------------------------------------------------

    private static AskSnapshot viewpointSnapshot() {
        BriefingRegion north = AskFixtures.region("Northumberland", true,
                AskFixtures.slot(1L, "Walltown Crags", 4), AskFixtures.slot(2L, "Cawfields", 5),
                AskFixtures.slot(7L, "Roman Wall Viewpoint", 4), AskFixtures.slot(8L, "Roman Wall Cafe Car Park", 3));
        return withWall(snapshotOf(north), 1L, 2L);
    }

    @Test
    @DisplayName("D10: an exact place match beats the area, so 'Roman Wall Viewpoint' returns the place")
    void exactPlaceBeatsArea() {
        LookUpPlaceResult payload = ok(tools(viewpointSnapshot()).lookUpPlace(
                new LookUpPlaceArgs("Roman Wall Viewpoint", null)));

        assertThat(payload.area()).isNull();
        assertThat(payload.places()).extracting(PlaceInfo::name).containsExactly("Roman Wall Viewpoint");
    }

    @Test
    @DisplayName("D10: an exact match by alias also beats the area")
    void exactAliasBeatsArea() {
        AskSnapshot snapshot = viewpointSnapshot().withNames(List.of(wall(1L, 2L)),
                Map.of(7L, List.of("Roman Wall")));

        LookUpPlaceResult payload = ok(tools(snapshot).lookUpPlace(new LookUpPlaceArgs("Roman Wall", null)));

        assertThat(payload.area()).isNull();
        // The exact match's rows first, then the partial match's, as for any place lookup.
        assertThat(payload.places()).extracting(PlaceInfo::name)
                .containsExactly("Roman Wall Viewpoint", "Roman Wall Cafe Car Park");
        // The place's own name contains the words too, so no alias is reported.
        assertThat(payload.places().getFirst().matchedAlias()).isNull();
    }

    @Test
    @DisplayName("D10: 'Roman Wall' matches places only partially, so the area wins and names them in the note")
    void partialPlacesGiveWayToTheArea() {
        LookUpPlaceResult payload = ok(tools(viewpointSnapshot()).lookUpPlace(
                new LookUpPlaceArgs("Roman Wall", null)));

        assertThat(payload.area().name()).isEqualTo(WALL);
        assertThat(payload.places()).isEmpty();
        assertThat(payload.note()).isEqualTo("Also matched places: Roman Wall Cafe Car Park, Roman Wall Viewpoint; "
                + "ask about one by name. " + AskTools.AREA_SUGGEST_NOTE);
    }

    @Test
    @DisplayName("D10: with no area the partial place matches are the result, as before")
    void noAreaMeansThePlaceResult() {
        AskSnapshot noAreas = snapshotOf(AskFixtures.region("Northumberland", true,
                AskFixtures.slot(7L, "Roman Wall Viewpoint", 4)));

        LookUpPlaceResult payload = ok(tools(noAreas).lookUpPlace(new LookUpPlaceArgs("Roman Wall", null)));

        assertThat(payload.area()).isNull();
        assertThat(payload.places()).extracting(PlaceInfo::name).containsExactly("Roman Wall Viewpoint");
    }

    @Test
    @DisplayName("the 'also matched' note names at most three places")
    void alsoMatchedIsCappedAtThree() {
        BriefingRegion north = AskFixtures.region("Northumberland", true,
                AskFixtures.slot(1L, "Walltown Crags", 4), AskFixtures.slot(11L, "Roman Wall A", 4),
                AskFixtures.slot(12L, "Roman Wall B", 4), AskFixtures.slot(13L, "Roman Wall C", 4),
                AskFixtures.slot(14L, "Roman Wall D", 4));

        LookUpPlaceResult payload = ok(tools(withWall(snapshotOf(north), 1L)).lookUpPlace(
                new LookUpPlaceArgs("Roman Wall", null)));

        assertThat(payload.note()).contains("Also matched places: Roman Wall A, Roman Wall B, Roman Wall C; ")
                .doesNotContain("Roman Wall D");
    }

    // an alias on a place -------------------------------------------------------------------

    private static AskSnapshot heughSnapshot() {
        BriefingRegion coast = AskFixtures.region("Teesside", true,
                AskFixtures.slot(5L, "Hartlepool Headland", 4), AskFixtures.slot(6L, "Seaton Carew", 3));
        return snapshotOf(coast).withNames(List.of(), Map.of(5L, List.of("Heugh Lighthouse", "The Heugh",
                "Heugh Battery")));
    }

    @Test
    @DisplayName("'the Heugh' finds Hartlepool Headland, shown by its real name with the alias that matched")
    void aliasLookupShowsTheRealNameAndTheAlias() {
        AskTools tools = tools(heughSnapshot());

        LookUpPlaceResult payload = ok(tools.lookUpPlace(new LookUpPlaceArgs("the Heugh", null)));

        assertThat(payload.area()).isNull();
        PlaceInfo place = payload.places().getFirst();
        assertThat(place.name()).isEqualTo("Hartlepool Headland");
        assertThat(place.matchedAlias()).isEqualTo("The Heugh");
        assertThat(place.status()).isEqualTo("PICK");
        // A lookup of a PICK place, by alias, still adds no evidence.
        assertThat(tools.evidence().pairs()).isEmpty();
    }

    @Test
    @DisplayName("a lookup by the place's own name carries no matchedAlias (and none is serialised)")
    void ownNameHasNoAliasField() {
        AskToolResult<LookUpPlaceResult> result = tools(heughSnapshot()).lookUpPlace(
                new LookUpPlaceArgs("Hartlepool", null));

        assertThat(result.payload().places().getFirst().matchedAlias()).isNull();
        assertThat(result.content()).doesNotContain("matchedAlias");
    }

    // scope, windows, caps ------------------------------------------------------------------

    private static AskScope scopeOf(String region) {
        return AskScope.of(List.of(1L), List.of(region));
    }

    @Test
    @DisplayName("scope: a cross-region area in a single-region question shows only that region's members")
    void scopeFiltersMembers() {
        BriefingRegion north = AskFixtures.region("Northumberland", true, AskFixtures.slot(1L, "Walltown Crags", 4));
        BriefingRegion cumbria = AskFixtures.region("Cumbria", true, AskFixtures.slot(2L, "Irthing Gorge", 5));
        AskSnapshot snapshot = withWall(snapshotOf(north, cumbria), 1L, 2L);

        AskToolResult<LookUpPlaceResult> result = new AskTools(snapshot, USER, scopeOf("Northumberland"),
                driveTimes, mapper).lookUpPlace(new LookUpPlaceArgs(WALL, null));
        LookUpPlaceResult payload = ok(result);

        assertThat(payload.area().places()).isEqualTo(1);
        assertThat(placeNames(payload.area().windows().getFirst())).containsExactly("Walltown Crags");
        assertThat(result.content()).doesNotContain("Irthing");
    }

    @Test
    @DisplayName("scope: when every member with a slot is out of scope there are no windows and no stars")
    void allMembersOutOfScope() {
        BriefingRegion north = AskFixtures.region("Northumberland", true, AskFixtures.slot(1L, "Walltown Crags", 4));
        BriefingRegion cumbria = AskFixtures.region("Cumbria", true, AskFixtures.slot(9L, "Buttermere", 5));
        AskSnapshot snapshot = withWall(snapshotOf(north, cumbria), 1L);

        AskToolResult<LookUpPlaceResult> result = new AskTools(snapshot, USER, scopeOf("Cumbria"),
                driveTimes, mapper).lookUpPlace(new LookUpPlaceArgs(WALL, null));
        LookUpPlaceResult payload = ok(result);

        assertThat(payload.area().windows()).isEmpty();
        assertThat(payload.area().places()).isZero();
        assertThat(payload.note()).isEqualTo(AskTools.AREA_OUT_OF_SCOPE_NOTE + " " + AskTools.AREA_SUGGEST_NOTE);
        assertThat(result.content()).doesNotContain("Walltown");
    }

    @Test
    @DisplayName("when no member has a slot in the windows, the note says so")
    void noMemberInTheWindows() {
        AskSnapshot snapshot = withWall(snapshotOf(AskFixtures.region("Northumberland", true,
                AskFixtures.slot(9L, "Alnmouth", 5))), 1L);

        LookUpPlaceResult payload = ok(tools(snapshot).lookUpPlace(new LookUpPlaceArgs(WALL, null)));

        assertThat(payload.area().windows()).isEmpty();
        assertThat(payload.note()).isEqualTo(AskTools.AREA_NO_PLACES_NOTE + " " + AskTools.AREA_SUGGEST_NOTE);
    }

    private static AskSnapshot manyWindows(int windows, BriefingRegion... regions) {
        List<BriefingDay> days = new ArrayList<>();
        for (int d = 0; d < windows; d++) {
            days.add(AskFixtures.sunsetDay(TODAY.plusDays(d), null, regions));
        }
        return AskFixtures.snapshotOf(AskFixtures.briefing(days, List.of()));
    }

    @Test
    @DisplayName("the window cap: six windows list four and say so; windowIds narrows to the asked ones")
    void windowCap() {
        BriefingRegion north = AskFixtures.region("Northumberland", true, AskFixtures.slot(1L, "Walltown Crags", 4));
        AskSnapshot snapshot = withWall(manyWindows(6, north), 1L);

        LookUpPlaceResult capped = ok(tools(snapshot).lookUpPlace(new LookUpPlaceArgs(WALL, null)));
        LookUpPlaceResult narrowed = ok(tools(snapshot).lookUpPlace(
                new LookUpPlaceArgs(WALL, List.of("2026-10-07_sunset"))));

        assertThat(capped.area().windows()).hasSize(AskTools.MAX_AREA_WINDOWS);
        assertThat(capped.note()).startsWith("Showing 4 of 6 windows; pass windowIds to narrow.");
        assertThat(narrowed.area().windows()).extracting(AreaWindow::windowId).containsExactly("2026-10-07_sunset");
        assertThat(narrowed.note()).isEqualTo(AskTools.AREA_SUGGEST_NOTE);
    }

    private static BriefingRegion crowdedRegion(int members) {
        List<BriefingSlot> slots = new ArrayList<>();
        for (int i = 1; i <= members; i++) {
            slots.add(AskFixtures.slot((long) i, "Crag " + (char) ('A' + i - 1), i % 2 == 0 ? 5 : 4));
        }
        return AskFixtures.region("Northumberland", true, slots.toArray(BriefingSlot[]::new));
    }

    @Test
    @DisplayName("the per-window cap: a window lists its best six, the counts cover all members, the note is "
            + "said once however many windows are cut")
    void placeCapAndCounts() {
        AskSnapshot snapshot = withWall(manyWindows(2, crowdedRegion(9)), 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L);

        AskToolResult<LookUpPlaceResult> result = tools(snapshot).lookUpPlace(new LookUpPlaceArgs(WALL, null));
        LookUpPlaceResult payload = ok(result);

        for (AreaWindow window : payload.area().windows()) {
            assertThat(window.places()).hasSize(AskTools.MAX_AREA_PLACES);
            assertThat(window.places()).extracting(AreaPlace::rating).containsExactly(5, 5, 5, 5, 4, 4);
            assertThat(window.rated()).isEqualTo(9);
            assertThat(window.fourStarOrBetter()).isEqualTo(9);
        }
        assertThat(result.content().split(java.util.regex.Pattern.quote(AskTools.AREA_PLACES_CUT_NOTE), -1))
                .hasSize(2);
        assertThat(payload.note()).isEqualTo(AskTools.AREA_PLACES_CUT_NOTE + " " + AskTools.AREA_SUGGEST_NOTE);
    }

    @Test
    @DisplayName("rated counts the in-range ratings of all members, canopy and unrated excluded; four-star counts "
            + "those at game-on or better")
    void ratedAndFourStarCounts() {
        BriefingRegion north = AskFixtures.region("Northumberland", true,
                AskFixtures.slot(1L, "Crag A", 5), AskFixtures.slot(2L, "Crag B", 4),
                AskFixtures.slot(3L, "Crag C", 3), AskFixtures.slot(4L, "Crag D", 2),
                AskFixtures.wood(5L, "Wood E", 5), AskFixtures.slot(6L, "Crag F", null));
        AskSnapshot snapshot = withWall(snapshotOf(north), 1L, 2L, 3L, 4L, 5L, 6L);

        AreaWindow window = ok(tools(snapshot).lookUpPlace(new LookUpPlaceArgs(WALL, null)))
                .area().windows().getFirst();

        assertThat(window.rated()).isEqualTo(4);
        assertThat(window.fourStarOrBetter()).isEqualTo(2);
        assertThat(window.places()).hasSize(6);
    }

    @Test
    @DisplayName("each listed place carries its status, and the result's reasons say each present status once")
    void statusesAndReasons() {
        BriefingRegion north = AskFixtures.region("Northumberland", true,
                AskFixtures.slot(1L, "Crag A", 5), AskFixtures.outlier(AskFixtures.slot(2L, "Crag B", 5)),
                AskFixtures.wood(3L, "Wood C", 4), AskFixtures.slot(4L, "Crag D", null),
                AskFixtures.slot(5L, "Crag E", 2));
        BriefingRegion thin = AskFixtures.region("Cumbria", false, AskFixtures.slot(6L, "Crag F", 3));
        AskSnapshot snapshot = withWall(snapshotOf(north, thin), 1L, 2L, 3L, 4L, 5L, 6L);

        LookUpPlaceResult payload = ok(tools(snapshot).lookUpPlace(new LookUpPlaceArgs(WALL, null)));

        assertThat(payload.area().windows().getFirst().places()).extracting(AreaPlace::status)
                .containsExactly("PICK", "NEIGHBOUR_OUTLIER", "CANOPY", "THIN_SAMPLE", "BELOW_GAME_ON", "UNRATED");
        assertThat(payload.reasons()).containsOnlyKeys("NEIGHBOUR_OUTLIER", "CANOPY", "THIN_SAMPLE", "UNRATED");
        assertThat(payload.area().windows().getFirst().places().getLast().rating()).isNull();
    }

    // evidence ---------------------------------------------------------------------------------

    @Test
    @DisplayName("a named-area lookup adds no evidence pair, though it is a tool call")
    void areaLookupIsNeverEvidence() {
        AskTools tools = tools(wallSnapshot());

        ok(tools.lookUpPlace(new LookUpPlaceArgs(WALL, null)));

        assertThat(tools.evidence().pairs()).isEmpty();
        assertThat(tools.evidence().events()).isEmpty();
        assertThat(tools.evidence().anyToolCalled()).isTrue();
        assertThat(tools.evidence().offers(new AskEvidence.Pair(2L, TODAY_SUNSET))).isFalse();
    }

    // budgets ----------------------------------------------------------------------------------

    private static String sixtyChars(String lead) {
        return (lead + " " + "Long Name ".repeat(10)).substring(0, 60).strip();
    }

    private static BriefingSlot longSlot(long id, String name, Integer rating, boolean wood, boolean outlier) {
        BriefingSlot base = wood ? AskFixtures.wood(id, name, rating)
                : AskFixtures.coastal(id, name, rating, "HIGH", true, "T".repeat(160));
        BriefingSlot withHeadline = AskFixtures.withHeadline(base, "H".repeat(200));
        return outlier ? AskFixtures.outlier(withHeadline) : withHeadline;
    }

    /**
     * Six windows. In the first two, eight members of four statuses (so the best six are cut); in the others,
     * six members, one unrated, so every status's reason is present. Three non-member places also match "roman
     * wall", so the 'also matched' note is present.
     */
    private static AskSnapshot areaWorstCase() {
        List<Long> ids = new ArrayList<>();
        List<BriefingDay> days = new ArrayList<>();
        for (int d = 0; d < 6; d++) {
            boolean crowded = d < 2;
            List<BriefingSlot> good = new ArrayList<>();
            good.add(longSlot(1L, sixtyChars("Walltown Crags " + d), 5, false, false));
            good.add(longSlot(2L, sixtyChars("Cawfields Quarry " + d), 4, false, false));
            good.add(longSlot(3L, sixtyChars("Housesteads Fort " + d), 4, false, true));
            good.add(longSlot(4L, sixtyChars("Irthing Gorge Wood " + d), 3, true, false));
            if (crowded) {
                good.add(longSlot(5L, sixtyChars("Steel Rigg Winshield " + d), 5, false, false));
                good.add(longSlot(6L, sixtyChars("Sycamore Gap Tree " + d), 5, false, false));
            } else {
                good.add(longSlot(7L, sixtyChars("Hotbank Crags Unrated " + d), null, false, false));
            }
            good.add(longSlot(20L, sixtyChars("Roman Wall Viewpoint A " + d), 4, false, false));
            good.add(longSlot(21L, sixtyChars("Roman Wall Viewpoint B " + d), 4, false, false));
            good.add(longSlot(22L, sixtyChars("Roman Wall Viewpoint C " + d), 4, false, false));
            BriefingRegion eligible = AskFixtures.region("Northumberland Coast And Tyneside Region", true,
                    good.toArray(BriefingSlot[]::new));
            BriefingRegion thin = AskFixtures.region("The Lake District National Park Region", false,
                    longSlot(8L, sixtyChars("Crammel Linn Waterfall " + d), 3, false, false));
            days.add(AskFixtures.sunsetDay(TODAY.plusDays(d), null, eligible, thin));
        }
        for (long id = 1; id <= 8; id++) {
            ids.add(id);
        }
        return withWall(AskFixtures.snapshotOf(AskFixtures.briefing(days, List.of())), ids.toArray(Long[]::new));
    }

    @Test
    @DisplayName("worst case: four windows of six 60-character places, every status, every note, serialise to at "
            + "most 4,500 characters")
    void areaWorstCaseFitsHalfTheCap() {
        AskToolResult<LookUpPlaceResult> result = tools(areaWorstCase()).lookUpPlace(
                new LookUpPlaceArgs("Roman Wall", null));
        LookUpPlaceResult payload = ok(result);

        assertThat(payload.area().windows()).hasSize(AskTools.MAX_AREA_WINDOWS);
        assertThat(payload.area().windows()).allSatisfy(w -> assertThat(w.places()).hasSize(AskTools.MAX_AREA_PLACES));
        assertThat(payload.reasons()).containsOnlyKeys("UNRATED", "CANOPY", "THIN_SAMPLE", "NEIGHBOUR_OUTLIER");
        assertThat(payload.note()).contains("Showing 4 of 6 windows").contains(AskTools.AREA_PLACES_CUT_NOTE)
                .contains("Also matched places:").endsWith(AskTools.AREA_SUGGEST_NOTE);
        System.out.println("[look_up_place] named-area worst-case payload chars: " + result.content().length());
        assertThat(result.content().length()).isLessThanOrEqualTo(WORST_CASE_CHARS);
    }

    @Test
    @DisplayName("worst case with the 'Also matched areas' note at its maximum: five other 80-character area "
            + "names, every word of them in the query, on top of every other note, still at most 4,500 characters")
    void areaWorstCaseWithAlsoMatchedAreasFitsHalfTheCap() {
        List<AskSnapshot.Area> areas = new ArrayList<>();
        StringBuilder query = new StringBuilder();
        Long[] members = {1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L};
        for (char c = 'A'; c <= 'G'; c++) {
            String name = ("Sub Region " + c + " Of The Long Named Northern Uplands And Coast Country "
                    + "Walks Extra Words Padding Padding").substring(0, 80).strip();
            areas.add(new AskSnapshot.Area(name, List.of(), Set.of(members)));
            query.append(name).append(' ');
        }
        AskSnapshot snapshot = areaWorstCase().withNames(areas, Map.of());

        AskToolResult<LookUpPlaceResult> result = tools(snapshot).lookUpPlace(
                new LookUpPlaceArgs(query.toString(), null));
        LookUpPlaceResult payload = ok(result);

        assertThat(payload.note()).contains("Also matched areas: ").contains("this result is for ")
                .contains(AskTools.AREA_PLACES_CUT_NOTE).endsWith(AskTools.AREA_SUGGEST_NOTE);
        String alsoMatched = payload.note().substring(payload.note().indexOf("Also matched areas: "));
        assertThat(alsoMatched.substring(0, alsoMatched.indexOf(';')).split(", "))
                .as("capped at MAX_ALSO_AREAS").hasSize(AskTools.MAX_ALSO_AREAS);
        System.out.println("[look_up_place] also-matched-areas worst-case payload chars: " + result.content().length());
        assertThat(result.content().length()).isLessThanOrEqualTo(WORST_CASE_CHARS);
    }

    @Test
    @DisplayName("worst case for a place found by another name: six rows, each with a 40-character matchedAlias, "
            + "still at most 4,500 characters")
    void aliasPlaceWorstCaseFitsHalfTheCap() {
        String place = "Hartlepool Headland And The Old Town Wall Nature Reserve Car Park ";
        List<BriefingDay> days = new ArrayList<>();
        for (int d = 0; d < 6; d++) {
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
        String alias = "Heugh " + "x".repeat(34);
        assertThat(alias).hasSize(40);
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(days, List.of())).withNames(List.of(),
                Map.of(1L, List.of(alias), 2L, List.of(alias), 3L, List.of(alias), 4L, List.of(alias)));

        AskToolResult<LookUpPlaceResult> result = tools(snapshot).lookUpPlace(new LookUpPlaceArgs("heugh", null));
        LookUpPlaceResult payload = ok(result);

        assertThat(payload.places()).hasSize(AskTools.MAX_PLACE_ROWS);
        assertThat(payload.places()).extracting(PlaceInfo::matchedAlias).containsOnly(alias);
        System.out.println("[look_up_place] alias worst-case payload chars: " + result.content().length());
        assertThat(result.content().length()).isLessThanOrEqualTo(WORST_CASE_CHARS);
    }

    @Test
    @DisplayName("a busy-day list_windows followed by an unfiltered named-area lookup is not cut off by the cap")
    void areaLookupAfterABusyDayListWindowsIsNotCutOff() {
        List<BriefingDay> days = new ArrayList<>();
        String[] names = {"Northumberland", "North York Moors & Coast", "Lake District", "Yorkshire Dales"};
        for (int d = 0; d < 6; d++) {
            BriefingRegion[] regions = new BriefingRegion[5];
            for (int r = 0; r < 4; r++) {
                regions[r] = AskFixtures.bandedRegion(names[r], 1_000L * (d * 4 + r + 1), 2, 8, 20, 25 + r, 6);
            }
            List<BriefingSlot> members = new ArrayList<>();
            for (long id = 1; id <= 7; id++) {
                members.add(AskFixtures.withHeadline(AskFixtures.coastal(id, sixtyChars("Crag " + id), 4, "HIGH",
                        true, "T".repeat(160)), "H".repeat(200)));
            }
            regions[4] = AskFixtures.region("Northumberland Coast", false, members.toArray(BriefingSlot[]::new));
            days.add(AskFixtures.sunsetDay(TODAY.plusDays(d), null, regions));
        }
        AskSnapshot snapshot = withWall(AskFixtures.snapshotOf(AskFixtures.briefing(days, List.of())),
                1L, 2L, 3L, 4L, 5L, 6L, 7L);
        AskTools tools = tools(snapshot);

        AskToolResult<?> listed = tools.listWindows();
        AskToolResult<LookUpPlaceResult> lookup = tools.lookUpPlace(new LookUpPlaceArgs(WALL, null));

        assertThat(listed.error()).isFalse();
        assertThat(lookup.error()).as(lookup.content()).isFalse();
        assertThat(lookup.payload().area().windows()).hasSize(AskTools.MAX_AREA_WINDOWS);
        assertThat(tools.charsUsed()).isLessThan(AskTools.RESULT_CHAR_CAP);
    }

    // -- rank_spots with areaName -----------------------------------------------------------------

    private static RankSpotsArgs inArea(String areaName) {
        return new RankSpotsArgs(null, null, null, null, null, null, 8, areaName);
    }

    private static List<Long> ids(AskToolResult<RankSpotsResult> result) {
        assertThat(result.error()).as(result.content()).isFalse();
        return result.payload().spots().stream().map(AskTools.SpotInfo::locationId).toList();
    }

    @Test
    @DisplayName("areaName keeps only that named area's places, by name and by another name for it")
    void areaNameFilters() {
        AskSnapshot snapshot = wallSnapshot();

        assertThat(ids(tools(snapshot).rankSpots(inArea(WALL)))).containsExactly(2L, 1L, 3L);
        assertThat(ids(tools(snapshot).rankSpots(inArea("roman wall")))).containsExactly(2L, 1L, 3L);
        assertThat(ids(tools(snapshot).rankSpots(inArea(null)))).contains(9L);
    }

    @Test
    @DisplayName("areaName results are ordinary picks: recorded as evidence, and a pick from one is accepted by "
            + "the validator")
    void areaNamePicksAreEvidenceAndValidate() {
        AskSnapshot snapshot = wallSnapshot();
        AskTools tools = tools(snapshot);
        ids(tools.rankSpots(inArea(WALL)));

        assertThat(tools.evidence().pairs()).containsExactlyInAnyOrder(new AskEvidence.Pair(1L, TODAY_SUNSET),
                new AskEvidence.Pair(2L, TODAY_SUNSET), new AskEvidence.Pair(3L, TODAY_SUNSET));
        AskAnswerValidator.Result verdict = new AskAnswerValidator().validate(
                new AskAnswerValidator.Raw(true, "Cawfields looks best on the Wall.",
                        List.of(new AskAnswerValidator.RawPick(2L, TODAY_SUNSET, "Five stars at sunset.")),
                        List.of(), null),
                snapshot, tools.evidence(), AskScope.ALL, null, null, false);
        assertThat(verdict.answer().picks()).hasSize(1);
        assertThat(verdict.answer().picks().getFirst().locationId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("an unknown named area is an error that lists the known ones")
    void unknownAreaIsAnError() {
        AskToolResult<RankSpotsResult> result = tools(wallSnapshot()).rankSpots(inArea("Great Wall of China"));

        assertThat(result.error()).isTrue();
        assertThat(result.content()).isEqualTo("Unknown named area 'Great Wall of China'. Known: " + WALL + ".");
    }

    @Test
    @DisplayName("an unknown name is capped at 40 characters and the known list at KNOWN_AREAS_CAP")
    void unknownAreaErrorIsBounded() {
        List<AskSnapshot.Area> many = new ArrayList<>();
        for (int i = 0; i < 90; i++) {
            many.add(new AskSnapshot.Area("Named Area Number " + i, List.of(), Set.of(1L)));
        }
        AskSnapshot snapshot = wallSnapshot().withNames(many, Map.of());

        AskToolResult<RankSpotsResult> result = tools(snapshot).rankSpots(inArea("Z".repeat(100)));

        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("'" + "Z".repeat(39) + "…'");
        assertThat(result.content().length()).isLessThanOrEqualTo(
                "Unknown named area ''. Known: .".length() + 40 + AskTools.KNOWN_AREAS_CAP);
    }

    @Test
    @DisplayName("a conversation with no user is refused areaName, and nothing is recorded")
    void noUserIsRefused() {
        AskTools ready = new AskTools(wallSnapshot(), AskUserContext.userLess(), AskScope.ALL, driveTimes, mapper);

        AskToolResult<RankSpotsResult> result = ready.rankSpots(inArea(WALL));

        assertThat(result.error()).isTrue();
        assertThat(result.content()).isEqualTo("areaName is not available in this conversation.");
        assertThat(ready.evidence().pairs()).isEmpty();
    }

    @Test
    @DisplayName("the held-back note counts only the named area's members")
    void heldBackNoteCountsOnlyMembers() {
        BriefingRegion north = AskFixtures.region("Northumberland", true,
                AskFixtures.slot(1L, "Walltown Crags", 4), AskFixtures.outlier(AskFixtures.slot(2L, "Cawfields", 5)),
                AskFixtures.outlier(AskFixtures.slot(8L, "Alnmouth", 5)),
                AskFixtures.outlier(AskFixtures.slot(9L, "Bamburgh", 5)));
        AskSnapshot snapshot = withWall(snapshotOf(north), 1L, 2L);

        RankSpotsResult inWall = tools(snapshot).rankSpots(inArea(WALL)).payload();
        RankSpotsResult everywhere = tools(snapshot).rankSpots(inArea(null)).payload();

        assertThat(inWall.note()).contains("1 place in these windows is rated well above");
        assertThat(everywhere.note()).contains("3 places in these windows are rated well above");
    }
}
