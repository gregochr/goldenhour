package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.AlertLevel;

import java.time.ZonedDateTime;

/**
 * Immutable snapshot of the current AuroraWatch UK status as parsed from the API XML.
 *
 * <p>This is the internal model produced by {@code AuroraWatchClient}. The REST-facing
 * response is {@link AuroraStatusResponse}.
 *
 * @param level      the current alert level
 * @param updatedAt  when AuroraWatch last updated the status
 * @param station    the station name reported by AuroraWatch (e.g. {@code "SAMNET/CRK2"})
 * @param expiresAt  when the cached status expires and should be re-fetched
 */
public record AuroraStatus(
        AlertLevel level,
        ZonedDateTime updatedAt,
        String station,
        ZonedDateTime expiresAt) {
}
