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
        boolean runIsKing = false;

        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            LunarTideType type = lunarPhaseService.classifyTide(date);
            boolean qualifies =
                    type == LunarTideType.SPRING_TIDE || type == LunarTideType.KING_TIDE;

            if (!qualifies) {
                if (runStart != null) {
                    runs.add(new Run(runStart, runEnd, runIsKing));
                    runStart = null;
                    runIsKing = false;
                }
                continue;
            }

            if (runStart == null) {
                runStart = date;
            }
            runEnd = date;
            // One perigean day makes the whole run a king run: the run is one event, and the
            // product already treats it that way rather than as n unrelated days.
            runIsKing = runIsKing || type == LunarTideType.KING_TIDE;
        }

        if (runStart != null) {
            runs.add(new Run(runStart, runEnd, runIsKing));
        }
        return runs;
    }

    private AlmanacEvent toEvent(Run run, List<LocationEntity> coastal) {
        Map<LocalDate, TideRunDay> enriched = coastal.isEmpty()
                ? Map.of()
                : tideRunBuilder.build(run.dates(), coastal, run.king());

        TideRunDay peak = pickPeak(run, enriched);

        // Absent keys rather than empty ones: metaOf drops anything null or blank, so a run beyond
        // the stored-extremes window arrives carrying dates and nothing else.
        Map<String, String> meta = peak == null
                ? Map.of()
                : AlmanacEvent.metaOf(
                        "range", peak.range(),
                        "rangeAnomaly", peak.rangeAnomaly(),
                        "highWater", peak.highWater(),
                        "verdict", peak.verdict(),
                        "location", peak.locationName(),
                        "peakDate", peakDateOf(run, enriched, peak));

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

    private static String peakDateOf(Run run, Map<LocalDate, TideRunDay> enriched, TideRunDay peak) {
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
