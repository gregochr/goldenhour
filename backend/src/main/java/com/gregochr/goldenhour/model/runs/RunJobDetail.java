package com.gregochr.goldenhour.model.runs;

import com.gregochr.goldenhour.model.JobRunDto;

/**
 * One job run in full, for the job-run panel: the run itself plus what the server serves for its row.
 * Works for any job run, including a cycle's batch, which never appears at the top level of the list.
 *
 * @param run               the full job run, with its batch scope
 * @param status            the served status
 * @param group             the served group
 * @param name              the served display name
 * @param note              the served one-line note, or null
 * @param noteTone          the note's tone, or null
 * @param triggeredManually whether an admin started the run
 * @param batch             the Anthropic batch behind the run, or null
 */
public record RunJobDetail(
        JobRunDto run,
        RunListStatus status,
        RunGroup group,
        String name,
        String note,
        NoteTone noteTone,
        boolean triggeredManually,
        RunBatchInfo batch
) {
}
