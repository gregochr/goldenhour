package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.SkyRatingEvalRunEntity;
import com.gregochr.goldenhour.entity.SkyRatingEvalTrigger;
import com.gregochr.goldenhour.model.ScheduledModelView;
import com.gregochr.goldenhour.model.SkyRatingEvalTrendPoint;
import com.gregochr.goldenhour.model.skyeval.SkyEvalOverview;
import com.gregochr.goldenhour.service.SkyRatingEvalOverviewService;
import com.gregochr.goldenhour.service.SkyRatingEvalService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * ADMIN endpoints for the sky-rating calibration eval — the persisted, graphable counterpart to the
 * gated {@code SkyRatingEvalTest}.
 *
 * <p>{@code POST /run} returns 202 immediately and scores the fixtures in the background (several
 * minutes of real Claude calls); poll {@code GET /runs/{id}} for completion. The read endpoints back
 * the admin drift graphs.
 */
@RestController
@RequestMapping("/api/admin/sky-rating-eval")
public class SkyRatingEvalController {

    private final SkyRatingEvalService service;
    private final SkyRatingEvalOverviewService overviewService;
    private final Executor asyncExecutor;

    /**
     * Constructs the controller.
     *
     * @param service         the sky-rating eval orchestration service
     * @param overviewService assembles the Sky Eval page payload
     */
    public SkyRatingEvalController(SkyRatingEvalService service, SkyRatingEvalOverviewService overviewService) {
        this.service = service;
        this.overviewService = overviewService;
        this.asyncExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Triggers an eval run. Returns 202 with the in-progress run; the scoring happens in the
     * background. Poll {@code GET /runs/{id}} for completion.
     *
     * @param model          the model to score with (default SONNET, the production near-term scorer)
     * @param runsPerFixture how many times to score each fixture (default
     *                       {@link SkyRatingEvalService#DEFAULT_RUNS_PER_FIXTURE})
     * @return the in-progress run (202 Accepted)
     * @throws IllegalArgumentException if the model is not selectable (mapped to 400)
     */
    @PostMapping("/run")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SkyRatingEvalRunEntity> run(
            @RequestParam(defaultValue = "SONNET") EvaluationModel model,
            @RequestParam(required = false) Integer runsPerFixture) {
        if (!model.isSelectable()) {
            throw new IllegalArgumentException("Model " + model + " cannot be evaluated; choose one of "
                    + EvaluationModel.selectable());
        }
        int runs = runsPerFixture != null ? runsPerFixture : SkyRatingEvalService.DEFAULT_RUNS_PER_FIXTURE;
        SkyRatingEvalRunEntity started = service.startRun(model, SkyRatingEvalTrigger.MANUAL, runs);
        CompletableFuture.runAsync(() -> service.executeRun(started.getId()), asyncExecutor);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(started);
    }

    /**
     * Starts a quick check: one MANUAL run per model, on the current build, scored in parallel in the
     * background. Returns 202 with the run ids; the Runs tab polls the overview for progress.
     *
     * @param body the models, repeats per fixture (1, 3 or 8) and optional fixture subset
     * @return the started run ids (202 Accepted)
     * @throws IllegalArgumentException if the request is invalid (mapped to 400)
     */
    @PostMapping("/check")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CheckResponse> check(@RequestBody CheckRequest body) {
        if (body == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        List<SkyRatingEvalRunEntity> started =
                service.startChecks(body.models(), body.runsPerFixture(), body.fixtureNames());
        started.forEach(run -> CompletableFuture.runAsync(() -> service.executeRun(run.getId()), asyncExecutor));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new CheckResponse(started.stream().map(SkyRatingEvalRunEntity::getId).toList()));
    }

    /**
     * Request body of {@code POST /check}.
     *
     * @param models         the models to check
     * @param runsPerFixture repeats per fixture: 1, 3 or 8
     * @param fixtureNames   fixtures to score, or null for all
     */
    public record CheckRequest(List<EvaluationModel> models, Integer runsPerFixture, List<String> fixtureNames) {
    }

    /**
     * Response of {@code POST /check}.
     *
     * @param runIds the started runs' ids, in model order
     */
    public record CheckResponse(List<Long> runIds) {
    }

    /**
     * Everything the Sky Eval page renders, in one read.
     *
     * @return the overview
     */
    @GetMapping("/overview")
    @PreAuthorize("hasRole('ADMIN')")
    public SkyEvalOverview overview() {
        return overviewService.overview();
    }

    /**
     * The weekly schedule: every selectable model and whether the weekly run includes it.
     *
     * @return one row per selectable model, in selectable order
     */
    @GetMapping("/schedule")
    @PreAuthorize("hasRole('ADMIN')")
    public List<ScheduledModelView> schedule() {
        return service.schedule();
    }

    /**
     * Switches one model on or off for the weekly run. Returns only that model's row, so overlapping
     * toggles from the page cannot overwrite each other.
     *
     * @param model the model name
     * @param body  the request body carrying {@code scheduled}
     * @return the model's updated row
     * @throws IllegalArgumentException if the model is unknown or not selectable, or {@code scheduled}
     *                                  is missing (mapped to 400)
     */
    @PutMapping("/schedule/{model}")
    @PreAuthorize("hasRole('ADMIN')")
    public ScheduledModelView setScheduled(@PathVariable String model,
            @RequestBody ScheduleRequest body) {
        if (body == null || body.scheduled() == null) {
            throw new IllegalArgumentException("Request body must carry a boolean 'scheduled'");
        }
        EvaluationModel parsed;
        try {
            parsed = EvaluationModel.valueOf(model);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown model: " + model, e);
        }
        return service.setScheduled(parsed, body.scheduled());
    }

    /**
     * Request body of {@code PUT /schedule/{model}}.
     *
     * @param scheduled true to include the model in the weekly run
     */
    public record ScheduleRequest(Boolean scheduled) {
    }

    /**
     * Recent runs, newest first.
     *
     * @return the runs list
     */
    @GetMapping("/runs")
    @PreAuthorize("hasRole('ADMIN')")
    public List<SkyRatingEvalRunEntity> runs() {
        return service.recentRuns();
    }

    /**
     * A single run, for progress polling.
     *
     * @param id the run id
     * @return the run
     */
    @GetMapping("/runs/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public SkyRatingEvalRunEntity run(@PathVariable Long id) {
        return service.getRun(id);
    }

    /**
     * The calibration-drift series — one aggregate point per (completed run × fixture).
     *
     * @return the trend points, oldest run first
     */
    @GetMapping("/trend")
    @PreAuthorize("hasRole('ADMIN')")
    public List<SkyRatingEvalTrendPoint> trend() {
        return service.trend();
    }
}
