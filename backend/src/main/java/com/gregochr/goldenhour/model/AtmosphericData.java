package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.TargetType;

import java.time.LocalDateTime;

/**
 * Pre-processed atmospheric data for the ±30-minute window around a solar event.
 *
 * <p>Populated from the Open-Meteo Forecast and Air Quality APIs and passed to
 * {@code EvaluationService} for Claude's colour-potential rating.
 *
 * @param locationName     human-readable location name (e.g. "Durham UK")
 * @param solarEventTime   UTC time of the sunrise or sunset being evaluated
 * @param targetType       SUNRISE or SUNSET
 * @param cloud            observer-point cloud cover at three altitude layers
 * @param weather          core weather observations (wind, visibility, precip, etc.)
 * @param aerosol          aerosol and boundary layer measurements
 * @param comfort          human comfort metrics (temperature, feels-like, precip probability)
 * @param directionalCloud cloud cover at solar/antisolar horizon points, or null if unavailable
 * @param tide             tide state snapshot, or null for inland locations
 * @param cloudApproach       cloud approach risk signals, or null if unavailable
 * @param mistTrend           hourly visibility and dew point trend around the event, or null
 * @param locationOrientation orientation hint (e.g. "sunrise-optimised"), or null for both/allday
 */
public record AtmosphericData(
        String locationName,
        LocalDateTime solarEventTime,
        TargetType targetType,
        CloudData cloud,
        WeatherData weather,
        AerosolData aerosol,
        ComfortData comfort,
        DirectionalCloudData directionalCloud,
        TideSnapshot tide,
        CloudApproachData cloudApproach,
        MistTrend mistTrend,
        String locationOrientation) {

    /**
     * Returns a copy with directional cloud data set.
     *
     * @param dc the directional cloud data to attach
     * @return a new instance with the directional cloud populated
     */
    public AtmosphericData withDirectionalCloud(DirectionalCloudData dc) {
        return new AtmosphericData(locationName, solarEventTime, targetType,
                cloud, weather, aerosol, comfort, dc, tide, cloudApproach, mistTrend,
                locationOrientation);
    }

    /**
     * Returns a copy with tide snapshot set.
     *
     * @param tideSnapshot the tide data to attach
     * @return a new instance with the tide populated
     */
    public AtmosphericData withTide(TideSnapshot tideSnapshot) {
        return new AtmosphericData(locationName, solarEventTime, targetType,
                cloud, weather, aerosol, comfort, directionalCloud, tideSnapshot, cloudApproach,
                mistTrend, locationOrientation);
    }

    /**
     * Returns a copy with cloud approach risk data set.
     *
     * @param approach the cloud approach risk signals to attach
     * @return a new instance with the cloud approach data populated
     */
    public AtmosphericData withCloudApproach(CloudApproachData approach) {
        return new AtmosphericData(locationName, solarEventTime, targetType,
                cloud, weather, aerosol, comfort, directionalCloud, tide, approach, mistTrend,
                locationOrientation);
    }

    /**
     * Returns a copy with location orientation set.
     *
     * @param orientation the orientation hint (e.g. "sunrise-optimised"), or null
     * @return a new instance with the location orientation populated
     */
    public AtmosphericData withLocationOrientation(String orientation) {
        return new AtmosphericData(locationName, solarEventTime, targetType,
                cloud, weather, aerosol, comfort, directionalCloud, tide, cloudApproach,
                mistTrend, orientation);
    }
}
