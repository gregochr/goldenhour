package com.gregochr.goldenhour.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.gregochr.goldenhour.util.LogSanitizer;

/**
 * The point on Earth the solar-relative schedules are timed against
 * (solar-relative-refresh-plan D5).
 *
 * <p>Defaults to Durham, 54.776 N, -1.575 E: the point {@code AuroraPollingJob},
 * {@code BriefingAuroraSummaryBuilder} and {@code AstroConditionsService} already use. Overridable
 * with {@code photocast.scheduler.solar-reference.lat} and {@code .lon}.
 *
 * <p>⚠️ <b>This class can never stop the application starting or a job being scheduled.</b> It reads
 * the two properties as raw strings (a typed binding to a double would fail startup on
 * {@code "north"}), and a missing, unparseable, non-finite or out-of-range value, or only one of the
 * two being set, logs ERROR and falls back to Durham for both coordinates. Mixing a configured
 * latitude with a default longitude would name a point nobody chose.
 */
@Component
public class SolarReferencePoint {

    /** Property holding the latitude. */
    static final String LAT_PROPERTY = "photocast.scheduler.solar-reference.lat";

    /** Property holding the longitude. */
    static final String LON_PROPERTY = "photocast.scheduler.solar-reference.lon";

    /** Durham latitude, the default. */
    static final double DEFAULT_LAT = 54.776;

    /** Durham longitude, the default. */
    static final double DEFAULT_LON = -1.575;

    private static final Logger LOG = LoggerFactory.getLogger(SolarReferencePoint.class);

    private final double lat;
    private final double lon;

    /**
     * Resolves the reference point from the environment.
     *
     * @param environment the Spring environment
     */
    @Autowired
    public SolarReferencePoint(Environment environment) {
        this(environment.getProperty(LAT_PROPERTY), environment.getProperty(LON_PROPERTY));
    }

    /**
     * Resolves the reference point from raw property values.
     *
     * @param rawLat the latitude property, or null when unset
     * @param rawLon the longitude property, or null when unset
     */
    SolarReferencePoint(String rawLat, String rawLon) {
        double resolvedLat = DEFAULT_LAT;
        double resolvedLon = DEFAULT_LON;
        boolean configured = rawLat != null && !rawLat.isBlank()
                || rawLon != null && !rawLon.isBlank();
        if (configured) {
            Double parsedLat = parse(rawLat, -90.0, 90.0);
            Double parsedLon = parse(rawLon, -180.0, 180.0);
            if (parsedLat != null && parsedLon != null) {
                resolvedLat = parsedLat;
                resolvedLon = parsedLon;
            } else {
                LOG.error("Invalid solar reference point ({}='{}', {}='{}'): both must be numbers, "
                        + "latitude in [-90, 90] and longitude in [-180, 180]. Using Durham "
                        + "({}, {}) so scheduling is unaffected.", LAT_PROPERTY,
                        LogSanitizer.sanitize(rawLat), LON_PROPERTY, LogSanitizer.sanitize(rawLon),
                        DEFAULT_LAT, DEFAULT_LON);
            }
        }
        this.lat = resolvedLat;
        this.lon = resolvedLon;
    }

    private static Double parse(String raw, double min, double max) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            double value = Double.parseDouble(raw.trim());
            return Double.isFinite(value) && value >= min && value <= max ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * The reference latitude.
     *
     * @return degrees north
     */
    public double lat() {
        return lat;
    }

    /**
     * The reference longitude.
     *
     * @return degrees east
     */
    public double lon() {
        return lon;
    }
}
