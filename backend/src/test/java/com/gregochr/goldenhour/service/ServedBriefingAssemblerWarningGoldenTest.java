package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.SubRegionProperties;
import com.gregochr.goldenhour.config.WarningsProperties;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.WeatherWarningVersionEntity;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.WeatherWarningVersionRepository;
import com.gregochr.goldenhour.service.pipeline.BestBetFallbackService;
import com.gregochr.goldenhour.service.warnings.WarningCoverage;
import com.gregochr.goldenhour.service.warnings.WarningOverlay;
import com.gregochr.goldenhour.service.warnings.WarningTimeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.gregochr.goldenhour.service.TideFactFixtures.day;
import static com.gregochr.goldenhour.service.TideFactFixtures.inland;
import static com.gregochr.goldenhour.service.TideFactFixtures.region;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The golden test for the weather-warnings series: a warning never changes a rating, verdict, Best bet, pick, count or
 * heat-field input. The REAL {@link ServedBriefingAssembler} is driven twice, with the overlay off and on, and the
 * only permitted difference is the warning fields (docs/engineering/weather-warnings-plan.md, principle 1).
 */
@ExtendWith(MockitoExtension.class)
class ServedBriefingAssemblerWarningGoldenTest {

    private static final LocalDate D = LocalDate.of(2026, 10, 11);
    private static final Instant NOW = Instant.parse("2026-10-08T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String BOX = "{\"type\":\"MultiPolygon\",\"coordinates\":[[[[-3,54],[-2,54],[-2,55],[-3,55],"
            + "[-3,54]]]]}";

    @Mock
    private BestBetFallbackService bestBetFallbackService;
    @Mock
    private BriefingRegionSnapshotService regionSnapshotService;
    @Mock
    private WindowTideRollupBuilder windowTideRollupBuilder;
    @Mock
    private BigSeasBuilder bigSeasBuilder;
    @Mock
    private EvaluationViewService evaluationViewService;
    @Mock
    private LocationPointProvider locationPointProvider;
    @Mock
    private WeatherWarningVersionRepository warningRepository;
    @Mock
    private LocationRepository locationRepository;
    @Mock
    private SolarEventFreshness solarEventFreshness;

    private ServedBriefingAssembler plain;
    private ServedBriefingAssembler warned;

    @BeforeEach
    void setUp() {
        NeighbourGuardProperties guard = new NeighbourGuardProperties();
        guard.setEnabled(false);
        when(regionSnapshotService.previousBuild(LocalDateTime.of(2026, 10, 8, 6, 0)))
                .thenReturn(BriefingRegionSnapshotService.PreviousBuild.none());
        when(evaluationViewService.loadServeTriage(D, D, Set.of(TargetType.SUNRISE, TargetType.SUNSET)))
                .thenReturn(EvaluationViewService.ServeTriage.EMPTY);
        WarningsProperties on = new WarningsProperties();
        on.setEnabled(true);
        WarningOverlay overlay = new WarningOverlay(warningRepository, new WarningTimeline(CLOCK),
                new WarningCoverage(), locationRepository, on, CLOCK, solarEventFreshness);
        plain = new ServedBriefingAssembler(bestBetFallbackService, regionSnapshotService, windowTideRollupBuilder,
                evaluationViewService, CLOCK, (days, resolver, triaged) -> days, locationPointProvider, guard,
                new NeighbourGuardLog(line -> { }), bigSeasBuilder);
        warned = new ServedBriefingAssembler(bestBetFallbackService, regionSnapshotService, windowTideRollupBuilder,
                evaluationViewService, CLOCK, (days, resolver, triaged) -> days, locationPointProvider, guard,
                new NeighbourGuardLog(line -> { }), bigSeasBuilder, overlay);
    }

    /** Stubs the roster the coverage matcher reads; only for tests where a warning survives the timeline. */
    private void rosterWithHawes() {
        RegionEntity dales = RegionEntity.builder().id(1L).name("Dales").build();
        LocationEntity hawes = new LocationEntity();
        hawes.setId(1L);
        hawes.setName("Hawes");
        hawes.setRegion(dales);
        hawes.setLat(54.3);
        hawes.setLon(-2.2);
        when(locationRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(hawes));
    }

    private static WeatherWarningVersionEntity version(String id, int v, String status, boolean simulated) {
        WeatherWarningVersionEntity w = new WeatherWarningVersionEntity();
        w.setWarningId(id);
        w.setWarningVersion(java.math.BigDecimal.valueOf(v));
        w.setStatus(status);
        w.setWarningLevel("Amber");
        w.setGeometry(BOX);
        w.setSimulated(simulated);
        w.setReceivedAt(Instant.parse("2026-10-08T06:00:00Z").plusSeconds(v));
        w.setValidFrom(Instant.parse("2026-10-10T00:00:00Z"));
        w.setValidTo(Instant.parse("2026-10-12T00:00:00Z"));
        return w;
    }

    private void worldWith(WeatherWarningVersionEntity... versions) {
        when(warningRepository.findAllVersionsOfWarningsValidAfter(NOW)).thenReturn(List.of(versions));
    }

    /** A summary stamped with its own solar event time, as the hierarchy builder always does. */
    private static BriefingEventSummary timed(TargetType type, BriefingRegion region) {
        return new BriefingEventSummary(type, List.of(region), List.<BriefingSlot>of(),
                LocalDateTime.of(2026, 10, 11, 6, 0), null);
    }

    /** Sunrise is a zero-scored (stood-down, blanked) window; sunset is rated. */
    private static DailyBriefingResponse snapshot() {
        BriefingSlot hawes = inland(1L, "Hawes");
        BriefingSlot rated = hawes.withClaudeScores(4, 4, 70, 60, "why", "h");
        return new DailyBriefingResponse(LocalDateTime.of(2026, 10, 8, 6, 0), "headline",
                List.of(day(D,
                        timed(TargetType.SUNRISE, region("Dales", 0, hawes)),
                        timed(TargetType.SUNSET, region("Dales", 1, rated)))),
                List.of(), null, null, false, null, List.of(), List.of());
    }

    /** The response with every warning field removed: what the overlay-off path would have served. */
    private static DailyBriefingResponse withoutWarnings(DailyBriefingResponse response) {
        List<BriefingDay> days = new ArrayList<>();
        for (BriefingDay day : response.days()) {
            List<BriefingEventSummary> summaries = new ArrayList<>();
            for (BriefingEventSummary es : day.eventSummaries()) {
                summaries.add(es.window() == null ? es : es.withWindow(es.window().withWarnings(null, null)));
            }
            days.add(day.withEventSummaries(summaries));
        }
        return response.withDays(days);
    }

    private static List<BriefingWindow> windows(DailyBriefingResponse response) {
        List<BriefingWindow> out = new ArrayList<>();
        for (BriefingDay day : response.days()) {
            for (BriefingEventSummary es : day.eventSummaries()) {
                out.add(es.window());
            }
        }
        return out;
    }

    @Test
    @DisplayName("with an active warning, only the warning fields differ: ratings, verdicts, picks, counts, "
            + "version and every other field are identical, stood-down and verdict-blanked windows included")
    void onlyTheWarningFieldsDiffer() {
        worldWith(version("W-ACT", 1, WeatherWarningVersionEntity.STATUS_ACTIVE, false));
        rosterWithHawes();

        DailyBriefingResponse base = plain.assembleForPlan(snapshot(), 0.5);
        DailyBriefingResponse served = warned.assembleForPlan(snapshot(), 0.5);

        assertThat(windows(served)).hasSize(2).allSatisfy(w -> {
            assertThat(w.warningIds()).containsExactly("W-ACT");
            assertThat(w.warningPlaceCounts()).hasSize(1);
        });
        assertThat(windows(base)).allSatisfy(w -> assertThat(w.warningIds()).isNull());
        assertThat(windows(served).get(0).verdict()).as("the blanked window is still blanked")
                .isEqualTo(windows(base).get(0).verdict());
        assertThat(withoutWarnings(served)).isEqualTo(base);
        assertThat(served.briefingVersion()).isEqualTo(base.briefingVersion());
    }

    @Test
    @DisplayName("a cancelled warning and a simulated warning change nothing at all")
    void cancelledAndSimulatedChangeNothing() {
        worldWith(version("W-CAN", 1, WeatherWarningVersionEntity.STATUS_ACTIVE, false),
                version("W-CAN", 2, WeatherWarningVersionEntity.STATUS_CANCELLED, false),
                version("SIM-1", 1, WeatherWarningVersionEntity.STATUS_ACTIVE, true));

        DailyBriefingResponse base = plain.assembleForPlan(snapshot(), 0.5);
        DailyBriefingResponse served = warned.assembleForPlan(snapshot(), 0.5);

        assertThat(served).isEqualTo(base);
        assertThat(windows(served)).allSatisfy(w -> assertThat(w.warningIds()).isNull());
    }

    @Test
    @DisplayName("the stored snapshot is never given warning fields: the overlay is serve-time only")
    void snapshotNeverCarriesWarnings() {
        worldWith(version("W-ACT", 1, WeatherWarningVersionEntity.STATUS_ACTIVE, false));
        rosterWithHawes();
        DailyBriefingResponse cached = snapshot();

        warned.assembleForPlan(cached, 0.5);

        assertThat(cached).isEqualTo(snapshot());
        assertThat(windows(cached)).allSatisfy(w -> assertThat(w == null || w.warningIds() == null).isTrue());
    }

    @Test
    @DisplayName("Lite, Pro and Admin read identical warning ids and counts, kept on gated slots")
    void roleShapersPassWarningsThrough() {
        worldWith(version("W-ACT", 1, WeatherWarningVersionEntity.STATUS_ACTIVE, false));
        rosterWithHawes();
        DailyBriefingResponse served = warned.assembleForPlan(snapshot(), 0.5);
        LitePickShaper picks = new LitePickShaper(mock(UserSubRegionService.class), new SubRegionProperties());
        // Nobody is picked, so every slot is gated; a warning is not a rating and must survive.
        PickScope nothingPicked = new PickScope(true, Set.of(), Set.of());

        DailyBriefingResponse lite = picks.shapeBriefing(new BriefingRoleShaper().forLite(served), nothingPicked);
        DailyBriefingResponse pro = served;
        DailyBriefingResponse admin = served;

        List<BriefingWindow> liteWindows = windows(lite);
        assertThat(liteWindows).hasSize(2);
        for (int i = 0; i < liteWindows.size(); i++) {
            assertThat(liteWindows.get(i).warningIds()).isEqualTo(windows(pro).get(i).warningIds())
                    .isEqualTo(windows(admin).get(i).warningIds()).containsExactly("W-ACT");
            assertThat(liteWindows.get(i).warningPlaceCounts()).isEqualTo(windows(pro).get(i).warningPlaceCounts());
        }
        assertThat(lite.days().get(0).eventSummaries().get(1).regions().get(0).slots())
                .allSatisfy(s -> assertThat(s.gated()).isTrue());
    }

    @Test
    @DisplayName("an event before the warning's validFrom is not covered")
    void eventBeforeValidFromIsNotCovered() {
        WeatherWarningVersionEntity w = version("W-LATE", 1, WeatherWarningVersionEntity.STATUS_ACTIVE, false);
        w.setValidFrom(Instant.parse("2026-10-11T09:00:00Z"));
        worldWith(w);
        rosterWithHawes();

        DailyBriefingResponse served = warned.assembleForPlan(snapshot(), 0.5);

        assertThat(windows(served)).allSatisfy(win -> assertThat(win.warningIds()).isNull());
    }
}
