package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.service.warnings.WarningPollStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The body of {@code GET /api/admin/warnings/status}: the Operations view of the warnings ingest. Nothing here is the
 * API key; only whether one is set.
 *
 * @param enabled            whether the kill switch is on (the raw setting)
 * @param active             whether warnings are effectively live: switch on and an API key set; false means dormant
 * @param apiKeyConfigured   whether a key is set
 * @param feedUrlConfigured  whether a feed URL is set
 * @param lastAttempt        the last poll attempt since startup, or null
 * @param lastSuccessAt      when the feed was last read successfully, or null
 * @param stale              whether the data is stale (see {@link WarningsResponse})
 * @param storedVersions     rows in the store, simulated included
 * @param simulatedVersions  how many of those are simulated
 * @param unknownTypes       weather types without a glyph seen since startup, with counts
 * @param activeWarnings     every warning in force, simulated included, whether or not it covers a location
 */
public record WarningsAdminStatus(
        boolean enabled,
        boolean active,
        boolean apiKeyConfigured,
        boolean feedUrlConfigured,
        WarningPollStatus.Attempt lastAttempt,
        Instant lastSuccessAt,
        boolean stale,
        long storedVersions,
        long simulatedVersions,
        Map<String, Integer> unknownTypes,
        List<WarningDto> activeWarnings) {
}
