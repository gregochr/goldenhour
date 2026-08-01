package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.MarineWaveEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideExtremeEntity;
import com.gregochr.goldenhour.entity.TideExtremeType;
import com.gregochr.goldenhour.model.SeaState;
import com.gregochr.goldenhour.model.TideRunDay;
import com.gregochr.goldenhour.model.TideStats;
import com.gregochr.goldenhour.repository.MarineWaveRepository;
import com.gregochr.goldenhour.repository.TideExtremeRepository;
import com.gregochr.goldenhour.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Builds the per-day {@link TideRunDay} rows behind a spring or king tide pill's 24-hour chart.
 *
 * <p>A spring tide is a multi-day <em>run</em>, not a set of unrelated days, and the one question it
 * has to answer is <em>does the useful water land near a solar event?</em> Answering that needs tide
 * clock times and solar clock times side by side, which is what this builder assembles: the day's
 * extrema, its sunrise and sunset, its range against the location's own mean, the sea state, and a
 * plain-language {@link TideRunDay#verdict()} stating the alignment in words.
 *
 * <h2>One location for the whole run</h2>
 *
 * <p>The run is drawn for a <b>single representative coastal location</b>, chosen once — the
 * configured {@code photocast.tide-run.preferred-anchor} when it is in the run and drawable,
 * otherwise the biggest single-day range anywhere in the run. Choosing per day would let the
 * curve jump between coastlines mid-run, so a reader comparing Tuesday to Thursday would be
 * comparing two different places. The chosen location is carried on every row and named in the
 * card's footer, because alignment genuinely differs across a coastline a topic may span —
 * naming it is the honest form of that caveat.
 *
 * <p>The anchor exists because biggest-range is not neutral across a long roster: range grows
 * southward on this coast, so the maximum reliably lands at the southern end and anchors every
 * run to the same distant place. See {@code selectRepresentative} for the full reasoning.
 *
 * <h2>Days with no derivable range are dropped, not faked</h2>
 *
 * <p>A day needs at least one high and one low water at the representative location to have a
 * range or a curve. A day missing either is left out of the run entirely and its pill falls back to
 * the generic fact chips, rather than rendering a chart from half a tide.
 */
@Component
public class TideRunBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(TideRunBuilder.class);

    /** The run chip text for an ordinary spring run. */
    public static final String SPRING_RUN_LABEL = "SPRING RUN";

    /** The run chip text for a perigean (king) run. */
    public static final String KING_RUN_LABEL = "KING RUN";

    /** A spring run's draw is the exposed foreground at low water. */
    public static final String SPRING_PHRASE = "low water bares the foreground";

    /** A king run's draw is the highest water, not the exposed foreground — different framing. */
    public static final String KING_PHRASE = "causeways & foreshore submerged — shoot reflections";

    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY_LABEL =
            DateTimeFormatter.ofPattern("EEE d", Locale.UK);

    /** Within this many minutes of a solar event, the useful water counts as aligned. */
    private static final long ALIGNED_WINDOW_MINUTES = 60;

    /** Within this many minutes, the *other* extremum is close enough to say "at sunrise". */
    private static final long COINCIDENT_MINUTES = 30;

    /** Below this, an offset is not worth stating as a duration — the water is simply on the event. */
    private static final long COINCIDENT_ROUNDING_MINUTES = 5;

    /** Below this, a range anomaly is display noise rather than a signal. */
    private static final double MIN_ANOMALY_METRES = 0.05;

    private final TideExtremeRepository tideExtremeRepository;
    private final MarineWaveRepository marineWaveRepository;
    private final TideService tideService;
    private final SolarService solarService;

    /**
     * Everything that is not a letter or a digit, for comparing two spellings of one place name.
     *
     * <p>The anchor is matched by name because ids are environment-specific, and matching by name
     * means matching how somebody typed it. {@code St. Mary's Lighthouse} in the Admin UI against
     * {@code St Mary's Lighthouse} in configuration is one missing full stop, and under a plain
     * {@code equalsIgnoreCase} that single character silently disabled the whole feature: the run
     * fell back to biggest-range and drew itself at a cove 90 miles away, with nothing anywhere
     * saying why. Punctuation and spacing are exactly the part of a place name people disagree
     * about — {@code St}/{@code St.}, {@code Marys}/{@code Mary's}, hyphen or space — and none of
     * those disagreements mean a different place.
     */
    private static final String NON_ALPHANUMERIC = "[^\\p{L}\\p{N}]";

    /**
     * The last unresolved anchor name warned about, so the WARN below fires once rather than once
     * per request. Hot topics are recomputed live on every serve, so an unguarded warning here
     * would write a line per briefing fetch — and a message that noisy stops being read, which is
     * how a silent fallback becomes a silent fallback again.
     */
    private final AtomicReference<String> warnedAnchor = new AtomicReference<>();

    /** Location name the run is drawn for when present and drawable; blank means biggest range. */
    private final String preferredAnchor;

    /**
     * Constructs a {@code TideRunBuilder}.
     *
     * @param tideExtremeRepository stored tide extrema (a DB-only read — never an API call, so this
     *                              is safe to call from a hot-topic strategy)
     * @param marineWaveRepository  the shared sea-state carrier
     * @param tideService           supplies each location's historical range statistics
     * @param solarService          supplies the day's sunrise and sunset
     * @param preferredAnchor       location name the run should be drawn for when it is in the
     *                              run and drawable; blank restores pure biggest-range selection
     */
    public TideRunBuilder(TideExtremeRepository tideExtremeRepository,
            MarineWaveRepository marineWaveRepository,
            TideService tideService,
            SolarService solarService,
            @Value("${photocast.tide-run.preferred-anchor:}") String preferredAnchor) {
        this.tideExtremeRepository = tideExtremeRepository;
        this.marineWaveRepository = marineWaveRepository;
        this.tideService = tideService;
        this.solarService = solarService;
        this.preferredAnchor = preferredAnchor;
    }

    /** A day's extrema at one location, already reduced to local clock minutes. */
    private record DayTides(List<Point> points, double high, double low) {

        double range() {
            return high - low;
        }
    }

    /** One extreme, as minutes past local midnight. */
    private record Point(TideExtremeType type, int minutes, double heightMetres) {
    }

    /**
     * Builds the run rows for the given dates, or an empty list when no run can be drawn.
     *
     * @param dates            the run's dates, ascending; one topic exists per date
     * @param coastalLocations the enabled coastal locations to choose a representative from
     * @param king             true for a perigean (king) run, false for an ordinary spring run
     * @return the run days keyed by date — a date is absent when its tide could not be derived
     */
    public Map<LocalDate, TideRunDay> build(List<LocalDate> dates,
            List<LocationEntity> coastalLocations, boolean king) {
        if (dates == null || dates.isEmpty() || coastalLocations == null
                || coastalLocations.isEmpty()) {
            return Map.of();
        }
        List<LocalDate> ordered = dates.stream().sorted().distinct().toList();
        Map<Long, List<TideExtremeEntity>> byLocation = fetchExtremes(coastalLocations, ordered);
        if (byLocation.isEmpty()) {
            return Map.of();
        }

        LocationEntity representative = selectRepresentative(coastalLocations, byLocation, ordered);
        if (representative == null) {
            return Map.of();
        }

        Map<LocalDate, DayTides> perDay = new LinkedHashMap<>();
        for (LocalDate date : ordered) {
            DayTides day = dayTides(byLocation.get(representative.getId()), date);
            if (day != null) {
                perDay.put(date, day);
            }
        }
        if (perDay.isEmpty()) {
            return Map.of();
        }

        double peakRange = perDay.values().stream()
                .mapToDouble(DayTides::range).max().orElse(Double.NaN);
        Optional<TideStats> stats = tideService.getTideStats(representative.getId());
        BigDecimal avgRange = stats.map(TideStats::avgRangeMetres).orElse(null);
        BigDecimal springThreshold = king
                ? stats.map(TideStats::springTideThreshold).orElse(null) : null;

        Map<LocalDate, TideRunDay> result = new LinkedHashMap<>();
        int dayNumber = 1;
        for (Map.Entry<LocalDate, DayTides> entry : perDay.entrySet()) {
            result.put(entry.getKey(), buildDay(entry.getKey(), entry.getValue(), representative,
                    dayNumber++, perDay.size(), peakRange, avgRange, springThreshold, king));
        }
        return result;
    }

    private Map<Long, List<TideExtremeEntity>> fetchExtremes(List<LocationEntity> coastalLocations,
            List<LocalDate> ordered) {
        List<Long> ids = coastalLocations.stream()
                .map(LocationEntity::getId).filter(java.util.Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        // A local day's extrema can sit either side of the UTC date boundary, so the fetch window
        // is the local span converted to UTC rather than the bare dates.
        LocalDateTime from = ordered.get(0).atStartOfDay(LONDON)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime to = ordered.get(ordered.size() - 1).plusDays(1).atStartOfDay(LONDON)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        Map<Long, List<TideExtremeEntity>> byLocation = new LinkedHashMap<>();
        for (TideExtremeEntity extreme : tideExtremeRepository
                .findByLocationIdInAndEventTimeBetweenOrderByEventTimeAsc(ids, from, to)) {
            byLocation.computeIfAbsent(extreme.getLocationId(), k -> new ArrayList<>()).add(extreme);
        }
        return byLocation;
    }

    /**
     * Picks the location the run is drawn for: the configured anchor when it is in this run and
     * has a drawable day, otherwise the biggest single-day range anywhere in the run.
     *
     * <p><b>Why an anchor exists at all.</b> Biggest-range is not a neutral rule on a roster that
     * spans a coastline. Tidal range on this coast grows southward toward the Humber approaches,
     * so the maximum reliably lands at the southern end of the roster — a 53-location set running
     * Bamburgh to Bridlington anchored every spring run to a cove on Flamborough Head, ~90 miles
     * from the reader. The footer names the representative as the honest caveat, but naming a
     * place the reader cannot locate carries no information: it makes the numbers read as
     * arbitrary rather than as measurements of somewhere. Range picks the most dramatic version
     * of the event; the anchor picks the most legible one, and legibility is what the footer was
     * for.
     *
     * <p>The anchor must still be <em>drawable</em> — matching by name is not enough, since a
     * location with no extremes for any date in the run would yield an anchored row with no
     * curve. A run that does not reach the anchor (a Yorkshire-only topic, say) falls back
     * silently rather than claiming a coastline it never covered.
     *
     * <p>Matched on name rather than id because ids are environment-specific: the same anchor has
     * different ids in local H2 and production, so a configured id would be a foot-gun that
     * silently selects the wrong coast. The trade is that renaming a location in the Admin UI
     * quietly disables the preference — the fallback then applies, so the row degrades to the
     * previous behaviour rather than breaking.
     */
    private LocationEntity selectRepresentative(List<LocationEntity> coastalLocations,
            Map<Long, List<TideExtremeEntity>> byLocation, List<LocalDate> ordered) {
        LocationEntity anchor = findAnchor(coastalLocations, byLocation, ordered);
        if (anchor != null) {
            return anchor;
        }
        LocationEntity best = null;
        double bestRange = Double.NEGATIVE_INFINITY;
        for (LocationEntity location : coastalLocations.stream()
                .sorted(Comparator.comparing(LocationEntity::getName,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList()) {
            List<TideExtremeEntity> extremes = byLocation.get(location.getId());
            if (extremes == null) {
                continue;
            }
            for (LocalDate date : ordered) {
                DayTides day = dayTides(extremes, date);
                if (day != null && day.range() > bestRange) {
                    bestRange = day.range();
                    best = location;
                }
            }
        }
        return best;
    }

    /**
     * The configured anchor, when it is in this run and has at least one drawable day.
     *
     * @param coastalLocations the run's candidate locations
     * @param byLocation       extremes keyed by location id
     * @param ordered          the run's dates, ascending
     * @return the anchor location, or null when unconfigured, absent from this run, or undrawable
     */
    private LocationEntity findAnchor(List<LocationEntity> coastalLocations,
            Map<Long, List<TideExtremeEntity>> byLocation, List<LocalDate> ordered) {
        if (preferredAnchor == null || preferredAnchor.isBlank()) {
            return null;
        }
        String wanted = preferredAnchor.trim();

        // Exact first, always. A configuration that names a roster entry outright must never be
        // resolved by the tolerant pass below, so a deliberate exact name cannot be beaten by a
        // punctuation-equal neighbour.
        LocationEntity named = matching(coastalLocations,
                name -> name.equalsIgnoreCase(wanted));
        if (named == null) {
            String normalised = normalise(wanted);
            named = normalised.isEmpty() ? null
                    : matching(coastalLocations, name -> normalise(name).equals(normalised));
            if (named != null) {
                warnOnce(wanted, "resolved to '" + LogSanitizer.sanitize(named.getName())
                        + "' by ignoring punctuation and spacing. The run still draws correctly;"
                        + " align photocast.tide-run.preferred-anchor with the location's name to"
                        + " silence this.");
            }
        }
        if (named == null) {
            warnOnce(wanted, "is not in this run's coastal roster of " + coastalLocations.size()
                    + " location(s); falling back to the biggest single-day range, which may be"
                    + " far from the reader. Check the name in Location Management.");
            return null;
        }

        List<TideExtremeEntity> extremes = byLocation.get(named.getId());
        for (LocalDate date : ordered) {
            if (dayTides(extremes, date) != null) {
                return named;
            }
        }
        // Named but undrawable: stop looking rather than matching a same-named location
        // elsewhere in the roster, and let the caller fall back to biggest range.
        warnOnce(wanted, "is in the run but has no day with both a high and a low water, so it"
                + " cannot be drawn; falling back to the biggest single-day range. This usually"
                + " means its tide extremes have not been refreshed for these dates.");
        return null;
    }

    /** First roster entry whose non-null name satisfies {@code test}, or null. */
    private static LocationEntity matching(List<LocationEntity> coastalLocations,
            java.util.function.Predicate<String> test) {
        for (LocationEntity location : coastalLocations) {
            if (location.getName() != null && test.test(location.getName())) {
                return location;
            }
        }
        return null;
    }

    /** A place name reduced to its letters and digits, lower-cased — see {@link #NON_ALPHANUMERIC}. */
    private static String normalise(String name) {
        return name.replaceAll(NON_ALPHANUMERIC, "").toLowerCase(Locale.UK);
    }

    /**
     * Logs an anchor problem once per distinct configured name.
     *
     * <p>The anchor's whole job is to keep the run legible, so when it does not apply the reader
     * gets a place they cannot locate and no indication that anything went wrong. That silence is
     * the defect worth fixing, not the fallback itself — the fallback is correct behaviour for a
     * run the anchor genuinely does not reach.
     */
    private void warnOnce(String wanted, String problem) {
        if (!wanted.equals(warnedAnchor.getAndSet(wanted))) {
            LOG.warn("[TIDE RUN] Configured anchor '{}' {}", LogSanitizer.sanitize(wanted), problem);
        }
    }

    /** Reduces one location's extrema to the given local day, or null when the day is incomplete. */
    private DayTides dayTides(List<TideExtremeEntity> extremes, LocalDate date) {
        if (extremes == null) {
            return null;
        }
        List<Point> points = new ArrayList<>();
        for (TideExtremeEntity extreme : extremes) {
            LocalDateTime local = extreme.getEventTime().atOffset(ZoneOffset.UTC)
                    .atZoneSameInstant(LONDON).toLocalDateTime();
            if (!local.toLocalDate().equals(date) || extreme.getHeightMetres() == null) {
                continue;
            }
            points.add(new Point(extreme.getType(), local.toLocalTime().toSecondOfDay() / 60,
                    extreme.getHeightMetres().doubleValue()));
        }
        points.sort(Comparator.comparingInt(Point::minutes));
        double high = points.stream().filter(p -> p.type() == TideExtremeType.HIGH)
                .mapToDouble(Point::heightMetres).max().orElse(Double.NaN);
        double low = points.stream().filter(p -> p.type() == TideExtremeType.LOW)
                .mapToDouble(Point::heightMetres).min().orElse(Double.NaN);
        // No high or no low means no derivable range and no drawable curve. Drop the day rather
        // than charting half a tide.
        return Double.isNaN(high) || Double.isNaN(low) ? null : new DayTides(points, high, low);
    }

    private TideRunDay buildDay(LocalDate date, DayTides day, LocationEntity location,
            int dayNumber, int dayCount, double peakRange, BigDecimal avgRange,
            BigDecimal springThreshold, boolean king) {
        int sunriseMinutes = localMinutes(
                solarService.sunriseUtc(location.getLat(), location.getLon(), date));
        int sunsetMinutes = localMinutes(
                solarService.sunsetUtc(location.getLat(), location.getLon(), date));

        TideExtremeType useful = king ? TideExtremeType.HIGH : TideExtremeType.LOW;
        Point usefulPoint = nearestSolar(day.points(), useful, sunriseMinutes, sunsetMinutes);
        Point otherPoint = nearestSolar(day.points(),
                king ? TideExtremeType.LOW : TideExtremeType.HIGH, sunriseMinutes, sunsetMinutes);

        // A one-day run has no peak to point at — every day is trivially the biggest, and a "peak
        // range" badge on a lone card claims a comparison that was never made.
        boolean peak = dayCount > 1 && Math.abs(day.range() - peakRange) < 1e-9;
        long gap = usefulPoint == null ? Long.MAX_VALUE
                : signedGap(usefulPoint.minutes(), sunriseMinutes, sunsetMinutes);
        boolean aligned = usefulPoint != null && Math.abs(gap) <= ALIGNED_WINDOW_MINUTES;

        return new TideRunDay(
                king ? KING_RUN_LABEL : SPRING_RUN_LABEL,
                dayNumber,
                dayCount,
                DAY_LABEL.format(date).toUpperCase(Locale.UK),
                location.getName(),
                metres(day.range()),
                rangeAnomaly(day.range(), avgRange),
                // King runs carry the absolute high water as well. The range against the mean is a
                // SPRING framing — what makes a tide king is how far the water clears the spring
                // threshold, and swapping the chart in for the fact chips would otherwise lose the
                // one number that distinguishes the two events.
                king ? metres(day.high()) : null,
                king ? springExcess(day.high(), springThreshold) : null,
                clock(sunriseMinutes),
                clock(sunsetMinutes),
                seas(location.getId(), date, usefulPoint, sunriseMinutes, sunsetMinutes),
                day.points().stream()
                        .map(p -> new TideRunDay.Extreme(
                                p.type() == TideExtremeType.HIGH ? "H" : "L", clock(p.minutes())))
                        .toList(),
                verdict(usefulPoint, otherPoint, sunriseMinutes, sunsetMinutes, aligned, peak, king),
                aligned,
                peak,
                // The editorial line is claimed ONLY on an aligned day. It states what the event
                // gives a photographer — "low water bares the foreground" — and on an unaligned day
                // that is true of the water and useless to the reader: the foreground is bared at
                // 01:00, in the dark. The verdict beside it already says the water misses the
                // light, so printing the draw anyway had the two lines arguing, with the more
                // confident-sounding one wrong. Silence is the honest form of "not tonight".
                aligned ? (king ? KING_PHRASE : SPRING_PHRASE) : null);
    }

    /** The extremum of the given type sitting closest to either solar event, or null if none. */
    private static Point nearestSolar(List<Point> points, TideExtremeType type,
            int sunriseMinutes, int sunsetMinutes) {
        return points.stream()
                .filter(p -> p.type() == type)
                .min(Comparator.comparingLong(
                        p -> Math.abs(signedGap(p.minutes(), sunriseMinutes, sunsetMinutes))))
                .orElse(null);
    }

    /**
     * Minutes from the nearer solar event to the extremum — positive when the water comes after the
     * sun. Deliberately does not wrap midnight: both solar events sit inside the local day, so a
     * 23:50 low water is 2h31 after sunset, not five hours before the next morning's sunrise.
     */
    private static long signedGap(int minutes, int sunriseMinutes, int sunsetMinutes) {
        long fromSunrise = minutes - (long) sunriseMinutes;
        long fromSunset = minutes - (long) sunsetMinutes;
        return Math.abs(fromSunrise) <= Math.abs(fromSunset) ? fromSunrise : fromSunset;
    }

    /** The solar event the extremum is measured against — the nearer of sunrise and sunset. */
    private static String solarWord(int minutes, int sunriseMinutes, int sunsetMinutes) {
        return Math.abs(minutes - (long) sunriseMinutes) <= Math.abs(minutes - (long) sunsetMinutes)
                ? "sunrise" : "sunset";
    }

    /**
     * The plain-language alignment call, in priority order: the aligned day states the gap exactly;
     * a day whose <em>other</em> extremum sits on a solar event says so, since "high water at
     * sunrise" is itself a reason to go; the peak day leads with its range; and everything else
     * places the useful water in the day and measures it to the nearer event.
     *
     * <p>Kept under ~40 characters — the verdict column is 224px of monospace.
     */
    private static String verdict(Point useful, Point other, int sunriseMinutes, int sunsetMinutes,
            boolean aligned, boolean peak, boolean king) {
        if (useful == null) {
            return peak ? "peak range" : "no clear tide/sun alignment";
        }
        String usefulLabel = king ? "HW" : "LW";
        String otherLabel = king ? "LW" : "HW";
        long gap = signedGap(useful.minutes(), sunriseMinutes, sunsetMinutes);
        String against = offsetPhrase(gap, solarWord(useful.minutes(), sunriseMinutes, sunsetMinutes));

        if (aligned) {
            return usefulLabel + " " + clock(useful.minutes()) + " · " + against;
        }
        if (other != null) {
            long otherGap = signedGap(other.minutes(), sunriseMinutes, sunsetMinutes);
            if (Math.abs(otherGap) <= COINCIDENT_MINUTES) {
                return otherLabel + " at "
                        + solarWord(other.minutes(), sunriseMinutes, sunsetMinutes)
                        + " · " + usefulLabel + " " + against;
            }
        }
        if (peak) {
            return "peak range · " + usefulLabel + " " + against;
        }
        return usefulLabel + " " + timeOfDay(useful.minutes()) + " · " + against;
    }

    /**
     * {@code "34m after sunrise"} / {@code "2h31 before sunset"} — or {@code "at sunrise"} when the
     * water lands on the event. "0m after sunrise" is a null statement dressed as a measurement,
     * and it fires on the single most emphatic day a run can have.
     */
    private static String offsetPhrase(long signedMinutes, String solarWord) {
        long magnitude = Math.abs(signedMinutes);
        if (magnitude <= COINCIDENT_ROUNDING_MINUTES) {
            return "at " + solarWord;
        }
        String duration = magnitude < 60
                ? magnitude + "m"
                : String.format(Locale.UK, "%dh%02d", magnitude / 60, magnitude % 60);
        return duration + (signedMinutes >= 0 ? " after " : " before ") + solarWord;
    }

    /** Places an unaligned extremum in the day without repeating its clock time. */
    private static String timeOfDay(int minutes) {
        int hour = minutes / 60;
        if (hour < 5) {
            return "overnight";
        }
        if (hour < 8) {
            return "early morning";
        }
        if (hour < 11) {
            return "mid-morning";
        }
        if (hour < 14) {
            return "midday";
        }
        if (hour < 17) {
            return "afternoon";
        }
        if (hour < 20) {
            return "early evening";
        }
        if (hour < 23) {
            return "late evening";
        }
        return "overnight";
    }

    /**
     * The sea state sampled for the solar event the useful water is measured against, falling back
     * to the other event — a sample exists per (location, date, event), and either one describes
     * the same day's sea well enough for a qualifier.
     */
    private String seas(Long locationId, LocalDate date, Point useful,
            int sunriseMinutes, int sunsetMinutes) {
        TargetType preferred = useful != null
                && "sunset".equals(solarWord(useful.minutes(), sunriseMinutes, sunsetMinutes))
                ? TargetType.SUNSET : TargetType.SUNRISE;
        TargetType fallback = preferred == TargetType.SUNSET
                ? TargetType.SUNRISE : TargetType.SUNSET;
        return sample(locationId, date, preferred)
                .or(() -> sample(locationId, date, fallback))
                .map(hs -> metres(hs) + " · " + SeaState.fromHs(hs).label())
                .orElse(null);
    }

    private Optional<Double> sample(Long locationId, LocalDate date, TargetType event) {
        return marineWaveRepository
                .findByLocation_IdAndEvaluationDateAndEventType(locationId, date, event)
                .map(MarineWaveEntity::getSignificantWaveHeightMetres);
    }

    /**
     * How far a king run's high water clears the location's spring-tide threshold — the excess that
     * makes it king rather than merely spring. Only ever positive: a "king" tide below the spring
     * threshold has nothing to claim, so it is left unstated rather than shown as a negative.
     */
    private static String springExcess(double highWater, BigDecimal springThreshold) {
        if (springThreshold == null) {
            return null;
        }
        double excess = highWater - springThreshold.doubleValue();
        return excess > MIN_ANOMALY_METRES ? "+" + metres(excess) + " over spring" : null;
    }

    private static String rangeAnomaly(double range, BigDecimal avgRangeMetres) {
        if (avgRangeMetres == null) {
            return null;
        }
        double anomaly = range - avgRangeMetres.doubleValue();
        if (Math.abs(anomaly) < MIN_ANOMALY_METRES) {
            return null;
        }
        return String.format(Locale.UK, "%+.1f", anomaly);
    }

    private static int localMinutes(LocalDateTime utc) {
        return utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(LONDON)
                .toLocalTime().toSecondOfDay() / 60;
    }

    private static String clock(int minutes) {
        return LocalTime.ofSecondOfDay(Math.floorMod(minutes, 1440) * 60L).format(CLOCK);
    }

    private static String metres(double value) {
        return String.format(Locale.UK, "%.1f m", value);
    }
}
