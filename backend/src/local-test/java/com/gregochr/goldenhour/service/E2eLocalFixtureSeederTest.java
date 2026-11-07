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
import com.gregochr.goldenhour.model.BestBetStatus;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.Verdict;
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
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link E2eLocalFixtureSeeder}: the write order, the self-check, the refusals and the failure path.
 *
 * <p>The collaborators are faked, not merely mocked: each stub reproduces what its real counterpart does with the
 * arguments it is given (the slot builder makes a slot per (place, date, event), the hierarchy builder groups those
 * slots by the places' regions, the rollup marks the regions it scored), and the stubs themselves only answer for
 * the EXACT arguments the seeder should pass (the right places, dates and events). A wrong or dropped argument
 * therefore gets Mockito's default null, and the seeder fails. The "served briefing" is derived from what the seeder
 * actually persisted, and carries a BEST pick only if real slots made it through, so the success path cannot pass
 * on a fabricated response. Every verify uses exact arguments or a captor with assertions on what was passed. The
 * only typed {@code any(Entity.class)} matchers left are the stubs that simulate the database's id assignment for
 * an entity the test cannot construct in advance; what they were given is captured and asserted.
 *
 * <p>The clock is fixed in a distant BST summer, far from the wall clock, so a system-clock read is caught.
 */
class E2eLocalFixtureSeederTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2031-07-14T12:00:00Z"), ZoneOffset.UTC);
    // Deliberately far from the wall clock (and in BST), so a read of the system clock is caught.
    private static final LocalDate TODAY = LocalDate.of(2031, 7, 14);
    private static final List<LocalDate> WINDOW_DATES = IntStream.rangeClosed(0, 4).mapToObj(TODAY::plusDays).toList();
    private static final Set<TargetType> BOTH_EVENTS = EnumSet.of(TargetType.SUNRISE, TargetType.SUNSET);
    private static final String SLOT_KEY = "Lake District|2031-07-14|SUNRISE";
    private static final BriefingEvaluationResult SEEDED_SCORE = new BriefingEvaluationResult("Blencathra", 4, 72,
            78, "s", null, null, "h");
    private static final List<String> PLACE_NAMES = E2eLocalFixtureSeeder.SPOTS.stream()
            .map(E2eLocalFixtureSeeder.Spot::name).toList();
    private static final int PLACES = 18;
    private static final int SLOTS = PLACES * 5 * 2;
    private static final int FORECAST_ROWS = PLACES * 8 * 2;
    private static final BriefingSlot.WeatherConditions WEATHER = new BriefingSlot.WeatherConditions(20,
            BigDecimal.ZERO, 20_000, 60, 9.0, 7.0, 1, new BigDecimal("3.2"), 30, 40);
    private static final String ROLE_SUMMARY = "2 role users, 3 sub-regions, 2 lite picks";
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

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
    private TideExtremeSnapshot snapshot;
    private E2eFixtureHealthIndicator health;
    private E2eRoleUsers roleUsers;
    private ListAppender<ILoggingEvent> logs;
    private final AtomicLong ids = new AtomicLong(100);

    // What the fakes saw and made, so assertions can follow the data through the chain.
    private final List<RegionEntity> savedRegions = new ArrayList<>();
    private final List<LocationEntity> savedLocations = new ArrayList<>();
    private final List<BriefingSlot> builtSlots = new ArrayList<>();
    private List<BriefingDay> builtDays;
    private List<BriefingDay> enrichedDays;
    private DailyBriefingCacheEntity persisted;

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
        snapshot = mock(TideExtremeSnapshot.class);
        health = new E2eFixtureHealthIndicator();
        roleUsers = mock(E2eRoleUsers.class);

        // The database's id assignment (typed matchers: the entities are built by the seeder; they are captured
        // below and asserted field by field).
        when(regionRepository.save(any(RegionEntity.class))).thenAnswer(inv -> {
            RegionEntity r = inv.getArgument(0);
            r.setId(ids.incrementAndGet());
            savedRegions.add(r);
            return r;
        });
        when(locationRepository.save(any(LocationEntity.class))).thenAnswer(inv -> {
            LocationEntity l = inv.getArgument(0);
            l.setId(ids.incrementAndGet());
            savedLocations.add(l);
            return l;
        });
        // Sunrise at 07:00 UTC and sunset at 16:00 UTC, whatever the place.
        when(freshness.eventTime(any(LocationEntity.class), any(LocalDate.class), any(TargetType.class)))
                .thenAnswer(inv -> {
                    LocalDate date = inv.getArgument(1);
                    return inv.getArgument(2) == TargetType.SUNRISE ? date.atTime(7, 0) : date.atTime(16, 0);
                });

        // The role users and sub-regions are handed exactly the saved fixture places (ids and regions set).
        when(roleUsers.seed(argThat(E2eLocalFixtureSeederTest::isTheSeededPlaces))).thenReturn(ROLE_SUMMARY);

        // The briefing chain answers ONLY for the exact arguments the seeder should pass.
        when(slotBuilder.snapshotFor(argThat(E2eLocalFixtureSeederTest::isTheSeededPlaces), eq(WINDOW_DATES)))
                .thenReturn(snapshot);
        when(slotBuilder.buildSlot(argThat(lw -> lw != null && PLACE_NAMES.contains(lw.location().getName())
                        && lw.forecast() != null),
                argThat(WINDOW_DATES::contains), argThat(BOTH_EVENTS::contains), isNull(), same(snapshot)))
                .thenAnswer(inv -> slotFor(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
        when(hierarchyBuilder.buildDays(argThat(slots -> slots != null && slots.size() == SLOTS),
                argThat(E2eLocalFixtureSeederTest::isTheSeededPlaces), eq(WINDOW_DATES)))
                .thenAnswer(inv -> {
                    builtDays = groupByRegion(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2));
                    return builtDays;
                });
        when(viewService.getScoresForEnrichmentBulk(TODAY, TODAY.plusDays(4), BOTH_EVENTS))
                .thenReturn(Map.of(SLOT_KEY, Map.of("Blencathra", SEEDED_SCORE)));
        when(viewService.getTriagedByBatchLocationNamesBulk(TODAY, TODAY.plusDays(4), BOTH_EVENTS))
                .thenReturn(Map.of(SLOT_KEY, Set.of("Wast Water")));
        when(rollup.enrich(argThat(days -> days != null && days == builtDays), any(RegionScoreResolver.class),
                any(TriagedByBatchResolver.class))).thenAnswer(inv -> {
                    enrichedDays = mapRegions(inv.getArgument(0), r -> r.withMeanRating(4.0));
                    return enrichedDays;
                });
        when(headlineGenerator.generateHeadline(argThat(E2eLocalFixtureSeederTest::isScoredAndGlossed)))
                .thenReturn("headline");
        when(cacheRepository.save(any(DailyBriefingCacheEntity.class))).thenAnswer(inv -> {
            persisted = inv.getArgument(0);
            return persisted;
        });
        when(cacheRepository.findById(1)).thenAnswer(inv -> Optional.ofNullable(persisted));
        when(almanac.refresh()).thenReturn(true);
        when(forecastRepository.count()).thenReturn((long) FORECAST_ROWS);
        for (String region : E2eLocalFixtureSeeder.REGIONS) {
            when(evaluationService.getCachedScores(region, TODAY, TargetType.SUNRISE))
                    .thenReturn(Map.of("Blencathra", SEEDED_SCORE));
        }
        // What the API would serve: built from what the seeder actually persisted, with a BEST pick only when
        // real slots reached the persisted regions.
        when(briefingService.getCachedBriefingForApi()).thenAnswer(inv -> servedFromPersisted());

        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(E2eLocalFixtureSeeder.class)).addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(E2eLocalFixtureSeeder.class)).detachAppender(logs);
    }

    // -- the fakes ---------------------------------------------------------------------------

    private static boolean isTheSeededPlaces(Collection<LocationEntity> places) {
        return places != null && places.stream().map(LocationEntity::getName).toList().equals(PLACE_NAMES)
                && places.stream().allMatch(p -> p.getId() != null && p.getRegion() != null);
    }

    private BriefingSlot slotFor(BriefingSlotBuilder.LocationWeather lw, LocalDate date, TargetType type) {
        LocalDateTime time = type == TargetType.SUNRISE ? date.atTime(7, 0) : date.atTime(16, 0);
        BriefingSlot slot = new BriefingSlot(lw.location().getId(), lw.location().getName(), time, Verdict.GO,
                WEATHER, BriefingSlot.TideInfo.NONE, List.of());
        builtSlots.add(slot);
        return slot;
    }

    private static List<BriefingDay> groupByRegion(List<BriefingSlot> slots, List<LocationEntity> places,
            List<LocalDate> dates) {
        Map<String, String> regionOf = places.stream()
                .collect(Collectors.toMap(LocationEntity::getName, p -> p.getRegion().getName()));
        List<String> regionNames = places.stream().map(p -> p.getRegion().getName()).distinct().toList();
        return dates.stream().map(date -> new BriefingDay(date, Stream.of(TargetType.SUNRISE, TargetType.SUNSET)
                .map(type -> new BriefingEventSummary(type, regionNames.stream()
                        .map(name -> new BriefingRegion(name, Verdict.GO, "s", List.of(), slots.stream()
                                .filter(s -> name.equals(regionOf.get(s.locationName()))
                                        && s.solarEventTime().toLocalDate().equals(date)
                                        && (s.solarEventTime().getHour() < 12) == (type == TargetType.SUNRISE))
                                .toList(), null, null, null, null, null, null))
                        .toList(), List.of(), null, null))
                .toList())).toList();
    }

    private static List<BriefingDay> mapRegions(List<BriefingDay> days, UnaryOperator<BriefingRegion> change) {
        return days.stream().map(day -> day.withEventSummaries(day.eventSummaries().stream()
                .map(es -> es.withRegions(es.regions().stream().map(change).toList())).toList())).toList();
    }

    private static Stream<BriefingRegion> regionsOf(List<BriefingDay> days) {
        return days.stream().flatMap(d -> d.eventSummaries().stream()).flatMap(es -> es.regions().stream());
    }

    /** What the headline generator must be handed: every region scored (the rollup ran) and glossed. */
    private static boolean isScoredAndGlossed(List<BriefingDay> days) {
        return days != null && days.size() == WINDOW_DATES.size() && regionsOf(days).allMatch(r ->
                Double.valueOf(4.0).equals(r.meanRating()) && !r.slots().isEmpty()
                        && ("Seeded E2E fixture: " + r.regionName()).equals(r.glossHeadline())
                        && r.glossDetail() != null);
    }

    private DailyBriefingResponse servedFromPersisted() throws Exception {
        if (persisted == null) {
            return null;
        }
        DailyBriefingResponse stored = MAPPER.readValue(persisted.getPayload(), DailyBriefingResponse.class);
        boolean realSlots = regionsOf(stored.days()).anyMatch(r -> !r.slots().isEmpty());
        return realSlots ? servedWithPick(BriefingWindow.PickKind.BEST) : servedWithPick(null);
    }

    private static DailyBriefingResponse servedWithPick(BriefingWindow.PickKind kind) {
        BriefingWindow.Pick pick = kind == null ? null
                : new BriefingWindow.Pick(kind, "Northumberland Coast", "h", null, 4.0, null, null);
        BriefingWindow window = new BriefingWindow(null, DisplayVerdict.WORTH_IT, 5, null, pick, List.of(), null,
                null);
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
                headlineGenerator, viewService, almanac, freshness, ds, env, CLOCK, MAPPER, health, roleUsers);
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

    private void seedOk() throws SQLException {
        seeder(databaseCalled("H2"), "local").onApplicationReady();
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
        verifyNoInteractions(regionRepository, forecastRepository, evaluationService, cacheRepository,
                tideRepository, slotBuilder, hierarchyBuilder, rollup, headlineGenerator, almanac, roleUsers);
        verify(locationRepository, never()).save(any(LocationEntity.class));
        assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("a refusal at the ready event stops startup and never goes UP")
    void refusalIsLoud() throws SQLException {
        assertThatThrownBy(() -> seeder(databaseCalled("PostgreSQL"), "local").onApplicationReady())
                .isInstanceOf(IllegalStateException.class);
        assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
        verifyNoInteractions(regionRepository, roleUsers);
    }

    // -- the write order and what each collaborator is handed --------------------------------

    @Test
    @DisplayName("writes in the load-bearing order, handing each collaborator exactly what it needs, then goes UP")
    void writeOrder() throws SQLException {
        E2eLocalFixtureSeeder seeder = seeder(databaseCalled("H2"), "local");
        assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);

        seeder.onApplicationReady();

        ArgumentCaptor<RegionEntity> regions = ArgumentCaptor.forClass(RegionEntity.class);
        ArgumentCaptor<LocationEntity> places = ArgumentCaptor.forClass(LocationEntity.class);
        ArgumentCaptor<Collection<TideExtremeEntity>> tides = tideCaptor();
        ArgumentCaptor<List<LocationEntity>> roleUserPlaces = placeListCaptor();
        ArgumentCaptor<Collection<ForecastEvaluationEntity>> forecastRows = forecastCaptor();
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<BriefingEvaluationResult>> results = resultsCaptor();
        ArgumentCaptor<Collection<LocationEntity>> snapshotPlaces = placesCaptor();
        ArgumentCaptor<List<LocalDate>> snapshotDates = datesCaptor();
        ArgumentCaptor<BriefingSlotBuilder.LocationWeather> weather =
                ArgumentCaptor.forClass(BriefingSlotBuilder.LocationWeather.class);
        ArgumentCaptor<LocalDate> slotDates = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<TargetType> slotTypes = ArgumentCaptor.forClass(TargetType.class);
        ArgumentCaptor<List<BriefingSlot>> daySlots = slotsCaptor();
        ArgumentCaptor<List<LocationEntity>> dayPlaces = placeListCaptor();
        ArgumentCaptor<List<LocalDate>> dayDates = datesCaptor();
        ArgumentCaptor<List<BriefingDay>> enrichArg = daysCaptor();
        ArgumentCaptor<RegionScoreResolver> scores = ArgumentCaptor.forClass(RegionScoreResolver.class);
        ArgumentCaptor<TriagedByBatchResolver> triaged = ArgumentCaptor.forClass(TriagedByBatchResolver.class);
        ArgumentCaptor<List<BriefingDay>> headlineArg = daysCaptor();
        ArgumentCaptor<DailyBriefingCacheEntity> cacheRow = ArgumentCaptor.forClass(DailyBriefingCacheEntity.class);

        InOrder order = inOrder(regionRepository, locationRepository, tideRepository, forecastRepository,
                evaluationService, slotBuilder, hierarchyBuilder, viewService, rollup, headlineGenerator,
                cacheRepository, briefingService, almanac, roleUsers);
        order.verify(regionRepository, times(3)).save(regions.capture());
        order.verify(locationRepository, times(PLACES)).save(places.capture());
        order.verify(tideRepository, times(3)).saveAll(tides.capture());
        order.verify(roleUsers).seed(roleUserPlaces.capture());
        order.verify(forecastRepository).saveAll(forecastRows.capture());
        order.verify(evaluationService, times(30)).mergeFromBatch(keys.capture(), results.capture());
        order.verify(slotBuilder).snapshotFor(snapshotPlaces.capture(), snapshotDates.capture());
        order.verify(slotBuilder, times(SLOTS)).buildSlot(weather.capture(), slotDates.capture(),
                slotTypes.capture(), isNull(), same(snapshot));
        order.verify(hierarchyBuilder).buildDays(daySlots.capture(), dayPlaces.capture(), dayDates.capture());
        order.verify(viewService).getScoresForEnrichmentBulk(TODAY, TODAY.plusDays(4), BOTH_EVENTS);
        order.verify(viewService).getTriagedByBatchLocationNamesBulk(TODAY, TODAY.plusDays(4), BOTH_EVENTS);
        order.verify(rollup).enrich(enrichArg.capture(), scores.capture(), triaged.capture());
        order.verify(headlineGenerator).generateHeadline(headlineArg.capture());
        order.verify(cacheRepository).save(cacheRow.capture());
        order.verify(briefingService).loadPersistedBriefing();
        order.verify(almanac).refresh();
        order.verify(briefingService).getCachedBriefingForApi();
        order.verify(roleUsers).verify();

        // The role users are handed the very places that were saved, in the fixture's order, and nothing else.
        assertThat(roleUserPlaces.getValue()).containsExactlyElementsOf(places.getAllValues());
        assertThat(roleUserPlaces.getValue()).extracting(LocationEntity::getName)
                .containsExactlyElementsOf(PLACE_NAMES);
        verifyNoMoreInteractions(roleUsers);

        // Regions and places, exactly as the fixture defines them.
        assertThat(regions.getAllValues()).extracting(RegionEntity::getName)
                .containsExactlyElementsOf(E2eLocalFixtureSeeder.REGIONS);
        assertThat(places.getAllValues()).hasSize(PLACES);
        for (int i = 0; i < PLACES; i++) {
            E2eLocalFixtureSeeder.Spot spot = E2eLocalFixtureSeeder.SPOTS.get(i);
            LocationEntity saved = places.getAllValues().get(i);
            assertThat(saved.getName()).isEqualTo(spot.name());
            assertThat(saved.getLat()).isEqualTo(spot.lat());
            assertThat(saved.getLon()).isEqualTo(spot.lon());
            assertThat(saved.getLocationType()).containsExactly(spot.type());
            assertThat(saved.getTideType()).isEqualTo(spot.tides());
            assertThat(saved.getRegion().getName()).isEqualTo(spot.region());
        }
        // Tide: one series per coastal place, in order.
        List<Long> coastalIds = places.getAllValues().stream().filter(p -> !p.getTideType().isEmpty())
                .map(LocationEntity::getId).toList();
        assertThat(coastalIds).hasSize(3);
        assertThat(tides.getAllValues().stream()
                .map(series -> series.iterator().next().getLocationId()).toList()).isEqualTo(coastalIds);
        assertThat(forecastRows.getValue()).hasSize(FORECAST_ROWS);
        // cached_evaluation: exactly region x (today..T+4) x event, once each.
        assertThat(keys.getAllValues()).doesNotHaveDuplicates().hasSize(30).containsExactlyInAnyOrderElementsOf(
                E2eLocalFixtureSeeder.REGIONS.stream().flatMap(region -> WINDOW_DATES.stream().flatMap(date ->
                        Stream.of("SUNRISE", "SUNSET").map(type -> region + "|" + date + "|" + type))).toList());
        assertThat(results.getAllValues()).allSatisfy(batch -> assertThat(batch).hasSize(6));

        // The slot builder: the seeded places, the five dates and both events, once each, with this snapshot.
        assertThat(snapshotPlaces.getValue()).extracting(LocationEntity::getName)
                .containsExactlyElementsOf(PLACE_NAMES);
        assertThat(snapshotDates.getValue()).isEqualTo(WINDOW_DATES);
        assertThat(weather.getAllValues()).extracting(lw -> lw.location().getName() + "|" + lw.forecast().getLatitude())
                .doesNotContainNull();
        List<String> slotCalls = new ArrayList<>();
        for (int i = 0; i < SLOTS; i++) {
            slotCalls.add(weather.getAllValues().get(i).location().getName() + "|" + slotDates.getAllValues().get(i)
                    + "|" + slotTypes.getAllValues().get(i));
        }
        assertThat(slotCalls).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(PLACE_NAMES.stream()
                .flatMap(name -> WINDOW_DATES.stream().flatMap(date -> Stream.of(TargetType.SUNRISE, TargetType.SUNSET)
                        .map(type -> name + "|" + date + "|" + type))).toList());

        // The hierarchy is built from THOSE slots, THOSE places and THOSE dates.
        assertThat(daySlots.getValue()).containsExactlyInAnyOrderElementsOf(builtSlots).hasSize(SLOTS);
        assertThat(dayPlaces.getValue()).extracting(LocationEntity::getName).containsExactlyElementsOf(PLACE_NAMES);
        assertThat(dayDates.getValue()).isEqualTo(WINDOW_DATES);

        // enrich is handed the built days, and the headline the scored-and-glossed ones.
        assertThat(enrichArg.getValue()).isSameAs(builtDays);
        assertThat(scores.getValue()).isNotNull();
        assertThat(triaged.getValue()).isNotNull();
        assertThat(headlineArg.getValue()).hasSize(5);
        assertThat(regionsOf(headlineArg.getValue())).allSatisfy(r -> {
            assertThat(r.meanRating()).isEqualTo(4.0);
            assertThat(r.slots()).hasSize(6);
            assertThat(r.glossHeadline()).isEqualTo("Seeded E2E fixture: " + r.regionName());
        });

        // The persisted row, then the reload and the almanac refresh.
        assertThat(cacheRow.getValue()).isSameAs(persisted);
        assertThat(cacheRow.getValue().getId()).isEqualTo(1);
        assertThat(cacheRow.getValue().getGeneratedAt()).isEqualTo(LocalDateTime.now(CLOCK));

        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
        assertThat(logs.list).anySatisfy(e -> assertThat(e.getFormattedMessage())
                .startsWith(E2eLocalFixtureSeeder.SEEDED_MARKER).endsWith(ROLE_SUMMARY));
    }

    @Test
    @DisplayName("the persisted briefing is assembled from real slots, scored and glossed, not just served")
    void briefingIsAssembledFromRealSlots() throws Exception {
        seedOk();

        DailyBriefingResponse stored = MAPPER.readValue(persisted.getPayload(), DailyBriefingResponse.class);
        assertThat(stored.headline()).isEqualTo("headline");
        assertThat(stored.days()).extracting(BriefingDay::date).isEqualTo(WINDOW_DATES);
        for (BriefingDay day : stored.days()) {
            assertThat(day.eventSummaries()).extracting(BriefingEventSummary::targetType)
                    .containsExactly(TargetType.SUNRISE, TargetType.SUNSET);
            for (BriefingEventSummary summary : day.eventSummaries()) {
                assertThat(summary.regions()).extracting(BriefingRegion::regionName)
                        .containsExactlyElementsOf(E2eLocalFixtureSeeder.REGIONS);
                for (BriefingRegion region : summary.regions()) {
                    List<String> expected = E2eLocalFixtureSeeder.SPOTS.stream()
                            .filter(s -> s.region().equals(region.regionName()))
                            .map(E2eLocalFixtureSeeder.Spot::name).toList();
                    assertThat(region.slots()).extracting(BriefingSlot::locationName)
                            .containsExactlyElementsOf(expected);
                    assertThat(region.slots()).allSatisfy(slot -> assertThat(slot.solarEventTime().toLocalDate())
                            .isEqualTo(day.date()));
                    assertThat(region.meanRating()).isEqualTo(4.0);
                    assertThat(region.glossHeadline()).isEqualTo("Seeded E2E fixture: " + region.regionName());
                }
            }
        }
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("the resolvers handed to enrich return the seeded scores and triage for a known slot, only there")
    void resolversCarryTheSeededValues() throws SQLException {
        seedOk();

        ArgumentCaptor<RegionScoreResolver> scores = ArgumentCaptor.forClass(RegionScoreResolver.class);
        ArgumentCaptor<TriagedByBatchResolver> triaged = ArgumentCaptor.forClass(TriagedByBatchResolver.class);
        verify(rollup).enrich(same(builtDays), scores.capture(), triaged.capture());

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
        seedOk();
        verify(briefingService, never()).refreshBriefing();
        verify(briefingService, never()).refreshBriefingIfIdle();
        verify(briefingService, times(1)).loadPersistedBriefing();
        verify(almanac, times(1)).refresh();
    }

    @Test
    @DisplayName("forecast rows run T-3..T+4 (T-4 empty), are scored before the event, and never PENDING")
    void forecastRows() throws SQLException {
        seedOk();

        ArgumentCaptor<Collection<ForecastEvaluationEntity>> captor = forecastCaptor();
        verify(forecastRepository).saveAll(captor.capture());
        Collection<ForecastEvaluationEntity> rows = captor.getValue();

        assertThat(rows).hasSize(FORECAST_ROWS);
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
                .allSatisfy(r -> assertThat(Duration.between(r.getSolarEventTime(),
                        r.getTide().getNextHighTime()).toMinutes()).isLessThan(90));
    }

    @Test
    @DisplayName("cached entries cover today..T+4 with the same star as the forecast rows")
    void cachedEntriesMatchForecastRows() throws SQLException {
        seedOk();

        ArgumentCaptor<List<BriefingEvaluationResult>> results = resultsCaptor();
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(evaluationService, times(3 * 5 * 2)).mergeFromBatch(keys.capture(), results.capture());
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
    void tideExtremes() throws SQLException {
        seedOk();

        ArgumentCaptor<Collection<TideExtremeEntity>> captor = tideCaptor();
        verify(tideRepository, times(3)).saveAll(captor.capture());
        List<Long> seriesIds = new ArrayList<>();
        for (Collection<TideExtremeEntity> series : captor.getAllValues()) {
            List<TideExtremeEntity> rows = new ArrayList<>(series);
            seriesIds.add(rows.getFirst().getLocationId());
            assertThat(rows).extracting(TideExtremeEntity::getLocationId).containsOnly(rows.getFirst().getLocationId());
            for (int i = 1; i < rows.size(); i++) {
                assertThat(rows.get(i).getType()).isNotEqualTo(rows.get(i - 1).getType());
            }
        }
        assertThat(seriesIds).isEqualTo(savedLocations.stream().filter(p -> !p.getTideType().isEmpty())
                .map(LocationEntity::getId).toList());
    }

    @Test
    @DisplayName("the persisted payload is the Jackson 2 JSON of the briefing, row 1, stamped with the clock")
    void persistsJson() throws SQLException {
        seedOk();
        ArgumentCaptor<DailyBriefingCacheEntity> captor = ArgumentCaptor.forClass(DailyBriefingCacheEntity.class);
        verify(cacheRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(1);
        assertThat(captor.getValue().getGeneratedAt()).isEqualTo(LocalDateTime.of(2031, 7, 14, 12, 0));
        assertThat(captor.getValue().getPayload()).contains("\"headline\":\"headline\"").contains("\"days\"");
    }

    // -- the self-check and the failure path -------------------------------------------------

    @Test
    @DisplayName("self-check: a briefing with only an ALSO pick (no BEST) fails, logs ERROR and stays DOWN")
    void selfCheckNeedsBestPick() throws SQLException {
        doReturn(servedWithPick(BriefingWindow.PickKind.ALSO)).when(briefingService).getCachedBriefingForApi();
        assertFailsLoudly("no window in the served briefing carries a BEST pick");
    }

    @Test
    @DisplayName("self-check: an empty served briefing fails, logs ERROR and stays DOWN")
    void selfCheckNeedsABriefing() throws SQLException {
        doReturn(null).when(briefingService).getCachedBriefingForApi();
        assertFailsLoudly("the served briefing is empty");
    }

    @Test
    @DisplayName("self-check: forecast rows that do not read back fail it")
    void selfCheckCountsRows() throws SQLException {
        doReturn(7L).when(forecastRepository).count();
        assertFailsLoudly("forecast_evaluation rows read back");
    }

    @Test
    @DisplayName("self-check: a missing briefing cache row fails it")
    void selfCheckNeedsTheCacheRow() throws SQLException {
        doReturn(Optional.empty()).when(cacheRepository).findById(1);
        assertFailsLoudly("daily_briefing_cache has no row");
    }

    @Test
    @DisplayName("self-check: a region with no cached evaluation today fails it")
    void selfCheckNeedsCachedScores() throws SQLException {
        for (String region : E2eLocalFixtureSeeder.REGIONS) {
            doReturn(Map.of()).when(evaluationService).getCachedScores(region, TODAY, TargetType.SUNRISE);
        }
        assertFailsLoudly("no cached evaluation today");
    }

    @Test
    @DisplayName("self-check: a missing or mis-roled reader, or picks that do not read back, fail it")
    void selfCheckNeedsTheRoleUsers() throws SQLException {
        doThrow(new IllegalStateException("Self-check: user 'lite' does not exist")).when(roleUsers).verify();
        assertFailsLoudly("user 'lite' does not exist");
        verify(roleUsers).seed(argThat(E2eLocalFixtureSeederTest::isTheSeededPlaces));
        verify(roleUsers).verify();
    }

    @Test
    @DisplayName("failure path: role users that cannot be written fail loudly before any forecast row is written")
    void roleUserWriteFailureStaysDown() throws SQLException {
        doThrow(new IllegalArgumentException("no users")).when(roleUsers)
                .seed(argThat(E2eLocalFixtureSeederTest::isTheSeededPlaces));
        assertFailsLoudly("no users");
        verifyNoInteractions(forecastRepository, evaluationService, cacheRepository, almanac);
        verify(roleUsers, never()).verify();
    }

    @Test
    @DisplayName("failure path: a write that throws is logged at ERROR, never swallowed into UP")
    void writeFailureStaysDown() throws SQLException {
        doThrow(new IllegalArgumentException("boom")).when(forecastRepository).saveAll(allForecastRows());
        assertFailsLoudly("boom");
        verify(briefingService, never()).loadPersistedBriefing();
        verify(almanac, never()).refresh();
    }

    private static Iterable<ForecastEvaluationEntity> allForecastRows() {
        return argThat(rows -> rows != null && StreamSupport.stream(rows.spliterator(), false).count()
                == FORECAST_ROWS);
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

    // -- generic captors ---------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Collection<TideExtremeEntity>> tideCaptor() {
        return ArgumentCaptor.forClass(Collection.class);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Collection<ForecastEvaluationEntity>> forecastCaptor() {
        return ArgumentCaptor.forClass(Collection.class);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<BriefingEvaluationResult>> resultsCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Collection<LocationEntity>> placesCaptor() {
        return ArgumentCaptor.forClass(Collection.class);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<LocationEntity>> placeListCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<LocalDate>> datesCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<BriefingSlot>> slotsCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<BriefingDay>> daysCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }
}
