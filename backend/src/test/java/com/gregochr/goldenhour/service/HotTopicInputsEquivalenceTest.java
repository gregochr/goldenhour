package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.NoaaSwpcClient;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.SlotAtmosphereEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AuroraTonightSummary;
import com.gregochr.goldenhour.model.CurrentNight;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.LunarEclipseSight;
import com.gregochr.goldenhour.model.MeteorClarity;
import com.gregochr.goldenhour.model.SeasonalWindow;
import com.gregochr.goldenhour.repository.ForecastScoreRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.SlotAtmosphereRepository;
import com.gregochr.goldenhour.service.aurora.AuroraForecastRunService;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import com.gregochr.goldenhour.util.LunarEclipseCalculator;
import com.gregochr.solarutils.LunarCalculator;
import com.gregochr.solarutils.MoonriseMoonsetCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.MonthDay;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves the per-serve {@link HotTopicInputs} changes how many queries a hot-topic pass makes and
 * nothing else. The real strategies, the real {@link HotTopicEventEnricher}, the real
 * {@link HotTopicAggregator} and the real {@link SlotSignalReader} run twice over one fixture:
 * the OLD way (each strategy's two-argument {@code detect} and the enricher's one-argument
 * {@code enrich} each load what they need, as before the change) and the NEW way (one
 * {@code HotTopicAggregator.getHotTopics} call sharing one {@code HotTopicInputs}).
 *
 * <p>Same shape as {@code BriefingTideSnapshotEquivalenceTest}: whole-list equality on the output,
 * then the statement count read from the repository mocks. The count is of repository calls, one
 * per prepared statement: {@code SlotSignalReader.read} is four of them, a roster is one.
 *
 * <p>Limits: the two tide strategies get a null briefing cache (their only exit before they would
 * read the coastal roster), so their equivalence is not exercised here; {@code
 * KingTideHotTopicStrategyTest} and {@code SpringTideHotTopicStrategyTest} cover them through the
 * same three-argument method. The roster ORDER is pinned by the Eclipse and Equinox topics, which
 * rank the enabled roster.
 */
class HotTopicInputsEquivalenceTest {

    /** Holds the solar eclipse (12 Aug), the Perseids (12 Aug) and the lunar eclipse (28 Aug). */
    private static final LocalDate AUGUST_FROM = LocalDate.of(2026, 8, 12);
    private static final LocalDate AUGUST_TO = LocalDate.of(2026, 8, 29);
    /** Holds the September equinox. */
    private static final LocalDate EQUINOX_FROM = LocalDate.of(2026, 9, 20);
    private static final LocalDate EQUINOX_TO = LocalDate.of(2026, 9, 24);

    private static final String READ_ROSTER_ENABLED = "findAllByEnabledTrueOrderByNameAsc";
    private static final String READ_ROSTER_COASTAL = "findCoastalLocations";
    private static final String READ_ROSTER_DARK_SKY = "findByBortleClassIsNotNullAndEnabledTrue";
    private static final String READ_ROSTER_BLUEBELL = "findBluebellLocations";

    /** One fixture world: the repositories and the strategies built over them. */
    private static final class World {
        final ForecastScoreRepository scores = mock(ForecastScoreRepository.class);
        final SlotAtmosphereRepository atmosphere = mock(SlotAtmosphereRepository.class);
        final LocationRepository locations = mock(LocationRepository.class);
        final TravelDayService travelDays = mock(TravelDayService.class);
        final SlotSignalReader reader = new SlotSignalReader(scores, atmosphere);
        final HotTopicSimulationService simulation = new HotTopicSimulationService();
        final HotTopicEventEnricher enricher;
        final HotTopicAggregator aggregator;
        final List<HotTopicStrategy> strategies;

