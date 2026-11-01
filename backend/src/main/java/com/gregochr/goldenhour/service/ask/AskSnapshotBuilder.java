package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.AstroConditionsEntity;
import com.gregochr.goldenhour.entity.AuroraForecastResultEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.LocationTideFact;
import com.gregochr.goldenhour.model.PlanRenderedEvent;
import com.gregochr.goldenhour.model.comingup.ComingUpCoincidenceLine;
import com.gregochr.goldenhour.model.comingup.ComingUpEntry;
import com.gregochr.goldenhour.repository.AstroConditionsRepository;
import com.gregochr.goldenhour.repository.AuroraForecastResultRepository;
import com.gregochr.goldenhour.service.AlmanacService;
import com.gregochr.goldenhour.service.AstroConditionsService;
import com.gregochr.goldenhour.service.BriefingService;
import com.gregochr.goldenhour.service.EclipseAlmanacSource;
import com.gregochr.goldenhour.service.EclipseHotTopicStrategy;
import com.gregochr.goldenhour.service.PlanWindowProjector;
import com.gregochr.goldenhour.service.SolarEventFreshness;
import com.gregochr.goldenhour.service.TravelDayService;
import com.gregochr.goldenhour.service.aurora.AuroraForecastRunService;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import com.gregochr.goldenhour.util.ForecastHorizon;
import com.gregochr.goldenhour.util.Rewind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Builds the {@link AskSnapshot} (plan §2.2) from {@link BriefingService#getCachedBriefingForApi()}
 * and nothing else about ratings.
 *
 * <p><b>The window set</b> is the events listed in the served briefing's {@code renderedEvents} (the
 * Plan tab's six; every summary carries a {@code window()}, so the cap is {@code renderedEvents}
 * and not window presence) whose {@code window()} is non-null, that
 * {@link PlanWindowProjector#hasPassed} has not retired, that are solar, and that are not travel
 * days. The elapsed test is the shared projector method, so Ask and the Plan tab retire a window
 * at the same minute.
 *
 * <p><b>Travel days are not marked on the served briefing.</b> A travel day still carries a window
 * and slots; the Plan client learns which dates are away from a separate {@code /api/travel-days}
 * fetch. The server-side test is {@link TravelDayService#isTravelDay}, the same one
 * {@code BriefingRollupBuilder} applies before the best-bet advisor sees a date, so it is used
 * here, once per distinct date.
 *
 * <p><b>The nights</b> (ask-night plan §2.1) are tonight's and tomorrow night's astro conditions and
 * aurora results, read from the same rows the Map's night rows read. "Tonight" is the poller's night,
 * {@link AuroraForecastRunService#currentNightDate()} — before nautical dawn that is yesterday's date,
 * the night still running — never the UK civil date. The ASTRO rows come through a query that fetch-joins
 * the location and its region: the Ready precompute builds a snapshot on the background executor
 * outside any transaction, where a lazily-loaded location is a {@code LazyInitializationException} and
 * no night answer would ever be written. A row becomes a spot only if its location is enabled, has a
 * Bortle class and a region (the briefing never walks {@code unregioned()}) and is not canopy-only (a
 * wood's star means the opposite of a sky star), and — for ASTRO — only if it belongs to the night's
 * latest generation: {@code AstroConditionsService.evaluateAndPersist} stamps one instant per run, a
 * run that fails for some places leaves their rows from the previous afternoon beside the fresh ones,
 * and a date filter alone would rank a day-old clear-sky forecast with today's. The night read is
 * fail-soft: a failure there leaves a snapshot with no nights, never none at all.
 *
 * <p>{@link #current()} memoises the snapshot for {@value #MEMO_SECONDS} seconds: the assembly it
 * reads is the full Plan-tab one and is not cheap. The memo is also keyed on the identity of the served
 * briefing it was built from ({@code BriefingService.getCachedBriefingForApi}, itself memoised and
 * invalidated by the serve epoch), so a rating write or a simulation toggle reaches Ask as soon as the
 * served briefing changes, not up to {@value #MEMO_SECONDS} seconds later. The TTL stays because the
 * snapshot also reads travel days, the almanac, the night forecasts and the aurora state.
 */
@Service
public class AskSnapshotBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(AskSnapshotBuilder.class);

    /** How long {@link #current()} reuses a snapshot. */
    static final int MEMO_SECONDS = 30;

    private static final Comparator<AskSnapshot.Window> CHRONOLOGICAL =
            Comparator.comparing(AskSnapshot.Window::date)
                    .thenComparing(AskSnapshot.Window::targetType);

    private final BriefingService briefingService;
    private final TravelDayService travelDayService;
    private final AlmanacService almanacService;
    private final SolarEventFreshness freshness;
    private final Clock clock;
    private final AstroConditionsRepository astroRepository;
    private final AuroraForecastResultRepository auroraRepository;
    private final AstroConditionsService astroService;
    private final AuroraForecastRunService nightSelector;
    private final AuroraStateCache auroraStateCache;
    private final AskAreas areas;

    private Instant memoBuiltAt;
    private AskSnapshot memo;
    private DailyBriefingResponse memoSource;

    /**
     * Creates the builder.
     *
     * @param briefingService  the source of the served briefing
     * @param travelDayService the away-date test
     * @param almanacService   the source of the "Coming up" entries
     * @param freshness        the project's single answer to "now" for solar events
     * @param clock            the application clock, for the memo and the UK civil date
     * @param astroRepository  the astro conditions rows, read with their location fetched
     * @param auroraRepository the stored (non-simulated) aurora results, read with their location fetched
     * @param astroService     resolves the window a stored astro row was scored over
     * @param nightSelector    the one owner of "which night is current"
     * @param auroraStateCache the aurora alert state, reported for tonight only and never while simulated
     */
    public AskSnapshotBuilder(BriefingService briefingService, TravelDayService travelDayService,
            AlmanacService almanacService, SolarEventFreshness freshness, Clock clock,
            AstroConditionsRepository astroRepository, AuroraForecastResultRepository auroraRepository,
            AstroConditionsService astroService, AuroraForecastRunService nightSelector,
            AuroraStateCache auroraStateCache) {
        this(briefingService, travelDayService, almanacService, freshness, clock, astroRepository,
                auroraRepository, astroService, nightSelector, auroraStateCache, () -> AskAreas.Names.NONE);
    }

    /**
     * Creates the builder, with the named areas and location aliases Ask understands.
     *
     * @param briefingService  the source of the served briefing
     * @param travelDayService the away-date test
     * @param almanacService   the source of the "Coming up" entries
     * @param freshness        the project's single answer to "now" for solar events
     * @param clock            the application clock, for the memo and the UK civil date
     * @param astroRepository  the astro conditions rows, read with their location fetched
     * @param auroraRepository the stored (non-simulated) aurora results, read with their location fetched
     * @param astroService     resolves the window a stored astro row was scored over
     * @param nightSelector    the one owner of "which night is current"
     * @param auroraStateCache the aurora alert state, reported for tonight only and never while simulated
     * @param areas            the named areas and location aliases, read once per build
     */
    @Autowired
    public AskSnapshotBuilder(BriefingService briefingService, TravelDayService travelDayService,
            AlmanacService almanacService, SolarEventFreshness freshness, Clock clock,
            AstroConditionsRepository astroRepository, AuroraForecastResultRepository auroraRepository,
            AstroConditionsService astroService, AuroraForecastRunService nightSelector,
            AuroraStateCache auroraStateCache, AskAreas areas) {
        this.briefingService = briefingService;
        this.travelDayService = travelDayService;
        this.almanacService = almanacService;
        this.freshness = freshness;
        this.clock = clock;
        this.astroRepository = astroRepository;
        this.auroraRepository = auroraRepository;
        this.astroService = astroService;
        this.nightSelector = nightSelector;
        this.auroraStateCache = auroraStateCache;
        this.areas = areas;
    }

    /**
     * The snapshot, reused for {@value #MEMO_SECONDS} seconds.
     *
     * <p><b>A rewound request never touches the memo.</b> While an admin's rewind is active
     * ({@link Rewind#isActive()}, the same test {@code AlmanacService} makes for its day cache) the
     * clock bean answers with the rewound instant, so the snapshot is built fresh for that moment and
     * the memo is neither read, which would serve the live snapshot to a view that is meant to be in
     * the past, nor written, which would serve the rewound one to every reader after it.
     *
     * <p>The memo's age test is also correct on its own terms: an age that is negative (the clock has
     * gone backwards) is never "younger than {@value #MEMO_SECONDS} seconds", so it rebuilds.
     *
     * @return the snapshot, or empty when no briefing has been built yet
     */
    public synchronized Optional<AskSnapshot> current() {
        if (Rewind.isActive()) {
            return build();
        }
        Instant now = clock.instant();
        // Cheap on a served-briefing memo hit; a different object means the served briefing changed
        // (a new build, or a serve-epoch bump), so the Ask snapshot built from the old one is stale.
        DailyBriefingResponse briefing = briefingService.getCachedBriefingForApi();
        if (briefing == null) {
            return Optional.empty();
        }
        if (memo != null && memoSource == briefing && isFresh(Duration.between(memoBuiltAt, now))) {
            return Optional.of(memo);
        }
        Optional<AskSnapshot> built = build(briefing);
        built.ifPresent(snapshot -> {
            memo = snapshot;
            memoBuiltAt = now;
            memoSource = briefing;
        });
        return built;
    }

    /** Whether a memo of this age may be reused: not negative, and under {@value #MEMO_SECONDS} seconds. */
    private static boolean isFresh(Duration age) {
        return !age.isNegative() && age.compareTo(Duration.ofSeconds(MEMO_SECONDS)) < 0;
    }

    /**
     * Builds a fresh snapshot, bypassing the memo.
     *
     * @return the snapshot, or empty when no briefing has been built yet
     */
    public Optional<AskSnapshot> build() {
        DailyBriefingResponse briefing = briefingService.getCachedBriefingForApi();
        if (briefing == null) {
            return Optional.empty();
        }
        return build(briefing);
    }

    private Optional<AskSnapshot> build(DailyBriefingResponse briefing) {
        LocalDateTime now = freshness.now();
        Set<AskWindowKey> rendered = renderedKeys(briefing);
        Map<LocalDate, Boolean> travel = new HashMap<>();
        List<AskSnapshot.Window> windows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (BriefingDay day : briefing.days()) {
            if (day == null || day.date() == null) {
                continue;
            }
            for (BriefingEventSummary summary : day.eventSummaries()) {
                AskSnapshot.Window window = toWindow(day.date(), summary, now, travel, rendered);
                if (window != null && seen.add(window.id())) {
                    windows.add(window);
                }
            }
        }
        windows.sort(CHRONOLOGICAL);
        LocalDateTime generatedAt = briefing.generatedAt();
        AskAreas.Names names = areaNames();
        return Optional.of(new AskSnapshot(generatedAt, AskClock.londonHHmm(generatedAt),
                ForecastHorizon.today(clock), windows, topics(briefing), comingUp(), briefing.stale(),
                nights(), names.areas(), names.locationAliases()));
    }

    /** The named areas and location aliases, or none when they cannot be read: Ask must still answer. */
    private AskAreas.Names areaNames() {
        try {
            AskAreas.Names names = areas.load();
            return names == null ? AskAreas.Names.NONE : names;
        } catch (RuntimeException e) {
            LOG.warn("[ASK] Could not read the named areas and aliases; building the snapshot without them: {}",
                    e.toString());
            return AskAreas.Names.NONE;
        }
    }

    // -- the nights ---------------------------------------------------------------------------

    /**
     * Tonight's and tomorrow night's forecasts, or none when the read fails: the nights are an extra
     * surface, and Ask still answers about the solar windows without them.
     */
    private AskSnapshot.Nights nights() {
        try {
            return buildNights();
        } catch (RuntimeException e) {
            LOG.warn("[ASK] Could not read the night forecasts for the snapshot: {}", e.toString());
            return AskSnapshot.Nights.NONE;
        }
    }

    private AskSnapshot.Nights buildNights() {
        LocalDate tonight = nightSelector.currentNightDate();
        List<AskSnapshot.Night> nights = new ArrayList<>();
        for (LocalDate date : List.of(tonight, tonight.plusDays(1))) {
            Optional<AskSnapshot.Night> astro = astroNight(date);
            astro.ifPresent(nights::add);
            auroraNight(date, astro.orElse(null)).ifPresent(nights::add);
        }
        return new AskSnapshot.Nights(tonight, nights, auroraState());
    }

    /**
     * The ASTRO night for a date: the rows of its latest generation whose places are eligible. The
     * generation is the maximum {@code run_timestamp} over the date's enabled, Bortle-enriched rows (the
     * query's own filter, repeated here because the rule is the builder's), so a stale row beside fresh
     * ones is never a spot.
     */
    private Optional<AskSnapshot.Night> astroNight(LocalDate date) {
        List<AstroConditionsEntity> rows = astroRepository.findByForecastDateFetchingLocation(date).stream()
                .filter(r -> r.getLocation() != null && r.getLocation().isEnabled()
                        && r.getLocation().getBortleClass() != null)
                .toList();
        Optional<Instant> generation = rows.stream().map(AstroConditionsEntity::getRunTimestamp)
                .max(Comparator.naturalOrder());
        if (generation.isEmpty()) {
            return Optional.empty();
        }
        List<AstroConditionsEntity> latest = rows.stream()
                .filter(r -> generation.get().equals(r.getRunTimestamp()))
                .filter(r -> isNightSpot(r.getLocation()))
                .toList();
        if (latest.isEmpty()) {
            return Optional.empty();
        }
        AstroConditionsService.NightWindow window = astroService.resolveNightWindow(latest.getFirst());
        List<AskSnapshot.NightSpot> spots = latest.stream().map(AskSnapshotBuilder::astroSpot).toList();
        return Optional.of(new AskSnapshot.Night(AskWindowId.format(date, AskWindowKind.ASTRO), date,
                AskWindowKind.ASTRO, window.start(), window.end(), generation.get(), spots));
    }

    /**
     * The AURORA night for a date: the stored non-simulated results whose places are eligible, each
     * carrying its astro figures beside it when the same place has a spot on the date's astro night.
     * The aurora entity holds no window of its own, so the night's window is the astro night's, or none.
     */
    private Optional<AskSnapshot.Night> auroraNight(LocalDate date, AskSnapshot.Night astro) {
        List<AuroraForecastResultEntity> rows = auroraRepository
                .findByForecastDateAndSimulatedFalseFetchingLocation(date).stream()
                .filter(r -> r.getLocation() != null && r.getLocation().isEnabled()
                        && r.getLocation().getBortleClass() != null && isNightSpot(r.getLocation()))
                .toList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<Long, AskSnapshot.NightSpot> astroByLocation = new LinkedHashMap<>();
        if (astro != null) {
            astro.spots().forEach(s -> astroByLocation.put(s.locationId(), s));
        }
        List<AskSnapshot.NightSpot> spots = rows.stream()
                .map(r -> auroraSpot(r, astroByLocation.get(r.getLocation().getId())))
                .toList();
        Instant generation = rows.stream().map(AuroraForecastResultEntity::getRunTimestamp)
                .max(Comparator.naturalOrder()).orElse(null);
        return Optional.of(new AskSnapshot.Night(AskWindowId.format(date, AskWindowKind.AURORA), date,
                AskWindowKind.AURORA, astro == null ? null : astro.start(),
                astro == null ? null : astro.end(), generation, spots));
    }

    /**
     * Whether a place may be a night spot: a region to name it under (the briefing never walks
     * {@code unregioned()}, and the admin form lets a location have none) and not canopy-only (a wood's
     * star means the opposite of a sky star). Enabled and Bortle are the caller's, which have already
     * filtered on them.
     */
    private static boolean isNightSpot(LocationEntity location) {
        return location.getRegion() != null && location.getRegion().getName() != null
                && !location.isWoodlandOnly();
    }

    private static AskSnapshot.NightSpot astroSpot(AstroConditionsEntity r) {
        LocationEntity l = r.getLocation();
        return new AskSnapshot.NightSpot(l.getId(), l.getName(), l.getRegion().getName(), l.getLat(),
                l.getLon(), l.isWoodlandOnly(), l.getBortleClass(), l.getSkyBrightnessSqm(), r.getStars(),
                r.getSummary(), r.getCloudExplanation(), r.getVisibilityExplanation(),
                r.getMoonExplanation(), r.getMoonPhase(), r.getMoonIlluminationPct(), null, null, false,
                null, null, null);
    }

    private static AskSnapshot.NightSpot auroraSpot(AuroraForecastResultEntity r,
            AskSnapshot.NightSpot astro) {
        LocationEntity l = r.getLocation();
        return new AskSnapshot.NightSpot(l.getId(), l.getName(), l.getRegion().getName(), l.getLat(),
                l.getLon(), l.isWoodlandOnly(), l.getBortleClass(), l.getSkyBrightnessSqm(),
                astro == null ? null : astro.astroStars(), astro == null ? null : astro.astroSummary(),
                astro == null ? null : astro.cloudExplanation(),
                astro == null ? null : astro.visibilityExplanation(),
                astro == null ? null : astro.moonExplanation(), astro == null ? null : astro.moonPhase(),
                astro == null ? null : astro.moonIlluminationPct(), r.getStars(), r.getSummary(),
                r.isTriaged(), r.getTriageReason(), r.getAlertLevel(), r.getMaxKp());
    }

    /**
     * The aurora alert state for tonight, or null while an admin simulation is active (its Kp is fake,
     * and nothing of it may reach an answer). No active alert reads as {@code QUIET}, so a quiet night
     * with no result rows is reported as quiet rather than unknown.
     *
     * <p><b>The simulation check brackets the alert reads.</b> {@code AuroraStateCache}'s getters take no
     * lock, so a reader making several reads can straddle a transition, and the cache exposes no atomic
     * snapshot to read instead. An admin starting a simulation between the check and the level/Kp reads would
     * publish the fake alert into a snapshot memoised for {@value #MEMO_SECONDS} seconds and served to PRO
     * askers. The cache writes a new simulation before it writes the level, so a reader that has seen a fake
     * level is bound to see the simulation on a read made after it: the check is therefore made again once
     * the alert fields are in hand, and the state is discarded if a simulation is now active.
     */
    private AskSnapshot.AuroraState auroraState() {
        if (auroraStateCache.getSimulatedData() != null) {
            return null;
        }
        AlertLevel level = auroraStateCache.getCurrentLevel();
        Double kp = level == null ? null : auroraStateCache.getLastTriggerKp();
        if (auroraStateCache.getSimulatedData() != null) {
            return null;
        }
        return level == null ? new AskSnapshot.AuroraState(AlertLevel.QUIET.name(), null)
                : new AskSnapshot.AuroraState(level.name(), kp);
    }

    private AskSnapshot.Window toWindow(LocalDate date, BriefingEventSummary summary,
            LocalDateTime now, Map<LocalDate, Boolean> travel, Set<AskWindowKey> rendered) {
        if (summary == null || summary.window() == null || !isSolar(summary.targetType())
                || !rendered.contains(new AskWindowKey(date, summary.targetType()))) {
            return null;
        }
        BriefingWindow window = summary.window();
        if (PlanWindowProjector.hasPassed(window.eventTime(), now)) {
            return null;
        }
        if (travel.computeIfAbsent(date, travelDayService::isTravelDay)) {
            return null;
        }
        Map<String, LocationTideFact> facts = tideFactsByLocation(window);
        List<AskSnapshot.Region> regions = summary.regions().stream()
                .map(region -> toRegion(region, facts)).toList();
        return new AskSnapshot.Window(AskWindowId.format(date, summary.targetType()), date,
                summary.targetType(), window.eventTime(), window.verdict(), bestRatingOf(summary, window),
                window.pick(), regions);
    }

    /**
     * The window's best rating as Ask reports it: the served figure, except that a neighbour outlier is
     * left out of the very population the Plan projector takes it over, so {@code list_windows} never
     * says "up to 4★" beside a list whose best pick is 3★. Not derived from pick eligibility, which
     * would also drop verdict-ineligible regions and sub-floor slots. A window with no outlier keeps
     * the served figure untouched.
     */
    private static Integer bestRatingOf(BriefingEventSummary summary, BriefingWindow window) {
        boolean anyOutlier = summary.regions().stream().flatMap(r -> r.slots().stream())
                .anyMatch(s -> Boolean.TRUE.equals(s.neighbourOutlier()));
        return anyOutlier ? PlanWindowProjector.bestRatingWithoutOutliers(summary) : window.bestRating();
    }

    /** Key prefix for a fact joined by location id. */
    private static final String ID_KEY = "id:";

    /** Key prefix for a fact joined by location name. */
    private static final String NAME_KEY = "name:";

    /**
     * The window's tide facts keyed for the slot join, built once per window: by location id and, for
     * a legacy fact written without one, by name. The first fact for a key wins, as in the served
     * projection. A window with no {@code tideFacts} yields an empty map.
     */
    static Map<String, LocationTideFact> tideFactsByLocation(BriefingWindow window) {
        Map<String, LocationTideFact> facts = new HashMap<>();
        if (window == null || window.tideFacts() == null) {
            return facts;
        }
        for (LocationTideFact fact : window.tideFacts()) {
            if (fact == null) {
                continue;
            }
            if (fact.locationId() != null) {
                facts.putIfAbsent(ID_KEY + fact.locationId(), fact);
            }
            if (fact.locationName() != null) {
                facts.putIfAbsent(NAME_KEY + fact.locationName(), fact);
            }
        }
        return facts;
    }

    private static AskSnapshot.Region toRegion(BriefingRegion region, Map<String, LocationTideFact> facts) {
        return new AskSnapshot.Region(region.regionName(), region.displayVerdict(),
                region.meanRating(), region.verdictEligible(), region.pickEligible(),
                region.slots().stream().map(slot -> toSlot(slot, facts)).toList(),
                region.coverageReason());
    }

    /**
     * Joins a slot to its window's tide fact: by location id first, then by name. The slot's own
     * {@code tide()} is deliberately never read, because the served slot no longer carries one
     * (window-tide-facts plan §3.2); a slot with no fact has the tide fields of an inland location.
     */
    private static AskSnapshot.Slot toSlot(BriefingSlot slot, Map<String, LocationTideFact> facts) {
        LocationTideFact fact = null;
        if (slot.locationId() != null) {
            fact = facts.get(ID_KEY + slot.locationId());
        }
        if (fact == null && slot.locationName() != null) {
            fact = facts.get(NAME_KEY + slot.locationName());
        }
        return new AskSnapshot.Slot(slot.locationId(), slot.locationName(), slot.claudeRating(),
                slot.displayVerdict(), slot.claudeHeadline(), slot.canopy(),
                fact == null ? null : fact.tideState(),
                fact != null && Boolean.TRUE.equals(fact.tideAligned()),
                fact == null ? null : fact.tideFitPhrase(),
                Boolean.TRUE.equals(slot.neighbourOutlier()), slot.claudeSummary(), slot.skyRating(),
                slot.fierySkyPotential(), slot.goldenHourPotential(),
                fact == null ? null : fact.nearestSolarOffsetPhrase());
    }

    /** A rendered event's identity: its date and target type. */
    private record AskWindowKey(LocalDate date, TargetType targetType) {
    }

    /**
     * The events the Plan tab draws: {@code DailyBriefingResponse.renderedEvents}, which
     * {@code PlanWindowProjector} publishes on every served briefing (the leading six non-past
     * events). A null or empty list yields no keys, so a payload that was never projected offers
     * no windows rather than every window.
     */
    private static Set<AskWindowKey> renderedKeys(DailyBriefingResponse briefing) {
        List<PlanRenderedEvent> events = briefing.renderedEvents();
        if (events == null) {
            return Set.of();
        }
        return events.stream().map(e -> new AskWindowKey(e.date(), e.targetType()))
                .collect(java.util.stream.Collectors.toSet());
    }

    private static boolean isSolar(TargetType type) {
        return type == TargetType.SUNRISE || type == TargetType.SUNSET;
    }

    private static List<AskSnapshot.Topic> topics(DailyBriefingResponse briefing) {
        List<HotTopic> served = briefing.hotTopics();
        if (served == null) {
            return List.of();
        }
        return served.stream()
                .map(t -> new AskSnapshot.Topic(t.type(), t.label(), t.detail(), t.date(),
                        t.regions(), t.safetyNote(), t.eventType()))
                .toList();
    }

    /**
     * The warning Ask attaches to an almanac entry of this type: the solar eclipse's lens-filter
     * note, from the same constant the hot topic uses, and nothing for any other type. The lunar
     * eclipse (type {@code lunar-eclipse}) carries no safety warning, so it gets none.
     *
     * @param almanacType the entry's almanac type
     * @return the safety note, or null
     */
    static String safetyNoteFor(String almanacType) {
        return EclipseAlmanacSource.TYPE.equalsIgnoreCase(almanacType)
                ? EclipseHotTopicStrategy.SAFETY_NOTE : null;
    }

    private List<AskSnapshot.ComingUp> comingUp() {
        try {
            List<ComingUpEntry> entries = almanacService.getFeed(AlmanacService.DEFAULT_DAYS)
                    .entries();
            return entries.stream()
                    .map(e -> new AskSnapshot.ComingUp(e.type(), e.title(), e.startDate(),
                            e.endDate(), e.detail(), safetyNoteFor(e.type()),
                            e.coincidence().stream().map(ComingUpCoincidenceLine::type)
                                    .filter(Objects::nonNull).toList()))
                    .toList();
        } catch (RuntimeException e) {
            // The almanac is an extra surface; Ask still answers about windows without it.
            LOG.warn("[ASK] Could not read the Coming up feed for the snapshot: {}",
                    e.toString());
            return List.of();
        }
    }
}
