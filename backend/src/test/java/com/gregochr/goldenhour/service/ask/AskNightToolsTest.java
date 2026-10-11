package com.gregochr.goldenhour.service.ask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.service.DriveTimeResolver;
import com.gregochr.goldenhour.service.ask.AskSnapshot.AuroraState;
import com.gregochr.goldenhour.service.ask.AskTools.NightSpotInfo;
import com.gregochr.goldenhour.service.ask.AskTools.RankNightSpotsArgs;
import com.gregochr.goldenhour.service.ask.AskTools.RankNightSpotsResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.gregochr.goldenhour.service.ask.AskFixtures.TODAY;
import static com.gregochr.goldenhour.service.ask.AskNightFixtures.TOMORROW_NIGHT;
import static com.gregochr.goldenhour.service.ask.AskNightFixtures.TONIGHT;
import static com.gregochr.goldenhour.service.ask.AskNightFixtures.astro;
import static com.gregochr.goldenhour.service.ask.AskNightFixtures.astroNight;
import static com.gregochr.goldenhour.service.ask.AskNightFixtures.aurora;
import static com.gregochr.goldenhour.service.ask.AskNightFixtures.auroraNight;
import static com.gregochr.goldenhour.service.ask.AskNightFixtures.nightsOnly;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@code rank_night_spots} and the night rows of {@code list_windows}: the role, night and
 * kind matrix (ask-night plan §2.2, §2.3).
 */
class AskNightToolsTest {

    private static final AskUserContext PRO = new AskUserContext(7L, UserRole.PRO_USER, true);
    private static final AskUserContext ADMIN = new AskUserContext(9L, UserRole.ADMIN, false);
    private static final AskUserContext LITE = new AskUserContext(8L, UserRole.LITE_USER, true);
    private static final AskUserContext PRO_AT_HOME = new AskUserContext(7L, UserRole.PRO_USER, true,
            new AskUserContext.Home(54.9, -1.6, 22));

    private final ObjectMapper mapper = new ObjectMapper();
    private final DriveTimeResolver driveTimes = mock(DriveTimeResolver.class);

    private AskTools tools(AskSnapshot snapshot, AskUserContext user) {
        return new AskTools(snapshot, user, AskScope.ALL, driveTimes, mapper);
    }

    private static RankNightSpotsArgs args(String night, String kind) {
        return new RankNightSpotsArgs(night, kind, null, null, null, null, null, null);
    }

    private static RankNightSpotsResult ok(AskToolResult<RankNightSpotsResult> result) {
        assertThat(result.error()).as(result.content()).isFalse();
        return result.payload();
    }

    /** Tonight: Whitby astro 5 and aurora 4, Bamburgh astro 4, Seahouses astro 3, and an aurora-only Kielder. */
    private static AskSnapshot tonight() {
        return nightsOnly(new AuroraState("MODERATE", 6.0),
                astroNight(TONIGHT, astro(1L, "Whitby", "Coast", 5, 3), astro(2L, "Bamburgh", "Coast", 4, 3),
                        astro(3L, "Seahouses", "Coast", 3, 4)),
                auroraNight(TONIGHT, aurora(1L, "Whitby", "Coast", 5, 4, 3),
                        aurora(4L, "Kielder", "Dales", null, 3, 2)));
    }

    // -- the role gate ----------------------------------------------------------------------

    @Test
    @DisplayName("a PRO asker gets astro and aurora figures beside each other, the window of each, and the state")
    void pro_getsBothKinds() {
        RankNightSpotsResult result = ok(tools(tonight(), PRO).rankNightSpots(args(null, null)));

        assertThat(result.night()).isEqualTo("2026-10-05");
        assertThat(result.when()).isEqualTo("tonight");
        assertThat(result.auroraAvailable()).isNull();
        assertThat(result.auroraState()).isEqualTo("alert moderate, Kp 6.0");
        assertThat(result.spots()).extracting(NightSpotInfo::name).containsExactly("Whitby", "Bamburgh", "Kielder",
                "Seahouses");
        NightSpotInfo whitby = result.spots().getFirst();
        assertThat(whitby.astroWindowId()).isEqualTo("2026-10-05_astro");
        assertThat(whitby.auroraWindowId()).isEqualTo("2026-10-05_aurora");
        assertThat(whitby.astroStars()).isEqualTo(5);
        assertThat(whitby.auroraStars()).isEqualTo(4);
        assertThat(whitby.clearSky()).isEqualTo("Clear skies");
        assertThat(whitby.moon()).isEqualTo("Waxing gibbous, 85% lit");
        assertThat(whitby.bortle()).isEqualTo(3);
        assertThat(whitby.nightStart()).isEqualTo("19:30");
        assertThat(whitby.nightEnd()).isEqualTo("06:00");
        NightSpotInfo kielder = result.spots().get(2);
        assertThat(kielder.astroWindowId()).isNull();
        assertThat(kielder.auroraWindowId()).isEqualTo("2026-10-05_aurora");
    }

