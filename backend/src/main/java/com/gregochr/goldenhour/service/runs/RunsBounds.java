package com.gregochr.goldenhour.service.runs;

import com.gregochr.goldenhour.repository.JobRunRepository;
import com.gregochr.goldenhour.repository.PipelineRunRepository;
import com.gregochr.goldenhour.repository.SkyRatingEvalRunRepository;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.stream.Stream;

/**
 * The days the Runs Day and Calendar views can be asked about: from the earliest UK day anything ran (never more than
 * {@value #MAX_DAYS_BACK} days back) to today. "Today" is the UK civil date from the injected clock.
 */
@Component
class RunsBounds {

    /** There is no retention job, so the picker is capped rather than reaching back for ever. */
    static final int MAX_DAYS_BACK = 400;

    private final JobRunRepository jobRuns;
    private final PipelineRunRepository pipelineRuns;
    private final SkyRatingEvalRunRepository evalRuns;
    private final Clock clock;

    /**
     * Constructs the bounds.
     *
     * @param jobRuns      job run store
     * @param pipelineRuns pipeline run store
     * @param evalRuns     sky-rating eval run store
     * @param clock        the application clock
     */
    RunsBounds(JobRunRepository jobRuns, PipelineRunRepository pipelineRuns, SkyRatingEvalRunRepository evalRuns,
            Clock clock) {
        this.jobRuns = jobRuns;
        this.pipelineRuns = pipelineRuns;
        this.evalRuns = evalRuns;
        this.clock = clock;
    }

    /**
     * Today on the UK civil calendar.
     *
     * @return today
     */
    LocalDate today() {
        return ForecastHorizon.today(clock);
    }

    /**
     * The earliest selectable day: the UK day of the earliest job run, cycle or sky eval, at most
     * {@value #MAX_DAYS_BACK} days back; today when nothing has run.
     *
     * @return the earliest day
     */
    LocalDate earliestDay() {
        LocalDate today = today();
        LocalDate floor = today.minusDays(MAX_DAYS_BACK);
        LocalDate earliest = Stream.of(
                        toInstant(jobRuns.findEarliestStartedAt()),
                        pipelineRuns.findEarliestTriggerTime(),
                        toInstant(evalRuns.findEarliestRunTimestamp()))
                .filter(java.util.Objects::nonNull)
                .map(ForecastHorizon::civilDate)
                .min(LocalDate::compareTo)
                .orElse(today);
        return earliest.isBefore(floor) ? floor : earliest;
    }

    /**
     * Checks a day is one that can be served.
     *
     * @param date     the day
     * @param earliest the earliest selectable day
     * @throws RunsRequestException if the day is in the future or before the earliest day
     */
    void requireSelectable(LocalDate date, LocalDate earliest) {
        if (date.isAfter(today())) {
            throw new RunsRequestException("date is in the future");
        }
        if (date.isBefore(earliest)) {
            throw new RunsRequestException("date is before the earliest day (" + earliest + ")");
        }
    }

    /**
     * Checks a month holds at least one selectable day.
     *
     * @param month    the month
     * @param earliest the earliest selectable day
     * @throws RunsRequestException if the month is after this one or ended before the earliest day
     */
    void requireSelectable(YearMonth month, LocalDate earliest) {
        if (month.isAfter(YearMonth.from(today()))) {
            throw new RunsRequestException("month is in the future");
        }
        if (month.atEndOfMonth().isBefore(earliest)) {
            throw new RunsRequestException("month is before the earliest day (" + earliest + ")");
        }
    }

    private static Instant toInstant(LocalDateTime utc) {
        return utc == null ? null : utc.toInstant(ZoneOffset.UTC);
    }
}
