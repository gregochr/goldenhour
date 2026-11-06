package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.Confidence;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.DriveStatus;
import com.gregochr.goldenhour.model.PlanRenderedEvent;
import com.gregochr.goldenhour.model.ReachEntry;
import com.gregochr.goldenhour.model.ReachVerdictResponse;
import com.gregochr.goldenhour.model.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Pins the reach-scoped headline engine (reach-scoped-card-plan D4, R2): served parity at full reach for home and
 * away, the pruning rules, the empty answers, and that it reads the served briefing alone.
 *
 * <p>The "served" briefing here is built by the real {@link BriefingRegionEvaluationRollup#enrich} and the real
 * {@link PlanWindowProjector#apply}, so parity is proved against the engine's own output and not a hand-written
 * fixture.
 */
@ExtendWith(MockitoExtension.class)
class ReachVerdictServiceTest {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    private static final LocalDateTime SUNRISE = LocalDateTime.of(2026, 10, 1, 6, 30);
    private static final String VERSION = "abc123def4567890";
    private static final String LAKES = "The Lake District";
    private static final String NORTH = "Northumberland";
    private static final Long LAKES_BASE_REGION_ID = 7L;
    private static final BriefingSlot.WeatherConditions WEATHER = new BriefingSlot.WeatherConditions(
            20, BigDecimal.ZERO, 15000, 70, 10.0, 8.0, 0, BigDecimal.ONE, 30, 40);

    @Mock
    private BriefingService briefingService;
    @Mock
    private ReachService reachService;
    @Mock
    private RegionService regionService;
    @Mock
    private RegionDriveDurationService regionDriveDurationService;
    @Mock
    private LocationService locationService;

    private final Authentication auth = new TestingAuthenticationToken("pro", "x", "ROLE_PRO_USER");
    private Clock clock;
    private BriefingRegionEvaluationRollup rollup;
    private ReachVerdictService service;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(TODAY.atStartOfDay(LONDON).toInstant(), LONDON);
        rollup = new BriefingRegionEvaluationRollup(clock);
        service = new ReachVerdictService(briefingService, rollup, reachService, regionService,
                regionDriveDurationService, locationService);
    }

    // ---------------------------------------------------------------------------------------------
    // Fixture: a realistic served briefing. Lakes has 12 places, 6 near (ids 101-106, rated 2) and 6 far (107-112,
    // rated 5); Northumberland has 6 places (201-206, rated 4). Served: Northumberland tops (4.0 over 3.5).
    // ---------------------------------------------------------------------------------------------

    private static BriefingSlot slot(long id) {
        return new BriefingSlot(id, "Place " + id, SUNRISE, Verdict.GO, WEATHER, BriefingSlot.TideInfo.NONE,
                List.of());
    }

    private static List<BriefingSlot> slots(long firstId, int count) {
        List<BriefingSlot> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(slot(firstId + i));
        }
        return out;
    }

    private static Map<String, BriefingEvaluationResult> ratings() {
        Map<String, BriefingEvaluationResult> results = new HashMap<>();
        for (long id = 101; id <= 106; id++) {
            results.put("Place " + id, rating(id, 2));
        }
        for (long id = 107; id <= 112; id++) {
            results.put("Place " + id, rating(id, 5));
        }
        for (long id = 201; id <= 206; id++) {
            results.put("Place " + id, rating(id, 4));
        }
        return results;
    }

    private static BriefingEvaluationResult rating(long id, int rating) {
        return new BriefingEvaluationResult("Place " + id, rating, 70, 60, "why");
    }

    private BriefingRegion rawRegion(String name, List<BriefingSlot> regionSlots) {
        return new BriefingRegion(name, Verdict.GO, "summary", List.of(), regionSlots, 12.0, 11.0, 3.0, 0,
                "Gloss for " + name, "detail", DisplayVerdict.WORTH_IT, 0);
    }

    private DailyBriefingResponse served(Map<String, BriefingEvaluationResult> results) {
        List<BriefingSlot> lakes = new ArrayList<>(slots(101, 12));
        List<BriefingSlot> north = slots(201, 6);
        List<BriefingDay> days = new ArrayList<>();
        for (int offset = 0; offset < 2; offset++) {
            List<BriefingEventSummary> events = new ArrayList<>();
            for (TargetType type : List.of(TargetType.SUNRISE, TargetType.SUNSET)) {
                events.add(new BriefingEventSummary(type,
                        List.of(rawRegion(LAKES, lakes), rawRegion(NORTH, north)), List.of()));
            }
            days.add(new BriefingDay(TODAY.plusDays(offset), events));
        }
        List<BriefingDay> enriched = rollup.enrich(days,
                (regionName, date, targetType) -> results);
        DailyBriefingResponse base = new DailyBriefingResponse(LocalDateTime.of(2026, 10, 1, 0, 0), "headline",
                enriched, List.of(), null, null, false, null, List.of(), List.of(), null, null, null, null, null);
        DailyBriefingResponse projected = PlanWindowProjector.apply(base, LocalDateTime.of(2026, 10, 1, 0, 0),
                Map.of(), Map.of());
        return projected.withBriefingVersion(VERSION);
    }

    private static List<ReachEntry> reach(int lakesNear, int lakesFar, int north, DriveStatus northStatus) {
        List<ReachEntry> entries = new ArrayList<>();
        for (long id = 101; id <= 106; id++) {
            entries.add(new ReachEntry(id, lakesNear, 10, DriveStatus.MEASURED));
        }
        for (long id = 107; id <= 112; id++) {
            entries.add(new ReachEntry(id, lakesFar, 40, DriveStatus.MEASURED));
        }
        for (long id = 201; id <= 206; id++) {
            entries.add(new ReachEntry(id, northStatus == DriveStatus.MEASURED ? north : null, 50, northStatus));
        }
        return entries;
    }

    private void home(DailyBriefingResponse servedBriefing, List<ReachEntry> entries) {
        when(briefingService.getCachedBriefingForApi()).thenReturn(servedBriefing);
        when(reachService.getReach(auth)).thenReturn(entries);
    }

    private static ReachVerdictResponse.Window window(ReachVerdictResponse response, int tier, LocalDate date,
            TargetType type) {
        return response.tiers().get(String.valueOf(tier)).stream()
                .filter(w -> w.date().equals(date) && w.targetType() == type)
                .findFirst().orElseThrow();
    }

    private static BriefingWindow servedWindow(DailyBriefingResponse served, LocalDate date, TargetType type) {
        return served.days().stream().filter(d -> d.date().equals(date)).findFirst().orElseThrow()
                .eventSummaries().stream().filter(e -> e.targetType() == type).findFirst().orElseThrow().window();
    }

    private static BriefingRegion servedRegion(DailyBriefingResponse served, String name) {
        return served.days().getFirst().eventSummaries().getFirst().regions().stream()
                .filter(r -> name.equals(r.regionName())).findFirst().orElseThrow();
    }

    // ---------------------------------------------------------------------------------------------
    // Served parity
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("sanity: the fixture serves Northumberland on top, Worth it, with a BEST pick on a window")
    void fixtureServesNorthumberlandOnTop() {
        DailyBriefingResponse served = served(ratings());

        BriefingWindow first = servedWindow(served, TODAY, TargetType.SUNRISE);
        assertThat(first.verdict()).isEqualTo(DisplayVerdict.WORTH_IT);
        assertThat(servedRegion(served, NORTH).meanRating()).isEqualTo(4.0);
        assertThat(servedRegion(served, LAKES).meanRating()).isEqualTo(3.5);
        assertThat(served.days().stream().flatMap(d -> d.eventSummaries().stream())
                .map(BriefingEventSummary::window)
                .filter(w -> w.pick() != null && w.pick().kind() == BriefingWindow.PickKind.BEST)).hasSize(1);
    }

    @Test
    @DisplayName("HOME served parity: at a reach that takes every place, each tier equals the served window "
            + "(verdict, mean, confidence, pick, and the region ranking)")
    void homeFullReachEqualsServed() {
        DailyBriefingResponse served = served(ratings());
        home(served, reach(20, 30, 40, DriveStatus.MEASURED));

        ReachVerdictResponse response = service.compute(auth, null);

        assertThat(response.measured()).isTrue();
        assertThat(response.briefingVersion()).isEqualTo(VERSION);
        for (int tier : List.of(45, 90, 150)) {
            for (BriefingDay day : served.days()) {
                for (BriefingEventSummary summary : day.eventSummaries()) {
                    BriefingWindow expected = summary.window();
                    ReachVerdictResponse.Window actual = window(response, tier, day.date(), summary.targetType());
                    assertThat(actual.displayVerdict()).as("verdict %s %s %s", tier, day.date(),
                            summary.targetType()).isEqualTo(expected.verdict());
                    assertThat(actual.confidence()).isEqualTo(expected.confidence());
                    assertThat(actual.pick()).isEqualTo(expected.pick());
                    assertThat(actual.meanRating()).isEqualTo(4.0);
                    assertThat(actual.regions()).extracting(ReachVerdictResponse.RegionVerdict::regionName)
                            .containsExactly(NORTH, LAKES);
                }
            }
        }
    }

    @Test
    @DisplayName("AWAY served parity: with every place inside the tier from the base, each tier equals the served "
            + "origin region (verdict, mean, confidence) and keeps a pick that names it")
    void awayFullReachEqualsServedOriginRegion() {
        DailyBriefingResponse served = served(ratings());
        when(briefingService.getCachedBriefingForApi()).thenReturn(served);
        awayOrigin(NORTH);
        stubAwayMatrix(20, 30);

        ReachVerdictResponse response = service.compute(auth, LAKES_BASE_REGION_ID);

        BriefingRegion expected = servedRegion(served, NORTH);
        for (int tier : List.of(45, 90, 150)) {
            ReachVerdictResponse.Window actual = window(response, tier, TODAY, TargetType.SUNRISE);
            assertThat(actual.displayVerdict()).isEqualTo(expected.displayVerdict());
            assertThat(actual.meanRating()).isEqualTo(expected.meanRating());
            assertThat(actual.confidence()).isEqualTo(expected.confidence());
            assertThat(actual.regions()).extracting(ReachVerdictResponse.RegionVerdict::regionName)
                    .containsExactly(NORTH);
        }
        BriefingWindow pickWindow = servedWindowWithBestPick(served);
        ReachVerdictResponse.Window pickCard = window(response, 150, dateOf(served, pickWindow),
                targetOf(served, pickWindow));
        assertThat(pickCard.pick()).isEqualTo(pickWindow.pick());
        assertThat(pickCard.pickRegion()).isEqualTo(NORTH);
    }

    private static BriefingWindow servedWindowWithBestPick(DailyBriefingResponse served) {
        return served.days().stream().flatMap(d -> d.eventSummaries().stream())
                .map(BriefingEventSummary::window)
                .filter(w -> w.pick() != null && w.pick().kind() == BriefingWindow.PickKind.BEST)
                .findFirst().orElseThrow();
    }

    private static LocalDate dateOf(DailyBriefingResponse served, BriefingWindow window) {
        return served.days().stream()
                .filter(d -> d.eventSummaries().stream().anyMatch(e -> e.window() == window))
                .findFirst().orElseThrow().date();
    }

    private static TargetType targetOf(DailyBriefingResponse served, BriefingWindow window) {
        return served.days().stream().flatMap(d -> d.eventSummaries().stream())
                .filter(e -> e.window() == window).findFirst().orElseThrow().targetType();
    }

    // ---------------------------------------------------------------------------------------------
    // Pruning
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("HOME: a tier prunes each region to the places inside it and re-ranks - the far Lakes places go "
            + "at 45 and Northumberland (60 min) drops out, so the Lakes' near places speak: Poor")
    void homeTierPrunesAndReRanks() {
        DailyBriefingResponse served = served(ratings());
        home(served, reach(20, 120, 60, DriveStatus.MEASURED));

        ReachVerdictResponse response = service.compute(auth, null);

        ReachVerdictResponse.Window at45 = window(response, 45, TODAY, TargetType.SUNRISE);
        assertThat(at45.displayVerdict()).isEqualTo(DisplayVerdict.STAND_DOWN);
        assertThat(at45.meanRating()).isEqualTo(2.0);
        assertThat(at45.regions()).extracting(ReachVerdictResponse.RegionVerdict::regionName)
                .containsExactly(LAKES);
        assertThat(at45.regions().getFirst().meanRating()).isEqualTo(2.0);

        // 90: Northumberland (60) is back and out-ranks the Lakes' near places; the far Lakes places still go.
        ReachVerdictResponse.Window at90 = window(response, 90, TODAY, TargetType.SUNRISE);
        assertThat(at90.displayVerdict()).isEqualTo(DisplayVerdict.WORTH_IT);
        assertThat(at90.meanRating()).isEqualTo(4.0);
        assertThat(at90.regions()).extracting(ReachVerdictResponse.RegionVerdict::regionName)
                .containsExactly(NORTH, LAKES);
        assertThat(at90.regions().get(1).meanRating()).isEqualTo(2.0);

        // 150: everything is in.
        ReachVerdictResponse.Window at150 = window(response, 150, TODAY, TargetType.SUNRISE);
        assertThat(at150.displayVerdict()).isEqualTo(servedWindow(served, TODAY, TargetType.SUNRISE).verdict());
        assertThat(at150.regions().get(1).meanRating()).isEqualTo(3.5);
    }

    @Test
    @DisplayName("an UNROUTABLE place is out at every tier and a PENDING one is in")
    void unroutableOutPendingIn() {
        DailyBriefingResponse served = served(ratings());
        List<ReachEntry> entries = new ArrayList<>(reach(20, 30, 40, DriveStatus.MEASURED).subList(0, 12));
        // Northumberland: three places UNROUTABLE, three PENDING.
        for (long id = 201; id <= 203; id++) {
            entries.add(new ReachEntry(id, null, 50, DriveStatus.UNROUTABLE));
        }
        for (long id = 204; id <= 206; id++) {
            entries.add(new ReachEntry(id, null, 50, DriveStatus.PENDING));
        }
        home(served, entries);

        ReachVerdictResponse response = service.compute(auth, null);

        ReachVerdictResponse.Window window = window(response, 45, TODAY, TargetType.SUNRISE);
        // Northumberland keeps its 3 PENDING places (3 rated 4 = mean 4.0, but a sample of 3 is below the gate):
        // it is still in the window's regions, and still ranked.
        assertThat(window.regions()).extracting(ReachVerdictResponse.RegionVerdict::regionName)
                .contains(NORTH);
        ReachVerdictResponse.RegionVerdict north = window.regions().stream()
                .filter(r -> NORTH.equals(r.regionName())).findFirst().orElseThrow();
        assertThat(north.meanRating()).isEqualTo(4.0);
        assertThat(north.displayVerdict()).isEqualTo(DisplayVerdict.UNRATED);
    }

    @Test
    @DisplayName("a slot with no location id is out of reach: the region is re-scored over the one place left")
    void nullLocationIdIsOut() {
        BriefingSlot noId = new BriefingSlot(null, "Mystery", SUNRISE, Verdict.GO, WEATHER,
                BriefingSlot.TideInfo.NONE, List.of());
        BriefingRegion only = rawRegion("Orphans", List.of(noId, slot(301)));
        DailyBriefingResponse base = new DailyBriefingResponse(LocalDateTime.of(2026, 10, 1, 0, 0), "h",
                rollup.enrich(List.of(new BriefingDay(TODAY, List.of(new BriefingEventSummary(
                        TargetType.SUNRISE, List.of(only), List.of())))),
                        (regionName, date, targetType) -> Map.of(
                                "Mystery", new BriefingEvaluationResult("Mystery", 4, 70, 60, "why"),
                                "Place 301", rating(301, 4))),
                List.of(), null, null, false, null, List.of(), List.of(), null, null, null, null, null);
        DailyBriefingResponse servedOne = PlanWindowProjector.apply(base, LocalDateTime.of(2026, 10, 1, 0, 0),
                Map.of(), Map.of()).withBriefingVersion(VERSION);
        // 301 is routable at 20 min but is the only place with an id; Mystery has none.
        home(servedOne, List.of(new ReachEntry(301L, 20, 5, DriveStatus.MEASURED)));

        ReachVerdictResponse response = service.compute(auth, null);

        ReachVerdictResponse.Window window = window(response, 45, TODAY, TargetType.SUNRISE);
        // One of two places survives: the region is re-scored over 1 place (a thin, unrated word) rather than dropped.
        assertThat(window.regions()).hasSize(1);
        assertThat(window.displayVerdict()).isEqualTo(DisplayVerdict.UNRATED);
    }

    @Test
    @DisplayName("a window whose every place is out gets the projector's empty answer: AWAITING, no mean, no pick")
    void windowEmptiedGetsAwaiting() {
        DailyBriefingResponse served = served(ratings());
        List<ReachEntry> entries = new ArrayList<>();
        for (ReachEntry entry : reach(20, 30, 40, DriveStatus.MEASURED)) {
            entries.add(new ReachEntry(entry.locationId(), null, entry.distanceMiles(), DriveStatus.UNROUTABLE));
        }
        // One place stays measured somewhere, so the reader IS measured; it is a Lakes place beyond every tier.
        entries.set(0, new ReachEntry(101L, 500, 10, DriveStatus.MEASURED));
        home(served, entries);

        ReachVerdictResponse response = service.compute(auth, null);

        assertThat(response.measured()).isTrue();
        for (int tier : List.of(45, 90, 150)) {
            ReachVerdictResponse.Window window = window(response, tier, TODAY, TargetType.SUNRISE);
            assertThat(window.displayVerdict()).isEqualTo(DisplayVerdict.AWAITING);
            assertThat(window.meanRating()).isNull();
            assertThat(window.confidence()).isNull();
            assertThat(window.pick()).isNull();
            assertThat(window.regions()).isEmpty();
        }
    }

    @Test
    @DisplayName("a verdict the pruning changes drops the region's gloss, so no pick is crowned on it")
    void changedVerdictDropsThePick() {
        DailyBriefingResponse served = served(ratings());
        home(served, reach(20, 120, 60, DriveStatus.MEASURED));

        ReachVerdictResponse response = service.compute(auth, null);

        // At 45 the Lakes (Worth it as served) is Poor over its near places: its prose no longer fits.
        for (BriefingDay day : served.days()) {
            for (BriefingEventSummary summary : day.eventSummaries()) {
                assertThat(window(response, 45, day.date(), summary.targetType()).pick()).isNull();
            }
        }
    }

    @Test
    @DisplayName("no measured drive at all: measured false, the three tier keys present and empty")
    void noMeasuredDriveIsInert() {
        DailyBriefingResponse served = served(ratings());
        List<ReachEntry> entries = new ArrayList<>();
        for (ReachEntry entry : reach(20, 30, 40, DriveStatus.MEASURED)) {
            entries.add(new ReachEntry(entry.locationId(), null, null, DriveStatus.PENDING));
        }
        home(served, entries);

        ReachVerdictResponse response = service.compute(auth, null);

        assertThat(response.measured()).isFalse();
        assertThat(response.tiers().keySet()).containsExactly("45", "90", "150");
        assertThat(response.tiers().values()).allSatisfy(windows -> assertThat(windows).isEmpty());
    }

    @Test
    @DisplayName("no briefing yet: measured still reported, no version, three empty tiers")
    void noBriefing() {
        when(briefingService.getCachedBriefingForApi()).thenReturn(null);
        when(reachService.getReach(auth)).thenReturn(reach(20, 30, 40, DriveStatus.MEASURED));

        ReachVerdictResponse response = service.compute(auth, null);

        assertThat(response.measured()).isTrue();
        assertThat(response.briefingVersion()).isNull();
        assertThat(response.tiers().values()).allSatisfy(windows -> assertThat(windows).isEmpty());
    }

    @Test
    @DisplayName("it reads the served briefing and the drive times, and nothing else")
    void readsOnlyTheServedBriefing() {
        DailyBriefingResponse served = served(ratings());
        home(served, reach(20, 30, 40, DriveStatus.MEASURED));

        service.compute(auth, null);

        verify(briefingService).getCachedBriefingForApi();
        verifyNoMoreInteractions(briefingService);
        verify(reachService).getReach(auth);
        verifyNoMoreInteractions(reachService, regionService, regionDriveDurationService, locationService);
    }

    // ---------------------------------------------------------------------------------------------
    // Away
    // ---------------------------------------------------------------------------------------------

    private RegionEntity awayOrigin(String name) {
        RegionEntity region = RegionEntity.builder().id(LAKES_BASE_REGION_ID).name(name).baseName("Base")
                .baseLat(54.5).baseLon(-3.0).build();
        when(regionService.findById(LAKES_BASE_REGION_ID)).thenReturn(region);
        return region;
    }

    private void stubAwayMatrix(int lakesMinutes, int northMinutes) {
        Map<Long, Integer> row = new HashMap<>();
        for (long id = 101; id <= 112; id++) {
            row.put(id, lakesMinutes);
        }
        for (long id = 201; id <= 206; id++) {
            row.put(id, northMinutes);
        }
        when(regionDriveDurationService.getMatrix()).thenReturn(Map.of(LAKES_BASE_REGION_ID, row));
        when(locationService.findAllEnabled()).thenReturn(locations());
    }

    private List<LocationEntity> locations() {
        List<LocationEntity> out = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        for (long id = 101; id <= 112; id++) {
            ids.add(id);
        }
        for (long id = 201; id <= 206; id++) {
            ids.add(id);
        }
        for (long id : ids) {
            out.add(LocationEntity.builder().id(id).name("Place " + id)
                    .driveTargetChangedAt(LocalDateTime.of(2026, 1, 1, 0, 0)).build());
        }
        return out;
    }

    @Test
    @DisplayName("AWAY prunes only the origin region, from the base matrix: the card stays its own region's")
    void awayPrunesOnlyTheOriginRegion() {
        DailyBriefingResponse served = served(ratings());
        when(briefingService.getCachedBriefingForApi()).thenReturn(served);
        awayOrigin(LAKES);
        Map<Long, Integer> row = new HashMap<>();
        for (long id = 101; id <= 106; id++) {
            row.put(id, 30);
        }
        for (long id = 107; id <= 112; id++) {
            row.put(id, 100);
        }
        for (long id = 201; id <= 206; id++) {
            row.put(id, 400);
        }
        when(regionDriveDurationService.getMatrix()).thenReturn(Map.of(LAKES_BASE_REGION_ID, row));
        when(locationService.findAllEnabled()).thenReturn(locations());

        ReachVerdictResponse response = service.compute(auth, LAKES_BASE_REGION_ID);

        // 45: the origin region's near six only: Poor 2.0, though Northumberland tops the roster.
        ReachVerdictResponse.Window at45 = window(response, 45, TODAY, TargetType.SUNRISE);
        assertThat(at45.displayVerdict()).isEqualTo(DisplayVerdict.STAND_DOWN);
        assertThat(at45.meanRating()).isEqualTo(2.0);
        assertThat(at45.regions()).extracting(ReachVerdictResponse.RegionVerdict::regionName)
                .containsExactly(LAKES);
        // 150: all twelve Lakes places (Northumberland at 400 min is not part of an away card at all).
        ReachVerdictResponse.Window at150 = window(response, 150, TODAY, TargetType.SUNRISE);
        assertThat(at150.meanRating()).isEqualTo(3.5);
        assertThat(at150.displayVerdict()).isEqualTo(DisplayVerdict.WORTH_IT);
        // The served pick names Northumberland, so no pick rides a Lakes card.
        for (int tier : List.of(45, 90, 150)) {
            for (BriefingDay day : served.days()) {
                for (BriefingEventSummary summary : day.eventSummaries()) {
                    assertThat(window(response, tier, day.date(), summary.targetType()).pick()).isNull();
                }
            }
        }
    }

    @Test
    @DisplayName("AWAY: an origin region with no place in reach reads AWAITING; unmeasured places are kept")
    void awayEmptyAndPending() {
        DailyBriefingResponse served = served(ratings());
        when(briefingService.getCachedBriefingForApi()).thenReturn(served);
        RegionEntity origin = awayOrigin(NORTH);
        Map<Long, Integer> row = new HashMap<>();
        row.put(101L, 20);
        when(regionDriveDurationService.getMatrix()).thenReturn(Map.of(LAKES_BASE_REGION_ID, row));
        when(locationService.findAllEnabled()).thenReturn(locations());

        // The base was never fully measured (null stamp): every unmeasured place reads PENDING and stays.
        ReachVerdictResponse pending = service.compute(auth, LAKES_BASE_REGION_ID);
        assertThat(window(pending, 45, TODAY, TargetType.SUNRISE).displayVerdict())
                .isEqualTo(servedRegion(served, NORTH).displayVerdict());

        // Measured after every place last moved, with no rows for Northumberland: UNROUTABLE, so the region empties.
        RegionEntity measured = RegionEntity.builder().id(LAKES_BASE_REGION_ID).name(NORTH).baseName("Base")
                .baseLat(54.5).baseLon(-3.0).drivesMeasuredAt(Instant.parse("2026-09-30T00:00:00Z")).build();
        when(regionService.findById(LAKES_BASE_REGION_ID)).thenReturn(measured);
        ReachVerdictResponse unroutable = service.compute(auth, LAKES_BASE_REGION_ID);
        ReachVerdictResponse.Window window = window(unroutable, 45, TODAY, TargetType.SUNRISE);
        assertThat(window.displayVerdict()).isEqualTo(DisplayVerdict.AWAITING);
        assertThat(window.regions()).isEmpty();
        assertThat(origin.getName()).isEqualTo(NORTH);
    }

    @Test
    @DisplayName("a region with no base is a 400, and an unknown region is not found")
    void awayRegionWithoutBaseOrUnknown() {
        RegionEntity noBase = RegionEntity.builder().id(9L).name("Nowhere").build();
        when(regionService.findById(9L)).thenReturn(noBase);
        when(regionService.findById(10L)).thenThrow(new NoSuchElementException("No region with id 10"));

        assertThatThrownBy(() -> service.compute(auth, 9L)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nowhere");
        assertThatThrownBy(() -> service.compute(auth, 10L)).isInstanceOf(NoSuchElementException.class);
        verifyNoMoreInteractions(briefingService, reachService);
    }

    // ---------------------------------------------------------------------------------------------
    // Window body facts
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("thinCount, and a blanked region dropped at a tier: a forced thin sample "
            + "wearing a rated word carries its rated count")
    void blankedAndThin() {
        BriefingRegion blankedRegion = new BriefingRegion("Blanked", Verdict.STANDDOWN, "s", List.of(), List.of(),
                null, null, null, null, null, "detail", DisplayVerdict.STAND_DOWN, 0);
        List<BriefingSlot> thinSlots = slots(401, 8);
        BriefingRegion thin = rawRegion("Thin", thinSlots);
        Map<String, BriefingEvaluationResult> results = new HashMap<>();
        results.put("Place 401", rating(401, 5).withForced(true));
        results.put("Place 402", rating(402, 5));
        results.put("Place 403", rating(403, 4));
        List<BriefingDay> enriched = rollup.enrich(List.of(new BriefingDay(TODAY, List.of(
                new BriefingEventSummary(TargetType.SUNRISE, List.of(thin), List.of()),
                new BriefingEventSummary(TargetType.SUNSET, List.of(blankedRegion), List.of())))),
                (regionName, date, targetType) -> results);
        DailyBriefingResponse base = new DailyBriefingResponse(LocalDateTime.of(2026, 10, 1, 0, 0), "h", enriched,
                List.of(), null, null, false, null, List.of(), List.of(), null, null, null, null, null);
        DailyBriefingResponse servedThin = PlanWindowProjector.apply(base, LocalDateTime.of(2026, 10, 1, 0, 0),
                Map.of(), Map.of()).withBriefingVersion(VERSION);
        List<ReachEntry> entries = new ArrayList<>();
        for (long id = 401; id <= 408; id++) {
            entries.add(new ReachEntry(id, 20, 5, DriveStatus.MEASURED));
        }
        home(servedThin, entries);

        ReachVerdictResponse response = service.compute(auth, null);

        ReachVerdictResponse.Window sunrise = window(response, 45, TODAY, TargetType.SUNRISE);
        assertThat(sunrise.displayVerdict()).isEqualTo(DisplayVerdict.WORTH_IT);
        assertThat(sunrise.thinCount()).isEqualTo(3);
        assertThat(sunrise.blanked()).isFalse();
        assertThat(sunrise.meanRating()).isEqualTo(4.7);
        assertThat(sunrise.confidence()).isNotNull();
        assertThat(sunrise.topRegionName()).isEqualTo("Thin");
        // A region the honesty filter blanked has no place to be reached, so at a tier it is dropped, not kept:
        // the window it alone filled takes the projector's empty answer.
        ReachVerdictResponse.Window sunset = window(response, 45, TODAY, TargetType.SUNSET);
        assertThat(sunset.displayVerdict()).isEqualTo(DisplayVerdict.AWAITING);
        assertThat(sunset.blanked()).isFalse();
        assertThat(sunset.topRegionName()).isNull();
        assertThat(sunset.meanRating()).isNull();
        assertThat(sunset.thinCount()).isNull();
    }

    @Test
    @DisplayName("an UNRATED word prints its mean (the evidence the gate withheld a verdict from), "
            + "but the topMeanRating is served either way")
    void unratedWordPrintsItsMean() {
        DailyBriefingResponse served = served(ratings());
        List<ReachEntry> entries = new ArrayList<>(reach(20, 120, 400, DriveStatus.MEASURED));
        // Keep two Lakes near places and nothing else at 45: 2 rated of 12 voting places is not a sample.
        for (int i = 2; i < 6; i++) {
            ReachEntry gone = entries.get(i);
            entries.set(i, new ReachEntry(gone.locationId(), 400, gone.distanceMiles(), DriveStatus.MEASURED));
        }
        home(served, entries);

        ReachVerdictResponse response = service.compute(auth, null);

        ReachVerdictResponse.Window window = window(response, 45, TODAY, TargetType.SUNRISE);
        assertThat(window.displayVerdict()).isEqualTo(DisplayVerdict.UNRATED);
        assertThat(window.meanRating()).isEqualTo(2.0);
        assertThat(window.topMeanRating()).isEqualTo(2.0);
        assertThat(window.confidence()).isEqualTo(Confidence.LOW);
    }

    @Test
    @DisplayName("the extracted arithmetic is idempotent on an unpruned served region")
    void rerollOfAServedRegionChangesNothing() {
        DailyBriefingResponse served = served(ratings());
        BriefingRegion north = servedRegion(served, NORTH);

        BriefingRegion again = rollup.rerollRegion(north, north.slots(), TODAY, TargetType.SUNRISE);

        assertThat(again.displayVerdict()).isEqualTo(north.displayVerdict());
        assertThat(again.meanRating()).isEqualTo(north.meanRating());
        assertThat(again.bestRating()).isEqualTo(north.bestRating());
        assertThat(again.confidence()).isEqualTo(north.confidence());
        assertThat(again.sampleSufficient()).isEqualTo(north.sampleSufficient());
        assertThat(again.forcedSample()).isEqualTo(north.forcedSample());
        assertThat(again.glossHeadline()).isEqualTo(north.glossHeadline());
        assertThat(again.scoredLocationCount()).isEqualTo(north.scoredLocationCount());
    }

    @Test
    @DisplayName("renderedEvents bounds where a pruned pick may land")
    void picksStayInsideTheRenderHorizon() {
        DailyBriefingResponse served = served(ratings());
        PlanRenderedEvent only = new PlanRenderedEvent(TODAY, TargetType.SUNSET);
        DailyBriefingResponse narrowed = served.withPlan(served.days(), List.of(only));
        home(narrowed, reach(20, 30, 40, DriveStatus.MEASURED));

        ReachVerdictResponse response = service.compute(auth, null);

        long picks = response.tiers().get("150").stream().filter(w -> w.pick() != null).count();
        assertThat(picks).isEqualTo(1);
        assertThat(window(response, 150, TODAY, TargetType.SUNSET).pick()).isNotNull();
        assertThat(Set.copyOf(response.tiers().get("150").stream().map(ReachVerdictResponse.Window::date).toList()))
                .contains(TODAY, TODAY.plusDays(1));
    }

    // ---------------------------------------------------------------------------------------------
    // Review fixes: away canopy rule, blanked far regions, honesty for an emptied sample
    // ---------------------------------------------------------------------------------------------

    private DailyBriefingResponse servedOf(List<BriefingRegion> regions, Map<String, BriefingEvaluationResult> results,
            java.util.Set<String> triaged) {
        List<BriefingDay> days = List.of(new BriefingDay(TODAY, List.of(
                new BriefingEventSummary(TargetType.SUNRISE, regions, List.of()))));
        List<BriefingDay> enriched = rollup.enrich(days, (regionName, date, targetType) -> results,
                (regionName, date, targetType) -> triaged);
        DailyBriefingResponse base = new DailyBriefingResponse(LocalDateTime.of(2026, 10, 1, 0, 0), "h", enriched,
                List.of(), null, null, false, null, List.of(), List.of(), null, null, null, null, null);
        return PlanWindowProjector.apply(base, LocalDateTime.of(2026, 10, 1, 0, 0), Map.of(), Map.of())
                .withBriefingVersion(VERSION);
    }

    @Test
    @DisplayName("AWAY: a base whose only place in reach is a wood still gets ITS region's card, not AWAITING "
            + "(the projector's window-level canopy rule thins the ranking, not the card)")
    void awayWoodlandOnlyRegionKeepsItsCard() {
        BriefingSlot wood1 = BriefingSlot.canopySlot(501L, "Place 501", SUNRISE, Verdict.GO, WEATHER, List.of());
        BriefingSlot wood2 = BriefingSlot.canopySlot(502L, "Place 502", SUNRISE, Verdict.GO, WEATHER, List.of());
        Map<String, BriefingEvaluationResult> results = new HashMap<>();
        results.put("Place 501", rating(501, 4));
        results.put("Place 502", rating(502, 4));
        for (long id = 201; id <= 206; id++) {
            results.put("Place " + id, rating(id, 4));
        }
        DailyBriefingResponse served = servedOf(List.of(rawRegion(NORTH, slots(201, 6)),
                rawRegion("Woods", List.of(wood1, wood2))), results, Set.of());
        when(briefingService.getCachedBriefingForApi()).thenReturn(served);
        awayOrigin("Woods");
        Map<Long, Integer> row = new HashMap<>();
        row.put(501L, 20);
        row.put(502L, 20);
        when(regionDriveDurationService.getMatrix()).thenReturn(Map.of(LAKES_BASE_REGION_ID, row));
        List<LocationEntity> locations = new ArrayList<>();
        for (long id : new long[] {501L, 502L}) {
            locations.add(LocationEntity.builder().id(id).name("Place " + id)
                    .driveTargetChangedAt(LocalDateTime.of(2026, 1, 1, 0, 0)).build());
        }
        when(locationService.findAllEnabled()).thenReturn(locations);

        ReachVerdictResponse.Window card = window(service.compute(auth, LAKES_BASE_REGION_ID), 45, TODAY,
                TargetType.SUNRISE);

        assertThat(card.topRegionName()).isEqualTo("Woods");
        assertThat(card.displayVerdict()).isNotEqualTo(DisplayVerdict.AWAITING);
        assertThat(card.regions()).extracting(ReachVerdictResponse.RegionVerdict::regionName)
                .containsExactly("Woods");
    }

    @Test
    @DisplayName("a distant blanked region is dropped at every tier, never left to speak for the window")
    void blankedFarRegionIsDropped() {
        BriefingRegion blanked = new BriefingRegion("Far", Verdict.STANDDOWN, "s", List.of(), List.of(),
                null, null, null, null, null, "detail", DisplayVerdict.STAND_DOWN, 0);
        Map<String, BriefingEvaluationResult> results = new HashMap<>();
        for (long id = 701; id <= 706; id++) {
            results.put("Place " + id, rating(id, 2));
        }
        DailyBriefingResponse served = servedOf(List.of(rawRegion("Near", slots(701, 6)), blanked), results, Set.of());
        List<ReachEntry> entries = new ArrayList<>();
        for (long id = 701; id <= 706; id++) {
            entries.add(new ReachEntry(id, 100, 30, DriveStatus.MEASURED));
        }
        home(served, entries);

        ReachVerdictResponse response = service.compute(auth, null);

        for (int tier : List.of(45, 90)) {
            ReachVerdictResponse.Window window = window(response, tier, TODAY, TargetType.SUNRISE);
            assertThat(window.displayVerdict()).isEqualTo(DisplayVerdict.AWAITING);
            assertThat(window.blanked()).isFalse();
            assertThat(window.topRegionName()).isNull();
            assertThat(window.regions()).isEmpty();
        }
        assertThat(window(response, 150, TODAY, TargetType.SUNRISE).topRegionName()).isEqualTo("Near");
    }

    private ReachVerdictResponse.Window emptiedSample(Set<String> triaged) {
        Map<String, BriefingEvaluationResult> results = new HashMap<>();
        results.put("Place 601", rating(601, 4));
        DailyBriefingResponse served = servedOf(List.of(rawRegion("Mixed", slots(601, 3))), results, triaged);
        List<ReachEntry> entries = List.of(new ReachEntry(601L, 100, 30, DriveStatus.MEASURED),
                new ReachEntry(602L, 20, 5, DriveStatus.MEASURED), new ReachEntry(603L, 20, 5, DriveStatus.MEASURED));
        home(served, entries);
        return window(service.compute(auth, null), 45, TODAY, TargetType.SUNRISE);
    }

    @Test
    @DisplayName("honesty, stood-down arm: the tier leaves only places the weather really stood down - STAND_DOWN, "
            + "blanked, no mean")
    void emptiedSampleStoodDown() {
        ReachVerdictResponse.Window window = emptiedSample(Set.of("Place 602"));

        assertThat(window.displayVerdict()).isEqualTo(DisplayVerdict.STAND_DOWN);
        assertThat(window.blanked()).isTrue();
        assertThat(window.meanRating()).isNull();
        assertThat(window.topRegionName()).isEqualTo("Mixed");
    }

    @Test
    @DisplayName("honesty, not-attempted arm: places simply not scored read AWAITING, never 'Stand down'")
    void emptiedSampleNotAttempted() {
        ReachVerdictResponse.Window window = emptiedSample(Set.of());

        assertThat(window.displayVerdict()).isEqualTo(DisplayVerdict.AWAITING);
        assertThat(window.blanked()).isFalse();
        assertThat(window.meanRating()).isNull();
    }
}
