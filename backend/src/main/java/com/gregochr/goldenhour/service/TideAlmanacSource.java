package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.model.AlmanacEvent;
import com.gregochr.goldenhour.model.AlmanacKind;
import com.gregochr.goldenhour.model.TideRunDay;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.comingup.ComingUpScoringProperties;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.function.Function;

/**
 * Spring and king tide runs across the whole feed, detected from the lunar cycle and enriched from
 * stored tide extremes where they reach.
 *
 * <p><strong>Two axes, and neither may answer for the other.</strong> <em>Which dates</em> a run
 * covers is measured from the water: {@link TideSizeIndex} applies the Plan tab's own height tests
 * across the whole feed. <em>What kind of event</em> it is comes from the moon: a king run is a
 * perigean spring, which is what the word means and what the copy describes. The lunar classifier
 * is also the <em>fallback</em> for dates when the database cannot answer at all.
 *
 * <p><strong>Why the moon alone was wrong.</strong> {@code classifyTide} qualifies a date within
 * ±1 day of syzygy, and a port's biggest tide lags syzygy by a day or two — the age of the tide,
 * a property of the coastline that no epoch arithmetic recovers. Around the August 2026 new moon the
 * lunar window covered 12–13 Aug while the roster's biggest water landed on 14–15 Aug, so the Plan
 * tab carried a tide card on the Friday and Saturday and this feed showed nothing for either day.
 *
 * <p>The two sources, and the seam between them:
 *
 * <ol>
 *   <li><strong>Which dates are spring or king</strong> comes from {@link TideSizeIndex}, a DB-only
 *       read over {@code tide_extreme} measured against per-location thresholds. The tide fetch
 *       horizon is sized to the feed's own ninety days, so this reaches — but it can be
 *       <em>unmeasurable</em> (a cold database, a roster with no history), and when it is, detection
 *       falls back to {@code classifyTide}: dates a day or two early beat no dates at all.</li>
 *   <li><strong>What those dates look like in metres and clock time</strong> comes from
 *       {@code TideRunBuilder}, also a DB-only read over {@code tide_extreme}. A run detected by
 *       the lunar fallback may therefore still be enriched, and one detected from heights always
 *       can be.</li>
 * </ol>
 *
 * <p><strong>Beyond the stored window the dates stand alone.</strong> {@code TideRunBuilder} omits
 * any date it cannot derive, so a run that starts inside the fetch window and ends outside it comes
 * back partly enriched — and the entry keeps its full span either way. No figure is ever
 * extrapolated from a neighbouring day, and a run with no derivable day at all is still emitted,
 * carrying its dates and nothing numeric. That is §3's degrade rule, and it is load-bearing: the
 * feed's whole value at three months is knowing a run is coming, and a fabricated range would make
 * the honest rows indistinguishable from the invented ones.
 *
 * <p>A run is a maximal block of consecutive spring-or-king days. King wins the label for the whole
 * run if any day in it is perigean, matching the product's existing rule that a run is one event
 * rather than n unrelated days — and {@code LunarPhaseService.classifyTide} now measures perigee
 * from the syzygy instant rather than per date, so every day of one lunar event already agrees.
 */
@Component
public class TideAlmanacSource implements AlmanacSource {

    /** Machine-readable discriminator for an ordinary spring run. */
    static final String TYPE_SPRING = "spring-tide";

    /** Machine-readable discriminator for a perigean (king) run. */
    static final String TYPE_KING = "king-tide";

    /**
     * Safety bound on the outward walk that finds a run's true first and last day.
     *
     * <p>A spring run is a couple of days and cannot approach this. It exists so a classifier
     * change can never turn the walk into an unbounded loop — the walk's terminating condition is
     * supplied by another class.
     */
    private static final int MAX_RUN_WALK_DAYS = 10;

    private static final String SPRING_TITLE = "Spring tide run";
    private static final String KING_TITLE = "King tide run";

    private static final String SPRING_DETAIL =
            "The moon's alignment pulls the tide further out and further in than usual, so the"
                    + " foreshore is at its widest and the high water at its highest.";

    private static final String KING_DETAIL =
            "A spring tide falling near the moon's closest approach — the largest tidal range of"
                    + " the cycle, and the one worth planning a coastal shoot around.";

