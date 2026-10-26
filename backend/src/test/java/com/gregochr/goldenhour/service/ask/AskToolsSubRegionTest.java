package com.gregochr.goldenhour.service.ask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.service.DriveTimeResolver;
import com.gregochr.goldenhour.service.ask.AskTools.LookUpPlaceArgs;
import com.gregochr.goldenhour.service.ask.AskTools.LookUpPlaceResult;
import com.gregochr.goldenhour.service.ask.AskTools.RankSpotsArgs;
import com.gregochr.goldenhour.service.ask.AskTools.RankSpotsResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.gregochr.goldenhour.service.ask.AskFixtures.TODAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Sub-regions as Ask sees them (sub-regions-plan §6): they are areas, so {@code look_up_place} resolves their
 * names and one-word aliases; a question that names several areas is answered for the best match with the
 * others named in a note; the V189 "Hadrian's Wall" area and the "Hadrian's Wall &amp; Tynedale" sub-region
 * both resolve deterministically; and the "Known:" list holds every one of the 25 names.
 */
class AskToolsSubRegionTest {

    private static final String WALL = "Hadrian's Wall";
    private static final String WALL_SUB_REGION = "Hadrian's Wall & Tynedale";
    private static final AskUserContext USER = new AskUserContext(7L, UserRole.PRO_USER, true);

    /** The 24 sub-region names of plan §3 and the V189 Wall area: the list the "Known:" error must hold. */
    private static final List<String> ALL_NAMES = List.of(WALL, "Berwick & Holy Island", "Bamburgh to Howick",
            "Alnwick & the Coquet Coast", "Druridge Bay to Tynemouth", "Cheviots & Coquetdale", WALL_SUB_REGION,
            "Newcastle & Durham", "Sunderland & the Durham Coast", "Windermere & Coniston", "The Langdales",
            "Ambleside, Grasmere & Rydal", "Wasdale, Eskdale & Duddon", "Buttermere, Loweswater & Ennerdale",
            "Keswick & Borrowdale", "Ullswater & Haweswater", "The Three Peaks", "Malhamdale & Airedale",
            "Wensleydale", "Swaledale", "Wharfedale", "Nidderdale", "Redcar to Whitby",
            "Robin Hood's Bay to Flamborough", "Inland North York Moors");

    private final ObjectMapper mapper = new ObjectMapper();
    private final DriveTimeResolver driveTimes = mock(DriveTimeResolver.class);

    private AskTools tools(AskSnapshot snapshot) {
        return new AskTools(snapshot, USER, AskScope.ALL, driveTimes, mapper);
    }

    private static AskSnapshot.Area area(String name, List<String> aliases, Long... ids) {
        return new AskSnapshot.Area(name, aliases, Set.of(ids));
    }

    private static AskSnapshot snapshotWith(List<AskSnapshot.Area> areas) {
        BriefingRegion north = AskFixtures.region("Northumberland", true,
                AskFixtures.slot(1L, "Walltown Crags", 4), AskFixtures.slot(2L, "Cawfields", 5),
                AskFixtures.slot(3L, "Allen Banks", 3), AskFixtures.slot(4L, "High Force", 4),
                AskFixtures.slot(5L, "Hardraw Force", 5));
        return AskFixtures.snapshotOf(AskFixtures.briefing(
                List.of(AskFixtures.sunsetDay(TODAY, null, north)), List.of())).withNames(areas, Map.of());
    }

    private static List<AskSnapshot.Area> wallAndSubRegion() {
        return List.of(area(WALL, List.of("Hadrian Wall", "Roman Wall"), 1L, 2L),
                area(WALL_SUB_REGION, List.of("South Tyne", "Tynedale", "Kielder"), 1L, 2L, 3L));
    }

    private static List<AskSnapshot.Area> dales() {
        return List.of(area("Swaledale", List.of("Teesdale", "Upper Teesdale"), 4L),
                area("Wensleydale", List.of("Upper Wensleydale"), 5L));
    }

    private static RankSpotsArgs inArea(String areaName) {
        return new RankSpotsArgs(null, null, null, null, null, null, 8, areaName);
    }

    private LookUpPlaceResult lookUp(AskSnapshot snapshot, String query) {
        AskToolResult<LookUpPlaceResult> result = tools(snapshot).lookUpPlace(new LookUpPlaceArgs(query, null));
        assertThat(result.error()).as(result.content()).isFalse();
        return result.payload();
    }

    @Test
    @DisplayName("a sub-region resolves by name, and by a one-word alias")
    void subRegionResolvesByNameAndOneWordAlias() {
        AskSnapshot snapshot = snapshotWith(dales());

        LookUpPlaceResult byName = lookUp(snapshot, "How is Swaledale this evening?");
        LookUpPlaceResult byAlias = lookUp(snapshot, "Is Teesdale any good tonight?");

        assertThat(byName.area().name()).isEqualTo("Swaledale");
        assertThat(byName.area().places()).isEqualTo(1);
        assertThat(byAlias.area().name()).isEqualTo("Swaledale");
    }

