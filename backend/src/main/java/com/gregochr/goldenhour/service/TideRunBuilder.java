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
 * {@link TideRunDay#alignmentPhrase()}, the sea-state sample and the editorial
 * {@link TideRunDay#phrase()} — so the row can no longer contradict itself. It used to, repeatedly:
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
    private static final long ALIGNED_WINDOW_MINUTES = 60;

    /**
     * Stored extremes (highs and lows together) a location needs before its highest recorded water
     * may be called "the record". At roughly four extremes a day this is about six months, so the
     * claim survives a 12-month backfill and is withheld from a location added last fortnight —
     * where the spring–neap gate alone would let a two-week maximum pass as an all-time high.
     */
    private static final long MIN_POINTS_FOR_RECORD = 700;

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

        LocationEntity representative = representativeSelector.select(
                coastalLocations, byLocation, ordered, TideRunBuilder::rangeOn);
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
        TideStats stats = tideService.getTideStats(representative.getId()).orElse(null);

        Map<LocalDate, TideRunDay.RosterAlignment> roster =
                rosterAlignment(coastalLocations, byLocation, perDay.keySet());

        Map<LocalDate, TideRunDay> result = new LinkedHashMap<>();
        int dayNumber = 1;
        for (Map.Entry<LocalDate, DayTides> entry : perDay.entrySet()) {
            result.put(entry.getKey(), buildDay(entry.getKey(), entry.getValue(), representative,
                    dayNumber++, perDay.size(), peakRange, stats, king,
                    roster.get(entry.getKey())));
        }
        return result;
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
                int sunriseMinutes = localMinutes(
                        solarService.sunriseUtc(location.getLat(), location.getLon(), date));
                int sunsetMinutes = localMinutes(
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
            int dayNumber, int dayCount, double peakRange, TideStats stats, boolean king,
            TideRunDay.RosterAlignment roster) {
        int sunriseMinutes = localMinutes(
                solarService.sunriseUtc(location.getLat(), location.getLon(), date));
        int sunsetMinutes = localMinutes(
                solarService.sunsetUtc(location.getLat(), location.getLon(), date));

        // ONE point drives this whole row: the water nearest a solar event, whichever kind it is.
        // The row used to run on two — a "useful" water picked by the run's lunar label, and
        // whatever else happened to land in the light — and every field below had to be told which
        // of them it followed. They disagreed, which is how a headline came to deny an alignment
        // the line directly beneath it stated. See the class Javadoc.
        Point nearest = nearestSolar(day.points(), sunriseMinutes, sunsetMinutes);
        Point inLight = withinWindow(nearest, sunriseMinutes, sunsetMinutes);

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
                // The absolute high water, its excess over the spring mark, and its rank against the
                // port's record — the "how big is this one, really" chips.
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
                notablyHigh ? springExcess(day.high(), stats.springTideThreshold()) : null,
                notablyHigh ? highWaterRank(day.high(), stats) : null,
                TideWording.clock(sunriseMinutes),
                TideWording.clock(sunsetMinutes),
                seas(location.getId(), date, nearest, sunriseMinutes, sunsetMinutes),
                day.points().stream()
                        .map(p -> new TideRunDay.Extreme(
                                p.type() == TideExtremeType.HIGH ? "H" : "L", TideWording.clock(p.minutes())))
                        .toList(),
                verdict(nearest, sunriseMinutes, sunsetMinutes, aligned, peak),
                aligned,
                // These four all read `inLight`, so they cannot disagree about which water the day
                // is about — the property that used to need a comment per field explaining which of
                // two points it followed, and that still went wrong.
                inLight == null ? null : solarWord(inLight.minutes(), sunriseMinutes, sunsetMinutes),
                alignmentPhrase(inLight, sunriseMinutes, sunsetMinutes),
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
     * The alignment clause for the water the verdict names — {@code "HW 09:08 · 58m after sunrise"}
     * — or null when no water lands in the light.
     *
     * <p>Always the same shape as {@link #verdict}'s aligned branch: label, clock time, offset. It
     * never carries the {@code "peak range · "} prefix and never drops the clock time, because it is
     * consumed where the range is already stated in its own chip and where a fact labelled
     * <em>alignment</em> should open by talking about alignment. Deriving it by trimming the
     * verdict's text was the alternative, and it would make that sentence's punctuation
     * load-bearing — a rewording there would silently corrupt this.
     */
    private static String alignmentPhrase(Point point, int sunriseMinutes, int sunsetMinutes) {
        if (point == null) {
            return null;
        }
        long gap = signedGap(point.minutes(), sunriseMinutes, sunsetMinutes);
        String word = solarWord(point.minutes(), sunriseMinutes, sunsetMinutes);
        return (point.type() == TideExtremeType.HIGH ? "HW" : "LW")
                + " " + TideWording.clock(point.minutes())
                + " · " + TideWording.offsetPhrase(gap, word);
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
     * below, and {@link TideRunDay#alignmentPhrase()} carries the full clause for surfaces that
     * state the range separately.
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

    /**
     * How far a king run's high water clears the location's spring-tide threshold — the excess that
     * makes it king rather than merely spring. Only ever positive: a "king" tide below the spring
     * threshold has nothing to claim, so it is left unstated rather than shown as a negative.
     */
    private static String springExcess(double highWater, BigDecimal springThreshold) {
        // No null guard: the sole call site is behind `notablyHigh`, which is itself defined as
        // "stats and its spring threshold both exist and the water clears the threshold". A check
        // here was unreachable — CodeQL said so on #495 — and worse than redundant: it told a reader
        // this could be called without a threshold, which is the state the gate exists to exclude.
        double excess = highWater - springThreshold.doubleValue();
        return excess > TideWording.MIN_ANOMALY_METRES ? "+" + TideWording.metres(excess) + " over spring" : null;
    }

    /**
     * Where a king run's high water sits against the highest ever recorded at this location — the
     * one reading on the card that says how <em>extraordinary</em> the tide is.
     *
     * <p><b>Why the record and not the mean.</b> The obvious second number is metres over mean high
     * water, and it carries no information: the spring threshold is defined as 1.25 × mean high, so
     * {@code overMean = overSpring + 0.25 × meanHigh} — the two chips would differ by a per-location
     * constant on every king tide the location ever sees. A percentile is no better at this end of
     * the distribution, because the king classification is itself an above-P95 test, so "top 5%"
     * would be true by construction of every card that can display it. The distance to the ceiling
     * is the only baseline left that moves independently of the spring excess.
     *
     * <p>The sample is past extremes only ({@code TideService.getTideStats} stops at the start of
     * today), so today's water is genuinely not in the record it is measured against. Two gates keep
     * the claim honest: {@code p95HighMetres} being present means at least one spring–neap cycle was
     * observed — the same gate the spring chip rides, so the two appear and vanish together — and
     * {@value #MIN_POINTS_FOR_RECORD} extremes means "the record" spans months rather than a
     * fortnight of a newly added location.
     *
     * @param highWater the day's highest water in metres
     * @param stats     the location's historical tide statistics, or null when it has none
     * @return the rank wording, or null when it cannot be claimed
     */
    private static String highWaterRank(double highWater, TideStats stats) {
        if (stats == null || stats.maxHighMetres() == null
                || stats.p95HighMetres() == null
                || stats.dataPoints() < MIN_POINTS_FOR_RECORD) {
            return null;
        }
        double shortfall = stats.maxHighMetres().doubleValue() - highWater;
        return shortfall <= TideWording.MIN_ANOMALY_METRES
                ? "highest recorded here"
                : TideWording.metres(shortfall) + " off the record";
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

    private static int localMinutes(LocalDateTime utc) {
        return utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(LONDON)
                .toLocalTime().toSecondOfDay() / 60;
    }
}
