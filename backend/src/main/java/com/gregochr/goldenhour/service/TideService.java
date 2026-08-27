package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.WorldTidesProperties;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TideExtremeEntity;
import com.gregochr.goldenhour.entity.TideExtremeType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.TideData;
import com.gregochr.goldenhour.model.TideStats;
import com.gregochr.goldenhour.repository.TideExtremeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Reads and classifies tide extremes for coastal locations from the {@code tide_extreme} table.
 *
 * <p>At forecast evaluation time, {@link #deriveTideData} looks up the stored extremes to
 * classify the tide state and find the next high and low tides — without calling the external
 * API on every run.
 *
 * <p>HIGH: solar event is within {@value #HIGH_LOW_THRESHOLD_MINUTES} minutes of a stored HIGH extreme.
 * LOW: solar event is within {@value #HIGH_LOW_THRESHOLD_MINUTES} minutes of a stored LOW extreme.
 * MID: everything else — tide is neither fully in nor fully out.
 *
 * <p><strong>Façade over two collaborators.</strong> Fetching from the WorldTides vendor API and
 * merging the response into {@code tide_extreme} lives in {@link WorldTidesIngestionService} —
 * this class delegates {@link #fetchAndStoreTideExtremes} and {@link #backfillTideExtremes} to
 * it unchanged. Every existing caller keeps calling {@code TideService}; nothing had to migrate.
 * {@code sameKindAdjacencies}/{@code Adjacency} stay defined here (rather than moving with
 * {@code checkTideIntegrity}) because {@code TideServiceTest} references them by this class's
 * name directly.
 */
@Service
public class TideService {

    private static final Logger LOG = LoggerFactory.getLogger(TideService.class);

    /** Days either side of the solar event time to query from the DB. */
    private static final long QUERY_WINDOW_DAYS = 2;

    /**
     * Minutes within which the solar event is classified as HIGH or LOW tide,
     * and within which the midpoint between consecutive extremes is considered mid-tide.
     */
    private static final long HIGH_LOW_THRESHOLD_MINUTES = 60;

    /** Decimal precision for stored tide heights. */
    private static final int HEIGHT_SCALE = 3;

    /** A HIGH tide exceeding 125% of average high is classified as a spring tide. */
    private static final BigDecimal SPRING_TIDE_FACTOR = new BigDecimal("1.25");

    /**
     * Fewest past high waters from which a spring or king threshold may be derived.
     *
     * <p>One spring–neap cycle is 14.77 days and a semidiurnal coast has about 1.93 high waters a
     * day, so a full cycle is ~28. Below that the sample has not seen both a spring and a neap and
     * cannot say what is unusual for the port: the mean sits wherever the fortnight happened to
     * fall. This is a floor on the <em>threshold</em> only — the mean, max and min are reported
     * from whatever history exists, because those describe the sample honestly at any size.
     */
    private static final long MIN_HIGHS_FOR_THRESHOLDS = 28;

    private final TideExtremeRepository tideExtremeRepository;
    private final WorldTidesIngestionService ingestionService;

    /**
     * Constructs a {@code TideService}.
     *
     * <p>Builds its own {@link WorldTidesIngestionService} from the same four dependencies
     * rather than having Spring inject one, so this constructor's signature — and the tests that
     * call it directly — do not have to change. Because {@code WorldTidesIngestionService} is
     * stateless (it only wraps these injected collaborators), this private instance behaves
     * identically to the separately Spring-managed bean of the same class.
     *
     * <p>⚠️ Because this instance is built with {@code new} rather than resolved through the
     * Spring context, calls made through it do not go through {@code WorldTidesIngestionService}'s
     * own {@code @Transactional} proxy. The transaction boundary for
     * {@link #fetchAndStoreTideExtremes} therefore comes from {@code @Transactional} on the
     * delegating method below — which runs on this ({@code TideService}'s) own proxy — not from
     * the annotation on the ingestion class. Do not remove either one.
     *
     * @param restClient             shared RestClient for outbound HTTP calls
     * @param tideExtremeRepository  repository for persisted tide extremes
     * @param worldTidesProperties   WorldTides API configuration
     * @param jobRunService          service for recording API call metrics
     */
    public TideService(RestClient restClient, TideExtremeRepository tideExtremeRepository,
            WorldTidesProperties worldTidesProperties, JobRunService jobRunService) {
        this.tideExtremeRepository = tideExtremeRepository;
        this.ingestionService = new WorldTidesIngestionService(
                restClient, tideExtremeRepository, worldTidesProperties, jobRunService);
    }

    /**
     * Fetches the forward tide window from WorldTides for a coastal location and merges them
     * into the {@code tide_extreme} table, replacing only the overlapping window while
     * preserving historical data.
     *
     * <p>If the WorldTides API key is not configured, or if the API call fails or returns
     * a non-200 status, no rows are deleted or written — the existing data is preserved.
     *
     * <p>Delegates to {@link WorldTidesIngestionService#fetchAndStoreTideExtremes(LocationEntity)}.
     *
     * @param location the coastal location to fetch tide data for
     */
    @Transactional
    public void fetchAndStoreTideExtremes(LocationEntity location) {
        ingestionService.fetchAndStoreTideExtremes(location);
    }

    /**
     * Fetches the forward tide window from WorldTides for a coastal location and merges them
     * into the {@code tide_extreme} table, replacing only the overlapping window while
     * preserving historical data. Optionally tracks the API call in a job run.
     *
     * <p>If the WorldTides API key is not configured, or if the API call fails or returns
     * a non-200 status, no rows are deleted or written — the existing data is preserved.
     *
     * <p>Delegates to
     * {@link WorldTidesIngestionService#fetchAndStoreTideExtremes(LocationEntity, JobRunEntity)}.
     *
     * @param location the coastal location to fetch tide data for
     * @param jobRun   the parent job run for metrics tracking, or {@code null}
     */
    @Transactional
    public void fetchAndStoreTideExtremes(LocationEntity location, JobRunEntity jobRun) {
        ingestionService.fetchAndStoreTideExtremes(location, jobRun);
    }

    /**
     * Delegates to {@link WorldTidesIngestionService#resolveFetchWindow}. Kept here, package-
     * private, purely so {@code TideFetchWindowTest} can keep exercising it directly against a
     * {@code TideService} instance without change.
     *
     * @param locationId the location primary key
     * @param startOfDay start of today, UTC
     * @param windowEnd  the target horizon
     * @return the span to fetch, or {@code null} when coverage already reaches the horizon
     */
    WorldTidesIngestionService.FetchWindow resolveFetchWindow(Long locationId,
            LocalDateTime startOfDay, LocalDateTime windowEnd) {
        return ingestionService.resolveFetchWindow(locationId, startOfDay, windowEnd);
    }

    /**
     * A same-type pair of consecutive tide extremes — the physically impossible sequence a lost
     * extreme leaves behind, since tides alternate HIGH and LOW.
     *
     * @param first  the earlier extreme
     * @param second the later extreme of the same {@link TideExtremeType}
     */
    record Adjacency(TideExtremeEntity first, TideExtremeEntity second) {
    }

    /**
     * Finds adjacent same-type extremes in a chronologically ordered list.
     *
     * <p>Package-private, static and pure so the scan can be unit-tested without a database.
     * Same-type adjacency only — no time-gap heuristic, since a long gap between alternating
     * kinds is legal (neap tides, or a location far from the equator).
     *
     * <p>Called by {@link WorldTidesIngestionService}'s post-merge integrity check, which reads
     * back the just-fetched span and logs a WARN for every anomaly this finds. Kept here rather
     * than moving with that check because {@code TideServiceTest} exercises it directly against
     * this class's name.
     *
     * @param ordered tide extremes in ascending event-time order
     * @return one {@link Adjacency} per same-type consecutive pair, in the order found
     */
    static List<Adjacency> sameKindAdjacencies(List<TideExtremeEntity> ordered) {
        List<Adjacency> found = new ArrayList<>();
        for (int i = 0; i < ordered.size() - 1; i++) {
            TideExtremeEntity current = ordered.get(i);
            TideExtremeEntity next = ordered.get(i + 1);
            if (current.getType() == next.getType()) {
                found.add(new Adjacency(current, next));
            }
        }
        return found;
    }

    /**
     * Backfills historical tide data for a location by fetching 7-day chunks going back
     * 12 months from today. Skips any chunk where data already exists in the database.
     *
     * <p>Delegates to {@link WorldTidesIngestionService#backfillTideExtremes}.
     *
     * @param location the coastal location to backfill
     * @param jobRun   the parent job run for metrics tracking, or {@code null}
     * @return the number of 7-day chunks actually fetched (skipped chunks not counted)
     */
    public int backfillTideExtremes(LocationEntity location, JobRunEntity jobRun) {
        return ingestionService.backfillTideExtremes(location, jobRun);
    }

    /**
     * Returns {@code true} if any tide extremes are stored for the given location.
     *
     * <p>Called at startup to decide whether a tide fetch is needed — avoids redundant
     * API calls when the {@code tide_extreme} table already has data for this location.
     *
     * @param locationId the location primary key
     * @return {@code true} if the {@code tide_extreme} table has at least one row for this location
     */
    public boolean hasStoredExtremes(Long locationId) {
        return tideExtremeRepository.existsByLocationId(locationId);
    }

    /**
     * Returns all tide extremes for a location on the given UTC calendar day, ordered chronologically.
     *
     * <p>Used by {@code TideController} to serve the daily tide schedule for a specific date.
     * The query window is midnight-to-midnight UTC, so extremes that straddle midnight
     * (e.g. a low tide at 01:15) appear on the day they actually occur.
     *
     * @param locationId the location primary key
     * @param date       the UTC calendar day to query
     * @return tide extremes for that day in chronological order; empty list if none stored
     */
    public List<TideExtremeEntity> getTidesForDate(Long locationId, java.time.LocalDate date) {
        LocalDateTime from = date.atStartOfDay();
        LocalDateTime to = date.plusDays(1).atStartOfDay().minusNanos(1);
        return tideExtremeRepository.findByLocationIdAndEventTimeBetweenOrderByEventTimeAsc(
                locationId, from, to);
    }

    /**
     * Derives tide data for a coastal location at a solar event time using stored extremes.
     *
     * <p>Queries the {@code tide_extreme} table for extremes within
     * {@value #QUERY_WINDOW_DAYS} days either side of the event time.
     * Returns empty if no extremes are stored (e.g. weekly refresh not yet run).
     *
     * @param locationId the location primary key
     * @param eventTime  UTC time of sunrise or sunset
     * @return Optional containing TideData if extremes are available, empty otherwise
     */
    public Optional<TideData> deriveTideData(Long locationId, LocalDateTime eventTime) {
        return deriveTideData(locationId, eventTime, HIGH_LOW_THRESHOLD_MINUTES);
    }

    /**
     * Derives tide data using an elevation-based window instead of the default ±60 min.
     *
     * @param locationId    the location primary key
     * @param eventTime     UTC time of sunrise or sunset
     * @param windowMinutes the combined blue+golden hour half-width in minutes
     * @return Optional containing TideData if extremes are available, empty otherwise
     */
    public Optional<TideData> deriveTideData(Long locationId, LocalDateTime eventTime,
            long windowMinutes) {
        List<TideExtremeEntity> extremes = fetchExtremesAround(locationId, eventTime);
        if (extremes.isEmpty()) {
            LOG.warn("No tide extremes in DB for locationId={} around {}", locationId, eventTime);
            return Optional.empty();
        }
        return Optional.of(buildTideData(extremes, eventTime, windowMinutes));
    }

    /**
     * Tide data classified at two alignment windows from a single extremes fetch.
     *
     * @param tight   tide data classified at the tight alignment window
     * @param widened tide data classified at the widened alignment window
     */
    public record DualWindowTideData(TideData tight, TideData widened) {
    }

    /**
     * Derives tide data at two alignment windows from a <em>single</em> extremes fetch.
     *
     * <p>The underlying extremes query depends only on {@code locationId} and {@code eventTime}
     * (a fixed ± day range), never on the window — so both windows classify the <em>same</em>
     * fetched tide curve. This lets the single tide-fact derivation seam obtain the tight-window
     * snapshot and the widened-window alignment flag without fetching twice.
     *
     * @param locationId           the location primary key
     * @param eventTime            UTC time of sunrise or sunset
     * @param tightWindowMinutes   the tight alignment window half-width in minutes
     * @param widenedWindowMinutes the widened alignment window half-width in minutes
     * @return both tide-data classifications, or empty if no extremes are available
     */
    public Optional<DualWindowTideData> deriveDualWindowTideData(Long locationId,
            LocalDateTime eventTime, long tightWindowMinutes, long widenedWindowMinutes) {
        List<TideExtremeEntity> extremes = fetchExtremesAround(locationId, eventTime);
        if (extremes.isEmpty()) {
            LOG.warn("No tide extremes in DB for locationId={} around {}", locationId, eventTime);
            return Optional.empty();
        }
        return Optional.of(new DualWindowTideData(
                buildTideData(extremes, eventTime, tightWindowMinutes),
                buildTideData(extremes, eventTime, widenedWindowMinutes)));
    }

    /**
     * Fetches the stored tide extremes around an event time. The query range depends only on
     * {@code locationId} and {@code eventTime} (a fixed ± day window), never on the alignment
     * window — so the same fetched curve can be classified at any number of windows.
     *
     * @param locationId the location primary key
     * @param eventTime  UTC time of the solar event
     * @return the stored extremes in the ± day range, ascending by event time (possibly empty)
     */
    private List<TideExtremeEntity> fetchExtremesAround(Long locationId, LocalDateTime eventTime) {
        return tideExtremeRepository
                .findByLocationIdAndEventTimeBetweenOrderByEventTimeAsc(
                        locationId,
                        eventTime.minusDays(QUERY_WINDOW_DAYS),
                        eventTime.plusDays(QUERY_WINDOW_DAYS));
    }

    /**
     * Computes whether the tide state aligns with the location's photographer preference.
     *
     * <p>For {@link TideType#MID}, alignment requires the solar event to be within
     * {@value #HIGH_LOW_THRESHOLD_MINUTES} minutes of the midpoint between consecutive
     * HIGH and LOW extremes — not merely "not HIGH and not LOW".
     *
     * @param tideData          the tide data snapshot at the solar event time
     * @param locationTideTypes the location's acceptable tide states
     * @return true if the tide state matches any of the location's preferences
     */
    public boolean calculateTideAligned(TideData tideData, Set<TideType> locationTideTypes) {
        if (locationTideTypes.isEmpty()) {
            return false;
        }
        TideState tideState = tideData.tideState();
        return locationTideTypes.stream().anyMatch(pref -> switch (pref) {
            case HIGH -> tideState == TideState.HIGH;
            case LOW -> tideState == TideState.LOW;
            case MID -> tideData.nearMidPoint();
        });
    }

    /**
     * Computes aggregate tide height statistics for a location from stored extremes
     * that have already occurred.
     *
     * <p>Returns empty if no past extremes are stored for the location.
     *
     * <p><strong>Past extremes only, deliberately.</strong> The sample stops at the start
     * of today (UTC), so the spring and king thresholds derived here are a climatology over
     * observed history rather than a figure that moves with the forward fetch window. Before
     * this bound the aggregates ran over every row in {@code tide_extreme}, which meant
     * changing {@code WorldTidesIngestionService.FETCH_LENGTH_SECONDS} silently re-classified
     * spring and king tides everywhere they are read. See {@code TideExtremeRepository
     * .findHeightStatsByLocationIdAndTypeBefore} for the full rationale.
     *
     * <p>Note this makes the statistics weakest for a newly added coastal location, which
     * has no history until the first backfill runs — the same locations for which the old
     * unbounded query was strongest. That trade is intended: a threshold that quietly
     * depends on the fetch horizon is the worse failure, because nothing on screen
     * attributes a moved badge to it.
     *
     * <p><strong>Thresholds need a whole cycle; averages do not.</strong> The spring and king
     * thresholds are withheld until {@value #MIN_HIGHS_FOR_THRESHOLDS} past high waters exist
     * — one spring–neap cycle — while the mean, max and min are reported from whatever history
     * there is. Without that floor, bounding the sample would have replaced one silent failure
     * with a louder one: a location fetched forward-only reports two high waters on its second
     * day, and two neap samples put the spring threshold below almost every later high water.
     *
     * @param locationId the location primary key
     * @return Optional containing TideStats if data is available, empty otherwise
     */
    public Optional<TideStats> getTideStats(Long locationId) {
        LocalDateTime statsCutoff = LocalDate.now(ZoneOffset.UTC).atStartOfDay();

        Object[] highStats = tideExtremeRepository.findHeightStatsByLocationIdAndTypeBefore(
                locationId, TideExtremeType.HIGH, statsCutoff);
        Object[] lowStats = tideExtremeRepository.findHeightStatsByLocationIdAndTypeBefore(
                locationId, TideExtremeType.LOW, statsCutoff);

        // H2 may return Object[1]{Object[4]{avg,max,min,count}} — unwrap if nested
        if (highStats.length == 1 && highStats[0] instanceof Object[]) {
            highStats = (Object[]) highStats[0];
        }
        if (lowStats.length == 1 && lowStats[0] instanceof Object[]) {
            lowStats = (Object[]) lowStats[0];
        }

        long highCount = highStats.length > 3 && highStats[3] != null ? (Long) highStats[3] : 0;
        long lowCount = lowStats.length > 3 && lowStats[3] != null ? (Long) lowStats[3] : 0;

        if (highCount == 0 && lowCount == 0) {
            return Optional.empty();
        }

        // A sample too short to contain a spring–neap cycle cannot describe one. Bounding the
        // sample to past extremes removed an accidental floor: the forward fetch window used to
        // guarantee roughly a fortnight of high waters even for a location with no history, and
        // taking that away left nothing in its place. Two neap samples put the spring threshold
        // under almost every subsequent high water — a standing king-tide flag on ordinary days,
        // not an occasional false positive — and two spring samples put it above the port's own
        // maximum so nothing fires at all. Both reach a persisted column and the Claude prompt.
        boolean cycleObserved = highCount >= MIN_HIGHS_FOR_THRESHOLDS;

        BigDecimal avgHigh = highCount > 0 ? toBigDecimal(highStats[0]) : null;
        BigDecimal maxHigh = highCount > 0 ? toBigDecimal(highStats[1]) : null;
        BigDecimal avgLow = lowCount > 0 ? toBigDecimal(lowStats[0]) : null;
        BigDecimal minLow = lowCount > 0 ? toBigDecimal(lowStats[2]) : null;

        BigDecimal avgRange = (avgHigh != null && avgLow != null)
                ? avgHigh.subtract(avgLow).setScale(HEIGHT_SCALE, RoundingMode.HALF_UP) : null;

        // Percentile and spring tide calculations require the full sorted height list
        BigDecimal p75 = null;
        BigDecimal p90 = null;
        BigDecimal p95 = null;
        long springCount = 0;
        BigDecimal springFreq = null;
        BigDecimal springThreshold = null;
        long kingCount = 0;

        if (cycleObserved) {
            List<BigDecimal> highHeights = tideExtremeRepository
                    .findHeightsByLocationIdAndTypeBeforeOrderByHeightAsc(
                            locationId, TideExtremeType.HIGH, statsCutoff);

            p75 = percentile(highHeights, 75);
            p90 = percentile(highHeights, 90);
            p95 = percentile(highHeights, 95);

            // Spring tide: HIGH tide exceeding 125% of average high
            springThreshold = avgHigh.multiply(SPRING_TIDE_FACTOR)
                    .setScale(HEIGHT_SCALE, RoundingMode.HALF_UP);
            final BigDecimal springThresholdFinal = springThreshold;
            springCount = highHeights.stream()
                    .filter(h -> h.compareTo(springThresholdFinal) > 0)
                    .count();
            springFreq = BigDecimal.valueOf(springCount)
                    .divide(BigDecimal.valueOf(highCount), HEIGHT_SCALE, RoundingMode.HALF_UP);

            // King tide: HIGH tide exceeding P95
            final BigDecimal kingThreshold = p95;
            kingCount = highHeights.stream()
                    .filter(h -> h.compareTo(kingThreshold) > 0)
                    .count();
        }

        return Optional.of(new TideStats(
                avgHigh, maxHigh, avgLow, minLow,
                highCount + lowCount,
                avgRange, p75, p90, p95,
                springCount, springFreq,
                springThreshold, p95, kingCount));
    }

    /**
     * Computes the p-th percentile from a sorted (ascending) list of values using
     * linear interpolation between nearest ranks.
     *
     * @param sorted ascending-sorted list of values (must not be empty)
     * @param p      percentile (0–100)
     * @return the interpolated percentile value
     */
    static BigDecimal percentile(List<BigDecimal> sorted, double p) {
        if (sorted.size() == 1) {
            return sorted.getFirst();
        }
        double rank = (p / 100.0) * (sorted.size() - 1);
        int lower = (int) Math.floor(rank);
        int upper = Math.min(lower + 1, sorted.size() - 1);
        BigDecimal fraction = BigDecimal.valueOf(rank - lower);
        BigDecimal diff = sorted.get(upper).subtract(sorted.get(lower));
        return sorted.get(lower).add(diff.multiply(fraction))
                .setScale(HEIGHT_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Builds a {@link TideData} snapshot from a list of stored tide extremes at an event time.
     *
     * <p>Package-private for unit testing.
     *
     * @param extremes  stored tide extremes in chronological order
     * @param eventTime UTC time of the solar event
     * @return tide data snapshot including state, mid-point proximity, and next high/low events
     */
    TideData buildTideData(List<TideExtremeEntity> extremes, LocalDateTime eventTime) {
        return buildTideData(extremes, eventTime, HIGH_LOW_THRESHOLD_MINUTES);
    }

    /**
     * Builds a tide data snapshot using an elevation-based window.
     */
    TideData buildTideData(List<TideExtremeEntity> extremes, LocalDateTime eventTime,
            long windowMinutes) {
        TideState state = classifyTideState(extremes, eventTime, windowMinutes);
        boolean nearMid = isMidPointAligned(extremes, eventTime, windowMinutes);

        TideExtremeEntity nextHigh = extremes.stream()
                .filter(e -> e.getType() == TideExtremeType.HIGH
                        && e.getEventTime().isAfter(eventTime))
                .findFirst()
                .orElse(null);

        TideExtremeEntity nextLow = extremes.stream()
                .filter(e -> e.getType() == TideExtremeType.LOW
                        && e.getEventTime().isAfter(eventTime))
                .findFirst()
                .orElse(null);

        LocalDateTime nearestHigh = findNearestExtreme(extremes, TideExtremeType.HIGH, eventTime);
        LocalDateTime nearestLow = findNearestExtreme(extremes, TideExtremeType.LOW, eventTime);

        return new TideData(
                state,
                nearMid,
                nextHigh != null ? nextHigh.getEventTime() : null,
                nextHigh != null ? nextHigh.getHeightMetres() : null,
                nextLow != null ? nextLow.getEventTime() : null,
                nextLow != null ? nextLow.getHeightMetres() : null,
                nearestHigh,
                nearestLow);
    }

    /**
     * Finds the tide extreme of the given type that is closest in time to {@code eventTime},
     * within a ±12-hour search window.
     *
     * <p>Package-private for unit testing.
     *
     * @param extremes  stored tide extremes in chronological order
     * @param type      the type of extreme to search for (HIGH or LOW)
     * @param eventTime UTC time of the solar event
     * @return the UTC time of the nearest matching extreme within ±12 hours, or null if none found
     */
    LocalDateTime findNearestExtreme(List<TideExtremeEntity> extremes,
            TideExtremeType type, LocalDateTime eventTime) {
        final long windowMinutes = 12 * 60;
        return extremes.stream()
                .filter(e -> e.getType() == type)
                .filter(e -> Math.abs(ChronoUnit.MINUTES.between(e.getEventTime(), eventTime))
                        <= windowMinutes)
                .min(java.util.Comparator.comparingLong(e ->
                        Math.abs(ChronoUnit.MINUTES.between(e.getEventTime(), eventTime))))
                .map(TideExtremeEntity::getEventTime)
                .orElse(null);
    }

    /**
     * Classifies the tide state at {@code eventTime} given a list of stored extremes.
     *
     * <p>Package-private for unit testing. Every production caller supplies its own window through
     * the overload below — the ±{@value #HIGH_LOW_THRESHOLD_MINUTES}-minute default is the
     * documented rule, not a path anything currently takes.
     *
     * @param extremes  stored tide extremes around the event time
     * @param eventTime UTC time of the solar event
     * @return HIGH, LOW, or MID
     */
    TideState classifyTideState(List<TideExtremeEntity> extremes, LocalDateTime eventTime) {
        return classifyTideState(extremes, eventTime, HIGH_LOW_THRESHOLD_MINUTES);
    }

    /**
     * Classifies the tide state using an elevation-based window width.
     *
     * <p><b>This overload is the single tide-state rule</b>, and the width is the caller's to
     * choose. Two production callers pass the same one: {@code TideFactDeriver} for the per-slot
     * tide facts, and {@code WindowTideRollupBuilder} for the Plan tab's per-window tide row. Both
     * size it with {@code TideFactDeriver.tightAlignmentWindowMinutes}, so a row and the
     * drill-down beneath it cannot call the same water HIGH and MID.
     */
    TideState classifyTideState(List<TideExtremeEntity> extremes, LocalDateTime eventTime,
            long windowMinutes) {
        boolean nearHigh = extremes.stream()
                .filter(e -> e.getType() == TideExtremeType.HIGH)
                .anyMatch(e -> Math.abs(ChronoUnit.MINUTES.between(e.getEventTime(), eventTime))
                        <= windowMinutes);
        if (nearHigh) {
            return TideState.HIGH;
        }

        boolean nearLow = extremes.stream()
                .filter(e -> e.getType() == TideExtremeType.LOW)
                .anyMatch(e -> Math.abs(ChronoUnit.MINUTES.between(e.getEventTime(), eventTime))
                        <= windowMinutes);
        if (nearLow) {
            return TideState.LOW;
        }

        return TideState.MID;
    }

    private boolean isMidPointAligned(List<TideExtremeEntity> extremes, LocalDateTime eventTime,
            long windowMinutes) {
        for (int i = 0; i < extremes.size() - 1; i++) {
            LocalDateTime t1 = extremes.get(i).getEventTime();
            LocalDateTime t2 = extremes.get(i + 1).getEventTime();
            long halfSeconds = ChronoUnit.SECONDS.between(t1, t2) / 2;
            LocalDateTime midpoint = t1.plusSeconds(halfSeconds);
            if (Math.abs(ChronoUnit.MINUTES.between(midpoint, eventTime)) <= windowMinutes) {
                return true;
            }
        }
        return false;
    }

    /**
     * Safely converts an aggregate query result value to {@link BigDecimal}.
     *
     * <p>H2 may return {@code Double} for {@code AVG()} and {@code BigDecimal} for
     * {@code MAX()}/{@code MIN()}, so a direct cast is unsafe.
     *
     * @param value the query result value
     * @return the value as a BigDecimal, or null if the input is null
     */
    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number num) {
            return BigDecimal.valueOf(num.doubleValue())
                    .setScale(HEIGHT_SCALE, RoundingMode.HALF_UP);
        }
        return new BigDecimal(value.toString())
                .setScale(HEIGHT_SCALE, RoundingMode.HALF_UP);
    }
}
