package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Admin-only REST endpoints for managing dynamically scheduled jobs.
 */
@RestController
@RequestMapping("/api/admin/scheduler")
public class SchedulerController {

    private final DynamicSchedulerService schedulerService;

    /**
     * Constructs the scheduler controller.
     *
     * @param schedulerService the dynamic scheduler service
     */
    public SchedulerController(DynamicSchedulerService schedulerService) {
        this.schedulerService = schedulerService;
    }

    /**
     * Lists all scheduled jobs with their current status and computed next fire time.
     *
     * @return list of job detail maps
     */
    @GetMapping("/jobs")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<Map<String, Object>>> listJobs() {
        List<SchedulerJobConfigEntity> configs = schedulerService.getAll();
        List<Map<String, Object>> result = configs.stream()
                .map(this::toDto)
                .toList();
        return ResponseEntity.ok(result);
    }

    /**
     * Updates the schedule expression for a job.
     *
     * @param jobKey the job key
     * @param body   request body with optional cronExpression or fixedDelayMs
     * @return the updated job detail
     */
    @PutMapping("/jobs/{jobKey}/schedule")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> updateSchedule(
            @PathVariable String jobKey,
            @RequestBody Map<String, Object> body) {
        String cronExpression = (String) body.get("cronExpression");
        Long fixedDelayMs = body.get("fixedDelayMs") != null
                ? ((Number) body.get("fixedDelayMs")).longValue() : null;

        SchedulerJobConfigEntity updated = schedulerService.updateSchedule(
                jobKey, cronExpression, fixedDelayMs);
        return ResponseEntity.ok(toDto(updated));
    }

    /**
     * Pauses a scheduled job.
     *
     * @param jobKey the job key
     * @return the updated job detail
     */
    @PostMapping("/jobs/{jobKey}/pause")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> pauseJob(@PathVariable String jobKey) {
        SchedulerJobConfigEntity updated = schedulerService.pause(jobKey);
        return ResponseEntity.ok(toDto(updated));
    }

    /**
     * Resumes a paused job.
     *
     * @param jobKey the job key
     * @return the updated job detail
     */
    @PostMapping("/jobs/{jobKey}/resume")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> resumeJob(@PathVariable String jobKey) {
        SchedulerJobConfigEntity updated = schedulerService.resume(jobKey);
        return ResponseEntity.ok(toDto(updated));
    }

    /**
     * Triggers a job to run immediately.
     *
     * @param jobKey the job key
     * @return confirmation message
     */
    @PostMapping("/jobs/{jobKey}/trigger")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> triggerJob(@PathVariable String jobKey) {
        schedulerService.triggerNow(jobKey);
        return ResponseEntity.ok(Map.of("status", "triggered", "jobKey", jobKey));
    }

    private Map<String, Object> toDto(SchedulerJobConfigEntity config) {
        Instant nextFireTime = schedulerService.calculateNextFireTime(config);
        return Map.ofEntries(
                Map.entry("id", config.getId()),
                Map.entry("jobKey", config.getJobKey()),
                Map.entry("displayName", config.getDisplayName()),
                Map.entry("description",
                        config.getDescription() != null ? config.getDescription() : ""),
                Map.entry("scheduleType", config.getScheduleType().name()),
                Map.entry("cronExpression",
                        config.getCronExpression() != null ? config.getCronExpression() : ""),
                Map.entry("fixedDelayMs",
                        config.getFixedDelayMs() != null ? config.getFixedDelayMs() : 0),
                Map.entry("initialDelayMs",
                        config.getInitialDelayMs() != null ? config.getInitialDelayMs() : 0),
                Map.entry("status", config.getStatus().name()),
                Map.entry("lastFireTime",
                        config.getLastFireTime() != null
                                ? config.getLastFireTime().toString() : ""),
                Map.entry("lastCompletionTime",
                        config.getLastCompletionTime() != null
                                ? config.getLastCompletionTime().toString() : ""),
                Map.entry("nextFireTime",
                        nextFireTime != null ? nextFireTime.toString() : ""),
                Map.entry("configSource",
                        config.getConfigSource() != null ? config.getConfigSource() : ""),
                Map.entry("updatedAt", config.getUpdatedAt().toString())
        );
    }
}
