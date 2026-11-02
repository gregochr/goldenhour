package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.ServiceName;

/**
 * The calls of one run (or one cycle's runs) to one service, summed by SQL so no call row is loaded.
 *
 * @param service          the service called
 * @param batch            true for Anthropic Message Batches rows (one row per request), false for synchronous calls
 * @param calls            how many calls were logged
 * @param durationMs       the sum of the calls' durations, or null when none recorded one
 * @param costMicroDollars the sum of the calls' cost, or null when none recorded one
 * @param errors           how many calls did not succeed
 */
public record ApiCallGroup(ServiceName service, Boolean batch, Long calls, Long durationMs, Long costMicroDollars,
        Long errors) {
}
