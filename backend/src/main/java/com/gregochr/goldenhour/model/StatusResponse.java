package com.gregochr.goldenhour.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Enriched application status pushed to clients via SSE.
 *
 * @param status    overall status: {@code "UP"}, {@code "DOWN"}, or {@code "DEGRADED"}
 * @param degraded  soft-fail component names (e.g. {@code ["mail"]}) that caused DEGRADED
 * @param database  database health
 * @param services  external service name to status (e.g. {@code "anthropic" -> "UP"})
 * @param build     build/git metadata
 * @param session   authenticated user info
 * @param checkedAt server timestamp when this status was assembled
 */
public record StatusResponse(
        String status,
        List<String> degraded,
        ComponentStatus database,
        Map<String, ComponentStatus> services,
        BuildInfo build,
        SessionInfo session,
        Instant checkedAt) {

    /**
     * Health status of a single component.
     *
     * @param status    status string ({@code "UP"}, {@code "DOWN"}, {@code "UNKNOWN"})
     * @param detail    optional detail (e.g. circuit breaker state)
     * @param latencyMs optional probe latency in milliseconds
     */
    public record ComponentStatus(String status, String detail, Long latencyMs) {
    }

    /**
     * Build and git metadata.
     *
     * @param commitId   abbreviated git commit hash
     * @param branch     git branch name
     * @param commitTime ISO-8601 commit timestamp
     * @param dirty      whether the working tree had uncommitted changes at build time
     */
    public record BuildInfo(String commitId, String branch, String commitTime, boolean dirty) {
    }

    /**
     * Authenticated session info.
     *
     * @param username  the authenticated user's username
     * @param role      the user's role (e.g. {@code "ADMIN"}, {@code "PRO_USER"})
     * @param loginTime the instant the current JWT was issued (may be {@code null})
     */
    public record SessionInfo(String username, String role, Instant loginTime) {
    }
}