    @Test
    @DisplayName("'Hadrian's Wall' resolves to the Wall area, with no 'also matched areas' note")
    void wallNameResolvesToTheWallArea() {
        LookUpPlaceResult result = lookUp(snapshotWith(wallAndSubRegion()), "Does Hadrian's Wall look good?");

        assertThat(result.area().name()).isEqualTo(WALL);
        assertThat(result.area().places()).isEqualTo(2);
        assertThat(result.note()).doesNotContain("Also matched areas");
    }

    @Test
    @DisplayName("'Hadrian's Wall & Tynedale', and its aliases, resolve to the sub-region, and the Wall area whose "
            + "words it contains is not offered as a second area")
    void wallAndTynedaleResolvesToTheSubRegion() {
        AskSnapshot snapshot = snapshotWith(wallAndSubRegion());

        LookUpPlaceResult full = lookUp(snapshot, "Hadrian's Wall & Tynedale tonight");
        LookUpPlaceResult alias = lookUp(snapshot, "How is Tynedale?");
        LookUpPlaceResult southTyne = lookUp(snapshot, "The South Tyne valley");

        assertThat(full.area().name()).isEqualTo(WALL_SUB_REGION);
        assertThat(full.area().places()).isEqualTo(3);
        assertThat(full.note()).doesNotContain("Also matched areas");
        assertThat(alias.area().name()).isEqualTo(WALL_SUB_REGION);
        assertThat(southTyne.area().name()).isEqualTo(WALL_SUB_REGION);
    }

    @Test
    @DisplayName("a question naming two areas is answered for the first and names the other in a note")
    void twoAreasGetAnAlsoMatchedNote() {
        LookUpPlaceResult result = lookUp(snapshotWith(dales()), "Swaledale or Wensleydale?");

        assertThat(result.area().name()).as("equal runs: alphabetical").isEqualTo("Swaledale");
        assertThat(result.note()).contains("Also matched areas: Wensleydale;")
                .contains("this result is for Swaledale only")
                .contains("call look_up_place once per area");
    }

    @Test
    @DisplayName("the 'also matched areas' note names at most five areas, the best first")
    void alsoMatchedAreasIsCapped() {
        List<AskSnapshot.Area> many = new ArrayList<>();
        for (char c = 'A'; c <= 'H'; c++) {
            many.add(area("Dale" + c, List.of(), 4L));
        }

        LookUpPlaceResult result = lookUp(snapshotWith(many), "daleA daleB daleC daleD daleE daleF daleG daleH");

        assertThat(result.area().name()).isEqualTo("DaleA");
        assertThat(result.note()).contains("Also matched areas: DaleB, DaleC, DaleD, DaleE, DaleF;")
                .doesNotContain("DaleG");
    }

    @Test
    @DisplayName("matchingAreas lists every match best first; matchArea is its first")
    void matchingAreasOrder() {
        List<AskSnapshot.Area> areas = new ArrayList<>(dales());
        areas.addAll(wallAndSubRegion());

        List<AskSnapshot.Area> matched = AskTools.matchingAreas(areas,
                AskTools.placeWords("hadrians wall tynedale or swaledale or wensleydale"));

        assertThat(matched).extracting(AskSnapshot.Area::name)
                .containsExactly(WALL_SUB_REGION, "Swaledale", "Wensleydale");
        assertThat(AskTools.matchArea(areas, AskTools.placeWords("hadrians wall tynedale or swaledale")).name())
                .isEqualTo(WALL_SUB_REGION);
        assertThat(AskTools.matchingAreas(areas, AskTools.placeWords("the moon"))).isEmpty();
        assertThat(AskTools.matchArea(areas, AskTools.placeWords("the moon"))).isNull();
    }

    @Test
    @DisplayName("an unknown area's 'Known:' list holds all 25 names, none cut")
    void knownListHoldsEveryName() {
        List<AskSnapshot.Area> areas = new ArrayList<>();
        for (String name : ALL_NAMES) {
            areas.add(area(name, List.of(), 1L));
        }

        AskToolResult<RankSpotsResult> result = tools(snapshotWith(areas)).rankSpots(
                inArea("Great Wall of China"));

        assertThat(result.error()).isTrue();
        String known = result.content().substring(result.content().indexOf("Known: ") + "Known: ".length());
        assertThat(known).isEqualTo(String.join(", ", ALL_NAMES) + ".");
        assertThat(String.join(", ", ALL_NAMES).length()).as("the list the cap must hold")
                .isLessThan(AskTools.KNOWN_AREAS_CAP);
    }

    @Test
    @DisplayName("rank_spots takes a sub-region's name and its members only")
    void rankSpotsInASubRegion() {
        AskToolResult<RankSpotsResult> result = tools(snapshotWith(dales())).rankSpots(
                inArea("Teesdale"));

        assertThat(result.error()).as(result.content()).isFalse();
        assertThat(result.payload().spots()).extracting(AskTools.SpotInfo::locationId).containsExactly(4L);
    }
}
