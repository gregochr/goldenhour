package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.repository.SlotAtmosphereRepository;
import com.gregochr.goldenhour.service.comingup.ComingUpScoringProperties;
import com.gregochr.goldenhour.util.ForecastHorizon;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Nightly retention prune for {@code slot_atmosphere} — the {@code slot_atmosphere_cleanup} job.
 *
 * <p>The table is written for every candidate slot the pipeline fetched weather for ("record
 * conditions for every place", #947), roughly 500 new rows a day, and was never pruned. Owner
 * decision 2026-10-02: keep {@value #DEFAULT_RETENTION_DAYS} days and delete the rest nightly.
 *
 * <p><b>The rule.</b> A row is deleted when its {@code evaluation_date} — the slot's own date, never
 * the time it was written — is <em>more than</em> {@code retention-days} before today's UK civil
 * date ({@link ForecastHorizon#today(Clock)}, so at 23:30 UTC in summer the day is already
 * tomorrow's). <b>The boundary is inclusive for the row:</b> a row whose date is exactly
 * {@code retention-days} before today is KEPT; the first date deleted is one day older. The cutoff
 * handed to {@link SlotAtmosphereRepository#deleteByEvaluationDateBefore} is therefore exactly
 * {@code today - retention-days}, and the delete is strictly <em>before</em> it.
 *
 * <p><b>Configuration.</b> {@code photocast.slot-atmosphere.retention-days} (default
 * {@value #DEFAULT_RETENTION_DAYS}). ⚠️ <b>A value shorter than the longest read-back is refused at
 * startup</b> (fail fast with an {@link IllegalStateException}, the way {@code SeasonConfig} refuses
 * a malformed season window) rather than clamped: a silently-clamped retention would hide a
 * misconfiguration that deletes history a reader depends on. The longest reader looks back
 * {@code coming-up.scoring.recurrent.trailing-window-days} days (default 60) — the Coming up
 * valley-inversions condition, via {@code SlotSignalReader}; every other reader looks only forward
 * from today (the hot-topic strategies) or at yesterday ({@code TopicDailyLogJob}). See
 * {@link #longestReadBackDays(ComingUpScoringProperties)}: a new reader that looks further back
 * must be added there, or its history will be pruned from under it.
 *
 * <p><b>Shape.</b> Mirrors {@code ForecastDispositionCleanupService}: one bulk {@code DELETE} in its
 * own transaction (on the repository method), an INFO line carrying the cutoff and the count
 * (including zero), no {@code job_run} row, no overlap guard (a second concurrent delete of the same
 * predicate is harmless). An exception from the repository propagates to the scheduler thread —
 * {@code DynamicSchedulerService} runs the target inside a {@code finally} that still records the
 * completion time, and the scheduler logs the failure; the next night's run retries the whole
 * predicate. Unlike that model job, the cutoff is derived from the injected {@link Clock} on the UK
 * calendar rather than {@code Instant.now()}, so the rule is testable and agrees with every other
 * date in the pipeline.
 *
 * <p>Prunes no other table — {@code forecast_score} and {@code forecast_evaluation} are untouched.
 */
@Service
public class SlotAtmosphereCleanupJob {

    private static final Logger LOG = LoggerFactory.getLogger(SlotAtmosphereCleanupJob.class);

    /** Scheduler job key, matching the V162-seeded {@code scheduler_job_config} row. */
    public static final String JOB_KEY = "slot_atmosphere_cleanup";

    /** Default retention, in days (owner decision 2026-10-02). */
    public static final int DEFAULT_RETENTION_DAYS = 180;

    /** {@code TopicDailyLogJob} reads yesterday's slot rows, one day back. */
    static final int TOPIC_DAILY_LOG_READ_BACK_DAYS = 1;

    private final SlotAtmosphereRepository repository;
    private final DynamicSchedulerService dynamicSchedulerService;
    private final Clock clock;
    private final int retentionDays;

    /**
     * Constructs the job, refusing a retention shorter than the longest reader's window.
     *
     * @param repository              the slot-atmosphere repository
     * @param dynamicSchedulerService scheduler to register the job target against
     * @param clock                   injectable clock; the cutoff is its UK civil date minus the
     *                                retention
     * @param scoringProperties       the Coming up knobs, read for the longest trailing window
     * @param retentionDays           {@code photocast.slot-atmosphere.retention-days}
     *                                (default {@value #DEFAULT_RETENTION_DAYS})
     * @throws IllegalStateException if {@code retentionDays} is shorter than
     *                               {@link #longestReadBackDays(ComingUpScoringProperties)}
     */
    public SlotAtmosphereCleanupJob(SlotAtmosphereRepository repository,
            DynamicSchedulerService dynamicSchedulerService, Clock clock,
            ComingUpScoringProperties scoringProperties,
            @Value("${photocast.slot-atmosphere.retention-days:" + DEFAULT_RETENTION_DAYS + "}")
            int retentionDays) {
        int required = longestReadBackDays(scoringProperties);
        if (retentionDays < required) {
            throw new IllegalStateException("photocast.slot-atmosphere.retention-days="
                    + retentionDays + " is shorter than the longest slot_atmosphere read-back ("
                    + required + " days: coming-up.scoring.recurrent.trailing-window-days or "
                    + "the topic daily log) — pruning would delete history a reader depends on. "
                    + "Raise retention-days to at least " + required + ".");
        }
        this.repository = repository;
        this.dynamicSchedulerService = dynamicSchedulerService;
        this.clock = clock;
        this.retentionDays = retentionDays;
    }

    /**
     * The longest any reader looks back from today over {@code slot_atmosphere}, in days: the
     * Coming up valley-inversions trailing history ({@code SlotSignalReader.read} over
     * {@code builtFor - trailing-window-days .. yesterday}) or the topic daily log's single day,
     * whichever is greater. Every hot-topic strategy reads forward from today only, and the dust
     * condition's trailing window reads {@code forecast_evaluation}, not this table.
     *
     * @param scoringProperties the Coming up knobs
     * @return the minimum retention, in days, that keeps every reader's history whole
     */
    public static int longestReadBackDays(ComingUpScoringProperties scoringProperties) {
        return Math.max(TOPIC_DAILY_LOG_READ_BACK_DAYS,
                scoringProperties.getRecurrent().getTrailingWindowDays());
    }

    /** Registers the prune with the dynamic scheduler, resolving the V162-seeded cron row. */
    @PostConstruct
    void registerJob() {
        dynamicSchedulerService.registerJobTarget(JOB_KEY, this::prune);
    }

    /**
     * Deletes every row whose {@code evaluation_date} is more than the retention before today's UK
     * civil date, in one bulk statement, and logs the cutoff and the count (including zero).
     *
     * @return the number of rows deleted
     */
    public int prune() {
        LocalDate cutoff = ForecastHorizon.today(clock).minusDays(retentionDays);
        int deleted = repository.deleteByEvaluationDateBefore(cutoff);
        LOG.info("[SLOT ATMOSPHERE] Cleanup complete — deleted {} row(s) with evaluation_date before {} "
                + "({} day retention)", deleted, cutoff, retentionDays);
        return deleted;
    }
}
