package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.service.LunarPhaseService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Maps {@link ForecastEvaluationEntity} to {@link ForecastEvaluationDto} with role-based
 * score selection.
 *
 * <p>LITE users receive basic scores (observer-point inference only). PRO and ADMIN users
 * receive enhanced scores (directional cloud data). The {@code basic_*} columns are an
 * internal persistence detail and never appear in the API response.
 */
@Component
public class ForecastDtoMapper {

    private final LunarPhaseService lunarPhaseService;

    /**
     * Constructs a {@code ForecastDtoMapper}.
     *
     * @param lunarPhaseService service for lunar phase and tide classification
     */
    public ForecastDtoMapper(LunarPhaseService lunarPhaseService) {
        this.lunarPhaseService = lunarPhaseService;
    }

    /**
     * Maps a list of entities to DTOs for the given role.
     *
     * @param entities the forecast evaluation entities
     * @param isLiteUser true if the caller is a LITE_USER
     * @return the mapped DTOs in the same order
     */
    public List<ForecastEvaluationDto> toDtoList(List<ForecastEvaluationEntity> entities,
            boolean isLiteUser) {
        return entities.stream().map(e -> toDto(e, isLiteUser)).toList();
    }

    /**
     * Maps a single entity to a DTO, selecting scores based on user tier.
     *
     * <p>For LITE users, {@code fierySkyPotential}, {@code goldenHourPotential}, and
     * {@code summary} are populated from the {@code basic_*} entity fields (falling back
     * to the enhanced fields if basic scores are null). For PRO/ADMIN users, the enhanced
     * directional scores are used directly.
     *
     * @param entity     the forecast evaluation entity
     * @param isLiteUser true if the caller is a LITE_USER
     * @return the mapped DTO
     */
    public ForecastEvaluationDto toDto(ForecastEvaluationEntity entity, boolean isLiteUser) {
        Integer fierySky;
        Integer goldenHour;
        String summary;

        if (isLiteUser && entity.getBasicFierySkyPotential() != null) {
            fierySky = entity.getBasicFierySkyPotential();
            goldenHour = entity.getBasicGoldenHourPotential();
            summary = entity.getBasicSummary();
        } else {
            fierySky = entity.getFierySkyPotential();
            goldenHour = entity.getGoldenHourPotential();
            summary = entity.getSummary();
        }

        // Lunar classification — deterministic from target date
        LunarTideType lunarTideType = null;
        String lunarPhase = null;
        if (entity.getTargetDate() != null) {
            lunarTideType = lunarPhaseService.classifyTide(entity.getTargetDate());
            lunarPhase = lunarPhaseService.getMoonPhase(entity.getTargetDate());
        }

        return new ForecastEvaluationDto(
                entity.getId(),
                entity.getLocationName(),
                entity.getLocationLat(),
                entity.getLocationLon(),
                entity.getTargetDate(),
                entity.getTargetType(),
                entity.getForecastRunAt(),
                entity.getDaysAhead(),
                entity.getRating(),
                fierySky,
                goldenHour,
                summary,
                entity.getSolarEventTime(),
                entity.getAzimuthDeg(),
                entity.getEvaluationModel(),
                entity.getLowCloud(),
                entity.getMidCloud(),
                entity.getHighCloud(),
                entity.getVisibility(),
                entity.getWindSpeed(),
                entity.getWindDirection(),
                entity.getPrecipitation(),
                entity.getHumidity(),
                entity.getWeatherCode(),
                entity.getBoundaryLayerHeight(),
                entity.getShortwaveRadiation(),
                entity.getPm25(),
                entity.getDust(),
                entity.getAerosolOpticalDepth(),
                entity.getTemperatureCelsius(),
                entity.getApparentTemperatureCelsius(),
                entity.getPrecipitationProbabilityPercent(),
                entity.getDewPointCelsius(),
                entity.getTideState(),
                entity.getNextHighTideTime(),
                entity.getNextHighTideHeightMetres(),
                entity.getNextLowTideTime(),
                entity.getNextLowTideHeightMetres(),
                entity.getTideAligned(),
                entity.getSolarLowCloud(),
                entity.getSolarMidCloud(),
                entity.getSolarHighCloud(),
                entity.getAntisolarLowCloud(),
                entity.getAntisolarMidCloud(),
                entity.getAntisolarHighCloud(),
                entity.getSolarTrendEventLowCloud(),
                entity.getSolarTrendEarliestLowCloud(),
                entity.getSolarTrendBuilding(),
                entity.getUpwindCurrentLowCloud(),
                entity.getUpwindEventLowCloud(),
                entity.getUpwindDistanceKm(),
                lunarTideType,
                lunarPhase);
    }
}
