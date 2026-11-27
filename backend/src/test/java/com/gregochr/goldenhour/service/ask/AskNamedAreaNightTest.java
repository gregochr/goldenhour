package com.gregochr.goldenhour.service.ask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.service.DriveTimeResolver;
import com.gregochr.goldenhour.service.ask.AskSnapshot.AuroraState;
import com.gregochr.goldenhour.service.ask.AskTools.NightSpotInfo;
import com.gregochr.goldenhour.service.ask.AskTools.RankNightSpotsArgs;
import com.gregochr.goldenhour.service.ask.AskTools.RankNightSpotsResult;
import com.gregochr.goldenhour.service.ask.AskTools.RankSpotsArgs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.gregochr.goldenhour.service.ask.AskFixtures.TODAY;
import static com.gregochr.goldenhour.service.ask.AskNightFixtures.TONIGHT;
import static com.gregochr.goldenhour.service.ask.AskNightFixtures.astro;
import static com.gregochr.goldenhour.service.ask.AskNightFixtures.astroNight;
import static com.gregochr.goldenhour.service.ask.AskNightFixtures.canopy;
import static com.gregochr.goldenhour.service.ask.AskNightFixtures.nightsOnly;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Ask regression triage 2026-11-27, B5: {@code rank_night_spots} resolves a named area, and a region name that
 * misses says what to pass (the named area, or the regions that exist) in both tools.
 */
class AskNamedAreaNightTest {

    private static final String COAST = "Northumberland Coast";
    private static final AskUserContext PRO = new AskUserContext(7L, UserRole.PRO_USER, true);

    private final ObjectMapper mapper = new ObjectMapper();
    private final DriveTimeResolver driveTimes = mock(DriveTimeResolver.class);

    private AskTools tools(AskSnapshot snapshot, AskUserContext user) {
        return new AskTools(snapshot, user, AskScope.ALL, driveTimes, mapper);
    }

    private static RankNightSpotsArgs night(List<String> regions, Integer minStars, String areaName) {
        return new RankNightSpotsArgs(null, "astro", regions, minStars, null, null, null, null, areaName);
    }

    private static AskSnapshot.Area coast(Long... ids) {
        return new AskSnapshot.Area(COAST, List.of("Durham coast"), Set.of(ids));
    }

    /** Whitby (1), Bamburgh (2) and Seahouses (3) on the Coast, Kielder (4) in the Dales, all astro tonight. */
    private static AskSnapshot nights(AskSnapshot.Area area) {
        return nightsOnly(new AuroraState("MODERATE", 6.0),
                astroNight(TONIGHT, astro(1L, "Whitby", "Coast", 5, 3), astro(2L, "Bamburgh", "Coast", 4, 3),
                        astro(3L, "Seahouses", "Coast", 3, 4), astro(4L, "Kielder", "Dales", 5, 1)))
                .withNames(List.of(area), Map.of());
    }

    private static RankNightSpotsResult ok(AskToolResult<RankNightSpotsResult> result) {
        assertThat(result.error()).as(result.content()).isFalse();
        return result.payload();
    }

    @Test
    @DisplayName("the old eight-argument RankNightSpotsArgs constructor stays, with no areaName")
    void olderConstructorStays() {
        assertThat(new RankNightSpotsArgs(null, null, null, null, null, null, null, null).areaName()).isNull();
    }

    @Test
    @DisplayName("an area hit keeps only the area's places, found by its own name or another name for it")
    void areaHitFiltersThePool() {
        AskSnapshot snapshot = nights(coast(2L, 3L));

        RankNightSpotsResult byName = ok(tools(snapshot, PRO).rankNightSpots(night(null, 3, COAST)));
        RankNightSpotsResult byAlias = ok(tools(snapshot, PRO).rankNightSpots(night(null, 3, "the Durham coast")));

        assertThat(byName.spots()).extracting(NightSpotInfo::name).containsExactly("Bamburgh", "Seahouses");
        assertThat(byAlias.spots()).extracting(NightSpotInfo::name).containsExactly("Bamburgh", "Seahouses");
    }

