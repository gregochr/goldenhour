package com.gregochr.goldenhour.service.batch;

/**
 * Identifies what triggered a batch submission — used for logging, diagnostics, and
 * (in future passes) to drive differences in gate application and result handling.
 *
 * <p>Pass 2 introduces the enum as a passive classifier threaded through
 * {@link BatchSubmissionService#submit}. Pass 3 is expected to use it to collapse
 * scheduled/admin/force/JFDI paths now that the submission mechanics are shared.
 */
public enum BatchTriggerSource {

    /** Cron-driven overnight forecast or aurora batch. */
    SCHEDULED,

    /** Admin UI "run now" trigger — uses the same gates as SCHEDULED. */
    ADMIN,

    /** Admin UI force-submit — bypasses all gates (triage, stability, cache). */
    FORCE,

    /** Admin UI JFDI (just-do-it) — bypasses all gates across T+0..T+3 × SUNRISE/SUNSET. */
    JFDI
}