    private final LunarPhaseService lunarPhaseService;
    private final TideRunBuilder tideRunBuilder;
    private final LocationRepository locationRepository;
    private final TideSizeIndex tideSizeIndex;
    private final ComingUpScoringProperties scoringProperties;

    /**
     * Constructs a {@code TideAlmanacSource}.
     *
     * @param lunarPhaseService  supplies the fallback date arithmetic and the perigee test
     * @param tideRunBuilder     supplies the bounded enrichment from stored extremes
     * @param locationRepository supplies the coastal roster the run is drawn for
     * @param tideSizeIndex      measures which dates carry spring- or king-sized water
     * @param scoringProperties  supplies the shoulder-day ratio floor and per-side cap
     */
    public TideAlmanacSource(LunarPhaseService lunarPhaseService,
            TideRunBuilder tideRunBuilder,
            LocationRepository locationRepository,
            TideSizeIndex tideSizeIndex,
            ComingUpScoringProperties scoringProperties) {
        this.lunarPhaseService = lunarPhaseService;
        this.tideRunBuilder = tideRunBuilder;
        this.locationRepository = locationRepository;
        this.tideSizeIndex = tideSizeIndex;
        this.scoringProperties = scoringProperties;
    }

    @Override
    public List<AlmanacEvent> events(LocalDate from, LocalDate to) {
        // Fetched once for the whole feed rather than per run: the roster does not change between
        // two dates in the same request, and a 90-day window can hold half a dozen runs. It is now
        // needed before detection as well as after it — the roster is what gets measured.
        List<LocationEntity> coastal = locationRepository.findCoastalLocations();

        // Measured with the run-walk's slack on both sides, plus one day for the shoulder probe that
        // follows a full walk. Without it a run straddling either edge is clipped, which misreports
        // its span and can drop the very day that decides its label.
        TideSizeIndex.Sizes sizes = tideSizeIndex.measure(coastal,
                from.minusDays(MAX_RUN_WALK_DAYS + 1L), to.plusDays(MAX_RUN_WALK_DAYS + 1L));

        List<Run> runs = detectRuns(from, to, sizes);
        if (runs.isEmpty()) {
            return List.of();
        }

        List<AlmanacEvent> events = new ArrayList<>(runs.size());
        for (Run run : runs) {
            events.add(toEvent(run, coastal));
        }
        return events;
    }

    /**
     * Groups the range's spring and king days into maximal consecutive runs.
     *
     * <p>Package-private so the grouping can be tested without a repository or a builder — it is
     * the half of this class that has no horizon, and it is where an off-by-one would silently
     * split one run into two.
     *
     * @param from  first day, inclusive
     * @param to    last day, inclusive
     * @param sizes the measured roster, or an unmeasured one to fall back to lunar arithmetic
     * @return runs in ascending date order
     */
    List<Run> detectRuns(LocalDate from, LocalDate to, TideSizeIndex.Sizes sizes) {
        List<Run> runs = new ArrayList<>();
        LocalDate runStart = null;
        LocalDate runEnd = null;

        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            if (!qualifies(date, sizes)) {
                if (runStart != null) {
                    runs.add(completeRun(runStart, runEnd, sizes));
                    runStart = null;
                }
                continue;
            }
            if (runStart == null) {
                runStart = date;
            }
            runEnd = date;
        }

