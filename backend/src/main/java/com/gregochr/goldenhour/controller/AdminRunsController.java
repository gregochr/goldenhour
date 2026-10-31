package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.runs.RunGroup;
import com.gregochr.goldenhour.model.runs.RunRange;
import com.gregochr.goldenhour.service.runs.AdminRunsService;
import com.gregochr.goldenhour.service.runs.RunCursor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The merged Runs list for the admin Operations tab: pipeline runs and top-level job runs in one
 * newest-first list with a served group, name, status and note, plus a single job run by id.
 *
 * <p>ADMIN-only. All logic lives in {@link AdminRunsService}; this class only reads and validates the
 * request. An unknown {@code range}, {@code kind}, {@code before} or a non-positive {@code size} is a 400.
 */
@RestController
@RequestMapping("/api/admin/runs")
@PreAuthorize("hasRole('ADMIN')")
public class AdminRunsController {

    private final AdminRunsService runsService;

    /**
     * Constructs the controller.
     *
     * @param runsService builds the merged list and the job-run detail
     */
    public AdminRunsController(AdminRunsService runsService) {
        this.runsService = runsService;
    }

    /**
     * One page of the merged runs list with its range-scoped aggregates.
     *
     * @param range     TODAY, WEEK (default) or MONTH, aligned to UK days
     * @param kind      ALL (default), CYCLES, BATCHES, TIDE, BRIEFING, AURORA or OTHER
     * @param attention true to keep only DEGRADED, PARTIAL and FAILED rows
     * @param before    the opaque cursor from the previous page's {@code nextCursor}
     * @param size      the page size (default 20, clamped to 100, at least 1)
     * @return the page, or 400 for an unknown value
     */
    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(defaultValue = "WEEK") String range,
            @RequestParam(defaultValue = "ALL") String kind,
            @RequestParam(defaultValue = "false") boolean attention,
            @RequestParam(required = false) String before,
            @RequestParam(defaultValue = "" + AdminRunsService.DEFAULT_SIZE) int size) {
        if (size < 1) {
            return ResponseEntity.badRequest().body("size must be >= 1");
        }
        try {
            RunCursor cursor = before == null || before.isBlank() ? null : RunCursor.decode(before);
            return ResponseEntity.ok(runsService.list(RunRange.parse(range), RunGroup.parseKind(kind),
                    attention, cursor, Math.min(size, AdminRunsService.MAX_SIZE)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("Invalid range, kind or cursor");
        }
    }

    /**
     * One job run in full, including a cycle's batch run, which never appears at the top level.
     *
     * @param id the job run id
     * @return the run with its served status, group, name, note and batch, or 404
     */
    @GetMapping("/job/{id}")
    public ResponseEntity<?> job(@PathVariable long id) {
        return runsService.jobDetail(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
