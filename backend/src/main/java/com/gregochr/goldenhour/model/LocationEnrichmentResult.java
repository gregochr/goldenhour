package com.gregochr.goldenhour.model;

/**
 * Result of enriching a location's coordinates with metadata from external APIs.
 *
 * <p>Returned by {@code LocationEnrichmentService} and used to pre-populate
 * fields in the admin Add Location form before saving. Nullable fields indicate
 * that the corresponding API call failed — the location can still be saved with
 * partial enrichment.
 *
 * @param bortleClass      Bortle dark-sky class (1–8), or null if lightpollutionmap was unreachable
 * @param skyBrightnessSqm SQM value (magnitudes per sq arcsecond), or null on failure
 * @param elevationMetres  elevation above sea level in metres, or null on failure
 * @param gridLat          Open-Meteo snapped grid latitude, or null on failure
 * @param gridLng          Open-Meteo snapped grid longitude, or null on failure
 */
public record LocationEnrichmentResult(
        Integer bortleClass,
        Double skyBrightnessSqm,
        Integer elevationMetres,
        Double gridLat,
        Double gridLng
) {
}
