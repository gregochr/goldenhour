package com.gregochr.goldenhour.model;

import java.util.List;

/**
 * Outcome of one batched archive fetch.
 *
 * <p>Carries the spent-allowance signal in the return value rather than as an exception because the
 * archive client shares the {@code open-meteo} retry and circuit breaker with the forecast client:
 * an exception escaping it would be retried and could open the shared breaker, cutting off forecast
 * fetches over a quota that only the archive has used up.
 *
 * @param responses            responses positionally aligned with the requested coordinates, with
 *                             {@code null} where a point was not fetched or its chunk failed
 * @param quotaExhaustedReason the "request limit exceeded" reason when Open-Meteo reported the
 *                             daily or hourly allowance spent, or {@code null} otherwise
 */
public record ArchiveBatchResult(List<OpenMeteoForecastResponse> responses,
                                 String quotaExhaustedReason) {

    /**
     * Returns whether Open-Meteo reported the archive allowance spent.
     *
     * @return true when a quota reason is present
     */
    public boolean quotaExhausted() {
        return quotaExhaustedReason != null;
    }
}