    @Test
    @DisplayName("an ADMIN asker sees aurora like a PRO one")
    void admin_getsAurora() {
        RankNightSpotsResult result = ok(tools(tonight(), ADMIN).rankNightSpots(args(null, "aurora")));

        assertThat(result.spots()).extracting(NightSpotInfo::name).containsExactly("Whitby", "Kielder");
    }

    @Test
    @DisplayName("a LITE asker gets astro only: no aurora figure, no aurora window, the Pro clause and no state")
    void lite_getsAstroOnly() {
        AskTools tools = tools(tonight(), LITE);

        RankNightSpotsResult result = ok(tools.rankNightSpots(args(null, "any")));

        assertThat(result.auroraAvailable()).isFalse();
        assertThat(result.auroraNote()).isEqualTo("aurora ratings are a Pro feature");
        assertThat(result.auroraState()).isNull();
        assertThat(result.spots()).extracting(NightSpotInfo::name).containsExactly("Whitby", "Bamburgh", "Seahouses");
        assertThat(result.spots()).allSatisfy(s -> {
            assertThat(s.auroraStars()).isNull();
            assertThat(s.auroraWindowId()).isNull();
            assertThat(s.alertLevel()).isNull();
        });
        assertThat(tools.evidence().pairs()).extracting(AskEvidence.Pair::windowId).containsOnly("2026-10-05_astro");
        assertThat(mapperJson(tools.rankNightSpots(args(null, "any")))).doesNotContain("aurora summary", "Kielder");
    }

    @Test
    @DisplayName("a LITE asker asking for aurora gets an empty list, the Pro clause, and no note about missing data")
    void lite_auroraKindIsEmptyWithTheProClause() {
        RankNightSpotsResult result = ok(tools(tonight(), LITE).rankNightSpots(args("tonight", "aurora")));

        assertThat(result.spots()).isEmpty();
        assertThat(result.auroraAvailable()).isFalse();
        assertThat(result.auroraNote()).isEqualTo("aurora ratings are a Pro feature");
        assertThat(result.note()).isNull();
    }

    @Test
    @DisplayName("the user-less (Ready) conversation is astro only, whatever its role would be")
    void userLess_isAstroOnly() {
        RankNightSpotsResult result = ok(tools(tonight(), AskUserContext.userLess()).rankNightSpots(args(null, null)));

        assertThat(result.auroraAvailable()).isFalse();
        assertThat(result.spots()).extracting(NightSpotInfo::name).containsExactly("Whitby", "Bamburgh", "Seahouses");
    }

    // -- the nights -------------------------------------------------------------------------

    @Test
    @DisplayName("tonight is the snapshot's night (the poller's), tomorrow is the next, an ISO date of either works")
    void nightResolution() {
        AskSnapshot snapshot = nightsOnly(null, astroNight(TONIGHT, astro(1L, "Whitby", "Coast", 5, 3)),
                astroNight(TOMORROW_NIGHT, astro(2L, "Bamburgh", "Coast", 4, 3)));
        AskTools tools = tools(snapshot, PRO);

        assertThat(ok(tools.rankNightSpots(args("tonight", "astro"))).spots()).extracting(NightSpotInfo::name)
                .containsExactly("Whitby");
        RankNightSpotsResult tomorrow = ok(tools.rankNightSpots(args("tomorrow", "astro")));
        assertThat(tomorrow.when()).isEqualTo("tomorrow night");
        assertThat(tomorrow.spots()).extracting(NightSpotInfo::name).containsExactly("Bamburgh");
        assertThat(ok(tools.rankNightSpots(args("2026-10-06", "astro"))).spots()).hasSize(1);
        assertThat(ok(tools.rankNightSpots(args("  TONIGHT ", "astro"))).spots()).hasSize(1);
        assertThat(tools.nightsConsulted()).containsExactly(TONIGHT, TOMORROW_NIGHT);
    }

