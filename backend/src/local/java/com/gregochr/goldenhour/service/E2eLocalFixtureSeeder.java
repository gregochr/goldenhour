package com.gregochr.goldenhour.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.DailyBriefingCacheEntity;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.SolarEventType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideDetails;
import com.gregochr.goldenhour.entity.TideExtremeEntity;
import com.gregochr.goldenhour.entity.TideExtremeType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.BestBetResult;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.repository.DailyBriefingCacheRepository;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.RegionRepository;
import com.gregochr.goldenhour.repository.TideExtremeRepository;
import com.gregochr.goldenhour.service.evaluation.CacheKeyFactory;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Gives the <b>E2E smoke suite's</b> local backend a fixed, rated forecast with no network call, no Claude
 * call and no briefing build, so the Map and Plan journeys have data to drive
 * ({@code docs/engineering/engineering-gaps-plan.md}, Phase 3a.1).
 *
 * <p><b>Impossible to run in production, in three independent ways</b> (the pattern of
 * {@code AskLocalFixtureSeeder}): the bean exists only under {@code local & !prod} and only when
 * {@code photocast.e2e.seed-fixture} is exactly {@code true} (only {@code scripts/e2e-smoke-backend.sh} sets
 * it); at run time it refuses unless the {@code local} profile is active; and it refuses unless the
 * datasource really is H2. A fourth refusal keeps it off any database that already holds locations. Unlike
 * the Ask seeder, every failure is loud: a refusal throws, and any other failure is logged at ERROR and the
 * {@link E2eFixtureHealthIndicator} stays {@code DOWN}, so the suite's {@code webServer} probe never goes
 * green on a half-written fixture.
 *
 * <p><b>It never calls {@code BriefingService.refreshBriefing*}</b>, which always fetches live Open-Meteo
 * weather and calls two Claude services. It assembles the briefing itself from the same builder beans, with
 * synthetic weather.
 *
 * <p><b>The write order is load-bearing</b> (each step feeds the next):
 * <ol>
 *   <li>regions, places (through the repositories, never {@code LocationService}, which can fetch elevation
 *       from Open-Meteo) and synthetic tide extremes for the coastal places;</li>
 *   <li>the sub-regions, the {@code lite} and {@code pro} readers and {@code lite}'s picks
 *       ({@link E2eRoleUsers}, Phase 3a.2), which need the saved places;</li>
 *   <li>{@code forecast_evaluation} rows for T-3..T+4 (T-4 is left empty on purpose);</li>
 *   <li>{@code cached_evaluation} for today..T+4, through {@code BriefingEvaluationService.mergeFromBatch};</li>
 *   <li>the briefing slots and days from synthetic weather;</li>
 *   <li>{@code BriefingRegionEvaluationRollup.enrich}, with the resolvers built as
 *       {@code BriefingService.bulkScoreResolver} builds them. Persisting without it drops every pick;</li>
 *   <li>the stamped gloss, the headline, and the {@code daily_briefing_cache} row, serialised with the
 *       Jackson 2 mapper; then {@code loadPersistedBriefing} and an almanac refresh;</li>
 *   <li>the self-check, and only then {@code UP}.</li>
 * </ol>
 *
 * <p><b>The ratings are a pure function</b> ({@link #rating}) so the specs and the unit test can mirror them.
 * Per window a region's base cycles 4, 3, 2 across the three regions, rotating each window; a place adds an
 * offset of -1, +1 or 0 that sums to zero over a region. So every place's star changes between adjacent
 * windows, and every window has one region averaging 4 (Worth it), one 3 and one 2 (below the also-good
 * floor).
 */
@Component
@Profile("local & !prod")
@ConditionalOnProperty(name = "photocast.e2e.seed-fixture", havingValue = "true")
public class E2eLocalFixtureSeeder {

    private static final Logger LOG = LoggerFactory.getLogger(E2eLocalFixtureSeeder.class);

    /** The log line the CI step greps for, so a vacuous pass is impossible. */
    static final String SEEDED_MARKER = "[E2E FIXTURE] seeded";

    /** Past days with forecast rows: T-3..T-1. T-4 is empty on purpose so the window control reaches its floor. */
    static final int PAST_DAYS_WITH_ROWS = 3;

    /** Future days with rows: today through T+4. */
    static final int FUTURE_DAYS = 4;

    /** Region base stars cycle through this many bands. */
    private static final int BANDS = 3;

    private static final int TOP_BAND = 4;
    private static final int MIN_STAR = 1;
    private static final int MAX_STAR = 5;

    /** A place's offset on its region's base for the window; sums to zero over the six places. */
    static final int[] PLACE_OFFSETS = {-1, 1, 0, -1, 1, 0};

    /** The hour before the seed, and six hours before an event: the latest a fixture row was "scored". */
    private static final Duration SCORED_BEFORE_SEED = Duration.ofHours(1);
    private static final Duration SCORED_BEFORE_EVENT = Duration.ofHours(6);

    /** Half a semi-diurnal tidal cycle: 12 h 25 min is 745 min, so an extreme every 372.5 min. */
    private static final long HALF_TIDE_CYCLE_SECONDS = 22_350L;
    private static final long TIDE_BACK_DAYS = 8;
    private static final long TIDE_FORWARD_DAYS = 10;
    private static final BigDecimal HIGH_WATER_METRES = new BigDecimal("5.000");
    private static final BigDecimal LOW_WATER_METRES = new BigDecimal("0.600");

    /** The rising-tide place: high water 45 minutes after the light, inside the sheet's 90-minute test. */
    private static final long RISING_TIDE_MINUTES = 45;
    private static final long OTHER_TIDE_MINUTES = 240;

    private static final String DATABASE = "H2";
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final int WEATHER_DAYS_BACK = 1;
    private static final int WEATHER_DAYS_FORWARD = 7;

    /** One fixture place: where it is, what it is, how its coast wants the tide. */
    record Spot(String name, String region, double lat, double lon, LocationType type,
            Set<TideType> tides, TideRole tideRole) {
    }

    /** How a coastal place's fixture tide sits. */
    enum TideRole {
        /** Inland. */
        NONE,
        /** High water shortly after the light: the sheet's rising-tide fact appears. */
        RISING,
        /** High water hours from the light. */
        OTHER
    }

    /** The three regions, each of six places. Pairwise at least 10 km apart, none on a region's centroid. */
    static final List<String> REGIONS = List.of("Northumberland Coast", "Lake District", "Yorkshire Dales");

    /** The places, six per region, in {@link #PLACE_OFFSETS} order within each region. */
    static final List<Spot> SPOTS = List.of(
            coast("Northumberland Coast", "Bamburgh Beach", 55.609, -1.709, Set.of(TideType.HIGH),
                    TideRole.RISING),
            coast("Northumberland Coast", "Craster Rocks", 55.472, -1.597, Set.of(TideType.LOW),
                    TideRole.OTHER),
            coast("Northumberland Coast", "Druridge Bay", 55.285, -1.560, Set.of(TideType.values()),
                    TideRole.OTHER),
            inland("Northumberland Coast", "Cheviot Edge", 55.478, -2.145),
            inland("Northumberland Coast", "Simonside Ridge", 55.330, -2.150),
            inland("Northumberland Coast", "Housesteads Crags", 55.010, -2.330),
            inland("Lake District", "Blencathra", 54.643, -3.050),
            inland("Lake District", "Haweswater", 54.500, -2.800),
            inland("Lake District", "Loweswater", 54.580, -3.360),
            inland("Lake District", "Wast Water", 54.439, -3.299),
            inland("Lake District", "Coniston Old Man", 54.371, -3.109),
            inland("Lake District", "Kentmere Pike", 54.380, -2.850),
            inland("Yorkshire Dales", "Malham Cove", 54.072, -2.160),
            inland("Yorkshire Dales", "Ingleborough", 54.164, -2.398),
            inland("Yorkshire Dales", "Hardraw Force", 54.318, -2.297),
            inland("Yorkshire Dales", "Buckden Pike", 54.206, -2.061),
            inland("Yorkshire Dales", "Simons Seat", 54.017, -1.920),
            inland("Yorkshire Dales", "Cautley Spout", 54.380, -2.520));

    private final RegionRepository regionRepository;
    private final LocationRepository locationRepository;
    private final TideExtremeRepository tideExtremeRepository;
    private final ForecastEvaluationRepository forecastEvaluationRepository;
    private final DailyBriefingCacheRepository briefingCacheRepository;
    private final BriefingEvaluationService evaluationService;
    private final BriefingService briefingService;
    private final BriefingSlotBuilder slotBuilder;
    private final BriefingHierarchyBuilder hierarchyBuilder;
    private final BriefingRegionEvaluationRollup rollup;
    private final BriefingHeadlineGenerator headlineGenerator;
    private final EvaluationViewService evaluationViewService;
    private final AlmanacService almanacService;
    private final SolarEventFreshness freshness;
    private final DataSource dataSource;
    private final Environment environment;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final E2eFixtureHealthIndicator health;
    private final E2eRoleUsers roleUsers;

    /**
     * Creates the seeder.
     *
     * @param regionRepository             regions
     * @param locationRepository           locations
     * @param tideExtremeRepository        stored tide extremes
     * @param forecastEvaluationRepository the rich per-slot forecast rows
     * @param briefingCacheRepository      the single-row briefing cache
     * @param evaluationService            the service the batch pipeline writes {@code cached_evaluation} through
     * @param briefingService              serves the briefing; its persisted row is reloaded after the write
     * @param slotBuilder                  builds the briefing slots from weather
     * @param hierarchyBuilder             groups slots into days, events and regions
     * @param rollup                       scores the hierarchy from the evaluation stores
     * @param headlineGenerator            the one-line briefing headline
     * @param evaluationViewService        the merged evaluation view the score resolvers read
     * @param almanacService               the Coming up feed, refreshed so it sees the seeded tides
     * @param freshness                    the project's one answer to "now" and to solar event times
     * @param dataSource                   the datasource, checked to be H2 before anything is written
     * @param environment                  the environment, checked for the {@code local} profile
     * @param clock                        the application clock
     * @param objectMapper                 the Jackson 2 mapper {@code daily_briefing_cache} is written with
     * @param health                       readiness, {@code UP} only once the self-check passes
     * @param roleUsers                    the {@code lite} and {@code pro} readers and the sub-regions the Lite
     *                                     reader's picks need
     */
    public E2eLocalFixtureSeeder(RegionRepository regionRepository, LocationRepository locationRepository,
            TideExtremeRepository tideExtremeRepository,
            ForecastEvaluationRepository forecastEvaluationRepository,
            DailyBriefingCacheRepository briefingCacheRepository, BriefingEvaluationService evaluationService,
            BriefingService briefingService, BriefingSlotBuilder slotBuilder,
            BriefingHierarchyBuilder hierarchyBuilder, BriefingRegionEvaluationRollup rollup,
            BriefingHeadlineGenerator headlineGenerator, EvaluationViewService evaluationViewService,
            AlmanacService almanacService, SolarEventFreshness freshness, DataSource dataSource,
            Environment environment, Clock clock, ObjectMapper objectMapper, E2eFixtureHealthIndicator health,
            E2eRoleUsers roleUsers) {
        this.regionRepository = regionRepository;
        this.locationRepository = locationRepository;
        this.tideExtremeRepository = tideExtremeRepository;
        this.forecastEvaluationRepository = forecastEvaluationRepository;
        this.briefingCacheRepository = briefingCacheRepository;
        this.evaluationService = evaluationService;
        this.briefingService = briefingService;
        this.slotBuilder = slotBuilder;
        this.hierarchyBuilder = hierarchyBuilder;
        this.rollup = rollup;
        this.headlineGenerator = headlineGenerator;
        this.evaluationViewService = evaluationViewService;
        this.almanacService = almanacService;
        this.freshness = freshness;
        this.dataSource = dataSource;
        this.environment = environment;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.health = health;
        this.roleUsers = roleUsers;
    }

    /**
     * Seeds the fixture once the application is ready, after {@code LocalAdminSeeder} (which runs at
     * {@code @PostConstruct}) and after the evaluation cache has been rehydrated.
     *
     * @throws IllegalStateException when the {@code local} profile is not active, the datasource is not H2,
     *         or the database already holds locations: the refusals stop a mis-set flag from ever writing
     *         into a real database
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void onApplicationReady() {
        requireLocalH2();
        requireEmptyLocations();
        try {
            String summary = seed();
            health.markReady();
            LOG.info("{}: {}", SEEDED_MARKER, summary);
        } catch (RuntimeException e) {
            health.markFailed(e.toString());
            LOG.error("[E2E FIXTURE] Seeding FAILED; /actuator/health/container stays DOWN", e);
        }
    }

    /**
     * Refuses unless this is the {@code local} profile on an H2 database.
     *
     * @throws IllegalStateException when either is not true, or the database cannot be asked
     */
    void requireLocalH2() {
        if (!environment.acceptsProfiles(Profiles.of("local"))
                || environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException(
                    "photocast.e2e.seed-fixture is only for the local profile; refusing to seed");
        }
        String product;
        try (Connection connection = dataSource.getConnection()) {
            product = connection.getMetaData().getDatabaseProductName();
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "photocast.e2e.seed-fixture could not confirm the database is H2; refusing to seed", e);
        }
        if (!DATABASE.equalsIgnoreCase(product)) {
            throw new IllegalStateException("photocast.e2e.seed-fixture refuses to write to a " + product
                    + " database: it is for a fresh local H2 file only");
        }
    }

    /**
     * Refuses unless the {@code locations} table is empty: the fixture is for a fresh database, never a
     * developer's own.
     *
     * @throws IllegalStateException when any location exists
     */
    void requireEmptyLocations() {
        if (locationRepository.count() != 0) {
            throw new IllegalStateException("photocast.e2e.seed-fixture refuses to seed a database that already"
                    + " holds locations; start from an empty H2 file");
        }
    }

    /**
     * Writes the fixture in the order the class comment gives, then self-checks it.
     *
     * @return a one-line summary for the log
     * @throws IllegalStateException when the self-check finds the served briefing without a BEST pick or a
     *         table without its rows
     */
    String seed() {
        Instant seedStart = clock.instant();
        LocalDate today = ForecastHorizon.today(clock);

        Map<String, RegionEntity> regions = saveRegions();
        List<LocationEntity> locations = savePlaces(regions);
        int tides = saveTides(locations, today);
        String roles = roleUsers.seed(locations);

        int forecastRows = saveForecastRows(locations, today, seedStart);
        int cachedEntries = saveCachedEvaluations(locations, today);

        List<LocalDate> dates = new ArrayList<>();
        for (int i = 0; i <= FUTURE_DAYS; i++) {
            dates.add(today.plusDays(i));
        }
        List<BriefingDay> days = buildDays(locations, dates);
        days = enrich(days);
        days = stampGloss(days);
        String headline = headlineGenerator.generateHeadline(days);
        persistBriefing(headline, days);
        briefingService.loadPersistedBriefing();
        almanacService.refresh();

        int picks = selfCheck(forecastRows, cachedEntries);
        roleUsers.verify();
        return regions.size() + " regions, " + locations.size() + " places, " + forecastRows
                + " forecast rows, " + cachedEntries + " cached entries, " + tides + " tide extremes, "
                + picks + " windows with a pick, " + roles;
    }

    // -- the ratings -------------------------------------------------------------------------

    /**
     * The fixture's star for one place in one window. Pure, so the specs and the unit test mirror it.
     *
     * @param regionIndex 0..2, the region's position in {@link #REGIONS}
     * @param placeIndex  0..5, the place's position within its region
     * @param date        the window's date
     * @param type        SUNRISE or SUNSET
     * @return a star from 1 to 5
     */
    static int rating(int regionIndex, int placeIndex, LocalDate date, TargetType type) {
        long window = date.toEpochDay() * 2 + (type == TargetType.SUNSET ? 1 : 0);
        int base = TOP_BAND - Math.floorMod(window + regionIndex, BANDS);
        return Math.max(MIN_STAR, Math.min(MAX_STAR, base + PLACE_OFFSETS[placeIndex]));
    }

    private static int regionIndex(String region) {
        return REGIONS.indexOf(region);
    }

    private static int placeIndex(Spot spot) {
        int index = 0;
        for (Spot candidate : SPOTS) {
            if (candidate.region().equals(spot.region())) {
                if (candidate == spot) {
                    return index;
                }
                index++;
            }
        }
        throw new IllegalArgumentException("Not a fixture spot: " + spot.name());
    }

    private static Spot spotOf(String name) {
        return SPOTS.stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
    }

    private static int ratingOf(Spot spot, LocalDate date, TargetType type) {
        return rating(regionIndex(spot.region()), placeIndex(spot), date, type);
    }

    // -- regions, places, tides --------------------------------------------------------------

    private Map<String, RegionEntity> saveRegions() {
        Map<String, RegionEntity> regions = new LinkedHashMap<>();
        for (String name : REGIONS) {
            regions.put(name, regionRepository.save(
                    RegionEntity.builder().name(name).enabled(true).createdAt(utcNow()).build()));
        }
        return regions;
    }

    private List<LocationEntity> savePlaces(Map<String, RegionEntity> regions) {
        List<LocationEntity> saved = new ArrayList<>();
        for (Spot spot : SPOTS) {
            saved.add(locationRepository.save(LocationEntity.builder()
                    .name(spot.name())
                    .lat(spot.lat())
                    .lon(spot.lon())
                    .solarEventType(new HashSet<>(Set.of(SolarEventType.SUNRISE, SolarEventType.SUNSET)))
                    .locationType(new HashSet<>(Set.of(spot.type())))
                    .tideType(new HashSet<>(spot.tides()))
                    .region(regions.get(spot.region()))
                    .createdAt(utcNow())
                    .build()));
        }
        return saved;
    }

    /**
     * Synthetic semi-diurnal extremes (5.0 m and 0.6 m) for the coastal places, anchored to today's sunset so
     * the series is continuous across the whole window. Invented numbers for a rehearsal, never mistaken for
     * data: the E2E database is thrown away every run.
     */
    private int saveTides(List<LocationEntity> locations, LocalDate today) {
        int total = 0;
        LocalDateTime fetchedAt = utcNow();
        for (LocationEntity location : locations) {
            if (location.getTideType().isEmpty()) {
                continue;
            }
            LocalDateTime anchor = freshness.eventTime(location, today, TargetType.SUNSET);
            LocalDateTime start = anchor.minusDays(TIDE_BACK_DAYS);
            LocalDateTime end = anchor.plusDays(TIDE_FORWARD_DAYS);
            // High water at the anchor; the series is walked back to its start in whole half cycles.
            long halfCyclesBack = Duration.between(start, anchor).getSeconds() / HALF_TIDE_CYCLE_SECONDS;
            TideExtremeType type = halfCyclesBack % 2 == 0 ? TideExtremeType.HIGH : TideExtremeType.LOW;
            LocalDateTime at = anchor.minusSeconds(halfCyclesBack * HALF_TIDE_CYCLE_SECONDS);
            List<TideExtremeEntity> rows = new ArrayList<>();
            for (; !at.isAfter(end); at = at.plusSeconds(HALF_TIDE_CYCLE_SECONDS)) {
                rows.add(TideExtremeEntity.builder().locationId(location.getId()).eventTime(at)
                        .heightMetres(type == TideExtremeType.HIGH ? HIGH_WATER_METRES : LOW_WATER_METRES)
                        .type(type).fetchedAt(fetchedAt).build());
                type = type == TideExtremeType.HIGH ? TideExtremeType.LOW : TideExtremeType.HIGH;
            }
            tideExtremeRepository.saveAll(rows);
            total += rows.size();
        }
        return total;
    }

    // -- forecast_evaluation -----------------------------------------------------------------

    /**
     * T-3..T+4, both events, every place. Each row is "scored" at {@code min(seedStart - 1h, event - 6h)}
     * (a naive UTC {@code forecastRunAt} and the same {@code scoredAt} instant), so {@code AsOfEventResolver}
     * serves a passed slot from it. The event time is the same one the client splits past from future on.
     */
    private int saveForecastRows(List<LocationEntity> locations, LocalDate today, Instant seedStart) {
        List<ForecastEvaluationEntity> rows = new ArrayList<>();
        for (LocationEntity location : locations) {
            Spot spot = spotOf(location.getName());
            for (int offset = -PAST_DAYS_WITH_ROWS; offset <= FUTURE_DAYS; offset++) {
                LocalDate date = today.plusDays(offset);
                for (TargetType type : List.of(TargetType.SUNRISE, TargetType.SUNSET)) {
                    rows.add(forecastRow(location, spot, date, type, offset, seedStart));
                }
            }
        }
        forecastEvaluationRepository.saveAll(rows);
        return rows.size();
    }

    private ForecastEvaluationEntity forecastRow(LocationEntity location, Spot spot, LocalDate date,
            TargetType type, int daysAhead, Instant seedStart) {
        LocalDateTime event = freshness.eventTime(location, date, type);
        Instant eventInstant = event.toInstant(ZoneOffset.UTC);
        Instant scoredAt = seedStart.minus(SCORED_BEFORE_SEED).isBefore(eventInstant.minus(SCORED_BEFORE_EVENT))
                ? seedStart.minus(SCORED_BEFORE_SEED) : eventInstant.minus(SCORED_BEFORE_EVENT);
        int star = ratingOf(spot, date, type);
        TideDetails tide = spot.tideRole() == TideRole.NONE ? null : TideDetails.builder()
                .state(TideState.HIGH)
                .nextHighTime(event.plusMinutes(spot.tideRole() == TideRole.RISING
                        ? RISING_TIDE_MINUTES : OTHER_TIDE_MINUTES))
                .nextHighHeightMetres(HIGH_WATER_METRES)
                .nextLowTime(event.plusMinutes(OTHER_TIDE_MINUTES * 2))
                .nextLowHeightMetres(LOW_WATER_METRES)
                .aligned(Boolean.TRUE)
                .build();
        return ForecastEvaluationEntity.builder()
                .locationLat(BigDecimal.valueOf(location.getLat()).setScale(6, java.math.RoundingMode.HALF_UP))
                .locationLon(BigDecimal.valueOf(location.getLon()).setScale(6, java.math.RoundingMode.HALF_UP))
                .location(location)
                .targetDate(date)
                .targetType(type)
                .forecastRunAt(LocalDateTime.ofInstant(scoredAt, ZoneOffset.UTC))
                .daysAhead(daysAhead)
                .lowCloud(20)
                .midCloud(30)
                .highCloud(40)
                .visibility(20_000)
                .windSpeed(new BigDecimal("3.20"))
                .windDirection(270)
                .precipitation(new BigDecimal("0.00"))
                .humidity(60)
                .weatherCode(1)
                .temperatureCelsius(9.0)
                .apparentTemperatureCelsius(7.0)
                .precipitationProbabilityPercent(10)
                .evaluationModel(EvaluationModel.SONNET_55)
                .rating(star)
                .skyRating(star)
                .fierySkyPotential(star * 18)
                .goldenHourPotential(Math.min(100, star * 18 + 6))
                .summary(summaryFor(spot, star))
                .solarEventTime(event)
                .tide(tide)
                .scoredAt(scoredAt)
                .build();
    }

    private static String summaryFor(Spot spot, int star) {
        return "Seeded E2E fixture for " + spot.name() + ": " + star + " star. Not a forecast.";
    }

    // -- cached_evaluation -------------------------------------------------------------------

    /** Today..T+4, both events, every place, through the service the batch pipeline writes through. */
    private int saveCachedEvaluations(List<LocationEntity> locations, LocalDate today) {
        int written = 0;
        for (String region : REGIONS) {
            for (int offset = 0; offset <= FUTURE_DAYS; offset++) {
                LocalDate date = today.plusDays(offset);
                for (TargetType type : List.of(TargetType.SUNRISE, TargetType.SUNSET)) {
                    List<BriefingEvaluationResult> results = new ArrayList<>();
                    for (LocationEntity location : locations) {
                        Spot spot = spotOf(location.getName());
                        if (!spot.region().equals(region)) {
                            continue;
                        }
                        int star = ratingOf(spot, date, type);
                        results.add(new BriefingEvaluationResult(spot.name(), star, star * 18,
                                Math.min(100, star * 18 + 6), summaryFor(spot, star), null, null,
                                "Seeded E2E fixture", null, star, false, false, null));
                    }
                    evaluationService.mergeFromBatch(CacheKeyFactory.build(region, date, type), results);
                    written += results.size();
                }
            }
        }
        return written;
    }

    // -- the briefing ------------------------------------------------------------------------

    private List<BriefingDay> buildDays(List<LocationEntity> locations, List<LocalDate> dates) {
        TideExtremeSnapshot tideSnapshot = slotBuilder.snapshotFor(locations, dates);
        List<BriefingSlot> slots = new ArrayList<>();
        for (LocationEntity location : locations) {
            BriefingSlotBuilder.LocationWeather weather =
                    new BriefingSlotBuilder.LocationWeather(location, syntheticWeather(location, dates.getFirst()));
            for (LocalDate date : dates) {
                for (TargetType type : List.of(TargetType.SUNRISE, TargetType.SUNSET)) {
                    BriefingSlot slot = slotBuilder.buildSlot(weather, date, type, null, tideSnapshot);
                    if (slot != null) {
                        slots.add(slot);
                    }
                }
            }
        }
        return hierarchyBuilder.buildDays(slots, locations, dates);
    }

    /** A calm, partly cloudy, dry hourly series (UTC) covering the whole build window. No network. */
    static OpenMeteoForecastResponse syntheticWeather(LocationEntity location, LocalDate today) {
        OpenMeteoForecastResponse.Hourly hourly = new OpenMeteoForecastResponse.Hourly();
        List<String> times = new ArrayList<>();
        List<Integer> low = new ArrayList<>();
        List<Integer> mid = new ArrayList<>();
        List<Integer> high = new ArrayList<>();
        List<Double> visibility = new ArrayList<>();
        List<Double> wind = new ArrayList<>();
        List<Double> precipitation = new ArrayList<>();
        List<Integer> weatherCode = new ArrayList<>();
        List<Integer> humidity = new ArrayList<>();
        List<Double> temperature = new ArrayList<>();
        List<Double> apparent = new ArrayList<>();
        // Relative to the injected clock's UK "today": a day back, a week forward.
        LocalDate start = today.minusDays(WEATHER_DAYS_BACK + 1L);
        for (int hour = 0; hour < (WEATHER_DAYS_BACK + 1 + WEATHER_DAYS_FORWARD + 1) * 24; hour++) {
            times.add(start.atStartOfDay().plusHours(hour).format(HOUR));
            low.add(20);
            mid.add(30);
            high.add(40);
            visibility.add(20_000.0);
            wind.add(3.2);
            precipitation.add(0.0);
            weatherCode.add(1);
            humidity.add(60);
            temperature.add(9.0);
            apparent.add(7.0);
        }
        hourly.setTime(times);
        hourly.setCloudCoverLow(low);
        hourly.setCloudCoverMid(mid);
        hourly.setCloudCoverHigh(high);
        hourly.setVisibility(visibility);
        hourly.setWindSpeed10m(wind);
        hourly.setPrecipitation(precipitation);
        hourly.setWeatherCode(weatherCode);
        hourly.setRelativeHumidity2m(humidity);
        hourly.setTemperature2m(temperature);
        hourly.setApparentTemperature(apparent);
        OpenMeteoForecastResponse response = new OpenMeteoForecastResponse();
        response.setLatitude(location.getLat());
        response.setLongitude(location.getLon());
        response.setHourly(hourly);
        return response;
    }

    /**
     * The scored hierarchy, with the resolvers built as {@code BriefingService.bulkScoreResolver} and
     * {@code bulkTriagedResolver} build them: one bulk load over the days' whole range and target types.
     */
    private List<BriefingDay> enrich(List<BriefingDay> days) {
        LocalDate start = days.getFirst().date();
        LocalDate end = days.getLast().date();
        Set<TargetType> types = days.stream()
                .flatMap(day -> day.eventSummaries().stream())
                .map(BriefingEventSummary::targetType)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(TargetType.class)));
        Map<String, Map<String, BriefingEvaluationResult>> scores =
                evaluationViewService.getScoresForEnrichmentBulk(start, end, types);
        Map<String, Set<String>> triaged =
                evaluationViewService.getTriagedByBatchLocationNamesBulk(start, end, types);
        RegionScoreResolver scoreResolver = (region, date, type) ->
                scores.getOrDefault(region + "|" + date + "|" + type, Map.of());
        TriagedByBatchResolver triagedResolver = (region, date, type) ->
                triaged.getOrDefault(region + "|" + date + "|" + type, Set.of());
        return rollup.enrich(days, scoreResolver, triagedResolver);
    }

    /** Stamps a fixed gloss on every region, so Best picks exist with no Claude call. */
    private List<BriefingDay> stampGloss(List<BriefingDay> days) {
        List<BriefingDay> out = new ArrayList<>();
        for (BriefingDay day : days) {
            List<BriefingEventSummary> events = new ArrayList<>();
            for (BriefingEventSummary summary : day.eventSummaries()) {
                List<BriefingRegion> regions = summary.regions().stream()
                        .map(r -> r.withGloss("Seeded E2E fixture: " + r.regionName(),
                                "Fixed text so a pick exists with no Claude call. Not a forecast."))
                        .toList();
                events.add(summary.withRegions(regions));
            }
            out.add(day.withEventSummaries(events));
        }
        return out;
    }

    private void persistBriefing(String headline, List<BriefingDay> days) {
        BestBetResult noPicks = BestBetResult.noPicks();
        DailyBriefingResponse response = new DailyBriefingResponse(LocalDateTime.now(clock), headline, days,
                noPicks.picks(), null, null, false, "fixture", List.of(), List.of(), noPicks.status());
        try {
            DailyBriefingCacheEntity entity = new DailyBriefingCacheEntity();
            entity.setId(1);
            entity.setGeneratedAt(response.generatedAt());
            entity.setPayload(objectMapper.writeValueAsString(response));
            briefingCacheRepository.save(entity);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise the fixture briefing", e);
        }
    }

    // -- the self-check ----------------------------------------------------------------------

    /**
     * The served briefing must carry at least one window with a BEST pick, and the row counts must read
     * back. {@code persistToDb} in the real service swallows its own errors, so this is the only proof.
     *
     * @return how many windows carry a pick
     */
    private int selfCheck(int expectedForecastRows, int expectedCachedEntries) {
        DailyBriefingResponse served = briefingService.getCachedBriefingForApi();
        if (served == null) {
            throw new IllegalStateException("Self-check: the served briefing is empty");
        }
        long windowsWithPick = served.days().stream()
                .flatMap(day -> day.eventSummaries().stream())
                .map(BriefingEventSummary::window)
                .filter(w -> w != null && w.pick() != null)
                .count();
        boolean best = served.days().stream()
                .flatMap(day -> day.eventSummaries().stream())
                .map(BriefingEventSummary::window)
                .anyMatch(w -> w != null && w.pick() != null && w.pick().kind() == BriefingWindow.PickKind.BEST);
        if (!best) {
            throw new IllegalStateException("Self-check: no window in the served briefing carries a BEST pick");
        }
        long forecastRows = forecastEvaluationRepository.count();
        if (forecastRows != expectedForecastRows) {
            throw new IllegalStateException("Self-check: " + forecastRows + " forecast_evaluation rows read back,"
                    + " expected " + expectedForecastRows);
        }
        if (briefingCacheRepository.findById(1).isEmpty()) {
            throw new IllegalStateException("Self-check: daily_briefing_cache has no row");
        }
        Map<String, Integer> cachedPerRegion = new HashMap<>();
        LocalDate today = ForecastHorizon.today(clock);
        for (String region : REGIONS) {
            cachedPerRegion.put(region, evaluationService.getCachedScores(region, today, TargetType.SUNRISE).size());
        }
        if (cachedPerRegion.values().stream().anyMatch(n -> n == 0)) {
            throw new IllegalStateException("Self-check: a region has no cached evaluation today (expected "
                    + expectedCachedEntries + " entries in all): " + cachedPerRegion);
        }
        return (int) windowsWithPick;
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static Spot coast(String region, String name, double lat, double lon, Set<TideType> tides,
            TideRole role) {
        return new Spot(name, region, lat, lon, LocationType.SEASCAPE, tides, role);
    }

    private static Spot inland(String region, String name, double lat, double lon) {
        return new Spot(name, region, lat, lon, LocationType.LANDSCAPE, Set.of(), TideRole.NONE);
    }
}
