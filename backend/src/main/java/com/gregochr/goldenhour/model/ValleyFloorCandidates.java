package com.gregochr.goldenhour.model;

import java.util.List;

/**
 * Response of {@code GET /api/locations/valley-floor-candidates}: always this shape, so an empty list
 * means "none qualify" and a failure is a 502 instead.
 *
 * @param siteSampleMetres the DEM height at the location itself, in metres (the comparison base, not the
 *                         stored OS height)
 * @param candidates       the qualifying points, lowest first
 */
public record ValleyFloorCandidates(int siteSampleMetres, List<ValleyFloorCandidate> candidates) {
}
