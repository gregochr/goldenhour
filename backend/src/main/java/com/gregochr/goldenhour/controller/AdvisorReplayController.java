package com.gregochr.goldenhour.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.gregochr.goldenhour.entity.ApiCallLogEntity;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.model.AdvisorReplayRequest;
import com.gregochr.goldenhour.model.AdvisorReplayResponse;
import com.gregochr.goldenhour.model.BestBetResult;
import com.gregochr.goldenhour.repository.ApiCallLogRepository;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.goldenhour.service.evaluation.BriefingBestBetAdvisor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;

/**
 * Admin-only entry point for the best-bet advisor replay harness — the standing before/after tool
 * for advisor prompt changes.
 *
 * <p>One request = one rollup run through up to two prompts (current vs an optional candidate),
 * returning both pick-sets {@link AdvisorReplayResponse side by side} for a by-eye diff. There is
 * deliberately no UI, no persistence, no automated pick comparison, and no batch runner — to
 * replay many cycles, call this N times.
 *
 * <p><b>Rollup source.</b> Supply {@code rollupJson} directly (e.g. a synthetic all-STANDDOWN
 * rollup — works immediately, no capture needed) or reference a captured advisor call by
 * {@code apiCallLogId}. <b>Capture dependency:</b> only {@code api_call_log} rows logged after the
 * rollup-capture change shipped carry a non-null {@code request_body}; older rows return a clear
 * error, and useful captured rollups accumulate from deploy onward.
 *
 * <p><b>Cost.</b> Each replay hits the live model with an API key — two model calls for a
 * two-prompt before/after. Admin-triggered and occasional, so spend is negligible, but it is real
 * spend, not a stub: do not call this in a loop unawares. ADMIN-only for exactly this reason.
 */
@RestController
@RequestMapping("/api/admin/advisor-replay")
@PreAuthorize("hasRole('ADMIN')")
public class AdvisorReplayController {

    private final BriefingBestBetAdvisor advisor;
    private final ApiCallLogRepository apiCallLogRepository;
    private final ModelSelectionService modelSelectionService;

    /**
     * Constructs the controller.
     *
     * @param advisor               the best-bet advisor exposing {@code replayWithPrompt}
     * @param apiCallLogRepository   resolves a captured rollup from {@code api_call_log}
     * @param modelSelectionService  resolves the default model when the request omits one
     */
    public AdvisorReplayController(BriefingBestBetAdvisor advisor,
            ApiCallLogRepository apiCallLogRepository,
            ModelSelectionService modelSelectionService) {
        this.advisor = advisor;
        this.apiCallLogRepository = apiCallLogRepository;
        this.modelSelectionService = modelSelectionService;
    }

    /**
     * Replays one rollup through the current prompt and, when supplied, a candidate prompt.
     *
     * @param request the rollup source, optional candidate prompt, and optional model
     * @return both pick-sets for a by-eye before/after comparison
     */
    @PostMapping
    public AdvisorReplayResponse replay(@RequestBody AdvisorReplayRequest request) {
        String rollupJson = resolveRollup(request);
        EvaluationModel model = request.model() != null
                ? request.model()
                : modelSelectionService.getActiveModel(RunType.BRIEFING_BEST_BET);
        try {
            BestBetResult current = advisor.replayWithPrompt(
                    rollupJson, advisor.currentSystemPrompt(), model);
            boolean hasCandidate = request.candidatePrompt() != null
                    && !request.candidatePrompt().isBlank();
            BestBetResult candidate = hasCandidate
                    ? advisor.replayWithPrompt(rollupJson, request.candidatePrompt(), model)
                    : null;
            return new AdvisorReplayResponse(model, current, candidate);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Rollup JSON could not be parsed: " + e.getMessage());
        }
    }

    /**
     * Resolves the rollup JSON from the request: the supplied {@code rollupJson} takes precedence;
     * otherwise the captured {@code request_body} of the referenced {@code api_call_log} row.
     *
     * @param request the replay request
     * @return the rollup JSON to replay
     * @throws IllegalArgumentException when neither source is supplied, or the referenced row
     *                                  predates rollup capture (null {@code request_body})
     * @throws NoSuchElementException   when the referenced {@code api_call_log} id does not exist
     */
    private String resolveRollup(AdvisorReplayRequest request) {
        if (request.rollupJson() != null && !request.rollupJson().isBlank()) {
            return request.rollupJson();
        }
        if (request.apiCallLogId() != null) {
            ApiCallLogEntity row = apiCallLogRepository.findById(request.apiCallLogId())
                    .orElseThrow(() -> new NoSuchElementException(
                            "No api_call_log row with id " + request.apiCallLogId()));
            String body = row.getRequestBody();
            if (body == null || body.isBlank()) {
                throw new IllegalArgumentException("api_call_log row " + request.apiCallLogId()
                        + " has no captured rollup (request_body is null — it predates the "
                        + "rollup-capture change)");
            }
            return body;
        }
        throw new IllegalArgumentException("Supply either rollupJson or apiCallLogId");
    }
}
