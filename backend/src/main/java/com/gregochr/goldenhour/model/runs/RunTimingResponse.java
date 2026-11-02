package com.gregochr.goldenhour.model.runs;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The Runs "Timing" view for one run type: every measured run, the statistics over them, the median of every timing
 * type over the same range, and what the sunset planner or the "when results are ready" card needs.
 *
 * <p>The nullable members are filled only where they apply (see each). Manual runs are left out where the type
 * records a manual flag; failed runs are always left out.
 *
 * @param type               the type measured
 * @param typeLabel          its label
 * @param rangeDays          the number of UK days measured, ending today
 * @param measure            {@code cycle} (start to end) or {@code ready} (start to the answers being ready)
 * @param runs               the measured runs, oldest first
 * @param stats              the statistics over them
 * @param typeMedians        the cycle-measure median in seconds of every timing type, null where it has no runs
 * @param withoutAnswersLink measure {@code ready}: cycles left out because no ask-ready run is linked; else 0
 * @param trigger            AFTERNOON: its sun-relative trigger, null when its schedule is a fixed time; else null
 * @param todaySunset        AFTERNOON: today's sunset at the reference point; else null
 * @param startsAt           OVERNIGHT and EVENING: when the scheduler starts it today; null when it has no fire today
 * @param typicalReadyAt     OVERNIGHT and EVENING: {@code startsAt} plus the median
 * @param p90ReadyAt         OVERNIGHT and EVENING: {@code startsAt} plus the 90th percentile
 * @param worstReadyAt       OVERNIGHT and EVENING: {@code startsAt} plus the slowest
 * @param readyBasis         OVERNIGHT and EVENING: {@code ready} when the times include the answers, {@code cycle}
 *                           when no run has an answers-ready link, so they are the cycle alone
 * @param sunriseToday       OVERNIGHT: today's sunrise at the reference point
 * @param slackAtP90Minutes  OVERNIGHT: minutes from {@code p90ReadyAt} to sunrise (negative when later)
 */
public record RunTimingResponse(
        RunDeepType type,
        String typeLabel,
        int rangeDays,
        String measure,
        List<TimingRun> runs,
        TimingStats stats,
        Map<String, Long> typeMedians,
        int withoutAnswersLink,
        Trigger trigger,
        Instant todaySunset,
        Instant startsAt,
        Instant typicalReadyAt,
        Instant p90ReadyAt,
        Instant worstReadyAt,
        String readyBasis,
        Instant sunriseToday,
        Long slackAtP90Minutes) {

    /**
     * One measured run.
     *
     * @param id              the run's id as the Day view serves it ({@code cycle-N}, {@code job-N}, {@code eval-N})
     * @param startedAt       when it started
     * @param date            the UK day it started on
     * @param durationSeconds the measured duration
     * @param sunsetAt        the sunset on that UK day at the reference point
     */
    public record TimingRun(String id, Instant startedAt, LocalDate date, long durationSeconds, Instant sunsetAt) {
    }

    /**
     * A duration and the day it happened.
     *
     * @param seconds the duration
     * @param date    the UK day
     */
    public record Extreme(long seconds, LocalDate date) {
    }

    /**
     * The statistics over the measured runs.
     *
     * @param count                   how many runs were measured
     * @param medianSeconds           the median, null for none
     * @param p90Seconds              the 90th percentile, null with fewer than two runs
     * @param slowest                 the longest, null for none
     * @param fastest                 the shortest, null for none
     * @param firstHalfMedianSeconds  the median over the first half of the range, null when it has no runs
     * @param secondHalfMedianSeconds the median over the second half, null when it has no runs
     * @param changePercent           second half against first, whole percent, null unless both halves have runs
     * @param skewed                  true when the 90th percentile is more than 1.35 times the median
     * @param manualExcluded          true when manual runs are left out of this type (it records a manual flag)
     */
    public record TimingStats(int count, Long medianSeconds, Long p90Seconds, Extreme slowest, Extreme fastest,
            Long firstHalfMedianSeconds, Long secondHalfMedianSeconds, Integer changePercent, boolean skewed,
            boolean manualExcluded) {
    }

    /**
     * A sun-relative trigger.
     *
     * @param solarEvent    {@code SUNRISE} or {@code SUNSET}
     * @param offsetMinutes minutes before the event
     */
    public record Trigger(String solarEvent, int offsetMinutes) {
    }
}
