package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.model.AlmanacEvent;
import com.gregochr.goldenhour.model.AlmanacKind;
import com.gregochr.goldenhour.model.TideRunDay;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Spring and king tide runs across the whole feed, detected from the lunar cycle and enriched from
 * stored tide extremes where they reach.
 *
 * <p><strong>This is the two-source path, and it is genuinely new plumbing rather than a reuse.</strong>
 * The plan implied {@code LunarPhaseService.classifyTide} was already wired into the tide
 * strategies. It is not: {@code SpringTideHotTopicStrategy} and {@code KingTideHotTopicStrategy} do
 * not inject {@code LunarPhaseService} at all, and derive spring/king from the five-day briefing
 * cache instead — which is exactly why they cannot answer for ninety days. The only callers of
 * {@code classifyTide} are {@code ForecastDtoMapper} and {@code TideFactDeriver}.
 *
 * <p>The two sources, and the seam between them:
 *
 * <ol>
 *   <li><strong>Which dates are spring or king</strong> comes from {@code classifyTide}, which is
 *       pure arithmetic over two reference epochs with no injected dependency and no horizon. It is
 *       correct as far ahead as it is asked.</li>
 *   <li><strong>What those dates look like in metres and clock time</strong> comes from
 *       {@code TideRunBuilder}, a DB-only read over {@code tide_extreme}. That table reaches only
 *       as far as the last WorldTides fetch, so this half has a horizon and the first half does
 *       not.</li>
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
 * rather than n unrelated days.
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

    /**
     * Constructs a {@code TideAlmanacSource}.
     *
     * @param lunarPhaseService  supplies the unbounded spring/king date arithmetic
     * @param tideRunBuilder     supplies the bounded enrichment from stored extremes
     * @param locationRepository supplies the coastal roster the run is drawn for
     */
    public TideAlmanacSource(LunarPhaseService lunarPhaseService,
            TideRunBuilder tideRunBuilder,
            LocationRepository locationRepository) {
        this.lunarPhaseService = lunarPhaseService;
        this.tideRunBuilder = tideRunBuilder;
        this.locationRepository = locationRepository;
    }

    @Override
    public List<AlmanacEvent> events(LocalDate from, LocalDate to) {
        List<Run> runs = detectRuns(from, to);
        if (runs.isEmpty()) {
            return List.of();
        }

        // Fetched once for the whole feed rather than per run: the roster does not change between
        // two dates in the same request, and a 90-day window can hold half a dozen runs.
        List<LocationEntity> coastal = locationRepository.findCoastalLocations();

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
     * @param from first day, inclusive
     * @param to   last day, inclusive
     * @return runs in ascending date order
     */
    List<Run> detectRuns(LocalDate from, LocalDate to) {
        List<Run> runs = new ArrayList<>();
        LocalDate runStart = null;
        LocalDate runEnd = null;

        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            if (!qualifies(date)) {
                if (runStart != null) {
                    runs.add(completeRun(runStart, runEnd));
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
            runs.add(completeRun(runStart, runEnd));
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
     * <p>The walk is cheap and safe: {@code classifyTide} is pure arithmetic over two epochs with
     * no horizon and no I/O, and a lunar run is a couple of days, so this costs a handful of extra
     * calls per run. It is bounded by {@link #MAX_RUN_WALK_DAYS} anyway, so a hypothetical
     * classifier that returned true forever cannot spin.
     */
    private Run completeRun(LocalDate firstSeen, LocalDate lastSeen) {
        LocalDate start = firstSeen;
        for (int i = 0; i < MAX_RUN_WALK_DAYS && qualifies(start.minusDays(1)); i++) {
            start = start.minusDays(1);
        }
        LocalDate end = lastSeen;
        for (int i = 0; i < MAX_RUN_WALK_DAYS && qualifies(end.plusDays(1)); i++) {
            end = end.plusDays(1);
        }

        // King is decided over the true span, not the visible one — the perigean day is what
        // defines the event, and it is the day most likely to fall outside the window.
        boolean king = false;
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            if (lunarPhaseService.classifyTide(date) == LunarTideType.KING_TIDE) {
                king = true;
                break;
            }
        }
        return new Run(start, end, king);
    }

    private boolean qualifies(LocalDate date) {
        LunarTideType type = lunarPhaseService.classifyTide(date);
        return type == LunarTideType.SPRING_TIDE || type == LunarTideType.KING_TIDE;
    }

    private AlmanacEvent toEvent(Run run, List<LocationEntity> coastal) {
        Map<LocalDate, TideRunDay> enriched = coastal.isEmpty()
                ? Map.of()
                : tideRunBuilder.build(run.dates(), coastal, run.king());

        TideRunDay peak = pickPeak(run, enriched);
        boolean isTruePeak = peak != null && peak.peak();

        // Absent keys rather than empty ones: metaOf drops anything null or blank, so a run beyond
        // the stored-extremes window arrives carrying dates and nothing else.
        //
        // "peakDate" is published only when the builder actually flagged that day as the run's
        // peak. On the fallback path the figures are real and correctly attributed to their own
        // day, but no maximum was computed — so they go out as "figuresFrom" with a
        // "partialCoverage" flag instead. Reusing "peakDate" there would make a day that merely
        // happened to be inside the stored-extremes window indistinguishable, to a renderer, from
        // the biggest tide of the run.
        Map<String, String> meta = peak == null
                ? Map.of()
                : AlmanacEvent.metaOf(
                        "range", peak.range(),
                        "rangeAnomaly", peak.rangeAnomaly(),
                        "highWater", peak.highWater(),
                        "verdict", peak.verdict(),
                        "location", peak.locationName(),
                        "peakDate", isTruePeak ? dateOf(run, enriched, peak) : null,
                        "figuresFrom", isTruePeak ? null : dateOf(run, enriched, peak),
                        "partialCoverage", isTruePeak ? null : "true");

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
     */
    record Run(LocalDate start, LocalDate end, boolean king) {

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