        if (runStart != null) {
            runs.add(completeRun(runStart, runEnd, sizes));
        }
        return runs;
    }

    /**
     * Extends a run outwards past the requested range to its true first and last day, then
     * classifies it.
     *
     * <p><strong>Why the walk exists.</strong> A run detected only within {@code [from, to]} is
     * clipped at both edges, which breaks two things at once. The reported span becomes a lie —
     * {@link AlmanacSource} promises a run's true dates precisely so "this began yesterday" does
     * not render as "starts today" — and, worse, the <em>label</em> changes. Perigee is a
     * half-day window, so exactly one day of a run can be perigean; clip that day off and a king
     * tide is served as an ordinary spring tide, with different copy and a different useful
     * water, one day before it corrects itself.
     *
     * <p>The walk is cheap and safe: both tests are in-memory — a set lookup against an index built
     * by one query, or pure epoch arithmetic — and a run is a handful of days, so this costs a few
     * extra probes per run. It is bounded by {@link #MAX_RUN_WALK_DAYS} anyway, so a hypothetical
     * classifier that returned true forever cannot spin. The index is deliberately measured with the
     * same slack, so the walk cannot leave the measured window and read a real day as a missing one.
     */
    private Run completeRun(LocalDate firstSeen, LocalDate lastSeen, TideSizeIndex.Sizes sizes) {
        LocalDate start = firstSeen;
        for (int i = 0; i < MAX_RUN_WALK_DAYS && qualifies(start.minusDays(1), sizes); i++) {
            start = start.minusDays(1);
        }
        LocalDate end = lastSeen;
        for (int i = 0; i < MAX_RUN_WALK_DAYS && qualifies(end.plusDays(1), sizes); i++) {
            end = end.plusDays(1);
        }

        // King is decided over the true span, not the visible one — the day that defines the event
        // is the one most likely to fall outside the window.
        boolean king = false;
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            if (isKing(date)) {
                king = true;
                break;
            }
        }
        return new Run(start, end, king, shoulderBefore(start, sizes), shoulderAfter(end, sizes));
    }

    /**
     * The outermost near-miss day before a run, or null. Walks outwards at most the configured cap,
     * stopping at the first day that qualifies (the walk's own cap was hit) or falls short of the
     * ratio floor. Needs a measured index: the lunar fallback has no ratios, so it yields none.
     */
    private LocalDate shoulderBefore(LocalDate start, TideSizeIndex.Sizes sizes) {
        LocalDate outer = null;
        for (int i = 1; i <= scoringProperties.getShoulder().getMaxPerSide(); i++) {
            LocalDate date = start.minusDays(i);
            if (!isShoulder(date, sizes)) {
                break;
            }
            outer = date;
        }
        return outer;
    }

    /** The outermost near-miss day after a run, or null; the mirror of {@link #shoulderBefore}. */
    private LocalDate shoulderAfter(LocalDate end, TideSizeIndex.Sizes sizes) {
        LocalDate outer = null;
        for (int i = 1; i <= scoringProperties.getShoulder().getMaxPerSide(); i++) {
            LocalDate date = end.plusDays(i);
            if (!isShoulder(date, sizes)) {
                break;
            }
            outer = date;
        }
        return outer;
    }

    private boolean isShoulder(LocalDate date, TideSizeIndex.Sizes sizes) {
        if (!sizes.usable() || qualifies(date, sizes)) {
            return false;
        }
        Double ratio = sizes.bestRatio().get(date);
        return ratio != null && ratio >= scoringProperties.getShoulder().getMinRatio();
    }

    /**
     * Whether a date belongs to a run: spring- or king-sized water somewhere on the roster, or —
     * when nothing could be measured — within the lunar classifier's syzygy window.
     *
     * <p>King is tested as well as spring rather than assumed to imply it. The two thresholds are
     * computed independently from one sample and neither is defined in terms of the other, so a
     * location's P95 may sit either side of 125% of its mean high.
     */
    private boolean qualifies(LocalDate date, TideSizeIndex.Sizes sizes) {
        if (sizes.usable()) {
            return sizes.springOn(date) || sizes.kingOn(date);
        }
        LunarTideType type = lunarPhaseService.classifyTide(date);
        return type == LunarTideType.SPRING_TIDE || type == LunarTideType.KING_TIDE;
    }

    /**
     * Whether a date belongs to a king tide — <b>an astronomical question, answered astronomically</b>.
     *
     * <p>The two axes this class uses are deliberately not interchangeable. <em>Which dates</em> a
     * run covers comes from the water, because a port's biggest tide lags syzygy by a day or two and
     * no lunar arithmetic recovers that lag. <em>What kind of event</em> it is comes from the moon,
     * because that is what the words mean: a king tide is a perigean spring.
     *
     * <p>This briefly accepted {@code sizes.kingOn(date)} as an alternative arm, lifted from the
     * Plan tab's detector for consistency. That was wrong in the direction that matters — it let a
     * high water in the top 5% for its port print the word "king" with no perigee involved, which
     * is labelling by outcome an event the card's copy defines by cause. The Plan tab's height arm
     * has been removed too, so the two surfaces still agree; they now agree on the right answer.
     */
    private boolean isKing(LocalDate date) {
        return lunarPhaseService.classifyTide(date) == LunarTideType.KING_TIDE;
    }

    private AlmanacEvent toEvent(Run run, List<LocationEntity> coastal) {
        TideRunBuilder.RunBuild built = coastal.isEmpty()
                ? TideRunBuilder.RunBuild.empty()
                : tideRunBuilder.buildWithEventWaters(run.dates(), coastal, run.king());
        Map<LocalDate, TideRunDay> enriched = built.days();

        TideRunDay peak = pickPeak(run, enriched);
        boolean isTruePeak = peak != null && peak.peak();

        // The sunrise and sunset high waters are questions about the RUN, each asked per event and
        // each answered by its own day: the peak day when its water lands in that event's light,
        // else the day whose high water is closest to the event. The figures above describe the
        // run's biggest water; these answer "which morning, which evening".
        EventPick sunrise = pickEvent(run, enriched, built.eventWaters(), TideRunBuilder.EventWaters::sunrise);
        EventPick sunset = pickEvent(run, enriched, built.eventWaters(), TideRunBuilder.EventWaters::sunset);
        boolean eventWaterPartial = run.dates().stream().anyMatch(d -> !enriched.containsKey(d));

        // Absent keys rather than empty ones: metaOf drops anything null or blank, so a run beyond
        // the stored-extremes window arrives carrying dates and nothing else.
        //
        // "peakDate" is published only when the builder actually flagged that day as the run's
        // peak. On the fallback path the figures are real and correctly attributed to their own
        // day, but no maximum was computed — so they go out as "figuresFrom" with a
        // "partialCoverage" flag instead. Reusing "peakDate" there would make a day that merely
        // happened to be inside the stored-extremes window indistinguishable, to a renderer, from
        // the biggest tide of the run.
        //
        // "sunriseHw…" / "sunsetHw…" name the HIGH water only (owner decision, tide-both-lights
        // plan): clock, signed gap in minutes from the event, the date it falls on, and an
        // "…Aligned" flag when it is inside the alignment window. They are absent when no measured
        // day has a high water that day. "eventWaterPartial" says some run date was not derivable,
        // so a "no high water within an hour of the event" finding holds only for the days measured.
        Map<String, String> meta = peak == null
                ? Map.of()
                : AlmanacEvent.metaOf(
                        "range", peak.range(),
                        "rangeAnomaly", peak.rangeAnomaly(),
                        "highWater", peak.highWater(),
                        "sunriseHw", sunrise == null ? null : TideWording.clock(sunrise.water().minutes()),
                        "sunriseHwGap", sunrise == null ? null : Long.toString(sunrise.water().gapMinutes()),
                        "sunriseHwDate", sunrise == null ? null : sunrise.date().toString(),
                        "sunriseHwAligned", sunrise != null && sunrise.water().aligned() ? "true" : null,
                        "sunsetHw", sunset == null ? null : TideWording.clock(sunset.water().minutes()),
                        "sunsetHwGap", sunset == null ? null : Long.toString(sunset.water().gapMinutes()),
                        "sunsetHwDate", sunset == null ? null : sunset.date().toString(),
                        "sunsetHwAligned", sunset != null && sunset.water().aligned() ? "true" : null,
                        "eventWaterPartial", eventWaterPartial ? "true" : null,
                        "location", peak.locationName(),
                        "peakDate", isTruePeak ? dateOf(run, enriched, peak) : null,
                        "figuresFrom", isTruePeak ? null : dateOf(run, enriched, peak),
                        "partialCoverage", isTruePeak ? null : "true",
                        "shoulderDates", shoulderDatesMeta(run));

        return new AlmanacEvent(
                run.start(),
                run.end(),
                AlmanacKind.ALMANAC,
                run.king() ? TYPE_KING : TYPE_SPRING,
                run.king() ? KING_TITLE : SPRING_TITLE,
                run.king() ? KING_DETAIL : SPRING_DETAIL,
                meta,
                List.of());
    }

    /** The run's shoulder dates as comma-separated ISO dates, ascending, or null when it has none. */
    private static String shoulderDatesMeta(Run run) {
        List<LocalDate> dates = run.shoulderDates();
        return dates.isEmpty() ? null
                : dates.stream().map(LocalDate::toString).collect(Collectors.joining(","));
    }

    /**
     * Picks the day whose figures represent the run: the one the builder marked as its peak, else
     * the first day it could derive at all.
     *
     * <p>Falling back to the first derivable day rather than to nothing matters at the feed's far
     * edge, where a run may straddle the end of the stored window and have exactly one day with
     * heights. One real figure beats none; a run's peak being outside the window is a reason to
     * caveat the number, not to withhold it.
     */
    private static TideRunDay pickPeak(Run run, Map<LocalDate, TideRunDay> enriched) {
        TideRunDay firstDerivable = null;
        for (LocalDate date : run.dates()) {
            TideRunDay day = enriched.get(date);
            if (day == null) {
                continue;
            }
            if (day.peak()) {
                return day;
            }
            if (firstDerivable == null) {
                firstDerivable = day;
            }
        }
        return firstDerivable;
    }

    /**
     * Picks the day whose high water the sunrise (or sunset) row states: the run's true peak day when
     * its water is aligned for that event, otherwise the day with the smallest gap to the event.
     * Ties go to the earliest day. Null when no measured day has a high water for the event.
     *
     * <p>Peak-first keeps the long-standing rule that a reader choosing one morning out of four wants
     * the one where the largest water and the usable light coincide, and it stops the row disagreeing
     * with the coincidence line, which reads this same pick on the peak day. {@code peak()} is never
     * true on a one-day run, so there the smallest-gap rule decides.
     */
    private static EventPick pickEvent(Run run, Map<LocalDate, TideRunDay> enriched,
            Map<LocalDate, TideRunBuilder.EventWaters> waters,
            Function<TideRunBuilder.EventWaters, TideRunBuilder.EventWater> selector) {
        EventPick best = null;
        for (LocalDate date : run.dates()) {
            TideRunBuilder.EventWaters day = waters.get(date);
            TideRunBuilder.EventWater water = day == null ? null : selector.apply(day);
            if (water == null) {
                continue;
            }
            TideRunDay runDay = enriched.get(date);
            if (water.aligned() && runDay != null && runDay.peak()) {
                return new EventPick(date, water);
            }
            if (best == null
                    || Math.abs(water.gapMinutes()) < Math.abs(best.water().gapMinutes())) {
                best = new EventPick(date, water);
            }
        }
        return best;
    }

    /** One event row's chosen day and its high water. */
    private record EventPick(LocalDate date, TideRunBuilder.EventWater water) {
    }

    private static String dateOf(Run run, Map<LocalDate, TideRunDay> enriched, TideRunDay peak) {
        for (LocalDate date : run.dates()) {
            if (enriched.get(date) == peak) {
                return date.toString();
            }
        }
        return null;
    }

    /**
     * A maximal block of consecutive spring-or-king days.
     *
     * @param start first day of the run
     * @param end   last day of the run
     * @param king  true when any day in the run is perigean
     * @param shoulderBefore the outermost near-miss day before the run, or null; the days from it up
     *                       to the day before {@code start} are all shoulders
     * @param shoulderAfter  the outermost near-miss day after the run, or null; mirror of
     *                       {@code shoulderBefore}
     */
    record Run(LocalDate start, LocalDate end, boolean king, LocalDate shoulderBefore,
            LocalDate shoulderAfter) {

        /**
         * Returns the near-miss shoulder dates beside the run, ascending.
         *
         * @return the shoulder dates, empty when there are none
         */
        List<LocalDate> shoulderDates() {
            List<LocalDate> dates = new ArrayList<>();
            if (shoulderBefore != null) {
                for (LocalDate date = shoulderBefore; date.isBefore(start); date = date.plusDays(1)) {
                    dates.add(date);
                }
            }
            if (shoulderAfter != null) {
                for (LocalDate date = end.plusDays(1); !date.isAfter(shoulderAfter);
                        date = date.plusDays(1)) {
                    dates.add(date);
                }
            }
            return dates;
        }

        /**
         * Returns every date in the run, ascending.
         *
         * @return the run's dates
         */
        List<LocalDate> dates() {
            List<LocalDate> dates = new ArrayList<>();
            for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
                dates.add(date);
            }
            return dates;
        }
    }
}
