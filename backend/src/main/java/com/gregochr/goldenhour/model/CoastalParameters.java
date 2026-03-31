package com.gregochr.goldenhour.model;

/**
 * Per-location coastal parameters used for storm surge calculation.
 *
 * <p>These are static properties of each coastal location that determine
 * how wind and pressure translate into water level changes. Stored on the
 * location entity and populated once per location during setup.
 *
 * <h3>Parameter guidance for Northern England east coast:</h3>
 * <ul>
 *   <li><b>Shore-normal bearing:</b> The compass bearing perpendicular to the
 *       coastline, pointing seaward. East-facing coasts ≈ 90°. Wind blowing
 *       FROM the shore-normal direction produces maximum onshore setup.</li>
 *   <li><b>Effective fetch:</b> The open-water distance over which wind can
 *       push water toward the location. For Northumberland, NW/N winds have
 *       ~200–400km of North Sea fetch. Use the dominant storm-wind direction
 *       fetch, not maximum possible.</li>
 *   <li><b>Average shelf depth:</b> Representative water depth over the fetch
 *       distance. Shallower water amplifies wind setup (setup ∝ 1/depth).
 *       Northumberland inner shelf is ~20–40m.</li>
 * </ul>
 *
 * @param shoreNormalBearingDegrees compass bearing of the outward shore-normal (0–360°)
 * @param effectiveFetchMetres      open-water fetch distance for dominant storm winds (m)
 * @param avgShelfDepthMetres       representative water depth over the fetch (m)
 * @param isCoastalTidal            true if this location has meaningful tidal surge exposure
 */
public record CoastalParameters(
        double shoreNormalBearingDegrees,
        double effectiveFetchMetres,
        double avgShelfDepthMetres,
        boolean isCoastalTidal
) {

    /**
     * Default parameters for locations without coastal surge exposure
     * (freshwater, inland, or no tidal data). Returns zero surge.
     */
    public static final CoastalParameters NON_TIDAL = new CoastalParameters(0, 0, 1, false);
}
