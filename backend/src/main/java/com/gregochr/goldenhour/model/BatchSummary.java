package com.gregochr.goldenhour.model;

import java.util.List;

/**
 * Read-time enrichment shown alongside a SCHEDULED_BATCH (or BATCH_NEAR_TERM / BATCH_FAR_TERM)
 * job run on the admin metrics page. Derived from {@code api_call_log} rows linked to the run.
 *
 * @param horizonRange      forecast horizon expressed relative to the run start date
 *                          (e.g. {@code "T"}, {@code "T+1"}, {@code "T to T+2"}); never null
 * @param eventTypes        distinct {@code target_type} values across the batch
 *                          (e.g. {@code ["SUNRISE"]} or {@code ["SUNRISE", "SUNSET"]})
 * @param evaluationModel   the Claude model recorded on the batch's API call rows
 *                          (e.g. {@code "HAIKU"}, {@code "SONNET"}); never null
 * @param locationCount     count of distinct {@code custom_id} values in the batch
 * @param regionCount       count of distinct regions resolved from the batch's locations
 * @param extendedThinking  {@code true} if the model variant uses extended thinking
 *                          (SONNET_ET / OPUS_ET); {@code null} if not applicable
 */
public record BatchSummary(
        String horizonRange,
        List<String> eventTypes,
        String evaluationModel,
        int locationCount,
        int regionCount,
        Boolean extendedThinking
) {
}
