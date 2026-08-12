package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TideExtremeEntity;
import com.gregochr.goldenhour.entity.TideExtremeType;
import com.gregochr.goldenhour.model.TideStats;
import com.gregochr.goldenhour.repository.TideExtremeRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Which dates across a long window carry spring-sized or king-sized water, measured from the tide
 * heights actually stored for the coastal roster.
 *
 * <h2>Why this exists — the moon says one thing and the water says another</h2>
 *
 * <p>The Plan tab and the "Coming up" feed used to classify tides by two different methods, and the
 * feed's was the one that could not see water. The Plan tab reads
 * {@code BriefingSlot.TideInfo.isKingTide}/{@code isSpringTide}, which {@code TideFactDeriver}
 * derives from stored heights against each location's own thresholds. The feed asked
 * {@code LunarPhaseService.classifyTide}, which is pure arithmetic over two reference epochs: a date
 * qualifies when it falls within ±1 day of syzygy.
 *
 * <p><b>That window closes before the water arrives.</b> A port's biggest tide of the cycle lags
 * syzygy by a day or two — the age of the tide — and the lag is a property of the coastline, not of
 * the moon, so no epoch arithmetic can recover it. Measured against the August 2026 new moon
 * (12 Aug 17:38 UTC) the lunar window covered 12–13 Aug while the roster's biggest water landed on
 * 14–15 Aug: the Plan tab carried a tide card for the Friday and Saturday and the feed showed
 * nothing at all for either day. The feed's own figures had been saying so for a while — both runs
 * it displayed reported their biggest day as the <em>last</em> day of the window, which is what a
 * window truncated before its peak looks like.
 *
 * <h2>The tests are lifted, not invented</h2>
 *
 * <p>A date is <b>spring</b> when any coastal location's biggest high water that day clears that
 * location's own spring threshold (125% of its mean high), and <b>king</b> when one clears its own
 * P95. Those are {@code TideFactDeriver}'s two comparisons, unchanged, including the strict
 * {@code >}. Roster-wide "any location" is {@code KingTideHotTopicStrategy.findKingTide}'s rule,
 * which returns the first qualifying slot anywhere in the briefing. Copying both exactly is the
 * point: a shared rule that is slightly loose is a calibration question, whereas two rules that
 * disagree are a product that contradicts itself between tabs.
 *
 * <p>King is <b>not</b> assumed to imply spring. The two thresholds are computed independently from
 * the same sample and neither is defined in terms of the other, so a location's P95 may sit either
 * side of 125% of its mean. The caller tests both.
 *
 * <h2>The day is a local day</h2>
 *
 * <p>Extremes are stored in UTC and bucketed here by their {@code Europe/London} date, matching
 * {@code TideRunBuilder}: the run rows, the chart axis and this index must agree about which day a
 * 00:40 BST high water belongs to, or a run's dates and its curve describe different nights.
 */
@Component
public class TideSizeIndex {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    private final TideExtremeRepository tideExtremeRepository;
    private final TideService tideService;

    /**
     * Today's per-location thresholds, or null before the first build.
     *
     * <p>{@code TideService.getTideStats} is four queries per location and pulls that location's
     * whole stored height history, so over a sixty-location roster it is the expensive half of this
     * class by a wide margin. Its answer is a pure function of the day — the sample is bounded at
     * the start of today UTC — so it is cached for exactly that long. The feed above it is cached
     * per day as well, but only for one requested length, so a client alternating {@code ?days=}
     * values would otherwise pay the full roster sweep on every request.
     */
    private final AtomicReference<CachedThresholds> thresholds = new AtomicReference<>();

    /**
     * Constructs a {@code TideSizeIndex}.
     *
     * @param tideExtremeRepository stored tide extremes — a DB-only read, never an API call
     * @param tideService           supplies each location's spring and king thresholds
     */
    public TideSizeIndex(TideExtremeRepository tideExtremeRepository, TideService tideService) {
        this.tideExtremeRepository = tideExtremeRepository;
        this.tideService = tideService;
    }