        World() {
            LocationEntity coast = location("Bamburgh", 55.6, -1.7, "Northumberland", 1L);
            LocationEntity fell = location("Great Gable", 54.5, -3.2, "Lake District", 2L);
            LocationEntity wood = location("Hardcastle Crags", 53.7, -2.0, "Pennines", 3L);
            when(locations.findCoastalLocations()).thenReturn(List.of(coast));
            when(locations.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(coast, fell, wood));
            when(locations.findByBortleClassIsNotNullAndEnabledTrue()).thenReturn(List.of(fell));
            when(locations.findBluebellLocations()).thenReturn(List.of(wood));

            when(scores.findComponentsByType(Mockito.anyLong(), any(), any())).thenReturn(List.of());
            when(atmosphere.findInDateRange(any(), any())).thenAnswer(inv -> {
                LocalDate from = inv.getArgument(0);
                return List.of(
                        slot(fell, from, TargetType.SUNRISE, a -> {
                            a.setInversionScore(9.0);
                            a.setInversionScored(true);
                            a.setAerosolOpticalDepth(new BigDecimal("0.9"));
                            a.setDust(new BigDecimal("150"));
                            a.setSnowDepthMetres(0.2);
                            a.setFreezingLevelMetres(300.0);
                        }),
                        slot(coast, from, TargetType.SUNSET, a -> a.setSurgeRiskLevel("HIGH")));
            });

            Clock clock = Clock.fixed(Instant.parse("2026-08-10T05:00:00Z"), ZoneOffset.UTC);
            SolarService solar = new SolarService();
            SolarEventFreshness freshness = new SolarEventFreshness(solar, clock);

            StormSurgeFactsBuilder surgeFacts = mock(StormSurgeFactsBuilder.class);
            when(surgeFacts.attach(any(), any())).thenAnswer(inv -> inv.getArgument(0));

            AuroraStateCache auroraState = mock(AuroraStateCache.class);
            when(auroraState.getCurrentLevel()).thenReturn(AlertLevel.MODERATE);
            when(auroraState.getLastTriggerKp()).thenReturn(5.2);
            BriefingAuroraSummaryBuilder auroraSummary = mock(BriefingAuroraSummaryBuilder.class);
            when(auroraSummary.buildAuroraTonightCached()).thenReturn(new AuroraTonightSummary(
                    AlertLevel.MODERATE, 5.2, 8, 30, List.of(), null, null, null, null, null, null, null));
            AuroraForecastRunService auroraRun = mock(AuroraForecastRunService.class);
            when(auroraRun.currentNight()).thenReturn(new CurrentNight(AUGUST_FROM,
                    AUGUST_FROM.plusDays(1).atTime(4, 30).toInstant(ZoneOffset.UTC)));
            BriefingService briefing = mock(BriefingService.class);
            LunarPhaseService lunar = mock(LunarPhaseService.class);
            when(lunar.isFullMoon(any())).thenReturn(false);
            when(lunar.getIlluminationFraction(any())).thenReturn(0.05);
            LunarEclipseCalculator lunarCalculator = mock(LunarEclipseCalculator.class);
            when(lunarCalculator.sight(any(), Mockito.anyDouble(), Mockito.anyDouble()))
                    .thenReturn(new LunarEclipseSight(40, 200, "SSW", null, null, false, false,
                            LocalDateTime.of(2026, 8, 28, 3, 33), LocalDateTime.of(2026, 8, 28, 4, 40), true));
            MeteorClarityService meteorClarity = mock(MeteorClarityService.class);
            when(meteorClarity.getCached()).thenReturn(MeteorClarity.EMPTY);

            strategies = List.of(
                    new BluebellHotTopicStrategy(reader,
                            new SeasonalWindow(MonthDay.of(1, 1), MonthDay.of(12, 31), "test"), freshness),
                    new StormSurgeHotTopicStrategy(reader, surgeFacts),
                    new MistHotTopicStrategy(reader, freshness),
                    new DustHotTopicStrategy(reader, freshness, new DustFactsBuilder(solar)),
                    new InversionHotTopicStrategy(reader, freshness),
                    new SnowFreshHotTopicStrategy(reader, freshness),
                    new SnowTopsHotTopicStrategy(reader),
                    new SupermoonHotTopicStrategy(lunar, locations, mock(LunarCalculator.class),
                            mock(MoonriseMoonsetCalculator.class), solar),
                    new SpringTideHotTopicStrategy(briefing, locations, freshness,
                            mock(CoastalTideFactsBuilder.class), mock(TideRunBuilder.class), lunar),
                    new KingTideHotTopicStrategy(briefing, locations, freshness,
                            mock(CoastalTideFactsBuilder.class), mock(TideRunBuilder.class), lunar),
                    new EclipseHotTopicStrategy(locations, solar, freshness),
                    new LunarEclipseHotTopicStrategy(locations, solar, freshness,
                            lunarCalculator),
                    new EquinoxHotTopicStrategy(solar, locations, freshness),
                    new AuroraHotTopicStrategy(auroraState, mock(NoaaSwpcClient.class), locations,
                            auroraSummary, auroraRun),
                    new MeteorHotTopicStrategy(lunar, locations, meteorClarity));
            enricher = new HotTopicEventEnricher(solar, locations, freshness);
            aggregator = new HotTopicAggregator(strategies, simulation, travelDays, enricher,
                    new HotTopicGlanceDecorator(), reader, locations);
        }

