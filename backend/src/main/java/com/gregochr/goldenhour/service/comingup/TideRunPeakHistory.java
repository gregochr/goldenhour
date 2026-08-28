package com.gregochr.goldenhour.service.comingup;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.service.LunarPhaseService;
import com.gregochr.goldenhour.service.TideRunBuilder;
import com.gregochr.goldenhour.service.TideSizeIndex;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The historical distribution of a tide run's peak range at one representative port (plan D4).
 *
 * <p>Tide magnitude scoring needs a distribution of <b>past run peaks at the same port a forward
 * run was drawn for</b> — not a representative re-selected per historical run, which would compare
 * the current run against a mix of coastlines rather than one. Run boundaries within that history
 * are found the same way {@code TideAlmanacSource} finds them going forward: {@link TideSizeIndex}'s
 * roster-wide height test, falling back to {@link LunarPhaseService#classifyTide} when the roster is
 * unmeasurable — the same "which dates are spring or king" rule answering both directions.
 *
 * <p>Deliberately simpler than {@code TideAlmanacSource.detectRuns}: it groups maximal consecutive
 * qualifying days with no outward walk past the query window's edges. A run-peak distribution is
 * built from years of history, so a day or two of truncation at the very edges of that window has no
 * material effect on it — unlike the almanac feed itself or P4's conditions strip, which need a
 * run's true first and last day and own the walk that finds them.
 */
@Component
public class TideRunPeakHistory {

    /**
     * Years of {@code tide_extreme} history to replay. Comfortably past the ~2.5-year floor plan
     * D4 names for a mature (non-cold-start) magnitude claim (60 observations, ~26/year), while
     * keeping the query bounded rather than open-ended.
     */
    static final int LOOKBACK_YEARS = 3;

    private final TideSizeIndex tideSizeIndex;
    private final TideRunBuilder tideRunBuilder;
    private final LunarPhaseService lunarPhaseService;

    /**
     * Constructs a {@code TideRunPeakHistory}.
     *
     * @param tideSizeIndex     measures which historical dates carried spring- or king-sized water
     * @param tideRunBuilder    supplies the fixed-location peak-range read
     * @param lunarPhaseService supplies the fallback date arithmetic when the roster is unmeasurable
     */
    public TideRunPeakHistory(TideSizeIndex tideSizeIndex, TideRunBuilder tideRunBuilder,
            LunarPhaseService lunarPhaseService) {
        this.tideSizeIndex = tideSizeIndex;
        this.tideRunBuilder = tideRunBuilder;
        this.lunarPhaseService = lunarPhaseService;
    }

    /**
     * The representative's own past run-peak ranges, in metres, over the trailing
     * {@value #LOOKBACK_YEARS} years ending strictly before both today and the run being scored —
     * never today or later, and never any date the run under evaluation itself covers.
     *
     * <p><b>The run being scored can itself start before "today".</b> Eligibility only requires an
     * entry to <em>end</em> beyond Plan's window (plan D1), so a run detected today can have been
     * walked backward to a start date a day or two in the past ({@code TideAlmanacSource}'s
     * {@code completeRun}). Bounding the lookback at "yesterday" alone is not enough in that case:
     * the run's own already-elapsed days would still qualify as roster-wide spring/king days and
     * re-enter the very distribution its own peak is compared against — the peak scoring itself
     * against itself, which silently depresses its own magnitude (the value counts as
     * {@code >= r} by definition, one degree short of comparing it to a copy of itself). Clamping
     * {@code to} to the day before whichever of "today" or the run's own start is earlier closes
     * that gap without needing to know the run's true (possibly walked-back) start precisely — the
     * requested start is always at least as early as the true one.
     *
     * @param representative the port a forward run was drawn for
     * @param coastalRoster  the enabled coastal locations — spring/king day qualification is
     *                       roster-wide, matching {@code TideAlmanacSource}
     * @param today          the UK civil date the almanac is built for
     * @param runStartDate   the first day of the run being scored, so its own days are excluded
     * @return past run peaks, oldest first; empty when nothing could be measured
     */
    public List<Double> peakRanges(LocationEntity representative, List<LocationEntity> coastalRoster,
            LocalDate today, LocalDate runStartDate) {
        if (representative == null || coastalRoster == null || coastalRoster.isEmpty()
                || today == null || runStartDate == null) {
            return List.of();
        }
        LocalDate to = (runStartDate.isBefore(today) ? runStartDate : today).minusDays(1);
        LocalDate from = to.minusYears(LOOKBACK_YEARS);
        TideSizeIndex.Sizes sizes = tideSizeIndex.measure(coastalRoster, from, to);

        List<Double> peaks = new ArrayList<>();
        for (List<LocalDate> run : groupIntoRuns(from, to, sizes)) {
            tideRunBuilder.peakRangeAt(representative, run).ifPresent(peaks::add);
        }
        return peaks;
    }

    /**
     * Groups the window's spring and king days into maximal consecutive runs.
     *
     * <p>Package-private so the grouping can be tested without a repository or a builder.
     *
     * @param from  first day, inclusive
     * @param to    last day, inclusive
     * @param sizes the measured roster, or an unmeasured one to fall back to lunar arithmetic
     * @return runs in ascending date order, each a list of consecutive qualifying dates
     */
    List<List<LocalDate>> groupIntoRuns(LocalDate from, LocalDate to, TideSizeIndex.Sizes sizes) {
        List<List<LocalDate>> runs = new ArrayList<>();
        List<LocalDate> current = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            if (qualifies(date, sizes)) {
                current.add(date);
            } else if (!current.isEmpty()) {
                runs.add(List.copyOf(current));
                current.clear();
            }
        }
        if (!current.isEmpty()) {
            runs.add(List.copyOf(current));
        }
        return runs;
    }

    private boolean qualifies(LocalDate date, TideSizeIndex.Sizes sizes) {
        if (sizes.usable()) {
            return sizes.springOn(date) || sizes.kingOn(date);
        }
        LunarTideType type = lunarPhaseService.classifyTide(date);
        return type == LunarTideType.SPRING_TIDE || type == LunarTideType.KING_TIDE;
    }
}
