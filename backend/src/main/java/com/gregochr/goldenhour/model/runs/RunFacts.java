package com.gregochr.goldenhour.model.runs;

import java.time.Instant;

/**
 * The inline facts of an expanded Day row. A finished row fills the first six, a scheduled row the last two.
 *
 * @param startedAt         finished: when the run started
 * @param finishedAt        finished: when it ended, or null while running
 * @param fasterThanPercent finished: the share of the type's previous 30 runs this one was faster than, 0 to 100;
 *                          null when fewer than ten earlier runs exist or the run has no duration
 * @param batches           cycles: Anthropic batches submitted, retries included
 * @param requests          cycles: requests across those batches
 * @param answersReadyAt    cycles: when its Ask ready answers finished, or null when none are linked
 * @param expectedStart     scheduled: when it should start (an estimate when the row is {@code estimated})
 * @param expectedFinish    scheduled: when it should finish, from the median of the type's last 30 runs
 */
public record RunFacts(
        Instant startedAt,
        Instant finishedAt,
        Integer fasterThanPercent,
        Integer batches,
        Integer requests,
        Instant answersReadyAt,
        Instant expectedStart,
        Instant expectedFinish) {
}
