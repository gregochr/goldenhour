package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.EvaluationModel;

/**
 * Request to the admin best-bet advisor replay harness.
 *
 * <p>Supplies a rollup (one of two ways) plus an optional candidate prompt to diff against the
 * current live prompt. One rollup, up to two prompts, per request — see
 * {@code AdvisorReplayController}.
 *
 * @param apiCallLogId    id of a captured advisor {@code api_call_log} row whose
 *                        {@code request_body} holds the rollup JSON. Only rows logged after the
 *                        rollup-capture change shipped carry a non-null {@code request_body};
 *                        ignored when {@code rollupJson} is supplied.
 * @param rollupJson      a rollup JSON supplied directly (e.g. a synthetic all-STANDDOWN rollup
 *                        to prove the stay-home floor without waiting for captures); takes
 *                        precedence over {@code apiCallLogId}.
 * @param candidatePrompt an optional candidate system prompt to run alongside the current one;
 *                        when null/blank only the current prompt is run.
 * @param model           the model to call; when null the active {@code BRIEFING_BEST_BET} model
 *                        is used.
 */
public record AdvisorReplayRequest(Long apiCallLogId, String rollupJson,
        String candidatePrompt, EvaluationModel model) {
}
