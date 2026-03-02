package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.ModelTestResultEntity;
import com.gregochr.goldenhour.entity.ModelTestRunEntity;
import com.gregochr.goldenhour.service.ModelTestService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST endpoints for model comparison tests.
 *
 * <p>Accessible only to ADMIN users. Triggers A/B/C tests across all three Claude models
 * (Haiku, Sonnet, Opus) using identical atmospheric data for one location per region.
 */
@RestController
@RequestMapping("/api/model-test")
public class ModelTestController {

    private final ModelTestService modelTestService;

    /**
     * Constructs a {@code ModelTestController}.
     *
     * @param modelTestService the model test orchestration service
     */
    public ModelTestController(ModelTestService modelTestService) {
        this.modelTestService = modelTestService;
    }

    /**
     * Triggers a model comparison test across all enabled regions.
     *
     * @return the completed test run with summary metrics
     */
    @PostMapping("/run")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ModelTestRunEntity> runTest() {
        ModelTestRunEntity testRun = modelTestService.runTest();
        return ResponseEntity.ok(testRun);
    }

    /**
     * Triggers a model comparison test for a single location.
     *
     * @param locationId the location ID to test
     * @return the completed test run with summary metrics
     */
    @PostMapping("/run-location")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ModelTestRunEntity> runTestForLocation(
            @RequestParam Long locationId) {
        ModelTestRunEntity testRun = modelTestService.runTestForLocation(locationId);
        return ResponseEntity.ok(testRun);
    }

    /**
     * Re-runs a previous model test using the same locations but fresh data.
     *
     * @param testRunId the ID of the previous test run to re-run
     * @return the completed new test run with summary metrics
     */
    @PostMapping("/rerun")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ModelTestRunEntity> rerunTest(
            @RequestParam Long testRunId) {
        ModelTestRunEntity testRun = modelTestService.rerunTest(testRunId);
        return ResponseEntity.ok(testRun);
    }

    /**
     * Re-runs a previous model test using identical atmospheric data (no Open-Meteo calls).
     *
     * <p>Tests Claude's determinism by replaying the exact same input data through
     * all three models.
     *
     * @param testRunId the ID of the previous test run to replay
     * @return the completed new test run with summary metrics
     */
    @PostMapping("/rerun-determinism")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ModelTestRunEntity> rerunDeterminism(
            @RequestParam Long testRunId) {
        ModelTestRunEntity testRun = modelTestService.rerunTestDeterministic(testRunId);
        return ResponseEntity.ok(testRun);
    }

    /**
     * Returns recent test runs (last 20).
     *
     * @return list of recent test runs
     */
    @GetMapping("/runs")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<ModelTestRunEntity>> getRecentRuns() {
        return ResponseEntity.ok(modelTestService.getRecentRuns());
    }

    /**
     * Returns results for a specific test run.
     *
     * @param testRunId the test run ID
     * @return list of results ordered by region name then model
     */
    @GetMapping("/results")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<ModelTestResultEntity>> getResults(
            @RequestParam Long testRunId) {
        return ResponseEntity.ok(modelTestService.getResults(testRunId));
    }
}
