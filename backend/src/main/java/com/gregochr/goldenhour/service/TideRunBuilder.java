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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import java.util.OptionalDouble;
import java.util.Objects;

/**
 * Builds the per-day {@link TideRunDay} rows behind a spring or king tide pill's 24-hour chart.
 *
 * <p>A spring tide is a multi-day <em>run</em>, not a set of unrelated days, and the one question it
 * has to answer is <em>does a useful water land near a solar event?</em> Answering that needs tide
 * clock times and solar clock times side by side, which is what this builder assembles: the day's
 * extrema, its sunrise and sunset, its range against the location's own mean, the sea state, and a
 * plain-language {@link TideRunDay#verdict()} stating the alignment in words.
 *
 * <h2>The run is framed by the water that lands in the light, not by its lunar label</h2>
 *
 * <p>This used to hard-code the answer — {@code useful = king ? HIGH : LOW} — so every spring run was
 * framed around low water and every king run around high. That is a claim about what a reader came
 * to photograph, decided by the moon. It is wrong for most of this roster: a spring tide's gift is a
 * big <em>range</em>, which lifts high water exactly as far as it drops low water, and most coastal
 * locations here are configured to shoot the high one.
 *
 * <p>So there is no "useful" water any more. Each day names the extremum sitting nearest a solar
 * event, and calls itself aligned when that water is inside {@link #ALIGNED_WINDOW_MINUTES}. <b>One
 * point drives everything the row says</b> — {@link TideRunDay#verdict()},
 * {@link TideRunDay#aligned()}, {@link TideRunDay#alignedEvent()},
 * the sea-state sample and the editorial {@link TideRunDay#phrase()} — so the row can no longer
 * contradict itself. It used to, repeatedly:
 * two candidate waters and two thresholds produced a headline denying an alignment the line beneath
 * it had just stated, and each fix bolted on another rule keyed to a different point. That
 * contradiction is now structurally impossible rather than defended by rules.
 *
 * <p>The editorial phrase follows the <b>water</b>, which is what it was always describing —
 * {@link #LOW_WATER_PHRASE} is about a bared foreground, {@link #HIGH_WATER_PHRASE} about a
 * submerged one. They read as run-type phrases only because the run type had already picked the
 * water.
 *
 * <p>The tally in {@link #rosterAlignment} stays <b>astronomical</b>, applying the same rule at every
 * location's own sunrise and sunset. It deliberately does not consult each location's
 * {@link com.gregochr.goldenhour.entity.TideType} preference: a preference-weighted count answers a
 * different question from the badge above it, and that mismatch is what once printed "no tide
 * alignments" over a chart drawing one.
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

    /** The run chip text for an ordinary spring run. */
    public static final String SPRING_RUN_LABEL = "SPRING RUN";

    /** The run chip text for a perigean (king) run. */
    public static final String KING_RUN_LABEL = "KING RUN";

    /**
     * The draw when <b>low</b> water is the one that lands in the light — a big range bares ground
     * that is normally covered. Keyed to the water, not to the run: a spring run whose high water is
     * the one in the light has nothing to say about an exposed foreground.
     */
    public static final String LOW_WATER_PHRASE = "low water bares the foreground";

    /**
     * The draw when <b>high</b> water is the one that lands in the light — the same tide, a
     * different picture. Keyed to the water for the reason given on {@link #LOW_WATER_PHRASE}.
     */
    public static final String HIGH_WATER_PHRASE =
            "causeways & foreshore submerged — shoot reflections";

    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final DateTimeFormatter DAY_LABEL =
            DateTimeFormatter.ofPattern("EEE d", Locale.UK);

    /**
     * Within this many minutes of a solar event, a water counts as landing in the light.
     *
     * <p>The <b>only</b> threshold in this class, deliberately. There used to be a second one at 30
     * minutes, and the two answered the same question — <em>is this water near a solar event?</em> —
     * with the stricter of them guarding the more useful sentence. A high water 58 minutes after
     * sunrise fell between them and was discarded in favour of a low water five hours out, in the
     * dark. The wording that second threshold existed for ("at sunrise" rather than "0m after
     * sunrise") belongs to {@link TideWording#offsetPhrase}, which applies it to every form here.
     */
    static final long ALIGNED_WINDOW_MINUTES = 60;

    private final TideExtremeRepository tideExtremeRepository;
    private final MarineWaveRepository marineWaveRepository;
    private final TideService tideService;
    private final SolarService solarService;

    /**
     * This builder's own representative selector.
     *
     * <p>Constructed here rather than injected because it carries warn-once state: sharing one
     * instance with the Plan tab's window rollup would warn for whichever caller ran first and stay
     * silent for the other. See {@link TideRepresentativeSelector}.
     */
    private final TideRepresentativeSelector representativeSelector;

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
        this.representativeSelector = new TideRepresentativeSelector(preferredAnchor);
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
     * A day's solar times and the water its row is about, computed once.
     *
     * <p>Exists so every field of the row is answered from the <em>same</em> arithmetic, rather than
     * several places each deciding which water a day is about — the class of split this builder has
     * already been bitten by twice.
     *
     * @param sunriseMinutes the day's sunrise, minutes past local midnight
     * @param sunsetMinutes  the day's sunset, minutes past local midnight
     * @param nearest        the extremum nearest a solar event, whatever its distance
     * @param inLight        that extremum when it falls inside the alignment window, else null
     */
    private record DayGeometry(int sunriseMinutes, int sunsetMinutes, Point nearest, Point inLight) {
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
        return buildWithEventWaters(dates, coastalLocations, king).days();
    }

    /**
     * The high water at each solar event on one day of a run, at the run's representative.
     *
     * <p>Deliberately <b>not</b> a field of {@link TideRunDay}: that record is {@code HotTopic.tideRun},
     * persisted in {@code daily_briefing_cache} and compared by value on every serve, so growing it
     * would touch every constructor site and invite the "recomputed live" trap. Only the Coming up
     * almanac source reads this.
     *
     * @param sunrise the high water nearest sunrise that day, or null when the day has no high water
     * @param sunset  the high water nearest sunset that day, or null when the day has no high water
     */
    public record EventWaters(EventWater sunrise, EventWater sunset) {
    }

    /**
     * One day's high water nearest one solar event.
     *
     * <p>"Nearest" means nearest <em>within that Europe/London civil day</em> — a summer 04:30
     * sunrise's true nearest high water may be the previous evening's, which belongs to the previous
     * day's row. Accepted and stated here rather than reached for across midnight.
     *
     * @param minutes    the high water, minutes past local midnight
     * @param gapMinutes minutes from the event to the water — positive when the water comes after
     *                   the event; never wrapped across midnight
     * @param aligned    true when {@code |gapMinutes|} is within {@link #ALIGNED_WINDOW_MINUTES}
     */
    public record EventWater(int minutes, long gapMinutes, boolean aligned) {
    }

    /**
     * A run built with its per-event high waters, both from the same extremes fetch and the same
     * per-day geometry pass.
     *
     * @param days        the run days keyed by date, exactly as {@link #build} returns them
     * @param eventWaters each derivable day's high water at sunrise and at sunset, keyed by date
     */
    public record RunBuild(Map<LocalDate, TideRunDay> days, Map<LocalDate, EventWaters> eventWaters) {

        /** An empty build — no day could be derived. */
        static RunBuild empty() {
            return new RunBuild(Map.of(), Map.of());
        }
    }

    /**
     * Builds the run rows and, from the same fetch, each day's high water at sunrise and at sunset.
     *
     * <p>{@link #build} delegates here, so its callers are unchanged. The event waters are a separate
     * output because {@link TideRunDay} is persisted and compared by value (see {@link EventWaters}).
     *
     * @param dates            the run's dates, ascending; one topic exists per date
     * @param coastalLocations the enabled coastal locations to choose a representative from
     * @param king             true for a perigean (king) run, false for an ordinary spring run
     * @return the run days and event waters keyed by date — a date is absent when its tide could not
     *         be derived
     */
    public RunBuild buildWithEventWaters(List<LocalDate> dates,
            List<LocationEntity> coastalLocations, boolean king) {
        if (dates == null || dates.isEmpty() || coastalLocations == null
                || coastalLocations.isEmpty()) {
            return RunBuild.empty();
        }
        List<LocalDate> ordered = dates.stream().sorted().distinct().toList();
        Map<Long, List<TideExtremeEntity>> byLocation = fetchExtremes(coastalLocations, ordered);
        if (byLocation.isEmpty()) {
            return RunBuild.empty();
        }

        LocationEntity representative = representativeSelector.select(
                coastalLocations, byLocation, ordered, TideRunBuilder::rangeOn);
        if (representative == null) {
            return RunBuild.empty();
        }

        Map<LocalDate, DayTides> perDay = new LinkedHashMap<>();
        for (LocalDate date : ordered) {
            DayTides day = dayTides(byLocation.get(representative.getId()), date);
            if (day != null) {
                perDay.put(date, day);
            }
        }
        if (perDay.isEmpty()) {
            return RunBuild.empty();
        }

        double peakRange = perDay.values().stream()
                .mapToDouble(DayTides::range).max().orElse(Double.NaN);
        TideStats stats = tideService.getTideStats(representative.getId()).orElse(null);

        Map<LocalDate, TideRunDay.RosterAlignment> roster =
                rosterAlignment(coastalLocations, byLocation, perDay.keySet());

        // Each day's solar times and the water its row is about, computed once for the run.
        Map<LocalDate, DayGeometry> geometry = new LinkedHashMap<>();
        for (Map.Entry<LocalDate, DayTides> entry : perDay.entrySet()) {
            geometry.put(entry.getKey(),
                    geometryOf(representative, entry.getKey(), entry.getValue()));
        }

        Map<LocalDate, TideRunDay> result = new LinkedHashMap<>();
        int dayNumber = 1;
        for (Map.Entry<LocalDate, DayTides> entry : perDay.entrySet()) {
            result.put(entry.getKey(), buildDay(entry.getKey(), entry.getValue(), representative,
                    geometry.get(entry.getKey()), dayNumber++, perDay.size(), peakRange, stats,
                    king, roster.get(entry.getKey())));
        }

        Map<LocalDate, EventWaters> eventWaters = new LinkedHashMap<>();
        for (Map.Entry<LocalDate, DayTides> entry : perDay.entrySet()) {
            DayGeometry geo = geometry.get(entry.getKey());
            eventWaters.put(entry.getKey(), new EventWaters(
                    highWaterAt(entry.getValue().points(), geo.sunriseMinutes()),
                    highWaterAt(entry.getValue().points(), geo.sunsetMinutes())));
        }
        return new RunBuild(result, eventWaters);
    }

    /**
     * The day's high water nearest ONE named solar event, or null when the day has no high water.
     *
     * <p>Its own helper, measuring to the one event it is given. {@link #nearestSolar} and
     * {@link #signedGap} measure to the <em>nearer</em> of the two events, so a midday water that is
     * nearest sunset but nearer sunrise would be worded against the wrong event; reusing them here
     * would print that. Ties go to the earlier water ({@code points} is sorted by clock time and the
     * comparison is strict).
     */
    private static EventWater highWaterAt(List<Point> points, int eventMinutes) {
        Point best = null;
        for (Point point : points) {
            if (point.type() != TideExtremeType.HIGH) {
                continue;
            }
            if (best == null || Math.abs(eventGap(point.minutes(), eventMinutes))
                    < Math.abs(eventGap(best.minutes(), eventMinutes))) {
                best = point;
            }
        }
        if (best == null) {
            return null;
        }
        long gap = eventGap(best.minutes(), eventMinutes);
        return new EventWater(best.minutes(), gap, Math.abs(gap) <= ALIGNED_WINDOW_MINUTES);
    }

    /**
     * Minutes from one named solar event to a water — positive when the water comes after the event.
     * Does not wrap midnight, for the same reason {@link #signedGap} does not.
     */
    private static long eventGap(int minutes, int eventMinutes) {
        return (long) minutes - eventMinutes;
    }

    /**
     * A run's chosen representative location and its own peak range, in metres.
     *
     * <p>Exists alongside {@link #build} because {@link TideRunDay#range()} is a formatted display
     * string ({@code "3.6 m"}), and the "Coming up" surprise model (plan D4) needs the raw number
     * to compare a run's peak against a historical distribution.
     *
     * @param representative the coastal location the run's figures were drawn for
     * @param rangeMetres    the run's biggest single-day range in metres
     */
    public record RunPeak(LocationEntity representative, double rangeMetres) {
    }

    /**
     * The run's representative location and its own peak range, chosen and computed the same way
     * {@link #build} does internally, exposed separately because {@link TideRunDay#range()} only
     * carries the formatted display string.
     *
     * @param dates            the run's dates, ascending
     * @param coastalLocations the enabled coastal locations to choose a representative from
     * @return the representative and its peak range, or empty when neither could be derived
     */
    public Optional<RunPeak> peakRange(List<LocalDate> dates, List<LocationEntity> coastalLocations) {
        if (dates == null || dates.isEmpty() || coastalLocations == null
                || coastalLocations.isEmpty()) {
            return Optional.empty();
        }
        List<LocalDate> ordered = dates.stream().sorted().distinct().toList();
        Map<Long, List<TideExtremeEntity>> byLocation = fetchExtremes(coastalLocations, ordered);
        if (byLocation.isEmpty()) {
            return Optional.empty();
        }
        LocationEntity representative = representativeSelector.select(
                coastalLocations, byLocation, ordered, TideRunBuilder::rangeOn);
        if (representative == null) {
            return Optional.empty();
        }
        OptionalDouble peak = peakOf(ordered, byLocation.get(representative.getId()));
        return peak.isPresent()
                ? Optional.of(new RunPeak(representative, peak.getAsDouble()))
                : Optional.empty();
    }

    /**
     * The peak daily range in metres at a <b>fixed</b> representative location, rather than one
     * selected per call.
     *
     * <p>Used to build a run-peak-range history at the same port a forward run was drawn for (plan
     * D4) — the magnitude distribution compares like against like, so the representative must not
     * float between the run being scored and the history it is scored against.
     *
     * @param representative the fixed location to measure
     * @param dates          the run's dates, ascending
     * @return the biggest single-day range in metres, or empty when no day in the range is
     *         drawable at that location
     */
    public OptionalDouble peakRangeAt(LocationEntity representative, List<LocalDate> dates) {
        if (representative == null || dates == null || dates.isEmpty()) {
            return OptionalDouble.empty();
        }
        List<LocalDate> ordered = dates.stream().sorted().distinct().toList();
        Map<Long, List<TideExtremeEntity>> byLocation =
                fetchExtremes(List.of(representative), ordered);
        return peakOf(ordered, byLocation.get(representative.getId()));
    }

    /**
     * Each derivable day's tidal range in metres at a <b>fixed</b> representative location — the
     * per-day figures the "Coming up" day-chip bars draw (supermoon-tide-run-plan.md S2).
     *
     * <p>A day with no stored extremes (or too few to form a range) is absent, never zero and never
     * synthesised. The representative is the one the run's peak was already measured at; it is never
     * re-selected here.
     *
     * @param representative the fixed location to measure
     * @param dates          the run's dates, in any order
     * @return date to range in metres, in ascending date order; empty on null or empty input
     */
    public Map<LocalDate, Double> dailyRangesAt(LocationEntity representative, List<LocalDate> dates) {
        Map<LocalDate, Double> ranges = new LinkedHashMap<>();
        if (representative == null || dates == null || dates.isEmpty()) {
            return ranges;
        }
        List<LocalDate> ordered = dates.stream().sorted().distinct().toList();
        Map<Long, List<TideExtremeEntity>> byLocation =
                fetchExtremes(List.of(representative), ordered);
        List<TideExtremeEntity> extremes = byLocation.get(representative.getId());
        for (LocalDate date : ordered) {
            DayTides day = dayTides(extremes, date);
            if (day != null) {
                ranges.put(date, day.range());
            }
        }
        return ranges;
    }

    private static OptionalDouble peakOf(List<LocalDate> dates, List<TideExtremeEntity> extremes) {
        return dates.stream()
                .map(date -> dayTides(extremes, date))
                .filter(Objects::nonNull)
                .mapToDouble(DayTides::range)
                .max();
    }

    /**
     * The day's solar times and the water its row is about.
     *
     * @param location the representative the run is drawn for
     * @param date     the local day
     * @param day      that day's extrema
     * @return the geometry every field of the row is derived from
     */
    private DayGeometry geometryOf(LocationEntity location, LocalDate date, DayTides day) {
        int sunriseMinutes = TideWording.londonMinutesOfDay(
                solarService.sunriseUtc(location.getLat(), location.getLon(), date));
        int sunsetMinutes = TideWording.londonMinutesOfDay(
                solarService.sunsetUtc(location.getLat(), location.getLon(), date));
        Point nearest = nearestSolar(day.points(), sunriseMinutes, sunsetMinutes);
        return new DayGeometry(sunriseMinutes, sunsetMinutes, nearest,
                withinWindow(nearest, sunriseMinutes, sunsetMinutes));
    }

    /**
     * Counts, per date, how much of the coastal roster shares the day's alignment.
     *
     * <p><b>Why this exists.</b> The chart is drawn for one representative coastline, and the pill's
     * headline used to pair that single-location claim with the roster's size — reading as though
     * all 61 locations aligned when the geometry stated was one. The count here lets the headline
     * name its own scope.
     *
     * <p><b>It is not a database read.</b> An earlier version of the headline counted
     * {@code forecast_evaluation.tide_aligned} rows, which fails twice over: that flag asks whether
     * the tide <em>state</em> matched each location's configured {@link com.gregochr.goldenhour
     * .entity.TideType} preference — a different question, preference-weighted rather than
     * astronomical — and it is written only by the synchronous engine, which in practice has stopped
     * writing. Every extreme needed here is already in {@code byLocation}; only the per-location
     * solar times are new, and those are arithmetic.
     *
     * <p><b>The rule is identical to the representative's</b>, deliberately: the extremum nearest a
     * solar event, if it lands inside the window. That makes the representative a member of its own
     * tally, so an aligned chart can never print above a zero. It is also why this takes no
     * {@code king} flag — the question is the same one on either kind of run.
     *
     * <p><b>It stays astronomical.</b> Weighting it by each location's
     * {@link com.gregochr.goldenhour.entity.TideType} would ask whether the water matched a stored
     * preference, which is a different question from the one the badge above it asks, and one the
     * badge cannot answer for a location whose preference is unset.
     *
     * @param coastalLocations the roster to measure
     * @param byLocation       every location's stored extremes, already fetched
     * @param dates            the dates the run draws
     * @return the tally per date
     */
    private Map<LocalDate, TideRunDay.RosterAlignment> rosterAlignment(
            List<LocationEntity> coastalLocations,
            Map<Long, List<TideExtremeEntity>> byLocation,
            java.util.Collection<LocalDate> dates) {
        Map<LocalDate, TideRunDay.RosterAlignment> out = new LinkedHashMap<>();
        for (LocalDate date : dates) {
            int sunriseAligned = 0;
            int sunsetAligned = 0;
            int measured = 0;
            for (LocationEntity location : coastalLocations) {
                if (location.getId() == null) {
                    continue;
                }
                DayTides day = dayTides(byLocation.get(location.getId()), date);
                if (day == null) {
                    continue;
                }
                measured++;
                int sunriseMinutes = TideWording.londonMinutesOfDay(
                        solarService.sunriseUtc(location.getLat(), location.getLon(), date));
                int sunsetMinutes = TideWording.londonMinutesOfDay(
                        solarService.sunsetUtc(location.getLat(), location.getLon(), date));
                Point named = waterInTheLight(day, sunriseMinutes, sunsetMinutes);
                if (named == null) {
                    continue;
                }
                if ("sunrise".equals(solarWord(named.minutes(), sunriseMinutes, sunsetMinutes))) {
                    sunriseAligned++;
                } else {
                    sunsetAligned++;
                }
            }
            out.put(date, new TideRunDay.RosterAlignment(sunriseAligned, sunsetAligned, measured));
        }
        return out;
    }

    /**
     * The water a day's row is about: the extremum nearest a solar event, when that water lands
     * inside the alignment window. Null when nothing of this day reaches the light.
     *
     * <p>Type-blind on purpose — see the class Javadoc. Whether a reader wants the high or the low
     * is not the moon's to decide, and the one question this row can actually answer is which water
     * the sun will be on.
     */
    private static Point waterInTheLight(DayTides day, int sunriseMinutes, int sunsetMinutes) {
        return withinWindow(nearestSolar(day.points(), sunriseMinutes, sunsetMinutes),
                sunriseMinutes, sunsetMinutes);
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
     * This builder's drawability and range probe, handed to the shared representative selector.
     *
     * <p>A run needs a chartable curve, which needs both a high and a low water in the local day —
     * exactly what {@link #dayTides} already decides. Supplying it as a probe rather than letting
     * the selector own the rule keeps the run's definition of "drawable" the run's own.
     *
     * @param extremes one location's stored extremes, or null when it has none
     * @param date     the local day to measure
     * @return the day's range in metres, or empty when the day cannot be drawn
     */
    private static OptionalDouble rangeOn(List<TideExtremeEntity> extremes, LocalDate date) {
        DayTides day = dayTides(extremes, date);
        return day == null ? OptionalDouble.empty() : OptionalDouble.of(day.range());
    }

    /** Reduces one location's extrema to the given local day, or null when the day is incomplete. */
    private static DayTides dayTides(List<TideExtremeEntity> extremes, LocalDate date) {
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
            DayGeometry geo, int dayNumber, int dayCount, double peakRange, TideStats stats,
            boolean king, TideRunDay.RosterAlignment roster) {
        int sunriseMinutes = geo.sunriseMinutes();
        int sunsetMinutes = geo.sunsetMinutes();

        // ONE point drives this whole row: the water nearest a solar event, whichever kind it is.
        // The row used to run on two — a "useful" water picked by the run's lunar label, and
        // whatever else happened to land in the light — and every field below had to be told which
        // of them it followed. They disagreed, which is how a headline came to deny an alignment
        // the line directly beneath it stated. See the class Javadoc.
        //
        // It arrives as DayGeometry rather than being derived here so that every field of the row
        // is answered from the same arithmetic.
        Point nearest = geo.nearest();
        Point inLight = geo.inLight();

        // Whether this port's water is actually big today, as opposed to whether the moon says the
        // event is a spring or a king one. Withheld rather than assumed when the location has no
        // observed spring-neap cycle to have a threshold from.
        boolean notablyHigh = stats != null && stats.springTideThreshold() != null
                && day.high() > stats.springTideThreshold().doubleValue();

        // A one-day run has no peak to point at — every day is trivially the biggest, and a "peak
        // range" badge on a lone card claims a comparison that was never made.
        boolean peak = dayCount > 1 && Math.abs(day.range() - peakRange) < 1e-9;
        boolean aligned = inLight != null;

        return new TideRunDay(
                king ? KING_RUN_LABEL : SPRING_RUN_LABEL,
                dayNumber,
                dayCount,
                DAY_LABEL.format(date).toUpperCase(Locale.UK),
                location.getName(),
                TideWording.metres(day.range()),
                rangeAnomaly(day.range(), stats == null ? null : stats.avgRangeMetres()),
                // The absolute high water — the "how big is this one, really" chip.
                //
                // GATED ON SIZE, NOT ON THE LUNAR LABEL. These used to be king-only, which was the
                // spring/king conflation one level down: king is now a statement about the moon
                // (a perigean spring), and the moon does not decide whether this port's water is
                // remarkable. A big spring tide has a notable high water and a reader deciding
                // whether to drive wants the number; a perigean spring at a port having an
                // unremarkable day does not, and would previously have got three chips saying so in
                // three ways. The size test is the port's own spring threshold — the same
                // comparison TideSizeIndex uses to decide which dates a run covers, so the chips and
                // the run's own dates cannot disagree about what counts as big.
                notablyHigh ? TideWording.metres(day.high()) : null,
                TideWording.clock(sunriseMinutes),
                TideWording.clock(sunsetMinutes),
                seas(location.getId(), date, nearest, sunriseMinutes, sunsetMinutes),
                day.points().stream()
                        .map(p -> new TideRunDay.Extreme(
                                p.type() == TideExtremeType.HIGH ? "H" : "L", TideWording.clock(p.minutes())))
                        .toList(),
                verdict(nearest, sunriseMinutes, sunsetMinutes, aligned, peak),
                aligned,
                // Reads `inLight`, like `aligned` above, so the two cannot disagree about which
                // water the day is about.
                inLight == null ? null : solarWord(inLight.minutes(), sunriseMinutes, sunsetMinutes),
                roster,
                peak,
                // The editorial line is claimed ONLY when a water reaches the light, and names the
                // water that got there. On a day where nothing does, the draw is true and useless —
                // the foreground is bared at 01:00, in the dark — and the verdict beside it already
                // says so, so printing it anyway had the two lines arguing with the more
                // confident-sounding one wrong. Silence is the honest form of "not tonight".
                phraseFor(inLight));
    }

    /**
     * The given extremum if it falls inside the alignment window, otherwise null. Uses the same
     * {@link #ALIGNED_WINDOW_MINUTES} as {@code aligned} and as verdict rule 3, so one question —
     * <em>is this water near a solar event?</em> — keeps one answer across the whole row.
     */
    private static Point withinWindow(Point point, int sunriseMinutes, int sunsetMinutes) {
        return point != null
                && Math.abs(signedGap(point.minutes(), sunriseMinutes, sunsetMinutes))
                        <= ALIGNED_WINDOW_MINUTES
                ? point : null;
    }

    /**
     * The extremum sitting closest to either solar event, of whichever type, or null when the day
     * carries no extremes at all.
     *
     * <p><b>Ties break toward the earlier water, not toward a type.</b> {@code points} is sorted by
     * clock time and {@link java.util.stream.Stream#min} keeps the first minimum, so the rule is
     * deterministic without smuggling the old lunar-label preference back in as a tiebreak. Both
     * extremes can genuinely be in the light: they sit about 6h12 apart, and a deep-winter day is
     * short enough for a high water just after sunrise and a low water just before sunset.
     */
    private static Point nearestSolar(List<Point> points, int sunriseMinutes, int sunsetMinutes) {
        return points.stream()
                .min(Comparator.comparingLong(
                        p -> Math.abs(signedGap(p.minutes(), sunriseMinutes, sunsetMinutes))))
                .orElse(null);
    }

    /**
     * The editorial line for the water that reached the light, or null when none did.
     *
     * <p>Keyed to the <b>water</b>, which is what both sentences were always describing. While the
     * run's lunar label picked the water, keying them to the label was indistinguishable from
     * keying them to the water; it stopped being so the moment the label stopped deciding.
     */
    private static String phraseFor(Point inLight) {
        if (inLight == null) {
            return null;
        }
        return inLight.type() == TideExtremeType.HIGH ? HIGH_WATER_PHRASE : LOW_WATER_PHRASE;
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
     * The plain-language alignment call for the water nearest the light — three forms, all of them
     * about the same point.
     *
     * <p>The run's peak day leads with its range and trades the clock time away; otherwise an
     * aligned day states its clock time and the exact gap, and an unaligned day places the water in
     * the day in words and measures it to the nearer event anyway. Nothing is lost by dropping a
     * clock time on the peak day: every extreme is labelled with its own on the chart directly
     * below, and the chart's own labels carry the full clause.
     *
     * <p><b>There is no longer a branch for "the other extremum".</b> There were three, added one at
     * a time as each previous rule was caught naming a water nobody could use, and they disagreed
     * about which point the row was about. The last of them existed only because a second, stricter
     * threshold guarded the more useful sentence — see {@link #ALIGNED_WINDOW_MINUTES}. With one
     * candidate water there is nothing left for those branches to arbitrate.
     *
     * <p>Kept under ~40 characters — the verdict column is 224px of monospace.
     */
    private static String verdict(Point nearest, int sunriseMinutes, int sunsetMinutes,
            boolean aligned, boolean peak) {
        if (nearest == null) {
            return peak ? "peak range" : "no clear tide/sun alignment";
        }
        String label = nearest.type() == TideExtremeType.HIGH ? "HW" : "LW";
        long gap = signedGap(nearest.minutes(), sunriseMinutes, sunsetMinutes);
        String against = TideWording.offsetPhrase(
                gap, solarWord(nearest.minutes(), sunriseMinutes, sunsetMinutes));

        if (peak) {
            return "peak range · " + label + " " + against;
        }
        if (aligned) {
            return label + " " + TideWording.clock(nearest.minutes()) + " · " + against;
        }
        return label + " " + timeOfDay(nearest.minutes()) + " · " + against;
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
     * The sea state sampled for the solar event the day's named water is measured against, falling
     * back to the other event — a sample exists per (location, date, event), and either one
     * describes the same day's sea well enough for a qualifier.
     */
    private String seas(Long locationId, LocalDate date, Point nearest,
            int sunriseMinutes, int sunsetMinutes) {
        TargetType preferred = nearest != null
                && "sunset".equals(solarWord(nearest.minutes(), sunriseMinutes, sunsetMinutes))
                ? TargetType.SUNSET : TargetType.SUNRISE;
        TargetType fallback = preferred == TargetType.SUNSET
                ? TargetType.SUNRISE : TargetType.SUNSET;
        return sample(locationId, date, preferred)
                .or(() -> sample(locationId, date, fallback))
                .map(hs -> TideWording.metres(hs) + " · " + SeaState.fromHs(hs).label())
                .orElse(null);
    }

    private Optional<Double> sample(Long locationId, LocalDate date, TargetType event) {
        return marineWaveRepository
                .findByLocation_IdAndEvaluationDateAndEventType(locationId, date, event)
                .map(MarineWaveEntity::getSignificantWaveHeightMetres);
    }

    private static String rangeAnomaly(double range, BigDecimal avgRangeMetres) {
        if (avgRangeMetres == null) {
            return null;
        }
        double anomaly = range - avgRangeMetres.doubleValue();
        if (Math.abs(anomaly) < TideWording.MIN_ANOMALY_METRES) {
            return null;
        }
        return String.format(Locale.UK, "%+.1f", anomaly);
    }
}
