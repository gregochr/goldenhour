package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.StormSurgeBreakdown;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Structured logging of surge predictions for post-MVP empirical calibration.
 *
 * <h3>Calibration workflow:</h3>
 * <ol>
 *   <li>This logger emits structured log lines each time a surge is
 *       calculated for a coastal location.</li>
 *   <li>After 30–50 days of operation, extract logged predictions.</li>
 *   <li>Compare against observed surge residuals from NTSLF tide gauges
 *       (North Shields).</li>
 *   <li>Adjust the WIND_SETUP_CALIBRATION_FACTOR in StormSurgeService.</li>
 * </ol>
 */
@Service
public class SurgeCalibrationLogger {

    private static final Logger CALIBRATION_LOG = LoggerFactory.getLogger("surge.calibration");

    /**
     * Log a surge prediction for later calibration analysis.
     *
     * @param locationId   PhotoCast location ID
     * @param locationName location name (e.g., "Craster")
     * @param forecastTime the time the surge prediction is for (next high tide)
     * @param surge        the calculated surge breakdown
     */
    public void logPrediction(Long locationId, String locationName,
                              Instant forecastTime, StormSurgeBreakdown surge) {
        if (!surge.isSignificant()) {
            return;
        }

        CALIBRATION_LOG.info(
                "SURGE_PREDICTION | location_id={} | location={} | forecast_time={} | "
                + "pressure_hpa={} | wind_speed_ms={} | wind_dir={} | "
                + "onshore_frac={} | pressure_rise_m={} | wind_rise_m={} | "
                + "total_surge_m={} | risk={}",
                locationId,
                locationName,
                forecastTime,
                surge.pressureHpa(),
                surge.windSpeedMs(),
                surge.windDirectionDegrees(),
                surge.onshoreComponentFraction(),
                surge.pressureRiseMetres(),
                surge.windRiseMetres(),
                surge.totalSurgeMetres(),
                surge.riskLevel()
        );
    }
}