    @Test
    @DisplayName("a date that is neither night, or not a date, is an error result the model can correct")
    void unknownNight_isAnError() {
        AskTools tools = tools(tonight(), PRO);

        AskToolResult<RankNightSpotsResult> far = tools.rankNightSpots(args("2026-10-09", null));
        AskToolResult<RankNightSpotsResult> junk = tools.rankNightSpots(args("next week", null));

        assertThat(far.error()).isTrue();
        assertThat(far.content()).contains("Unknown night '2026-10-09'");
        assertThat(junk.error()).isTrue();
        assertThat(tools.nightsConsulted()).isEmpty();
    }

    @Test
    @DisplayName("a snapshot with no nights answers a note, not an error")
    void noNights() {
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(List.of(), List.of()));

        RankNightSpotsResult result = ok(tools(snapshot, PRO).rankNightSpots(null));

        assertThat(result.spots()).isEmpty();
        assertThat(result.note()).isEqualTo("No night forecast is available yet.");
    }

    // -- the aurora state -------------------------------------------------------------------

    @Test
    @DisplayName("tonight with a quiet state reads quiet; with an alert it names the level and the Kp")
    void auroraState_forTonight() {
        AskSnapshot.Night night = astroNight(TONIGHT, astro(1L, "W", "Coast", 5, 3));
        AskSnapshot quiet = nightsOnly(new AuroraState("QUIET", null), night);
        AskSnapshot strong = nightsOnly(new AuroraState("STRONG", 7.3), night);

        assertThat(ok(tools(quiet, PRO).rankNightSpots(args("tonight", "any"))).auroraState())
                .isEqualTo("quiet: no aurora alert is active");
        assertThat(ok(tools(strong, PRO).rankNightSpots(args("tonight", "any"))).auroraState())
                .isEqualTo("alert strong, Kp 7.3");
    }

    @Test
    @DisplayName("the state cache is tonight's only: a later night with no rows reads 'not forecast yet', never "
            + "tonight's state relabelled; with rows it says nothing of its own")
    void auroraState_isNeverRelabelledForALaterNight() {
        AskSnapshot noRows = nightsOnly(new AuroraState("STRONG", 7.0),
                astroNight(TOMORROW_NIGHT, astro(1L, "W", "Coast", 5, 3)));
        AskSnapshot rows = nightsOnly(new AuroraState("STRONG", 7.0),
                auroraNight(TOMORROW_NIGHT, aurora(1L, "W", "Coast", 5, 4, 3)));

        assertThat(ok(tools(noRows, PRO).rankNightSpots(args("tomorrow", "any"))).auroraState())
                .isEqualTo("not forecast yet");
        assertThat(ok(tools(rows, PRO).rankNightSpots(args("tomorrow", "any"))).auroraState()).isNull();
    }

    @Test
    @DisplayName("a simulation leaves no state to report, and a question about astro alone never carries it")
    void auroraState_nothingWhenSimulatedOrNotAsked() {
        AskSnapshot simulated = nightsOnly(null, astroNight(TONIGHT, astro(1L, "W", "Coast", 5, 3)));

        assertThat(ok(tools(simulated, PRO).rankNightSpots(args("tonight", "any"))).auroraState()).isNull();
        assertThat(ok(tools(tonight(), PRO).rankNightSpots(args("tonight", "astro"))).auroraState()).isNull();
    }

    // -- the empty answers ------------------------------------------------------------------

    @Test
    @DisplayName("astro with no astro rows says so, even when aurora rows exist")
    void astroKind_withNoRows() {
        AskSnapshot snapshot = nightsOnly(new AuroraState("QUIET", null),
                auroraNight(TONIGHT, aurora(4L, "Kielder", "Dales", null, 3, 2)));

        RankNightSpotsResult result = ok(tools(snapshot, PRO).rankNightSpots(args("tonight", "astro")));

        assertThat(result.spots()).isEmpty();
        assertThat(result.note()).isEqualTo("no astro forecast for tonight yet");
    }

    @Test
    @DisplayName("any keeps the aurora-only spots beside the astro note when the astro job did not run")
    void anyKind_keepsAuroraOnlySpots() {
        AskSnapshot snapshot = nightsOnly(new AuroraState("MODERATE", 6.0),
                auroraNight(TONIGHT, aurora(4L, "Kielder", "Dales", null, 3, 2)));

        RankNightSpotsResult result = ok(tools(snapshot, PRO).rankNightSpots(args("tonight", null)));

        assertThat(result.spots()).extracting(NightSpotInfo::name).containsExactly("Kielder");
        assertThat(result.note()).isEqualTo("no astro forecast for tonight yet");
    }

    @Test
    @DisplayName("any with neither kind has a blanket note; aurora with no rows has its own")
    void emptyNights() {
        AskSnapshot neither = nightsOnly(new AuroraState("QUIET", null),
                astroNight(TOMORROW_NIGHT, astro(1L, "W", "Coast", 5, 3)));

        assertThat(ok(tools(neither, PRO).rankNightSpots(args("tonight", "any"))).note())
                .isEqualTo("no night forecast for tonight yet");
        assertThat(ok(tools(neither, PRO).rankNightSpots(args("tonight", "aurora"))).note())
                .isEqualTo("no aurora forecast for tonight yet");
        assertThat(ok(tools(neither, LITE).rankNightSpots(args("tonight", "any"))).note())
                .isEqualTo("no astro forecast for tonight yet");
    }

    @Test
    @DisplayName("a region with night rows elsewhere but none in it says so; an unknown region is an error")
    void regionNotes() {
        AskTools tools = tools(tonight(), PRO);

        RankNightSpotsResult none = ok(tools.rankNightSpots(new RankNightSpotsArgs(null, null, List.of("Dales"), 5,
                null, null, null, null)));
        AskToolResult<RankNightSpotsResult> unknown = tools.rankNightSpots(new RankNightSpotsArgs(null, "astro",
                List.of("Atlantis"), null, null, null, null, null));

        assertThat(none.spots()).isEmpty();
        assertThat(unknown.error()).isTrue();
        AskSnapshot coastOnly = nightsOnly(null, astroNight(TONIGHT, astro(1L, "W", "Coast", 5, 3)),
                astroNight(TOMORROW_NIGHT, astro(2L, "Elsewhere", "Dales", 5, 3)));
        RankNightSpotsResult noPlaces = ok(tools(coastOnly, PRO).rankNightSpots(new RankNightSpotsArgs("tonight",
                "astro", List.of("Dales"), null, null, null, null, null)));
        assertThat(noPlaces.note()).isEqualTo("no night-scored places in Dales");
    }

    @Test
    @DisplayName("a region that has night rows and no solar window is a name the tool knows")
    void regionNamesIncludeTheNights() {
        BriefingRegion solarOnly = AskFixtures.region("Coast", true, AskFixtures.slot(1L, "A", 4));
        AskSnapshot base = AskFixtures.snapshotOf(AskFixtures.briefing(
                List.of(AskFixtures.sunsetDay(TODAY, null, solarOnly)), List.of()));
        AskSnapshot snapshot = AskNightFixtures.withNights(base, null,
                astroNight(TONIGHT, astro(2L, "Kielder", "Dales", 5, 2)));

        RankNightSpotsResult result = ok(tools(snapshot, PRO).rankNightSpots(new RankNightSpotsArgs(null, "astro",
                List.of("dales"), null, null, null, null, null)));

        assertThat(result.spots()).extracting(NightSpotInfo::name).containsExactly("Kielder");
    }

    @Test
    @DisplayName("a question scoped to one region never sees another region's places")
    void scopeIsHonoured() {
        AskScope coast = TestScopes.of("Coast");

        RankNightSpotsResult result = ok(new AskTools(tonight(), PRO, coast, driveTimes, mapper)
                .rankNightSpots(args(null, null)));

        assertThat(result.spots()).extracting(NightSpotInfo::region).containsOnly("Coast");
    }

    // -- the filters ------------------------------------------------------------------------

    @Test
    @DisplayName("minStars, maxBortle and limit narrow the list")
    void filters() {
        AskTools tools = tools(tonight(), PRO);

        assertThat(ok(tools.rankNightSpots(new RankNightSpotsArgs(null, "astro", null, 4, null, null, null, null)))
                .spots()).extracting(NightSpotInfo::name).containsExactly("Whitby", "Bamburgh");
        assertThat(ok(tools.rankNightSpots(new RankNightSpotsArgs(null, "astro", null, 1, 3, null, null, null)))
                .spots()).extracting(NightSpotInfo::name).containsExactly("Whitby", "Bamburgh");
        assertThat(ok(tools.rankNightSpots(new RankNightSpotsArgs(null, "astro", null, 1, null, null, null, 1)))
                .spots()).hasSize(1);
        RankNightSpotsResult nothing = ok(tools.rankNightSpots(new RankNightSpotsArgs(null, "astro", null, 5, 1, null,
                null, null)));
        assertThat(nothing.spots()).isEmpty();
        assertThat(nothing.note()).isEqualTo(AskTools.NOTHING_NIGHT_MATCHES_NOTE);
    }

    @Test
    @DisplayName("a bad kind is an error result")
    void badKind() {
        assertThat(tools(tonight(), PRO).rankNightSpots(args(null, "meteors")).error()).isTrue();
    }

    @Test
    @DisplayName("maxDriveMinutes uses the asker's own drive times and makes the answer personal; the user-less "
            + "conversation cannot use it")
    void driveLimit() {
        when(driveTimes.getAllMinutes(7L)).thenReturn(Map.of(1L, 30, 2L, 90));
        AskTools tools = tools(tonight(), PRO);

        RankNightSpotsResult result = ok(tools.rankNightSpots(new RankNightSpotsArgs(null, "astro", null, null, null,
                60, null, null)));

        assertThat(result.spots()).extracting(NightSpotInfo::name).containsExactly("Whitby");
        assertThat(result.spots().getFirst().driveMinutes()).isEqualTo(30);
        assertThat(tools.personal()).isTrue();
        AskTools userLess = tools(tonight(), AskUserContext.userLess());
        assertThat(userLess.rankNightSpots(new RankNightSpotsArgs(null, null, null, null, null, 60, null, null))
                .error()).isTrue();
        AskUserContext noTimes = new AskUserContext(7L, UserRole.PRO_USER, false);
        RankNightSpotsResult none = ok(tools(tonight(), noTimes).rankNightSpots(new RankNightSpotsArgs(null, null,
                null, null, null, 60, null, null)));
        assertThat(none.note()).isEqualTo(AskTools.NO_DRIVE_TIMES_NOTE);
    }

    @Test
    @DisplayName("nearHome keeps places within the saved radius as the crow flies, says the radius, and is personal")
    void nearHome() {
        AskTools tools = tools(tonight(), PRO_AT_HOME);

        RankNightSpotsResult result = ok(tools.rankNightSpots(new RankNightSpotsArgs(null, "astro", null, null, null,
                null, true, null)));

        assertThat(result.withinMilesOfHome()).isEqualTo(22);
        assertThat(result.spots()).hasSize(3);
        assertThat(tools.personal()).isTrue();
        RankNightSpotsResult noHome = ok(tools(tonight(), PRO).rankNightSpots(new RankNightSpotsArgs(null, null, null,
                null, null, null, true, null)));
        assertThat(noHome.note()).isEqualTo(AskTools.NO_HOME_NOTE);
        assertThat(tools(tonight(), AskUserContext.userLess()).rankNightSpots(new RankNightSpotsArgs(null, null, null,
                null, null, null, true, null)).error()).isTrue();
    }

    @Test
    @DisplayName("a place too far from home is not near home")
    void nearHome_excludesFarPlaces() {
        AskUserContext farHome = new AskUserContext(7L, UserRole.PRO_USER, true,
                new AskUserContext.Home(51.5, -0.1, 10));

        RankNightSpotsResult result = ok(tools(tonight(), farHome).rankNightSpots(new RankNightSpotsArgs(null, null,
                null, null, null, null, true, null)));

        assertThat(result.spots()).isEmpty();
        assertThat(result.note()).isEqualTo("Nothing within 10 miles of home matches those filters.");
    }

    // -- the conversation's state -----------------------------------------------------------

    @Test
    @DisplayName("the conversation is uncacheable the moment the tool is called, whichever branch runs")
    void uncacheable_isSetOnEveryBranch() {
        AskSnapshot snapshot = tonight();
        AskTools ok = tools(snapshot, PRO);
        AskTools badArgument = tools(snapshot, PRO);
        AskTools noNights = tools(AskFixtures.snapshotOf(AskFixtures.briefing(List.of(), List.of())), PRO);
        AskTools untouched = tools(snapshot, PRO);

        ok.rankNightSpots(args(null, null));
        badArgument.rankNightSpots(args(null, "meteors"));
        noNights.rankNightSpots(args(null, null));
        untouched.listWindows();

        assertThat(ok.uncacheable()).isTrue();
        assertThat(badArgument.uncacheable()).isTrue();
        assertThat(noNights.uncacheable()).isTrue();
        assertThat(untouched.uncacheable()).isFalse();
    }

    @Test
    @DisplayName("every returned (location, window) pair is evidence, the aurora ones only for an asker who got them")
    void evidence() {
        AskTools pro = tools(tonight(), PRO);

        pro.rankNightSpots(args(null, null));

        assertThat(pro.evidence().pairs()).contains(new AskEvidence.Pair(1L, "2026-10-05_astro"),
                new AskEvidence.Pair(1L, "2026-10-05_aurora"), new AskEvidence.Pair(4L, "2026-10-05_aurora"),
                new AskEvidence.Pair(2L, "2026-10-05_astro"));
        assertThat(pro.nightsConsulted()).containsExactly(LocalDate.of(2026, 10, 5));
    }

    // -- list_windows -----------------------------------------------------------------------

    @Test
    @DisplayName("list_windows appends the night windows: aurora only for a PRO or ADMIN asker")
    void listWindows_roleGate() {
        AskSnapshot snapshot = nightsOnly(null, astroNight(TONIGHT, astro(1L, "W", "Coast", 4, 3)),
                auroraNight(TONIGHT, aurora(1L, "W", "Coast", 4, 3, 3)),
                astroNight(TOMORROW_NIGHT, astro(1L, "W", "Coast", 5, 3)));

        List<AskTools.WindowInfo> pro = tools(snapshot, PRO).listWindows().payload().windows();
        List<AskTools.WindowInfo> lite = tools(snapshot, LITE).listWindows().payload().windows();
        List<AskTools.WindowInfo> userLess = tools(snapshot, AskUserContext.userLess()).listWindows().payload()
                .windows();

        assertThat(pro).extracting(AskTools.WindowInfo::id).containsExactly("2026-10-05_astro", "2026-10-05_aurora",
                "2026-10-06_astro");
        assertThat(lite).extracting(AskTools.WindowInfo::id).containsExactly("2026-10-05_astro", "2026-10-06_astro");
        assertThat(userLess).extracting(AskTools.WindowInfo::id).containsExactly("2026-10-05_astro",
                "2026-10-06_astro");
        AskTools.WindowInfo tonightAstro = pro.getFirst();
        assertThat(tonightAstro.day()).isEqualTo("Tonight");
        assertThat(tonightAstro.event()).isEqualTo("astro");
        assertThat(tonightAstro.time()).isEqualTo("19:30");
        assertThat(tonightAstro.bestRating()).isEqualTo(4);
        assertThat(tonightAstro.verdict()).isNull();
        assertThat(pro.getLast().day()).isEqualTo("Tomorrow night");
    }

    @Test
    @DisplayName("list_windows omits a night with no place in the question's scope")
    void listWindows_scopeGate() {
        AskSnapshot snapshot = nightsOnly(null, astroNight(TONIGHT, astro(1L, "W", "Dales", 4, 3)));

        List<AskTools.WindowInfo> windows = new AskTools(snapshot, PRO, TestScopes.of("Coast"), driveTimes, mapper)
                .listWindows().payload().windows();

        assertThat(windows).isEmpty();
    }

    private String mapperJson(AskToolResult<RankNightSpotsResult> result) {
        return result.content();
    }
}