        /** The aggregator as it was before the change: every strategy and the enricher load alone. */
        List<HotTopic> oldWayLive(LocalDate from, LocalDate to) {
            List<HotTopic> topics = new ArrayList<>();
            for (HotTopicStrategy s : strategies) {
                topics.addAll(s.detect(from, to));
            }
            List<HotTopic> sorted = topics.stream().sorted().toList();
            return new HotTopicGlanceDecorator().decorate(enricher.enrich(sorted).stream()
                    .filter(t -> oldActionable(t, from))
                    .toList());
        }

        List<HotTopic> oldWaySimulated(LocalDate from, LocalDate to) {
            return new HotTopicGlanceDecorator().decorate(
                    enricher.enrich(simulation.getSimulatedTopics(from, to)));
        }

        private boolean oldActionable(HotTopic topic, LocalDate from) {
            List<LocalDate> covered = topic.coveredDates();
            if (covered.isEmpty()) {
                return true;
            }
            List<LocalDate> ahead = covered.stream().filter(d -> !d.isBefore(from)).toList();
            return (ahead.isEmpty() ? covered : ahead).stream().anyMatch(d -> !travelDays.isTravelDay(d));
        }

        int calls(Object mock, String method) {
            return (int) Mockito.mockingDetails(mock).getInvocations().stream()
                    .filter(i -> i.getMethod().getName().equals(method))
                    .count();
        }

        /** Prepared statements issued by SlotSignalReader reads: three score queries and one readings. */
        int slotStatements() {
            return calls(scores, "findComponentsByType") + calls(atmosphere, "findInDateRange");
        }

        int rosterStatements() {
            return calls(locations, READ_ROSTER_ENABLED) + calls(locations, READ_ROSTER_COASTAL)
                    + calls(locations, READ_ROSTER_DARK_SKY) + calls(locations, READ_ROSTER_BLUEBELL);
        }
    }

    private static LocationEntity location(String name, double lat, double lon, String region, long id) {
        LocationEntity entity = new LocationEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setLat(lat);
        entity.setLon(lon);
        entity.setEnabled(true);
        RegionEntity regionEntity = new RegionEntity();
        regionEntity.setName(region);
        entity.setRegion(regionEntity);
        return entity;
    }

    private static SlotAtmosphereEntity slot(LocationEntity location, LocalDate date, TargetType event,
            java.util.function.Consumer<SlotAtmosphereEntity> fill) {
        SlotAtmosphereEntity a = new SlotAtmosphereEntity();
        a.setLocation(location);
        a.setEvaluationDate(date);
        a.setEventType(event);
        a.setEvaluatedAt(Instant.parse("2026-08-10T04:00:00Z"));
        fill.accept(a);
        return a;
    }

