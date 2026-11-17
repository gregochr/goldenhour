package com.gregochr.goldenhour.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One weather warning in force, as served. Levels and weather types are strings exactly as the Met Office sent them.
 *
 * @param warningId     the Met Office warning id
 * @param warningVersion the version in force
 * @param level         the level word (Yellow, Amber, Red ...)
 * @param weatherTypes  the weather types as received
 * @param likelihood    likelihood as received
 * @param impact        impact as received
 * @param validFrom     start of validity
 * @param validTo       end of validity
 * @param issuedAt      when issued
 * @param modifiedAt    when last modified
 * @param headline      the Met Office headline
 * @param whatToExpect  the "what to expect" lines
 * @param furtherDetails the further-details text
 * @param affectedAreas the affected areas
 * @param sourceUrl     the feed's detail link
 * @param geometry      the vertex-thinned GeoJSON geometry (a plain map), or null
 * @param upgradedFrom  the previous level when the level rose within the last 24 hours, else null
 * @param upgradedAt    when the upgrading version was received, else null
 * @param locationIds   ids of the enabled locations inside the warning's area
 * @param simulated     true only on the admin surface for an admin-simulated warning
 */
public record WarningDto(
        String warningId,
        int warningVersion,
        String level,
        List<String> weatherTypes,
        String likelihood,
        String impact,
        Instant validFrom,
        Instant validTo,
        Instant issuedAt,
        Instant modifiedAt,
        String headline,
        List<String> whatToExpect,
        String furtherDetails,
        List<String> affectedAreas,
        String sourceUrl,
        Map<String, Object> geometry,
        String upgradedFrom,
        Instant upgradedAt,
        Set<Long> locationIds,
        boolean simulated) {
}