    @Test
    @DisplayName("an area hit does not make the answer personal; the night tool is uncacheable either way")
    void areaHitIsNotPersonal() {
        AskTools tools = tools(nights(coast(2L, 3L)), PRO);

        ok(tools.rankNightSpots(night(null, 3, COAST)));

        assertThat(tools.personal()).isFalse();
        assertThat(tools.uncacheable()).isTrue();
    }

    @Test
    @DisplayName("an area hit that leaves no night-scored place says so by the area's name")
    void areaHitWithNoPlaceNamesTheArea() {
        AskSnapshot snapshot = nights(coast(99L));

        RankNightSpotsResult result = ok(tools(snapshot, PRO).rankNightSpots(night(null, null, COAST)));

        assertThat(result.spots()).isEmpty();
        assertThat(result.note()).isEqualTo("no night-scored places in " + COAST);
    }

    @Test
    @DisplayName("an area hit whose places fail the other filters gets an area-named note, not the generic one")
    void areaHitFilteredOutGetsTheAreaNote() {
        AskSnapshot snapshot = nights(coast(3L));

        RankNightSpotsResult result = ok(tools(snapshot, PRO).rankNightSpots(night(null, 5, COAST)));

        assertThat(result.spots()).isEmpty();
        assertThat(result.note()).isEqualTo("Nothing in " + COAST
                + " matches those filters. Try fewer filters or a lower minStars.")
                .isNotEqualTo(AskTools.NOTHING_NIGHT_MATCHES_NOTE);
    }

    @Test
    @DisplayName("an area miss fails with the Known list and points at regionNames")
    void areaMissListsTheKnownAreas() {
        AskToolResult<RankNightSpotsResult> result = tools(nights(coast(2L)), PRO)
                .rankNightSpots(night(null, null, "Great Wall of China"));

        assertThat(result.error()).isTrue();
        assertThat(result.content()).isEqualTo("Unknown named area 'Great Wall of China'. Known: " + COAST
                + ". Or use regionNames.");
    }

    @Test
    @DisplayName("areaName fails with no user behind the conversation, as in rank_spots")
    void areaNameNeedsAUser() {
        AskToolResult<RankNightSpotsResult> result = tools(nights(coast(2L)), AskUserContext.userLess())
                .rankNightSpots(night(null, null, COAST));

        assertThat(result.error()).isTrue();
        assertThat(result.content()).isEqualTo("areaName is not available in this conversation.");
    }

    @Test
    @DisplayName("areaName and regionNames both apply: their places in common, or an empty note naming the region")
    void areaNameWithRegionNames() {
        AskSnapshot snapshot = nights(coast(2L, 4L));

        RankNightSpotsResult both = ok(tools(snapshot, PRO).rankNightSpots(night(List.of("Coast"), 3, COAST)));
        RankNightSpotsResult none = ok(tools(snapshot, PRO).rankNightSpots(night(List.of("Dales"), 3, "")));
        RankNightSpotsResult apart = ok(tools(nights(coast(2L)), PRO).rankNightSpots(
                night(List.of("Dales"), 3, COAST)));

        assertThat(both.spots()).extracting(NightSpotInfo::name).containsExactly("Bamburgh");
        assertThat(none.spots()).extracting(NightSpotInfo::name).containsExactly("Kielder");
        assertThat(apart.spots()).isEmpty();
        assertThat(apart.note()).isEqualTo("no night-scored places in Dales");
    }

    @Test
    @DisplayName("a canopy spot is filtered out of the night pool before the empty-region note")
    void canopySpotsAreDroppedFromTheNightPool() {
        AskSnapshot snapshot = nightsOnly(new AuroraState("MODERATE", 6.0),
                astroNight(TONIGHT, canopy(astro(5L, "Hollin Wood", "Dales", 5, 3))));

        RankNightSpotsResult result = ok(tools(snapshot, PRO).rankNightSpots(night(List.of("Dales"), 3, null)));

        assertThat(result.spots()).isEmpty();
        assertThat(result.note()).isEqualTo("no night-scored places in Dales");
    }

