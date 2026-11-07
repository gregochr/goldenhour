package com.gregochr.goldenhour.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gregochr.goldenhour.entity.DailyBriefingCacheEntity;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideExtremeEntity;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.BestBetStatus;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.repository.DailyBriefingCacheRepository;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.RegionRepository;
import com.gregochr.goldenhour.repository.TideExtremeRepository;
import com.gregochr.goldenhour.util.TimeSlotUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Status;
import org.springframework.core.env.StandardEnvironment;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link E2eLocalFixtureSeeder}: the write order, the self-check, the refusals and the failure path. The
 * seeder lives under {@code src/local} (outside CI's backend job), so the E2E job runs this class with
 * {@code -Plocal-dev}.
 */
class E2eLocalFixtureSeederTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2031-07-14T12:00:00Z"), ZoneOffset.UTC);
    // Deliberately far from the wall clock (and in BST), so a read of the system clock is caught.
    private static final LocalDate TODAY = LocalDate.of(2031, 7, 14);
    private static final Set<TargetType> BOTH_EVENTS = EnumSet.of(TargetType.SUNRISE, TargetType.SUNSET);
    private static final String SLOT_KEY = "Lake District|2031-07-14|SUNRISE";
    private static final BriefingEvaluationResult SEEDED_SCORE = new BriefingEvaluationResult("Blencathra", 4, 72,
            78, "s", null, null, "h");

    private RegionRepository regionRepository;
    private LocationRepository locationRepository;
    private TideExtremeRepository tideRepository;
    private ForecastEvaluationRepository forecastRepository;
    private DailyBriefingCacheRepository cacheRepository;
    private BriefingEvaluationService evaluationService;
    private BriefingService briefingService;
    private BriefingSlotBuilder slotBuilder;
    private BriefingHierarchyBuilder hierarchyBuilder;
    private BriefingRegionEvaluationRollup rollup;
    private BriefingHeadlineGenerator headlineGenerator;
    private EvaluationViewService viewService;
    private AlmanacService almanac;
    private SolarEventFreshness freshness;
    private E2eFixtureHealthIndicator health;
    private ListAppender<ILoggingEvent> logs;
    private final AtomicLong ids = new AtomicLong(100);

    @BeforeEach
    void setUp() {
        regionRepository = mock(RegionRepository.class);
        locationRepository = mock(LocationRepository.class);
        tideRepository = mock(TideExtremeRepository.class);
        forecastRepository = mock(ForecastEvaluationRepository.class);
        cacheRepository = mock(DailyBriefingCacheRepository.class);
        evaluationService = mock(BriefingEvaluationService.class);
        briefingService = mock(BriefingService.class);
        slotBuilder = mock(BriefingSlotBuilder.class);
        hierarchyBuilder = mock(BriefingHierarchyBuilder.class);
        rollup = mock(BriefingRegionEvaluationRollup.class);
        headlineGenerator = mock(BriefingHeadlineGenerator.class);
        viewService = mock(EvaluationViewService.class);
        almanac = mock(AlmanacService.class);
        freshness = mock(SolarEventFreshness.class);
        health = new E2eFixtureHealthIndicator();

        when(regionRepository.save(any(RegionEntity.class))).thenAnswer(inv -> {
            RegionEntity r = inv.getArgument(0);
            r.setId(ids.incrementAndGet());
            return r;
        });
        when(locationRepository.save(any(LocationEntity.class))).thenAnswer(inv -> {
            LocationEntity l = inv.getArgument(0);
            l.setId(ids.incrementAndGet());
            return l;
        });
        // Sunrise at 07:00 UTC and sunset at 16:00 UTC, whatever the place.
        when(freshness.eventTime(any(), any(), any())).thenAnswer(inv -> {
            LocalDate date = inv.getArgument(1);
            return inv.getArgument(2) == TargetType.SUNRISE ? date.atTime(7, 0) : date.atTime(16, 0);
        });
        when(forecastRepository.count()).thenReturn(288L);
        when(cacheRepository.findById(1)).thenReturn(java.util.Optional.of(new DailyBriefingCacheEntity()));
        when(evaluationService.getCachedScores(anyString(), any(), any())).thenReturn(Map.of("x", mock(
                BriefingEvaluationResult.class)));
        when(briefingService.getCachedBriefingForApi()).thenReturn(servedWithPick(BriefingWindow.PickKind.BEST));
        when(headlineGenerator.generateHeadline(anyList())).thenReturn("headline");
        when(hierarchyBuilder.buildDays(anyList(), anyList(), anyList())).thenAnswer(inv -> {
            List<LocalDate> dates = inv.getArgument(2);
            return dates.stream().map(d -> new BriefingDay(d, List.of(
                    new BriefingEventSummary(TargetType.SUNRISE, List.of(), List.of(), null, null),
                    new BriefingEventSummary(TargetType.SUNSET, List.of(), List.of(), null, null)))).toList();
        });
        when(rollup.enrich(anyList(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        // Exact arguments: today..T+4 and both solar events. A wrong horizon or a dropped SUNSET gets Mockito's
        // default (an empty map), which the resolver test below then catches.
        when(viewService.getScoresForEnrichmentBulk(TODAY, TODAY.plusDays(4), BOTH_EVENTS))
                .thenReturn(Map.of(SLOT_KEY, Map.of("Blencathra", SEEDED_SCORE)));
        when(viewService.getTriagedByBatchLocationNamesBulk(TODAY, TODAY.plusDays(4), BOTH_EVENTS))
                .thenReturn(Map.of(SLOT_KEY, Set.of("Wast Water")));

        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(E2eLocalFixtureSeeder.class)).addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(E2eLocalFixtureSeeder.class)).detachAppender(logs);
    }

    private static DailyBriefingResponse servedWithPick(BriefingWindow.PickKind kind) {
        BriefingWindow window = new BriefingWindow(null, DisplayVerdict.WORTH_IT, 5, null,
                new BriefingWindow.Pick(kind, "Northumberland Coast", "h", null, 4.0, null, null),
                List.of(), null, null);
        BriefingEventSummary summary = new BriefingEventSummary(TargetType.SUNSET, List.of(), List.of(), null,
                window);
        return new DailyBriefingResponse(LocalDateTime.now(CLOCK), "h", List.of(new BriefingDay(TODAY,
                List.of(summary))), List.of(), null, null, false, "m", List.of(), List.of(),
                BestBetStatus.SUCCESS_NO_PICKS);
    }

    private E2eLocalFixtureSeeder seeder(DataSource ds, String... profiles) {
        StandardEnvironment env = new StandardEnvironment();
        env.setActiveProfiles(profiles);
        return new E2eLocalFixtureSeeder(regionRepository, locationRepository, tideRepository, forecastRepository,
                cacheRepository, evaluationService, briefingService, slotBuilder, hierarchyBuilder, rollup,
                headlineGenerator, viewService, almanac, freshness, ds, env, CLOCK,
                new ObjectMapper().registerModule(new JavaTimeModule()), health);
    }

    private static DataSource databaseCalled(String product) throws SQLException {
        DataSource ds = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData meta = mock(DatabaseMetaData.class);
        when(ds.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(meta);
        when(meta.getDatabaseProductName()).thenReturn(product);
        return ds;
    }

    // -- the fixture's shape -----------------------------------------------------------------

    @Test
    @DisplayName("three regions of six places, sky types only, no comma-space in a name, 10 km apart")
    void fixtureShape() {
        assertThat(E2eLocalFixtureSeeder.REGIONS).hasSize(3);
        for (String region : E2eLocalFixtureSeeder.REGIONS) {
            assertThat(E2eLocalFixtureSeeder.SPOTS.stream().filter(s -> s.region().equals(region))).hasSize(6);
        }
        assertThat(E2eLocalFixtureSeeder.SPOTS).allSatisfy(s -> {
            assertThat(s.type()).isIn(LocationType.LANDSCAPE, LocationType.SEASCAPE);
            assertThat(s.name()).doesNotContain(", ");
        });
        List<E2eLocalFixtureSeeder.Spot> spots = E2eLocalFixtureSeeder.SPOTS;
        for (int i = 0; i < spots.size(); i++) {
            for (int j = i + 1; j < spots.size(); j++) {
                assertThat(kilometres(spots.get(i), spots.get(j)))
                        .as("%s to %s", spots.get(i).name(), spots.get(j).name()).isGreaterThanOrEqualTo(10.0);
            }
        }
    }

    private static double kilometres(E2eLocalFixtureSeeder.Spot a, E2eLocalFixtureSeeder.Spot b) {
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLon = Math.toRadians(b.lon() - a.lon());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(a.lat()))
                * Math.cos(Math.toRadians(b.lat())) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * 6371 * Math.asin(Math.sqrt(h));
    }

    @Test
    @DisplayName("every place changes star between adjacent windows, and each window has a 4, a 3 and a 2 region")
    void ratingFormula() {
        for (int day = 0; day < 12; day++) {
            LocalDate date = TODAY.plusDays(day);
            for (TargetType type : List.of(TargetType.SUNRISE, TargetType.SUNSET)) {
                TargetType nextType = type == TargetType.SUNRISE ? TargetType.SUNSET : TargetType.SUNRISE;
                LocalDate nextDate = type == TargetType.SUNSET ? date.plusDays(1) : date;
                List<Double> means = new ArrayList<>();
                for (int region = 0; region < 3; region++) {
                    double sum = 0;
                    for (int place = 0; place < 6; place++) {
                        int star = E2eLocalFixtureSeeder.rating(region, place, date, type);
                        assertThat(star).isBetween(1, 5);
                        assertThat(E2eLocalFixtureSeeder.rating(region, place, nextDate, nextType))
                                .as("region %d place %d %s %s", region, place, date, type).isNotEqualTo(star);
                        sum += star;
                    }
                    means.add(sum / 6);
                }
                assertThat(means).containsExactlyInAnyOrder(4.0, 3.0, 2.0);
            }
        }
    }

    // -- the refusals ------------------------------------------------------------------------

    @Test
    @DisplayName("on H2 under the local profile the check passes")
    void localH2Passes() throws SQLException {
        assertThatCode(() -> seeder(databaseCalled("H2"), "local").requireLocalH2()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("it refuses without the local profile, and with prod beside it")
    void refusesWithoutLocalOrWithProd() throws SQLException {
        assertThatThrownBy(() -> seeder(databaseCalled("H2")).requireLocalH2())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("local profile");
        assertThatThrownBy(() -> seeder(databaseCalled("H2"), "local", "prod").requireLocalH2())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("it refuses a database that is not H2, naming it, and one it cannot ask")
    void refusesAnythingButH2() throws SQLException {
        assertThatThrownBy(() -> seeder(databaseCalled("PostgreSQL"), "local").requireLocalH2())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("PostgreSQL");
        DataSource broken = mock(DataSource.class);
        when(broken.getConnection()).thenThrow(new SQLException("down"));
        assertThatThrownBy(() -> seeder(broken, "local").requireLocalH2())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("could not confirm");
    }

    @Test
    @DisplayName("it refuses a database that already holds locations, and writes nothing")
    void refusesNonEmptyLocations() throws SQLException {
        when(locationRepository.count()).thenReturn(1L);
        E2eLocalFixtureSeeder seeder = seeder(databaseCalled("H2"), "local");
        assertThatThrownBy(seeder::onApplicationReady)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already holds locations");
        verifyNoInteractions(regionRepository, forecastRepository, evaluationService, cacheRepository);
        assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("a refusal at the ready event stops startup and never goes UP")
    void refusalIsLoud() throws SQLException {
        assertThatThrownBy(() -> seeder(databaseCalled("PostgreSQL"), "local").onApplicationReady())
                .isInstanceOf(IllegalStateException.class);
        assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
        verifyNoInteractions(regionRepository);
    }

    // -- the write order and the self-check --------------------------------------------------

    @Test
    @DisplayName("writes in the load-bearing order, then reloads, refreshes the almanac, self-checks and goes UP")
    void writeOrder() throws SQLException {
        E2eLocalFixtureSeeder seeder = seeder(databaseCalled("H2"), "local");
        assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);

        seeder.onApplicationReady();

        InOrder order = inOrder(regionRepository, locationRepository, tideRepository, forecastRepository,
                evaluationService, slotBuilder, hierarchyBuilder, viewService, rollup, headlineGenerator,
                cacheRepository, briefingService, almanac);
        order.verify(regionRepository, atLeastOnce()).save(any(RegionEntity.class));
        order.verify(locationRepository, atLeastOnce()).save(any(LocationEntity.class));
        order.verify(tideRepository, atLeastOnce()).saveAll(anyCollection());
        order.verify(forecastRepository).saveAll(anyCollection());
        order.verify(evaluationService, atLeastOnce()).mergeFromBatch(anyString(), anyList());
        order.verify(slotBuilder).snapshotFor(anyCollection(), anyList());
        order.verify(hierarchyBuilder).buildDays(anyList(), anyList(), anyList());
        order.verify(viewService).getScoresForEnrichmentBulk(TODAY, TODAY.plusDays(4), BOTH_EVENTS);
        order.verify(viewService).getTriagedByBatchLocationNamesBulk(TODAY, TODAY.plusDays(4), BOTH_EVENTS);
        order.verify(rollup).enrich(anyList(), any(), any());
        order.verify(headlineGenerator).generateHeadline(anyList());
        order.verify(cacheRepository).save(any(DailyBriefingCacheEntity.class));
        order.verify(briefingService).loadPersistedBriefing();
        order.verify(almanac).refresh();
        order.verify(briefingService).getCachedBriefingForApi();

        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
        assertThat(logs.list).anySatisfy(e -> assertThat(e.getFormattedMessage())
                .startsWith(E2eLocalFixtureSeeder.SEEDED_MARKER));
    }

    @Test
    @DisplayName("the resolvers handed to enrich return the seeded scores and triage for a known slot, only there")
    void resolversCarryTheSeededValues() throws SQLException {
        seeder(databaseCalled("H2"), "local").onApplicationReady();

        ArgumentCaptor<RegionScoreResolver> scores = ArgumentCaptor.forClass(RegionScoreResolver.class);
        ArgumentCaptor<TriagedByBatchResolver> triaged = ArgumentCaptor.forClass(TriagedByBatchResolver.class);
        verify(rollup).enrich(anyList(), scores.capture(), triaged.capture());

        assertThat(scores.getValue().resolve("Lake District", TODAY, TargetType.SUNRISE))
                .containsExactly(Map.entry("Blencathra", SEEDED_SCORE));
        assertThat(triaged.getValue().resolve("Lake District", TODAY, TargetType.SUNRISE))
                .containsExactly("Wast Water");
        assertThat(scores.getValue().resolve("Lake District", TODAY, TargetType.SUNSET)).isEmpty();
        assertThat(triaged.getValue().resolve("Yorkshire Dales", TODAY, TargetType.SUNRISE)).isEmpty();
    }

    @Test
    @DisplayName("it never builds a briefing the real way: no refresh, no weather fetch")
    void neverRefreshesTheBriefing() throws SQLException {
        seeder(databaseCalled("H2"), "local").onApplicationReady();
        verify(briefingService, never()).refreshBriefing();
        verify(briefingService, never()).refreshBriefingIfIdle();
    }

    @Test
    @DisplayName("forecast rows run T-3..T+4 (T-4 empty), are scored before the event, and never PENDING")
    void forecastRows() throws SQLException {
        seeder(databaseCalled("H2"), "local").onApplicationReady();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<ForecastEvaluationEntity>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(forecastRepository).saveAll(captor.capture());
        Collection<ForecastEvaluationEntity> rows = captor.getValue();

        assertThat(rows).hasSize(18 * 8 * 2);
        assertThat(rows).extracting(ForecastEvaluationEntity::getTargetDate)
                .doesNotContain(TODAY.minusDays(4)).contains(TODAY.minusDays(3), TODAY.plusDays(4));
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getBatchState()).isNull();
            assertThat(row.getRating()).isBetween(1, 5);
            assertThat(row.getForecastRunAt()).isEqualTo(LocalDateTime.ofInstant(row.getScoredAt(),
                    ZoneOffset.UTC));
            Instant event = row.getSolarEventTime().toInstant(ZoneOffset.UTC);
            assertThat(row.getScoredAt()).isBefore(event);
            assertThat(row.getScoredAt()).isBeforeOrEqualTo(CLOCK.instant().minusSeconds(3600));
            assertThat(row.getDaysAhead()).isEqualTo((int) (row.getTargetDate().toEpochDay() - TODAY.toEpochDay()));
        });
        // The one rising-tide place: Bamburgh Beach's next high water is inside the sheet's 90-minute test.
        assertThat(rows.stream().filter(r -> r.getLocationName().equals("Bamburgh Beach")))
                .allSatisfy(r -> assertThat(java.time.Duration.between(r.getSolarEventTime(),
                        r.getTide().getNextHighTime()).toMinutes()).isLessThan(90));
    }

    @Test
    @DisplayName("cached entries cover today..T+4 with the same star as the forecast rows")
    void cachedEntriesMatchForecastRows() throws SQLException {
        seeder(databaseCalled("H2"), "local").onApplicationReady();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BriefingEvaluationResult>> results = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(evaluationService, org.mockito.Mockito.times(3 * 5 * 2)).mergeFromBatch(keys.capture(),
                results.capture());
        assertThat(keys.getAllValues())
                .contains("Lake District|2031-07-14|SUNRISE", "Yorkshire Dales|2031-07-18|SUNSET")
                .doesNotContain("Lake District|2031-07-13|SUNRISE");
        for (int i = 0; i < keys.getAllValues().size(); i++) {
            String[] parts = keys.getAllValues().get(i).split("\\|");
            int region = E2eLocalFixtureSeeder.REGIONS.indexOf(parts[0]);
            List<BriefingEvaluationResult> batch = results.getAllValues().get(i);
            assertThat(batch).hasSize(6);
            for (int place = 0; place < 6; place++) {
                BriefingEvaluationResult r = batch.get(place);
                assertThat(r.rating()).isEqualTo(E2eLocalFixtureSeeder.rating(region, place,
                        LocalDate.parse(parts[1]), TargetType.valueOf(parts[2])));
                assertThat(r.triageReason()).isNull();
            }
        }
    }

    @Test
    @DisplayName("tide extremes alternate high and low for the three coastal places only")
    void tides() throws SQLException {
        seeder(databaseCalled("H2"), "local").onApplicationReady();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<TideExtremeEntity>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(tideRepository, org.mockito.Mockito.times(3)).saveAll(captor.capture());
        for (Collection<TideExtremeEntity> series : captor.getAllValues()) {
            List<TideExtremeEntity> rows = new ArrayList<>(series);
            for (int i = 1; i < rows.size(); i++) {
                assertThat(rows.get(i).getType()).isNotEqualTo(rows.get(i - 1).getType());
            }
        }
    }

    @Test
    @DisplayName("the persisted payload is the Jackson 2 JSON of the briefing")
    void persistsJson() throws SQLException {
        seeder(databaseCalled("H2"), "local").onApplicationReady();
        ArgumentCaptor<DailyBriefingCacheEntity> captor = ArgumentCaptor.forClass(DailyBriefingCacheEntity.class);
        verify(cacheRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(1);
        assertThat(captor.getValue().getPayload()).contains("\"headline\":\"headline\"").contains("\"days\"");
    }

    @Test
    @DisplayName("self-check: a briefing with only an ALSO pick (no BEST) fails, logs ERROR and stays DOWN")
    void selfCheckNeedsBestPick() throws SQLException {
        when(briefingService.getCachedBriefingForApi()).thenReturn(servedWithPick(BriefingWindow.PickKind.ALSO));
        assertFailsLoudly("no window in the served briefing carries a BEST pick");
    }

    @Test
    @DisplayName("self-check: an empty served briefing fails, logs ERROR and stays DOWN")
    void selfCheckNeedsABriefing() throws SQLException {
        when(briefingService.getCachedBriefingForApi()).thenReturn(null);
        assertFailsLoudly("the served briefing is empty");
    }

    @Test
    @DisplayName("self-check: forecast rows that do not read back fail it")
    void selfCheckCountsRows() throws SQLException {
        when(forecastRepository.count()).thenReturn(7L);
        assertFailsLoudly("forecast_evaluation rows read back");
    }

    @Test
    @DisplayName("self-check: a missing briefing cache row fails it")
    void selfCheckNeedsTheCacheRow() throws SQLException {
        when(cacheRepository.findById(1)).thenReturn(java.util.Optional.empty());
        assertFailsLoudly("daily_briefing_cache has no row");
    }

    @Test
    @DisplayName("self-check: a region with no cached evaluation fails it")
    void selfCheckNeedsCachedScores() throws SQLException {
        when(evaluationService.getCachedScores(anyString(), any(), any())).thenReturn(Map.of());
        assertFailsLoudly("no cached evaluation today");
    }

    @Test
    @DisplayName("failure path: a write that throws is logged at ERROR, never swallowed into UP")
    void writeFailureStaysDown() throws SQLException {
        when(forecastRepository.saveAll(anyCollection())).thenThrow(new IllegalArgumentException("boom"));
        assertFailsLoudly("boom");
        verify(briefingService, never()).loadPersistedBriefing();
    }

    private void assertFailsLoudly(String reason) throws SQLException {
        E2eLocalFixtureSeeder seeder = seeder(databaseCalled("H2"), "local");
        assertThatCode(seeder::onApplicationReady).doesNotThrowAnyException();
        assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat((String) health.health().getDetails().get("reason")).contains(reason);
        assertThat(logs.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.ERROR);
            assertThat(e.getFormattedMessage()).contains("Seeding FAILED");
        });
        assertThat(logs.list).noneSatisfy(e -> assertThat(e.getFormattedMessage())
                .startsWith(E2eLocalFixtureSeeder.SEEDED_MARKER));
    }

    // -- the indicator and the weather -------------------------------------------------------

    @Test
    @DisplayName("the indicator is DOWN with a reason until marked ready, and DOWN again once failed")
    void indicatorLifecycle() {
        E2eFixtureHealthIndicator indicator = new E2eFixtureHealthIndicator();
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(indicator.health().getDetails()).containsKey("reason");
        indicator.markReady();
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
        indicator.markFailed("nope");
        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(indicator.health().getDetails()).containsEntry("reason", "nope");
    }

    @Test
    @DisplayName("the synthetic weather covers the whole window with equal-length series the slot builder can index")
    void syntheticWeather() {
        LocationEntity place = LocationEntity.builder().name("x").lat(55.0).lon(-2.0).build();
        OpenMeteoForecastResponse forecast = E2eLocalFixtureSeeder.syntheticWeather(place, TODAY);
        OpenMeteoForecastResponse.Hourly h = forecast.getHourly();
        int n = h.getTime().size();
        for (List<?> series : List.of(h.getCloudCoverLow(), h.getCloudCoverMid(), h.getCloudCoverHigh(),
                h.getVisibility(), h.getWindSpeed10m(), h.getPrecipitation(), h.getWeatherCode(),
                h.getRelativeHumidity2m(), h.getTemperature2m(), h.getApparentTemperature())) {
            assertThat(series).hasSize(n);
        }
        assertThat(h.getTime().getFirst()).isEqualTo("2031-07-12T00:00");
        assertThat(new HashSet<>(h.getTime())).hasSize(n);
        // A T+4 sunset (and a T-1 sunrise) both land inside the series.
        int sunset = TimeSlotUtils.findBestIndex(h.getTime(), TODAY.plusDays(4).atTime(16, 10), TargetType.SUNSET);
        assertThat(h.getTime().get(sunset)).isEqualTo("2031-07-18T16:00");
    }
}
