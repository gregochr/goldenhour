package com.gregochr.goldenhour.model;

/**
 * A single sea-state sample — the marine conditions at one location and time.
 *
 * @param significantWaveHeightMetres significant wave height Hs (mean of the highest third), or null
 *                                    when the grid cell has no wave data (a land/estuary point)
 * @param swellWaveHeightMetres       swell wave height (long-period component), or null
 * @param waveDirectionDegrees        mean wave direction of origin in degrees, or null
 */
public record MarineWaveSample(
        Double significantWaveHeightMetres,
        Double swellWaveHeightMetres,
        Integer waveDirectionDegrees) {

    /**
     * The sea-state band for this sample's significant wave height.
     *
     * @return the {@link SeaState} band, or null when Hs is absent
     */
    public SeaState seaState() {
        return significantWaveHeightMetres == null ? null : SeaState.fromHs(significantWaveHeightMetres);
    }
}
