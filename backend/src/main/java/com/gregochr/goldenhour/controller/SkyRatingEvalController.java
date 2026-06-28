package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.SkyRatingEvalResultEntity;
import com.gregochr.goldenhour.entity.SkyRatingEvalRunEntity;
import com.gregochr.goldenhour.entity.SkyRatingEvalTrigger;
import com.gregochr.goldenhour.model.SkyRatingEvalTrendPoint;
import com.gregochr.goldenhour.service.SkyRatingEvalService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
    private final Executor asyncExecutor;

    /**
     * Constructs the controller.
     *
     * @param service the sky-rating eval orchestration service
     */
    public SkyRatingEvalController(SkyRatingEvalService service) {
        this.service = service;
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
     */
    @PostMapping("/run")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SkyRatingEvalRunEntity> run(
            @RequestParam(defaultValue = "SONNET") EvaluationModel model,
            @RequestParam(required = false) Integer runsPerFixture) {
        int runs = runsPerFixture != null ? runsPerFixture : SkyRatingEvalService.DEFAULT_RUNS_PER_FIXTURE;
        SkyRatingEvalRunEntity started = service.startRun(model, SkyRatingEvalTrigger.MANUAL, runs);
        CompletableFuture.runAsync(() -> service.executeRun(started.getId()), asyncExecutor);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(started);
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
     * The per-(fixture × run-index) results for one run.
     *
     * @param id the run id
     * @return the results
     */
    @GetMapping("/runs/{id}/results")
    @PreAuthorize("hasRole('ADMIN')")
    public List<SkyRatingEvalResultEntity> results(@PathVariable Long id) {
        return service.resultsForRun(id);
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
