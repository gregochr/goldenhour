package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.PromptTestResultEntity;
import com.gregochr.goldenhour.entity.PromptTestRunEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.service.GitInfoService;
import com.gregochr.goldenhour.service.PromptTestService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST endpoints for prompt regression testing.
 *
 * <p>Accessible only to ADMIN users. Runs Claude evaluations across all colour
 * locations with a chosen model, stores atmospheric data for replay, and exposes
 * git commit information for tracking prompt changes.
 */
@RestController
@RequestMapping("/api/prompt-test")
public class PromptTestController {

    private final PromptTestService promptTestService;
    private final GitInfoService gitInfoService;

    /**
     * Constructs a {@code PromptTestController}.
     *
     * @param promptTestService the prompt test orchestration service
     * @param gitInfoService    the git commit info service
     */
    public PromptTestController(PromptTestService promptTestService,
            GitInfoService gitInfoService) {
        this.promptTestService = promptTestService;
        this.gitInfoService = gitInfoService;
    }

    /**
     * Triggers a prompt test across all colour locations with fresh weather data.
     *
     * @param model   the evaluation model to use
     * @param runType the run type controlling the date range
     * @return the completed test run with summary metrics
     */
    @PostMapping("/run")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PromptTestRunEntity> runTest(
            @RequestParam EvaluationModel model,
            @RequestParam RunType runType) {
        PromptTestRunEntity testRun = promptTestService.runTest(model, runType);
        return ResponseEntity.ok(testRun);
    }

    /**
     * Replays a previous test using stored atmospheric data and the parent's model.
     *
     * @param parentRunId the ID of the previous test run to replay
     * @return the completed new test run with summary metrics
     */
    @PostMapping("/replay")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PromptTestRunEntity> replayTest(
            @RequestParam Long parentRunId) {
        PromptTestRunEntity testRun = promptTestService.replayTest(parentRunId);
        return ResponseEntity.ok(testRun);
    }

    /**
     * Returns recent prompt test runs (last 20).
     *
     * @return list of recent test runs
     */
    @GetMapping("/runs")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<PromptTestRunEntity>> getRecentRuns() {
        return ResponseEntity.ok(promptTestService.getRecentRuns());
    }

    /**
     * Returns results for a specific test run.
     *
     * @param testRunId the test run ID
     * @return list of results ordered by location name
     */
    @GetMapping("/results")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<PromptTestResultEntity>> getResults(
            @RequestParam Long testRunId) {
        return ResponseEntity.ok(promptTestService.getResults(testRunId));
    }

    /**
     * Returns the current build's git commit information.
     *
     * @return map of git info fields
     */
    @GetMapping("/git-info")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> getGitInfo() {
        return ResponseEntity.ok(Map.of(
                "available", gitInfoService.isAvailable(),
                "commitHash", gitInfoService.getCommitHash(),
                "commitAbbrev", gitInfoService.getCommitAbbrev(),
                "commitDate", gitInfoService.getCommitDate() != null
                        ? gitInfoService.getCommitDate().toString() : "",
                "dirty", gitInfoService.isDirty(),
                "branch", gitInfoService.getBranch()
        ));
    }
}
