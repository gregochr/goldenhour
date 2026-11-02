package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.SchedulerFireStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * How a scheduled job tells the scheduler what its fire amounted to (scheduler-redesign-plan.md D2).
 *
 * <p>{@code DynamicSchedulerService.wrapTarget} binds a per-fire {@link Carrier} to a JDK
 * {@link ScopedValue} for the duration of the job body. A job calls {@link #report} (and, for a forecast
 * cycle, {@link #linkPipelineRun}) from the thread the scheduler runs it on; the wrapper reads the carrier
 * afterwards and stores what it holds in {@code scheduler_fire_log}. No job signature changes.
 *
 * <p>A binding is visible only inside the {@code run} call that made it, so it cannot leak across the
 * scheduler's pooled threads, and work a job hands to another thread (a cycle's wait-and-brief tail, the
 * astro Ask precompute) cannot report: by design. With nothing bound (a job body called directly, a
 * thread the scheduler did not start) every call is a no-op logged at DEBUG. When a job reports nothing the
 * fire is {@code SUCCEEDED} with no summary; the last report wins.
 */
public final class SchedulerFireOutcome {

    private static final Logger LOG = LoggerFactory.getLogger(SchedulerFireOutcome.class);

    /** The carrier of the fire running on this thread, bound by the wrapper. */
    private static final ScopedValue<Carrier> CURRENT = ScopedValue.newInstance();

    private SchedulerFireOutcome() {
    }

    /**
     * Reports the outcome of the fire currently running on this thread; the last call wins.
     *
     * @param status  how the fire ended; {@code RUNNING} and null are ignored
     * @param summary a one-line account (sanitised and cut to 200 characters when stored), or null
     */
    public static void report(SchedulerFireStatus status, String summary) {
        if (status == null || status == SchedulerFireStatus.RUNNING) {
            LOG.debug("Ignoring a scheduler fire report with status {}", status);
            return;
        }
        if (!CURRENT.isBound()) {
            LOG.debug("Scheduler fire report ({}) with no fire bound — ignored", status);
            return;
        }
        Carrier carrier = CURRENT.get();
        carrier.status = status;
        carrier.summary = summary;
    }

    /**
     * Links the fire currently running on this thread to the pipeline run it started.
     *
     * @param pipelineRunId the {@code pipeline_run} id
     */
    public static void linkPipelineRun(long pipelineRunId) {
        if (!CURRENT.isBound()) {
            LOG.debug("Scheduler pipeline-run link ({}) with no fire bound — ignored", pipelineRunId);
            return;
        }
        CURRENT.get().pipelineRunId = pipelineRunId;
    }

    /**
     * Runs a job body with the given carrier bound, so reports made inside it land in the carrier.
     *
     * @param carrier the fire's carrier
     * @param body    the job body
     */
    static void runBound(Carrier carrier, Runnable body) {
        ScopedValue.where(CURRENT, carrier).run(body);
    }

    /**
     * The status for a run over many items where some may have failed: none failed is SUCCEEDED, all
     * failed is FAILED, otherwise PARTIAL.
     *
     * @param failed how many items failed
     * @param total  how many items were attempted
     * @return the status to report
     */
    public static SchedulerFireStatus statusFor(int failed, int total) {
        if (failed <= 0) {
            return SchedulerFireStatus.SUCCEEDED;
        }
        return failed >= total ? SchedulerFireStatus.FAILED : SchedulerFireStatus.PARTIAL;
    }

    /**
     * Words a count for a summary, choosing the singular or plural noun.
     *
     * @param n        the count
     * @param singular the noun for exactly one, e.g. {@code "row"}
     * @param plural   the noun otherwise, e.g. {@code "rows"}
     * @return e.g. {@code "1 row"} or {@code "3 rows"}
     */
    public static String count(long n, String singular, String plural) {
        return n + " " + (n == 1 ? singular : plural);
    }

    /**
     * Runs a body with a fresh carrier bound and returns what it reported. Meant for tests, which live in
     * many packages and cannot reach the package-private {@link Carrier}.
     *
     * @param body the code to run as if it were a scheduler fire
     * @return the status, summary and pipeline run the body reported (each null when it reported none)
     */
    public static Report capture(Runnable body) {
        Carrier carrier = new Carrier();
        runBound(carrier, body);
        return new Report(carrier.status(), carrier.summary(), carrier.pipelineRunId());
    }

    /**
     * An immutable snapshot of what a fire reported, returned by {@link #capture}.
     *
     * @param status        the reported status, or null
     * @param summary       the reported summary, or null
     * @param pipelineRunId the linked pipeline run, or null
     */
    public record Report(SchedulerFireStatus status, String summary, Long pipelineRunId) {
    }

    /** What one fire has reported so far. Mutable, one per fire, confined to the fire's thread. */
    static final class Carrier {

        private SchedulerFireStatus status;
        private String summary;
        private Long pipelineRunId;

        /**
         * The reported status.
         *
         * @return the status, or null when the job reported none
         */
        SchedulerFireStatus status() {
            return status;
        }

        /**
         * The reported summary.
         *
         * @return the summary, or null
         */
        String summary() {
            return summary;
        }

        /**
         * The linked pipeline run.
         *
         * @return the pipeline run id, or null
         */
        Long pipelineRunId() {
            return pipelineRunId;
        }
    }
}