    /**
     * One location's two size thresholds, either of which may be absent.
     *
     * <p>Absent means "not yet answerable": {@code TideService} withholds both until a whole
     * spring–neap cycle of high waters has been observed, because two neap samples put the spring
     * threshold under almost every later high water. An entry is stored even when both are null, so
     * a location with no usable history does not force the cache to rebuild on every request.
     *
     * @param spring height above which a high water is spring-sized, or null
     * @param king   height above which a high water is king-sized, or null
     */
    private record Thresholds(BigDecimal spring, BigDecimal king) {

        boolean usable() {
            return spring != null || king != null;
        }
    }

    /** Today's thresholds for a set of locations. */
    private record CachedThresholds(LocalDate builtFor, Map<Long, Thresholds> byLocation) { }

    /**
     * Which dates in a window carry spring-sized or king-sized water.
     *
     * <p>{@link #usable} is the honesty gate and the caller's fallback signal. It is false when no
     * location on the roster has both a threshold and a high water in the window — a cold database,
     * a roster with no coastal locations, or a fetch that has not run. A false answer here means
     * "not measured", which must never be rendered as "no spring tides in the next ninety days".
     *
     * @param springDates dates carrying spring-sized water somewhere on the roster
     * @param kingDates   dates carrying king-sized water somewhere on the roster
     * @param usable      whether anything could be measured at all
     */
    public record Sizes(Set<LocalDate> springDates, Set<LocalDate> kingDates, boolean usable) {

        /** An index that measured nothing — the caller falls back to lunar arithmetic. */
        public static final Sizes UNMEASURED = new Sizes(Set.of(), Set.of(), false);

        /** Normalises the sets so callers never have to null-check them. */
        public Sizes {
            springDates = springDates == null ? Set.of() : Set.copyOf(springDates);
            kingDates = kingDates == null ? Set.of() : Set.copyOf(kingDates);
        }

        /**
         * Whether this date carries spring-sized water somewhere on the roster.
         *
         * @param date the local date
         * @return true when it does
         */
        public boolean springOn(LocalDate date) {
            return springDates.contains(date);
        }

        /**
         * Whether this date carries king-sized water somewhere on the roster.
         *
         * @param date the local date
         * @return true when it does
         */
        public boolean kingOn(LocalDate date) {
            return kingDates.contains(date);
        }
    }

    /**
     * Measures every date in the window against the roster's stored heights.
     *
     * <p>One query for the window's high waters, plus one cached threshold sweep. The window should
     * already include whatever slack the caller's run-walk needs on each side: a run is grouped from
     * consecutive qualifying dates, so a date outside the window is indistinguishable here from one
     * that did not qualify, and a run clipped at the edge reports the wrong span and can lose the
     * very day that decides its label.
     *
     * @param coastalLocations the roster to measure
     * @param from             first day, inclusive
     * @param to               last day, inclusive
     * @return the measured dates, or {@link Sizes#UNMEASURED} when nothing could be measured
     */
    public Sizes measure(List<LocationEntity> coastalLocations, LocalDate from, LocalDate to) {
        if (coastalLocations == null || coastalLocations.isEmpty() || from == null || to == null
                || to.isBefore(from)) {
            return Sizes.UNMEASURED;
        }
        List<Long> ids = coastalLocations.stream()
                .map(LocationEntity::getId).filter(Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return Sizes.UNMEASURED;
        }

        Map<Long, Thresholds> byLocation = thresholdsFor(ids);
        // Tested over the ids asked for, not over the cached map, which may hold a superset from an
        // earlier call with a longer roster. A borrowed "yes" here would turn "nothing could be
        // measured" into "nothing qualified", which is the one confusion this class exists to
        // prevent — the caller renders the first as a fallback and the second as a finding.
        if (ids.stream().map(byLocation::get)
                .noneMatch(limits -> limits != null && limits.usable())) {
            return Sizes.UNMEASURED;
        }

        Map<Long, Map<LocalDate, Double>> dayMaxima = dayMaxHighWater(ids, from, to);

        Set<LocalDate> spring = new HashSet<>();
        Set<LocalDate> king = new HashSet<>();
        // Thresholds and extremes have to meet at the same location for anything to be measurable:
        // one location with history and another with heights answers nothing between them.
        boolean measured = false;
        for (Map.Entry<Long, Map<LocalDate, Double>> entry : dayMaxima.entrySet()) {
            Thresholds limits = byLocation.get(entry.getKey());
            if (limits == null || !limits.usable()) {
                continue;
            }
            measured = true;
            for (Map.Entry<LocalDate, Double> day : entry.getValue().entrySet()) {
                double high = day.getValue();
                if (limits.spring() != null && high > limits.spring().doubleValue()) {
                    spring.add(day.getKey());
                }
                if (limits.king() != null && high > limits.king().doubleValue()) {
                    king.add(day.getKey());
                }
            }
        }
        return measured ? new Sizes(spring, king, true) : Sizes.UNMEASURED;
    }

