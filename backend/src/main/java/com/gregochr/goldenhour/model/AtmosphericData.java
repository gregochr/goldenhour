package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.ForecastStability;
import com.gregochr.goldenhour.entity.TargetType;

import java.time.LocalDateTime;

/**
 * Pre-processed atmospheric data for the ±30-minute window around a solar event.
 *
 * <p>Populated from the Open-Meteo Forecast and Air Quality APIs and passed to
 * {@code EvaluationService} for Claude's colour-potential rating.
 *
 * @param locationName            human-readable location name (e.g. "Durham UK")
 * @param solarEventTime          UTC time of the sunrise or sunset being evaluated
 * @param targetType              SUNRISE or SUNSET
 * @param cloud                   observer-point cloud cover at three altitude layers
 * @param weather                 core weather observations (wind, visibility, precip, etc.)
 * @param aerosol                 aerosol and boundary layer measurements
 * @param comfort                 human comfort metrics (temperature, feels-like, precip probability)
 * @param directionalCloud        cloud cover at solar/antisolar horizon points, or null if unavailable
 * @param tide                    tide state snapshot, or null for inland locations
 * @param cloudApproach           cloud approach risk signals, or null if unavailable
 * @param mistTrend               hourly visibility and dew point trend around the event, or null
 * @param locationOrientation     orientation hint (e.g. "sunrise-optimised"), or null for both/allday
 * @param surge                   storm surge breakdown, or null for inland/non-coastal locations
 * @param adjustedRangeMetres     tidal range adjusted for surge (upper bound), or null
 * @param astronomicalRangeMetres predicted astronomical tidal range, or null
 * @param inversionScore          cloud inversion likelihood score (0–10), or null if not applicable
 * @param bluebellConditionScore  bluebell photography conditions, or null outside season/non-bluebell
 * @param stability               synoptic-scale forecast stability, or null on manual runs
 * @param stabilityReason         human-readable signal summary from the stability classifier, or null
 * @param pressureTrend           hourly pressure tendency around the event, or null if data unavailable
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
        String locationOrientation,
        StormSurgeBreakdown surge,
        Double adjustedRangeMetres,
        Double astronomicalRangeMetres,
        Double inversionScore,
        BluebellConditionScore bluebellConditionScore,
        ForecastStability stability,
        String stabilityReason,
        PressureTrend pressureTrend) {

    /**
     * Backward-compatible constructor for callers that don't supply surge, orientation,
     * stability, or pressure trend data.
     *
     * @param locationName     human-readable location name
     * @param solarEventTime   UTC time of the solar event
     * @param targetType       SUNRISE or SUNSET
     * @param cloud            observer-point cloud cover
     * @param weather          core weather observations
     * @param aerosol          aerosol measurements
     * @param comfort          comfort metrics
     * @param directionalCloud directional cloud data, or null
     * @param tide             tide snapshot, or null
     * @param cloudApproach    cloud approach data, or null
     * @param mistTrend        mist trend, or null
     */
    public AtmosphericData(
            String locationName, LocalDateTime solarEventTime, TargetType targetType,
            CloudData cloud, WeatherData weather, AerosolData aerosol, ComfortData comfort,
            DirectionalCloudData directionalCloud, TideSnapshot tide,
            CloudApproachData cloudApproach, MistTrend mistTrend) {
        this(locationName, solarEventTime, targetType, cloud, weather, aerosol, comfort,
                directionalCloud, tide, cloudApproach, mistTrend, null, null, null, null,
                null, null, null, null, null);
    }

    /**
     * Backward-compatible constructor for callers that supply mist trend and pressure trend
     * but not surge, orientation, stability, or other later fields.
     *
     * @param locationName     human-readable location name
     * @param solarEventTime   UTC time of the solar event
     * @param targetType       SUNRISE or SUNSET
     * @param cloud            observer-point cloud cover
     * @param weather          core weather observations
     * @param aerosol          aerosol measurements
     * @param comfort          comfort metrics
     * @param directionalCloud directional cloud data, or null
     * @param tide             tide snapshot, or null
     * @param cloudApproach    cloud approach data, or null
     * @param mistTrend        mist trend, or null
     * @param pressureTrend    pressure trend, or null
     */
    public AtmosphericData(
            String locationName, LocalDateTime solarEventTime, TargetType targetType,
            CloudData cloud, WeatherData weather, AerosolData aerosol, ComfortData comfort,
            DirectionalCloudData directionalCloud, TideSnapshot tide,
            CloudApproachData cloudApproach, MistTrend mistTrend,
            PressureTrend pressureTrend) {
        this(locationName, solarEventTime, targetType, cloud, weather, aerosol, comfort,
                directionalCloud, tide, cloudApproach, mistTrend, null, null, null, null,
                null, null, null, null, pressureTrend);
    }

    /**
     * Returns a copy with directional cloud data set.
     *
     * @param dc the directional cloud data to attach
     * @return a new instance with the directional cloud populated
     */
    public AtmosphericData withDirectionalCloud(DirectionalCloudData dc) {
        return new AtmosphericData(locationName, solarEventTime, targetType,
                cloud, weather, aerosol, comfort, dc, tide, cloudApproach, mistTrend,
                locationOrientation, surge, adjustedRangeMetres, astronomicalRangeMetres,
                inversionScore, bluebellConditionScore, stability, stabilityReason,
                pressureTrend);
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
                mistTrend, locationOrientation, surge, adjustedRangeMetres,
                astronomicalRangeMetres, inversionScore, bluebellConditionScore,
                stability, stabilityReason, pressureTrend);
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
                locationOrientation, surge, adjustedRangeMetres, astronomicalRangeMetres,
                inversionScore, bluebellConditionScore, stability, stabilityReason,
                pressureTrend);
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
                mistTrend, orientation, surge, adjustedRangeMetres, astronomicalRangeMetres,
                inversionScore, bluebellConditionScore, stability, stabilityReason,
                pressureTrend);
    }

    /**
     * Returns a copy with storm surge data set.
     *
     * @param surgeBreakdown    the storm surge breakdown
     * @param adjustedRange     tidal range adjusted for surge (upper bound), or null
     * @param astronomicalRange predicted astronomical tidal range, or null
     * @return a new instance with the surge data populated
     */
    public AtmosphericData withSurge(StormSurgeBreakdown surgeBreakdown,
            Double adjustedRange, Double astronomicalRange) {
        return new AtmosphericData(locationName, solarEventTime, targetType,
                cloud, weather, aerosol, comfort, directionalCloud, tide, cloudApproach,
                mistTrend, locationOrientation, surgeBreakdown, adjustedRange, astronomicalRange,
                inversionScore, bluebellConditionScore, stability, stabilityReason,
                pressureTrend);
    }

    /**
     * Returns a copy with cloud inversion score set.
     *
     * @param score the inversion likelihood score (0–10), or null
     * @return a new instance with the inversion score populated
     */
    public AtmosphericData withInversionScore(Double score) {
        return new AtmosphericData(locationName, solarEventTime, targetType,
                cloud, weather, aerosol, comfort, directionalCloud, tide, cloudApproach,
                mistTrend, locationOrientation, surge, adjustedRangeMetres,
                astronomicalRangeMetres, score, bluebellConditionScore,
                stability, stabilityReason, pressureTrend);
    }

    /**
     * Returns a copy with bluebell condition score set.
     *
     * @param score the bluebell condition score, or null
     * @return a new instance with the bluebell score populated
     */
    public AtmosphericData withBluebellConditionScore(BluebellConditionScore score) {
        return new AtmosphericData(locationName, solarEventTime, targetType,
                cloud, weather, aerosol, comfort, directionalCloud, tide, cloudApproach,
                mistTrend, locationOrientation, surge, adjustedRangeMetres,
                astronomicalRangeMetres, inversionScore, score,
                stability, stabilityReason, pressureTrend);
    }

    /**
     * Returns a copy with forecast stability classification set.
     *
     * @param forecastStability the synoptic-scale stability classification, or null
     * @param reason            human-readable signal summary, or null
     * @return a new instance with the stability populated
     */
    public AtmosphericData withStability(ForecastStability forecastStability, String reason) {
        return new AtmosphericData(locationName, solarEventTime, targetType,
                cloud, weather, aerosol, comfort, directionalCloud, tide, cloudApproach,
                mistTrend, locationOrientation, surge, adjustedRangeMetres,
                astronomicalRangeMetres, inversionScore, bluebellConditionScore,
                forecastStability, reason, pressureTrend);
    }
}
