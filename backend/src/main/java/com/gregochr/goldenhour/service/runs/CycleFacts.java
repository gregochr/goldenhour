package com.gregochr.goldenhour.service.runs;

import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.JobRunEntity;

import java.util.List;
import java.util.Map;

/**
 * What belongs to a set of cycles: the Anthropic batches each submitted, and the distinct job runs behind them
 * (their batches' runs, their disposition anchor, and batch runs matched on the batch id their notes name).
 *
 * @param batchesByCycle the batches by cycle id
 * @param membersByCycle the member job runs by cycle id, each run once
 */
record CycleFacts(Map<Long, List<ForecastBatchEntity>> batchesByCycle, Map<Long, List<JobRunEntity>> membersByCycle) {

    /**
     * The batches of a cycle.
     *
     * @param cycleId the cycle id
     * @return its batches, empty when none
     */
    List<ForecastBatchEntity> batches(long cycleId) {
        return batchesByCycle.getOrDefault(cycleId, List.of());
    }

    /**
     * The member job runs of a cycle.
     *
     * @param cycleId the cycle id
     * @return its member runs, empty when none
     */
    List<JobRunEntity> members(long cycleId) {
        return membersByCycle.getOrDefault(cycleId, List.of());
    }
}
