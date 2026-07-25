package com.gregochr.goldenhour.client;

import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

/**
 * Declarative HTTP interface for the Open-Meteo Historical Weather (archive) API.
 *
 * <p>Returns <em>analysed</em> weather for a past date rather than a forecast of it — the
 * reanalysis of what actually happened. This is what lets a past forecast be scored without a
 * human observation: every threshold the scoring rules turn on (solar-horizon low cloud, the
 * building trend, strip-vs-blanket) is a cloud claim, and a cloud claim can be checked against
 * the archive.
 *
 * <p>The response shape matches the forecast API's, so {@link OpenMeteoForecastResponse} is
 * reused for both.
 *
 * <p><strong>Lag:</strong> the archive trails real time by several days, so only sufficiently
 * old dates can be verified — see {@code CloudVerificationService.ARCHIVE_LAG_DAYS}.
 */
@HttpExchange
public interface OpenMeteoArchiveApi {

    /**
     * Fetches analysed hourly weather for a past date range at one point.
     *
     * @param latitude  latitude in decimal degrees
     * @param longitude longitude in decimal degrees
     * @param startDate first date to return (ISO {@code yyyy-MM-dd}, inclusive)
     * @param endDate   last date to return (ISO {@code yyyy-MM-dd}, inclusive)
     * @param hourly    comma-separated list of hourly parameters to return
     * @param timezone  timezone for timestamps (e.g. "UTC")
     * @return the deserialized archive response
     */
    @GetExchange("/v1/archive")
    OpenMeteoForecastResponse getArchive(
            @RequestParam double latitude,
            @RequestParam double longitude,
            @RequestParam("start_date") String startDate,
            @RequestParam("end_date") String endDate,
            @RequestParam String hourly,
            @RequestParam String timezone);
}
