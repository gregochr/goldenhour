package com.gregochr.goldenhour.model;

import java.util.List;

/**
 * Body of {@code POST /api/admin/warnings/simulate}: a small, clearly simulated warning around a point.
 *
 * @param level         Yellow, Amber or Red (case ignored); defaults to Amber
 * @param weatherTypes  the weather types; defaults to {@code ["WIND"]}
 * @param headline      the headline; defaults to a "Simulated warning" line
 * @param durationHours hours the warning stays valid, 1 to 72; defaults to 6
 * @param latitude      the centre latitude, -90 to 90
 * @param longitude     the centre longitude, -180 to 180
 */
public record SimulateWarningRequest(String level, List<String> weatherTypes, String headline,
        Integer durationHours, double latitude, double longitude) {
}
