package com.gregochr.goldenhour.model;

import java.util.List;

/**
 * One area's outlook for one reader ({@code GET /api/areas/outlook}): a figure per window, never a verdict.
 *
 * @param areaId  the area's id
 * @param windows the windows that have at least one rated or gated member, by date then sunrise before sunset
 */
public record AreaOutlookEntry(Long areaId, List<AreaWindowOutlook> windows) {
}
