package com.gregochr.goldenhour.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One weather warning in force, as served. Levels and weather types are strings as the Met Office sent them (levels
 * upper case); likelihood and impact are the raw feed integers, never words.
 *
 * @param warningId     the Met Office warning id
 * @param warningVersion the version in force, as the feed writes it (for example "3.0")
 * @param level         the level word (YELLOW, AMBER, RED ...)
 * @param weatherTypes  the weather types as received
 * @param likelihood    likelihood, the raw integer (1 to 4), or null
 * @param impact        impact, the raw integer (1 to 4), or null
 * @param validFrom     start of validity
 * @param validTo       end of validity
 * @param issuedAt      when issued
 * @param modifiedAt    when last modified
 * @param headline      the Met Office headline
 * @param whatToExpect  the "what to expect" lines
 * @param furtherDetails the further-details text
 * @param updateDescription the Met Office text describing what changed in an update, or null
 * @param whatShouldIDo the Met Office advice text, or null
 * @param updated       true when the version in force is 2.0 or later (the style guide requires an "Updated" label)
 * @param affectedAreas the affected region codes (NE, NW, YH ...)
 * @param geometry      the vertex-thinned GeoJSON geometry (a plain map), or null
 * @param upgradedFrom  the previous level when the level rose within the last 24 hours, else null
 * @param upgradedAt    when the upgrading version was received, else null
 * @param locationIds   ids of the enabled locations inside the warning's area
 * @param simulated     true only on the admin surface for an admin-simulated warning
 */
public record WarningDto(
        String warningId,
        String warningVersion,
        String level,
        List<String> weatherTypes,
        Integer likelihood,
        Integer impact,
        Instant validFrom,
        Instant validTo,
        Instant issuedAt,
        Instant modifiedAt,
        String headline,
        List<String> whatToExpect,
        String furtherDetails,
        String updateDescription,
        String whatShouldIDo,
        boolean updated,
        List<String> affectedAreas,
        Map<String, Object> geometry,
        String upgradedFrom,
        Instant upgradedAt,
        Set<Long> locationIds,
        boolean simulated) {
}
