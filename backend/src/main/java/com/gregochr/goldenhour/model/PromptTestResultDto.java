package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.PromptTestResultEntity;
import com.gregochr.goldenhour.entity.TargetType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Response DTO for one location evaluation within a prompt regression test run.
 *
 * <p>Excludes {@code testRunId}, the stored prompt and raw response, and token
 * usage/cache counts, while retaining the denormalised atmospheric fields the UI shows.
 *
 * @param id                        the database identifier
 * @param locationId                foreign key to the evaluated location
 * @param locationName              denormalised location name
 * @param targetDate                the date being evaluated
 * @param targetType                whether SUNRISE or SUNSET was evaluated
 * @param evaluationModel           the Claude model used
 * @param rating                    star rating (1-5), or null if the evaluation failed
 * @param fierySkyPotential         Fiery Sky Potential (0-100), or null if failed
 * @param goldenHourPotential       Golden Hour Potential (0-100), or null if failed
 * @param summary                   Claude's plain-English summary, or null if failed
 * @param atmosphericDataJson       serialised atmospheric data JSON for replay
 * @param durationMs                how long the Claude call took in milliseconds
 * @param costMicroDollars          cost of this evaluation in micro-dollars
 * @param succeeded                 whether the evaluation succeeded
 * @param errorMessage              error message if the evaluation failed, else null
 * @param createdAt                 when this result was created (UTC)
 * @param lowCloudPercent           low cloud cover percentage (0-100)
 * @param midCloudPercent           mid-level cloud cover percentage (0-100)
 * @param highCloudPercent          high cloud cover percentage (0-100)
 * @param visibilityMetres          visibility in metres
 * @param windSpeedMs               wind speed in metres per second
 * @param windDirectionDegrees      wind direction in degrees (0-360)
 * @param precipitationMm           precipitation in millimetres
 * @param humidityPercent           relative humidity percentage (0-100)
 * @param weatherCode               WMO weather code
 * @param pm25                      PM2.5 particulate matter concentration
 * @param dustUgm3                  dust concentration in micrograms per cubic metre
 * @param aerosolOpticalDepth       aerosol optical depth
 * @param temperatureCelsius        air temperature in degrees Celsius
 * @param apparentTemperatureCelsius apparent (feels-like) temperature in degrees Celsius
 * @param precipitationProbability  probability of precipitation (0-100)
 * @param tideState                 tide state at the time of the solar event
 * @param tideAligned               whether the tide aligned with the location's preference
 */
public record PromptTestResultDto(
        Long id,
        Long locationId,
        String locationName,
        LocalDate targetDate,
        TargetType targetType,
        EvaluationModel evaluationModel,
        Integer rating,
        Integer fierySkyPotential,
        Integer goldenHourPotential,
        String summary,
        String atmosphericDataJson,
        Long durationMs,
        Long costMicroDollars,
        Boolean succeeded,
        String errorMessage,
        LocalDateTime createdAt,
        Integer lowCloudPercent,
        Integer midCloudPercent,
        Integer highCloudPercent,
        Integer visibilityMetres,
        BigDecimal windSpeedMs,
        Integer windDirectionDegrees,
        BigDecimal precipitationMm,
        Integer humidityPercent,
        Integer weatherCode,
        BigDecimal pm25,
        BigDecimal dustUgm3,
        BigDecimal aerosolOpticalDepth,
        Double temperatureCelsius,
        Double apparentTemperatureCelsius,
        Integer precipitationProbability,
        String tideState,
        Boolean tideAligned
) {

    /**
     * Builds a {@code PromptTestResultDto} from a {@link PromptTestResultEntity}, copying exposed fields.
     *
     * @param e the source entity
     * @return the populated DTO
     */
    public static PromptTestResultDto from(PromptTestResultEntity e) {
        return new PromptTestResultDto(
                e.getId(),
                e.getLocationId(),
                e.getLocationName(),
                e.getTargetDate(),
                e.getTargetType(),
                e.getEvaluationModel(),
                e.getRating(),
                e.getFierySkyPotential(),
                e.getGoldenHourPotential(),
                e.getSummary(),
                e.getAtmosphericDataJson(),
                e.getDurationMs(),
                e.getCostMicroDollars(),
                e.getSucceeded(),
                e.getErrorMessage(),
                e.getCreatedAt(),
                e.getLowCloudPercent(),
                e.getMidCloudPercent(),
                e.getHighCloudPercent(),
                e.getVisibilityMetres(),
                e.getWindSpeedMs(),
                e.getWindDirectionDegrees(),
                e.getPrecipitationMm(),
                e.getHumidityPercent(),
                e.getWeatherCode(),
                e.getPm25(),
                e.getDustUgm3(),
                e.getAerosolOpticalDepth(),
                e.getTemperatureCelsius(),
                e.getApparentTemperatureCelsius(),
                e.getPrecipitationProbability(),
                e.getTideState(),
                e.getTideAligned()
        );
    }
}