    /**
     * The biggest high water on each local day, per location.
     *
     * <p>The day's maximum rather than the high water nearest a solar event, which is what
     * {@code TideFactDeriver} compares on the Plan tab. There is no solar event to be near here —
     * the feed answers for a date, not for a slot — and on the peak day of a run both of the day's
     * high waters are large, so the two readings separate only at the run's edges. The maximum is
     * the more inclusive of the two, which is the safe direction for a feed whose failure mode was
     * omitting days.
     */
    private Map<Long, Map<LocalDate, Double>> dayMaxHighWater(List<Long> ids,
            LocalDate from, LocalDate to) {
        // A local day's extremes sit either side of the UTC date boundary, so the fetch window is
        // the local span converted to UTC rather than the bare dates.
        LocalDateTime windowStart = from.atStartOfDay(LONDON)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime windowEnd = to.plusDays(1).atStartOfDay(LONDON)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();

        Map<Long, Map<LocalDate, Double>> byLocation = new LinkedHashMap<>();
        for (TideExtremeEntity extreme : tideExtremeRepository
                .findByLocationIdInAndTypeAndEventTimeBetweenOrderByEventTimeAsc(
                        ids, TideExtremeType.HIGH, windowStart, windowEnd)) {
            if (extreme.getHeightMetres() == null || extreme.getEventTime() == null) {
                continue;
            }
            LocalDate localDate = extreme.getEventTime().atOffset(ZoneOffset.UTC)
                    .atZoneSameInstant(LONDON).toLocalDate();
            // The fetch window is a closed UTC interval ending at the local midnight that opens the
            // day after `to`, so an extreme landing exactly on it belongs to a day this call was
            // never asked about — and one whose other high water was not fetched. Reporting it
            // would be a verdict on a partly-read day.
            if (localDate.isBefore(from) || localDate.isAfter(to)) {
                continue;
            }
            byLocation.computeIfAbsent(extreme.getLocationId(), k -> new HashMap<>())
                    .merge(localDate, extreme.getHeightMetres().doubleValue(), Math::max);
        }
        return byLocation;
    }

    /**
     * Today's thresholds for the given locations, from cache when it already covers them all.
     *
     * <p>Rebuilt when the day has rolled or when the roster has grown — a location added through the
     * Admin UI mid-morning would otherwise be missing from the map until midnight, and a missing
     * entry is silently indistinguishable from one with no history.
     */
    private Map<Long, Thresholds> thresholdsFor(List<Long> ids) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        CachedThresholds cached = thresholds.get();
        if (cached != null && cached.builtFor().isEqual(today)
                && cached.byLocation().keySet().containsAll(ids)) {
            return cached.byLocation();
        }

        Map<Long, Thresholds> built = new LinkedHashMap<>();
        for (Long id : ids) {
            TideStats stats = tideService.getTideStats(id).orElse(null);
            // An entry either way. A location with no usable history must be *present* and empty,
            // not missing — a missing key is what containsAll above tests, so recording its absence
            // as an absence would rebuild the whole roster's thresholds on every request.
            built.put(id, stats == null
                    ? new Thresholds(null, null)
                    : new Thresholds(stats.springTideThreshold(), stats.p95HighMetres()));
        }
        Map<Long, Thresholds> snapshot = Map.copyOf(built);
        thresholds.set(new CachedThresholds(today, snapshot));
        return snapshot;
    }

    /**
     * Drops the cached thresholds so the next measurement recomputes them.
     *
     * <p>Exists for tests and for an admin path that has just backfilled history; the cache
     * otherwise turns over on its own at the date roll.
     */
    public void evict() {
        thresholds.set(null);
    }
}