    @Test
    @DisplayName("a region name that is a named area is told to pass areaName, in both tools")
    void regionMissNamingAnAreaPointsAtAreaName() {
        AskSnapshot snapshot = nights(coast(2L));

        AskToolResult<RankNightSpotsResult> night = tools(snapshot, PRO)
                .rankNightSpots(night(List.of("Durham coast"), null, null));
        AskToolResult<AskTools.RankSpotsResult> spots = tools(snapshot, PRO)
                .rankSpots(new RankSpotsArgs(null, List.of(COAST), null, null, null, 5));

        assertThat(night.error()).isTrue();
        assertThat(night.content()).isEqualTo("'Durham coast' is a named area: pass it as areaName.");
        assertThat(spots.error()).isTrue();
        assertThat(spots.content()).isEqualTo("'" + COAST + "' is a named area: pass it as areaName.");
    }

    @Test
    @DisplayName("a region name that names nothing lists the regions that exist, within scope, in both tools")
    void regionMissNamingNothingListsTheRegions() {
        AskSnapshot snapshot = nights(coast(2L));
        AskTools scoped = new AskTools(snapshot, PRO, TestScopes.of("Coast"), driveTimes, mapper);

        AskToolResult<RankNightSpotsResult> night = tools(snapshot, PRO)
                .rankNightSpots(night(List.of("Atlantis"), null, null));
        AskToolResult<RankNightSpotsResult> narrowed = scoped.rankNightSpots(night(List.of("Atlantis"), null, null));
        AskToolResult<AskTools.RankSpotsResult> spots = tools(snapshot, PRO)
                .rankSpots(new RankSpotsArgs(null, List.of("Atlantis"), null, null, null, 5));

        assertThat(night.content()).isEqualTo("Unknown region 'Atlantis'. Known: Coast, Dales.");
        assertThat(narrowed.content()).isEqualTo("Unknown region 'Atlantis'. Known: Coast.");
        assertThat(spots.content()).isEqualTo("Unknown region 'Atlantis'. Known: Coast, Dales.");
    }

    @Test
    @DisplayName("with no user the region miss never mentions areaName, even for a name that is an area")
    void userLessRegionMissNeverMentionsAreaName() {
        AskSnapshot snapshot = nights(coast(2L));

        AskToolResult<RankNightSpotsResult> result = tools(snapshot, AskUserContext.userLess())
                .rankNightSpots(night(List.of(COAST), null, null));

        assertThat(result.content()).isEqualTo("Unknown region '" + COAST + "'. Known: Coast, Dales.")
                .doesNotContain("areaName");
    }

    @Test
    @DisplayName("a snapshot with no region at all lists none")
    void noRegionsListsNone() {
        AskSnapshot empty = AskFixtures.snapshotOf(AskFixtures.briefing(List.of(), List.of()));

        AskToolResult<AskTools.RankSpotsResult> result = tools(empty, PRO)
                .rankSpots(new RankSpotsArgs(null, List.of("Atlantis"), null, null, null, 5));

        assertThat(result.content()).isEqualTo("Unknown region 'Atlantis'. Known: none.");
    }

    @Test
    @DisplayName("a solar snapshot's regions are listed too, so a typed region miss on rank_spots names them")
    void solarRegionsAreListed() {
        BriefingRegion hills = AskFixtures.region("Hills", true, AskFixtures.slot(2L, "B", 4));
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(
                List.of(AskFixtures.sunsetDay(TODAY, null, hills)), List.of()));

        AskToolResult<AskTools.RankSpotsResult> result = tools(snapshot, PRO)
                .rankSpots(new RankSpotsArgs(null, List.of("Atlantis"), null, null, null, 5));

        assertThat(result.content()).isEqualTo("Unknown region 'Atlantis'. Known: Hills.");
    }
}
