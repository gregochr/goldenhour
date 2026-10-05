package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.SolarEventType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideExtremeEntity;
import com.gregochr.goldenhour.entity.TideExtremeType;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEvaluationResult;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.RegionRepository;
import com.gregochr.goldenhour.repository.TideExtremeRepository;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
import com.gregochr.goldenhour.service.BriefingService;
import com.gregochr.goldenhour.service.PlanWindowProjector;
import com.gregochr.goldenhour.service.SolarEventFreshness;
import com.gregochr.goldenhour.service.evaluation.CacheKeyFactory;
import com.gregochr.goldenhour.service.evaluation.RatingValidator;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Gives a <b>local</b> app a rich, rated Ask state with no Claude call and no forecast run, so the
 * whole Ask surface can be looked at on a laptop (plan §9).
 *
 * <p><b>Impossible to run in production, in three independent ways.</b>
 * <ol>
 *   <li>The bean exists only under the {@code local} profile and never under {@code prod}
 *       ({@code @Profile("local & !prod")}) <em>and</em> only when
 *       {@code photocast.ask.seed-local-fixture} is exactly {@code true} (a missing or odd value is
 *       off). A production deployment has neither.</li>
 *   <li>At runtime the seeder re-checks the active profile and <b>refuses</b> (an
 *       {@link IllegalStateException} that stops startup) unless {@code local} is active.</li>
 *   <li>At runtime it also refuses unless the datasource really is H2 (the database product name,
 *       not a URL guess). The local profile is H2 by construction; a Postgres behind it is a
 *       misconfiguration this must never write into.</li>
 * </ol>
 * Seeding itself is best effort: a failure after those checks (Open-Meteo unreachable, say) is
 * logged and never stops the application.
 *
 * <p><b>What it writes</b> (all idempotent, run again and nothing doubles):
 * <ul>
 *   <li>three regions and their locations, through the repositories — nothing seeds a local database
 *       with regions or locations (the {@code forecast.locations} block in
 *       {@code application-local.yml} is bound by nothing), so the fixture brings its own:
 *       <b>Fixture Northumberland Coast</b> (seven sky locations — three of them coastal — and a
 *       wood), <b>Fixture Lake District</b> (six) and <b>Fixture Yorkshire Dales</b> (eight);</li>
 *   <li>rated entries for the next {@value #WINDOWS} upcoming solar windows, written through
 *       {@link BriefingEvaluationService#mergeFromBatch} and {@code mergeWoodlandFromBatch}, the
 *       service the batch pipeline writes {@code cached_evaluation} through — never the table or its
 *       JSON by hand. They are read back by the Plan tab's own serve-time re-enrichment, so they
 *       reach {@code GET /api/briefing} like a real rating;</li>
 *   <li>synthetic tide extremes for the coastal fixture locations (below);</li>
 *   <li>a briefing build ({@link BriefingService#refreshBriefingIfIdle}), when the cached briefing
 *       does not already hold today with every fixture location.</li>
 * </ul>
 *
 * <p><b>How the verdict sample gate is satisfied</b> ({@code VerdictSampleGate}: at least
 * {@value com.gregochr.goldenhour.service.VerdictSampleGate#MIN_RATED} rated voting slots <em>and</em>
 * examined slots covering at least half the voting roster): the two eligible regions rate every one
 * of their sky locations, seven of seven and six of six. The third region is <b>deliberately
 * ineligible</b>: eight locations, one of them rated 4★, so it has a 4★ slot the sample gate must
 * refuse to crown. The wood is rated 5★ and is a canopy slot, which Ask never picks.
 *
 * <p><b>Tide.</b> Nothing local holds tide data (the WorldTides key is empty), so the fixture
 * writes synthetic semi-diurnal extremes (12 h 25 min period, 5.0 m and 0.6 m) for its three coastal
 * locations through {@link TideExtremeRepository}, anchored to the first upcoming window so that one
 * location has high water at the light and wants it (a match), one has high water at the light and
 * wants low (a miss with {@code tideState} HIGH and {@code tideAligned} false), and one has low water
 * at the light and wants it (a match). Extremes are only ever <em>added</em>, continuing the existing
 * series' phase when there is one, never overwritten, and only for fixture locations. They are
 * invented numbers for a local rehearsal and never mistaken for data: the locations are named
 * {@code (fixture)}.
 *
 * <p><b>It never deletes anything it did not create, and says how it recognises its own.</b> It
 * deletes nothing at all. It writes only regions named {@code Fixture …}, locations named
 * {@code … (fixture)} and the cache entries and tide extremes of those locations. A pre-existing
 * row with one of those names is reused untouched. Its rating entries are <em>merged</em> into the
 * region's cached entry ({@code mergeFromBatch} keeps every other location), never replacing it.
 *
 * <p><b>Known limit — no BEST BET / ALSO GOOD on the windows.</b> A window's forecast-wide pick
 * needs the region's Claude-written gloss headline ({@code PlanWindowProjector#candidate}), and the
 * fixture makes no Claude call, so locally {@code BriefingWindow.pick} is null and
 * {@code list_windows}'s {@code bestBet}/{@code alsoGood} are absent. The ratings, the verdicts, the
 * sample gate, tide and the Ask tools' picks are all real; only the pick is not.
 *
 * <p><b>Cost warning.</b> The briefing build it triggers makes the same calls a normal build does,
 * including the gloss and best-bet Claude calls <em>with whatever {@code ANTHROPIC_API_KEY} is
 * set</em>. With the placeholder key from {@code application-local.yml} they fail harmlessly; with
 * a real key they spend a few Haiku calls. That is why {@code seed-local-fixture} is off by default.
 */
@Component
@Profile("local & !prod")
@ConditionalOnProperty(name = "photocast.ask.seed-local-fixture", havingValue = "true")
public class AskLocalFixtureSeeder {

    private static final Logger LOG = LoggerFactory.getLogger(AskLocalFixtureSeeder.class);

    /** How many upcoming windows are rated: two days of sunrises and sunsets. */
    static final int WINDOWS = 4;

    /** The suffix every fixture location name carries: how the seeder recognises its own. */
    static final String LOCATION_SUFFIX = " (fixture)";

    /** The prefix every fixture region name carries. */
    static final String REGION_PREFIX = "Fixture ";

    /** Half a semi-diurnal tidal cycle: 12 h 25 min is 745 min, so an extreme every 372.5 min. */
    private static final long HALF_TIDE_CYCLE_SECONDS = 22_350L;

    private static final BigDecimal HIGH_WATER_METRES = new BigDecimal("5.000");
    private static final BigDecimal LOW_WATER_METRES = new BigDecimal("0.600");

    /** Extremes are generated this many half cycles before and after the anchor. */
    private static final int TIDE_BACK_HALF_CYCLES = 6;
    private static final int TIDE_FORWARD_HALF_CYCLES = 19;

    /** The series must reach this far past the first window for the later windows' ±2-day reads. */
    private static final long TIDE_COVERAGE_DAYS = 4;

    private static final String DATABASE = "H2";

    private final RegionRepository regionRepository;
    private final LocationRepository locationRepository;
    private final TideExtremeRepository tideExtremeRepository;
    private final BriefingEvaluationService evaluationService;
    private final BriefingService briefingService;
    private final AskSnapshotBuilder snapshotBuilder;
    private final SolarEventFreshness freshness;
    private final DataSource dataSource;
    private final Environment environment;
    private final Clock clock;

    /**
     * Creates the seeder.
     *
     * @param regionRepository      regions
     * @param locationRepository    locations
     * @param tideExtremeRepository stored tide extremes
     * @param evaluationService     the service the batch pipeline writes {@code cached_evaluation} through
     * @param briefingService       builds the briefing
     * @param snapshotBuilder       reads back what Ask now sees, for the summary line
     * @param freshness             the project's one answer to "now" and to solar event times
     * @param dataSource            the datasource, checked to be H2 before anything is written
     * @param environment           the environment, checked for the {@code local} profile
     * @param clock                 the application clock
     */
    public AskLocalFixtureSeeder(RegionRepository regionRepository,
            LocationRepository locationRepository, TideExtremeRepository tideExtremeRepository,
            BriefingEvaluationService evaluationService, BriefingService briefingService,
            AskSnapshotBuilder snapshotBuilder, SolarEventFreshness freshness,
            DataSource dataSource, Environment environment, Clock clock) {
        this.regionRepository = regionRepository;
        this.locationRepository = locationRepository;
        this.tideExtremeRepository = tideExtremeRepository;
        this.evaluationService = evaluationService;
        this.briefingService = briefingService;
        this.snapshotBuilder = snapshotBuilder;
        this.freshness = freshness;
        this.dataSource = dataSource;
        this.environment = environment;
        this.clock = clock;
    }

    /**
     * Seeds the fixture once the application is ready. Runs last, after the evaluation cache has
     * been rehydrated from the database, so its merges land on the persisted state.
     *
     * @throws IllegalStateException when the {@code local} profile is not active or the datasource
     *         is not H2: the refusal that stops a mis-set flag from ever writing to a real database
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void onApplicationReady() {
        requireLocalH2();
        try {
            seed();
        } catch (RuntimeException e) {
            LOG.warn("[ASK FIXTURE] Seeding stopped early, the application carries on: {}", e.toString());
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
                    "photocast.ask.seed-local-fixture is only for the local profile; refusing to seed");
        }
        String product;
        try (Connection connection = dataSource.getConnection()) {
            product = connection.getMetaData().getDatabaseProductName();
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "photocast.ask.seed-local-fixture could not confirm the database is H2; refusing to seed",
                    e);
        }
        if (!DATABASE.equalsIgnoreCase(product)) {
            throw new IllegalStateException("photocast.ask.seed-local-fixture refuses to write to a "
                    + product + " database: it is for the local H2 file only");
        }
    }

    /**
     * Writes the fixture. Package-private so a test can run it twice and compare.
     *
     * @return the one-line summary of what Ask now sees
     */
    String seed() {
        Map<String, RegionEntity> regions = new LinkedHashMap<>();
        for (String name : Fixture.REGION_NAMES) {
            regions.put(name, ensureRegion(name));
        }
        Map<String, LocationEntity> locations = new LinkedHashMap<>();
        for (Fixture.Spot spot : Fixture.SPOTS) {
            locations.put(spot.name(), ensureLocation(spot, regions.get(spot.region())));
        }
        List<Window> windows = upcomingWindows(locations.values().iterator().next());
        if (windows.isEmpty()) {
            LOG.warn("[ASK FIXTURE] No upcoming solar window to rate; nothing seeded beyond regions/locations");
            return "no upcoming windows";
        }
        int tide = 0;
        for (Fixture.Spot spot : Fixture.SPOTS) {
            if (spot.light() != Fixture.LightTide.NONE) {
                tide += ensureTide(locations.get(spot.name()), spot, windows.getFirst());
            }
        }
        int rated = writeRatings(windows);
        LOG.info("[ASK FIXTURE] {} regions, {} locations, {} rated entries over {} windows, {} tide extremes added",
                regions.size(), locations.size(), rated, windows.size(), tide);

        if (briefingCovers(locations.values())) {
            LOG.info("[ASK FIXTURE] The cached briefing already holds today with every fixture location; "
                    + "not rebuilding it");
        } else if (!briefingService.refreshBriefingIfIdle()) {
            LOG.warn("[ASK FIXTURE] A briefing build is already running; the fixture ratings reach "
                    + "the Plan tab through serve-time re-enrichment once it finishes");
        }
        String summary = summarise(snapshotBuilder.build());
        LOG.info("[ASK FIXTURE] {}", summary);
        return summary;
    }

    // -- regions and locations ----------------------------------------------------------------

    private RegionEntity ensureRegion(String name) {
        return regionRepository.findByName(name).orElseGet(() -> regionRepository.save(
                RegionEntity.builder().name(name).enabled(true).createdAt(utcNow()).build()));
    }

    private LocationEntity ensureLocation(Fixture.Spot spot, RegionEntity region) {
        return locationRepository.findByName(spot.name()).orElseGet(() -> {
            LocationEntity entity = LocationEntity.builder()
                    .name(spot.name())
                    .lat(spot.lat())
                    .lon(spot.lon())
                    .solarEventType(new HashSet<>(Set.of(SolarEventType.SUNRISE, SolarEventType.SUNSET)))
                    .locationType(new HashSet<>(spot.types()))
                    .tideType(new HashSet<>(spot.tides()))
                    .region(region)
                    .createdAt(utcNow())
                    .build();
            return locationRepository.save(entity);
        });
    }

    // -- windows ------------------------------------------------------------------------------

    /** One upcoming solar window. */
    record Window(LocalDate date, TargetType type, LocalDateTime time) {
    }

    /**
     * The next {@value #WINDOWS} solar events that have not passed, by the Plan tab's own elapsed
     * test ({@link PlanWindowProjector#hasPassed}), timed at one reference location. A few minutes'
     * difference between two UK locations is not worth a per-location window set.
     */
    private List<Window> upcomingWindows(LocationEntity reference) {
        LocalDate today = ForecastHorizon.today(clock);
        LocalDateTime now = freshness.now();
        List<Window> all = new ArrayList<>();
        for (LocalDate date = today.minusDays(1); !date.isAfter(today.plusDays(3)); date = date.plusDays(1)) {
            for (TargetType type : List.of(TargetType.SUNRISE, TargetType.SUNSET)) {
                LocalDateTime time = freshness.eventTime(reference, date, type);
                if (time != null && !PlanWindowProjector.hasPassed(time, now)) {
                    all.add(new Window(date, type, time));
                }
            }
        }
        all.sort(Comparator.comparing(Window::time));
        return all.stream().limit(WINDOWS).toList();
    }

    // -- ratings ------------------------------------------------------------------------------

    /** Writes every fixture rating for the windows; returns how many (location, window) entries. */
    private int writeRatings(List<Window> windows) {
        int written = 0;
        for (String regionName : Fixture.REGION_NAMES) {
            for (int w = 0; w < windows.size(); w++) {
                Window window = windows.get(w);
                String key = CacheKeyFactory.build(regionName, window.date(), window.type());
                List<BriefingEvaluationResult> sky = new ArrayList<>();
                List<BriefingEvaluationResult> wood = new ArrayList<>();
                for (Fixture.Spot spot : Fixture.SPOTS) {
                    // A spot with no rating for this window (unrated, or a table shorter than the window
                    // count) is simply not written: never an index past the table's end.
                    if (!spot.region().equals(regionName) || w >= spot.ratings().size()) {
                        continue;
                    }
                    BriefingEvaluationResult result = result(spot, spot.ratings().get(w));
                    if (spot.canopy()) {
                        if (window.type() == TargetType.SUNRISE) {
                            wood.add(result);
                        }
                    } else {
                        sky.add(result);
                    }
                }
                if (!sky.isEmpty()) {
                    evaluationService.mergeFromBatch(key, sky);
                    written += sky.size();
                }
                if (!wood.isEmpty()) {
                    evaluationService.mergeWoodlandFromBatch(key, wood);
                    written += wood.size();
                }
            }
        }
        return written;
    }

    private static BriefingEvaluationResult result(Fixture.Spot spot, int rating) {
        int fiery = rating * 18;
        int golden = Math.min(100, rating * 18 + 6);
        return new BriefingEvaluationResult(spot.name(), rating, fiery, golden,
                "Fixture rating for local rehearsal: " + rating + " stars. Not a forecast.",
                null, null, "Fixture data, not a forecast", null, spot.canopy() ? null : rating);
    }

    // -- tide ---------------------------------------------------------------------------------

    /**
     * Adds synthetic tide extremes for one coastal fixture location where the series does not
     * already reach {@value #TIDE_COVERAGE_DAYS} days past the first window. Continues the stored
     * series' phase when there is one; otherwise anchors it to the first window. Returns how many
     * rows were added.
     */
    private int ensureTide(LocationEntity location, Fixture.Spot spot, Window first) {
        LocalDateTime light = freshness.eventTime(location, first.date(), first.type());
        if (light == null) {
            return 0;
        }
        LocalDateTime wanted = light.plusDays(TIDE_COVERAGE_DAYS);
        List<TideExtremeEntity> stored = tideExtremeRepository
                .findByLocationIdAndEventTimeBetweenOrderByEventTimeAsc(location.getId(),
                        light.minusDays(60), light.plusDays(60));
        LocalDateTime start;
        TideExtremeType startType;
        if (stored.isEmpty()) {
            TideExtremeType atLight = spot.light() == Fixture.LightTide.HIGH_AT_LIGHT
                    ? TideExtremeType.HIGH : TideExtremeType.LOW;
            start = light.minusSeconds(HALF_TIDE_CYCLE_SECONDS * TIDE_BACK_HALF_CYCLES);
            startType = TIDE_BACK_HALF_CYCLES % 2 == 0 ? atLight : opposite(atLight);
        } else {
            TideExtremeEntity last = stored.getLast();
            if (!last.getEventTime().isBefore(wanted)) {
                return 0;
            }
            start = last.getEventTime().plusSeconds(HALF_TIDE_CYCLE_SECONDS);
            startType = opposite(last.getType());
        }
        LocalDateTime end = light.plusSeconds(HALF_TIDE_CYCLE_SECONDS * TIDE_FORWARD_HALF_CYCLES);
        List<TideExtremeEntity> added = new ArrayList<>();
        LocalDateTime fetchedAt = utcNow();
        TideExtremeType type = startType;
        for (LocalDateTime at = start; !at.isAfter(end); at = at.plusSeconds(HALF_TIDE_CYCLE_SECONDS)) {
            added.add(TideExtremeEntity.builder().locationId(location.getId()).eventTime(at)
                    .heightMetres(type == TideExtremeType.HIGH ? HIGH_WATER_METRES : LOW_WATER_METRES)
                    .type(type).fetchedAt(fetchedAt).build());
            type = opposite(type);
        }
        tideExtremeRepository.saveAll(added);
        return added.size();
    }

    private static TideExtremeType opposite(TideExtremeType type) {
        return type == TideExtremeType.HIGH ? TideExtremeType.LOW : TideExtremeType.HIGH;
    }

    // -- briefing and summary -----------------------------------------------------------------

    /**
     * Whether the cached briefing already holds today and every fixture location in some slot, so a
     * restart does not repeat a build (a weather fetch, and Claude calls with a real key).
     */
    private boolean briefingCovers(Iterable<LocationEntity> fixtureLocations) {
        List<BriefingDay> days = briefingService.getCachedDays();
        if (days == null || days.isEmpty()) {
            return false;
        }
        LocalDate today = ForecastHorizon.today(clock);
        if (days.stream().noneMatch(d -> today.equals(d.date()))) {
            return false;
        }
        Set<String> present = new HashSet<>();
        days.forEach(d -> d.eventSummaries().forEach(s -> s.regions().forEach(
                r -> r.slots().forEach(slot -> present.add(slot.locationName())))));
        for (LocationEntity location : fixtureLocations) {
            if (!present.contains(location.getName())) {
                return false;
            }
        }
        return true;
    }

    /**
     * One line saying what the Ask snapshot now holds: the windows, and per window the regions with
     * their pick-eligible candidate counts ({@code -} for a region the sample gate refuses).
     *
     * @param snapshot what Ask reads, or empty when no briefing exists
     * @return the summary
     */
    static String summarise(Optional<AskSnapshot> snapshot) {
        if (snapshot.isEmpty()) {
            return "Ask snapshot: no briefing has been built, so no windows";
        }
        AskSnapshot s = snapshot.get();
        StringBuilder out = new StringBuilder("Ask snapshot: ").append(s.windows().size())
                .append(" windows");
        for (AskSnapshot.Window w : s.windows()) {
            out.append(" | ").append(w.id()).append(": ");
            List<String> parts = new ArrayList<>();
            for (AskSnapshot.Region r : w.regions()) {
                parts.add(r.name() + " " + regionState(r));
            }
            out.append(String.join(", ", parts));
        }
        return out.toString();
    }

    /**
     * What Ask can take from one region: its pick-eligible slot count, or, when the sample gate
     * refuses the region, how many 3-star-or-better sky slots it is holding back (zero says the
     * region simply has no ratings).
     */
    private static String regionState(AskSnapshot.Region region) {
        if (region.verdictEligible()) {
            long eligible = region.slots().stream()
                    .filter(slot -> AskSnapshot.isPickEligible(region, slot)).count();
            return eligible + " eligible";
        }
        long heldBack = region.slots().stream()
                .filter(slot -> !slot.canopy() && RatingValidator.isInRange(slot.rating())
                        && slot.rating() >= AskSnapshot.MIN_PICK_RATING)
                .count();
        return heldBack == 0 ? "unrated" : "refused by the sample gate (" + heldBack + " rated 3★+ held back)";
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /** The fixture's catalogue: who, where, how each is rated, and how the tide sits. */
    static final class Fixture {

        /** How a coastal spot's tide sits at the first window's light. */
        enum LightTide {
            /** Inland: no tide. */
            NONE,
            /** High water at the light. */
            HIGH_AT_LIGHT,
            /** Low water at the light. */
            LOW_AT_LIGHT
        }

        /**
         * One fixture location.
         *
         * @param name    the name, always ending {@value AskLocalFixtureSeeder#LOCATION_SUFFIX}
         * @param region  the region name
         * @param lat     latitude
         * @param lon     longitude
         * @param types   location types
         * @param tides   the tide states the location wants; empty for inland
         * @param light   how the synthetic tide sits at the first window's light
         * @param ratings the rating per window, in window order; empty means unrated
         */
        record Spot(String name, String region, double lat, double lon, Set<LocationType> types,
                Set<TideType> tides, LightTide light, List<Integer> ratings) {

            boolean canopy() {
                return types.equals(Set.of(LocationType.WOODLAND));
            }
        }

        static final String COAST = REGION_PREFIX + "Northumberland Coast";
        static final String LAKES = REGION_PREFIX + "Lake District";
        static final String DALES = REGION_PREFIX + "Yorkshire Dales";

        static final List<String> REGION_NAMES = List.of(COAST, LAKES, DALES);

        static final List<Spot> SPOTS = List.of(
                sky(COAST, "Cheviot Edge", 55.478, -2.145, List.of(3, 4, 5, 3)),
                sky(COAST, "Kielder Water", 55.234, -2.588, List.of(3, 3, 4, 4)),
                sky(COAST, "Hadrian's Wall Crags", 55.012, -2.378, List.of(2, 3, 3, 4)),
                sky(COAST, "Simonside Ridge", 55.300, -1.950, List.of(4, 3, 4, 3)),
                shore(COAST, "Bamburgh Beach", 55.609, -1.709, TideType.HIGH,
                        LightTide.HIGH_AT_LIGHT, List.of(5, 4, 4, 3)),
                shore(COAST, "Dunstanburgh Shore", 55.489, -1.593, TideType.LOW,
                        LightTide.HIGH_AT_LIGHT, List.of(4, 4, 3, 3)),
                shore(COAST, "Craster Rocks", 55.472, -1.597, TideType.LOW,
                        LightTide.LOW_AT_LIGHT, List.of(4, 5, 3, 4)),
                new Spot("Hollin Wood" + LOCATION_SUFFIX, COAST, 54.962, -2.358,
                        Set.of(LocationType.WOODLAND), Set.of(), LightTide.NONE, List.of(5, 5, 5, 5)),
                sky(LAKES, "Catbells", 54.568, -3.167, List.of(4, 5, 4, 4)),
                sky(LAKES, "Derwentwater Shore", 54.587, -3.140, List.of(5, 4, 5, 3)),
                sky(LAKES, "Buttermere", 54.541, -3.277, List.of(3, 4, 4, 5)),
                sky(LAKES, "Haystacks", 54.513, -3.284, List.of(4, 3, 5, 4)),
                sky(LAKES, "Castlerigg Circle", 54.603, -3.098, List.of(3, 3, 4, 4)),
                sky(LAKES, "Blencathra", 54.643, -3.050, List.of(4, 4, 3, 3)),
                sky(DALES, "Malham Cove", 54.072, -2.160, List.of(4, 4, 4, 4)),
                sky(DALES, "Gordale Scar", 54.078, -2.120, List.of()),
                sky(DALES, "Ingleborough", 54.164, -2.398, List.of()),
                sky(DALES, "Whernside", 54.227, -2.411, List.of()),
                sky(DALES, "Pen-y-ghent", 54.153, -2.249, List.of()),
                sky(DALES, "Hardraw Force", 54.318, -2.297, List.of()),
                sky(DALES, "Buckden Pike", 54.206, -2.061, List.of()),
                sky(DALES, "Simon's Seat", 54.017, -1.920, List.of()));

        private Fixture() {
        }

        private static Spot sky(String region, String name, double lat, double lon, List<Integer> ratings) {
            return new Spot(name + LOCATION_SUFFIX, region, lat, lon, Set.of(LocationType.LANDSCAPE),
                    Set.of(), LightTide.NONE, ratings);
        }

        private static Spot shore(String region, String name, double lat, double lon, TideType wants,
                LightTide light, List<Integer> ratings) {
            return new Spot(name + LOCATION_SUFFIX, region, lat, lon, Set.of(LocationType.SEASCAPE),
                    Set.of(wants), light, ratings);
        }
    }
}
