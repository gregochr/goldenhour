package com.gregochr.goldenhour.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.time.Instant;

/**
 * Returns lightweight build and deploy metadata for the admin Job Runs screen.
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminBuildInfoController {

    private final String version;
    private final Instant deployedAt;

    /**
     * Constructs the controller, capturing the JVM start time once at startup.
     *
     * @param version the application version, read from the {@code APP_VERSION}
     *                environment variable (defaults to {@code "dev"})
     */
    public AdminBuildInfoController(@Value("${APP_VERSION:dev}") String version) {
        this.version = version;
        this.deployedAt = Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime());
    }

    /**
     * Build and deploy metadata.
     *
     * @param version    application version string (e.g. {@code "v2.8.12"} or {@code "dev"})
     * @param deployedAt JVM start time (proxy for container start / deploy time)
     */
    public record BuildInfoResponse(String version, Instant deployedAt) {
    }

    /**
     * Returns the application version and deploy timestamp.
     *
     * @return build info
     */
    @GetMapping("/build-info")
    public BuildInfoResponse buildInfo() {
        return new BuildInfoResponse(version, deployedAt);
    }
}