    @Test
    @DisplayName("august window — live branch output is identical, and slot signals are read once, not seven times")
    void augustWindow_liveBranch_identicalAndReadOnce() {
        World oldWorld = new World();
        List<HotTopic> before = oldWorld.oldWayLive(AUGUST_FROM, AUGUST_TO);
        World newWorld = new World();
        List<HotTopic> after = newWorld.aggregator.getHotTopics(AUGUST_FROM, AUGUST_TO);

        System.out.printf("hot-topic statements AUGUST: slot before=%d after=%d, roster before=%d after=%d%n",
                oldWorld.slotStatements(), newWorld.slotStatements(),
                oldWorld.rosterStatements(), newWorld.rosterStatements());

        assertThat(after).isNotEmpty().isEqualTo(before);
        assertThat(after).extracting(HotTopic::type)
                .contains("ECLIPSE", "AURORA", "INVERSION", "DUST", "STORM_SURGE");
        assertThat(oldWorld.slotStatements()).isGreaterThan(newWorld.slotStatements());
        assertThat(newWorld.slotStatements()).isEqualTo(4);
        assertThat(newWorld.calls(newWorld.locations, READ_ROSTER_ENABLED)).isEqualTo(1);
        assertThat(newWorld.calls(newWorld.locations, READ_ROSTER_COASTAL)).isLessThanOrEqualTo(1);
        assertThat(newWorld.calls(newWorld.locations, READ_ROSTER_DARK_SKY)).isEqualTo(1);
        assertThat(newWorld.calls(newWorld.locations, READ_ROSTER_BLUEBELL)).isEqualTo(1);
        assertThat(newWorld.rosterStatements()).isLessThan(oldWorld.rosterStatements());
    }

    @Test
    @DisplayName("equinox window — live branch output is identical (the enabled roster's order is pinned)")
    void equinoxWindow_liveBranch_identical() {
        World oldWorld = new World();
        List<HotTopic> before = oldWorld.oldWayLive(EQUINOX_FROM, EQUINOX_TO);
        World newWorld = new World();
        List<HotTopic> after = newWorld.aggregator.getHotTopics(EQUINOX_FROM, EQUINOX_TO);

        System.out.printf("hot-topic statements EQUINOX: slot before=%d after=%d, roster before=%d after=%d%n",
                oldWorld.slotStatements(), newWorld.slotStatements(),
                oldWorld.rosterStatements(), newWorld.rosterStatements());

        assertThat(after).isNotEmpty().isEqualTo(before);
        assertThat(after).extracting(HotTopic::type).contains("EQUINOX");
        assertThat(newWorld.slotStatements()).isEqualTo(4);
        assertThat(newWorld.rosterStatements()).isLessThanOrEqualTo(4);
    }

    @Test
    @DisplayName("simulation branch — output is identical and the enricher's four rosters are read once each")
    void simulationBranch_identicalAndRostersReadOnce() {
        World oldWorld = new World();
        oldWorld.simulation.setEnabled(true);
        oldWorld.simulation.setTypeActive("SUPERMOON", true);
        oldWorld.simulation.setTypeActive("INVERSION", true);
        List<HotTopic> before = oldWorld.oldWaySimulated(AUGUST_FROM, AUGUST_TO);

        World newWorld = new World();
        newWorld.simulation.setEnabled(true);
        newWorld.simulation.setTypeActive("SUPERMOON", true);
        newWorld.simulation.setTypeActive("INVERSION", true);
        List<HotTopic> after = newWorld.aggregator.getHotTopics(AUGUST_FROM, AUGUST_TO);

        assertThat(after).isNotEmpty().isEqualTo(before);
        assertThat(newWorld.slotStatements()).isZero();
        assertThat(newWorld.rosterStatements()).isEqualTo(4);
        assertThat(newWorld.calls(newWorld.locations, READ_ROSTER_ENABLED)).isEqualTo(1);
    }

    @Test
    @DisplayName("travel days — the filter asks the travel-day service once per distinct date")
    void travelFilter_asksOncePerDate() {
        World world = new World();
        world.aggregator.getHotTopics(AUGUST_FROM, AUGUST_TO);

        Map<Object, Long> perDate = new java.util.HashMap<>();
        Mockito.mockingDetails(world.travelDays).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("isTravelDay"))
                .forEach(i -> perDate.merge(i.getArgument(0), 1L, Long::sum));

        assertThat(perDate).isNotEmpty();
        assertThat(Set.copyOf(perDate.values())).containsExactly(1L);
    }
}
