package com.gregochr.goldenhour.service.runs;

import com.gregochr.goldenhour.model.runs.RunCalendarResponse;
import com.gregochr.goldenhour.model.runs.RunCalendarResponse.CalendarDay;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the Day view's calendar popover: one month of UK days, each with its run count, spend and the number of runs
 * that need attention.
 *
 * <p>One load over the whole month, grouped by UK day in memory; it never builds a Day view per date. A day's
 * bounds are its two UK midnights, so a run at 23:30 UTC on the eve of the clocks going back (00:30 BST) counts on
 * the next day.
 */
@Service
public class RunsCalendarService {

    private final RunsLoader loader;
    private final RunsBounds bounds;

    /**
     * Constructs the service.
     *
     * @param loader loads the month's rows
     * @param bounds the days that can be asked about
     */
    RunsCalendarService(RunsLoader loader, RunsBounds bounds) {
        this.loader = loader;
        this.bounds = bounds;
    }

    /**
     * Serves one month.
     *
     * @param month the month
     * @return its selectable days (none in the future, none before the first run)
     * @throws RunsRequestException if the month is after this one or ended before the earliest day
     */
    public RunCalendarResponse month(YearMonth month) {
        LocalDate earliest = bounds.earliestDay();
        bounds.requireSelectable(month, earliest);
        LocalDate today = bounds.today();

        LocalDate first = month.atDay(1);
        LocalDate lastIncl = month.atEndOfMonth().isAfter(today) ? today : month.atEndOfMonth();
        Instant from = ForecastHorizon.ukDayStartUtc(first).toInstant(ZoneOffset.UTC);
        Instant to = ForecastHorizon.ukDayStartUtc(lastIncl.plusDays(1)).toInstant(ZoneOffset.UTC);

        Map<LocalDate, List<DeepRun>> byDay = new HashMap<>();
        for (DeepRun run : loader.load(from, to).rows()) {
            byDay.computeIfAbsent(ForecastHorizon.civilDate(run.startedAt()), d -> new ArrayList<>()).add(run);
        }

        List<CalendarDay> days = new ArrayList<>();
        BigDecimal max = BigDecimal.ZERO.setScale(2);
        for (LocalDate d = first.isBefore(earliest) ? earliest : first; !d.isAfter(lastIncl); d = d.plusDays(1)) {
            List<DeepRun> runs = byDay.getOrDefault(d, List.of());
            BigDecimal spend = RunFx.pence(runs.stream().mapToDouble(DeepRun::costGbp).sum());
            days.add(new CalendarDay(d, runs.size(), spend, runs.stream().allMatch(DeepRun::costComplete),
                    (int) runs.stream().filter(r -> r.status().needsAttention()).count()));
            if (spend.compareTo(max) > 0) {
                max = spend;
            }
        }
        return new RunCalendarResponse(month.toString(), days, max, earliest);
    }
}
