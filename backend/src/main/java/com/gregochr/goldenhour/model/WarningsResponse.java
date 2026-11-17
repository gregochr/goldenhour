package com.gregochr.goldenhour.model;

import java.time.Instant;
import java.util.List;

/**
 * The body of {@code GET /api/warnings}. The same for every signed-in reader and role.
 *
 * <p>{@code stale} is the guard against an empty list reading as an all-clear: when true, no surface may say "no
 * warnings". It is true when the last successful poll is older than the configured limit, when there has been none
 * since the backend started, and when the feature is switched off.
 *
 * @param enabled       whether the warnings feature is switched on
 * @param lastCheckedAt when the feed was last read successfully, or null if not since startup
 * @param stale         whether the data is too old (or never read) to be trusted as complete
 * @param warnings      the warnings in force that cover at least one enabled location, most severe first
 * @param metOfficeUrl  the public Met Office warnings page every warning links to (the feed has no per-warning URL)
 * @param attribution   the attribution line the licence requires beside warning text
 */
public record WarningsResponse(boolean enabled, Instant lastCheckedAt, boolean stale, List<WarningDto> warnings,
        String metOfficeUrl, String attribution) {
}
