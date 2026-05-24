package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.service.LunarPhaseService;
import com.gregochr.goldenhour.service.SolarService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
    private final SolarService solarService;

    /**
     * Constructs a {@code ForecastDtoMapper}.
     *
     * @param lunarPhaseService service for lunar phase and tide classification
     * @param solarService      service for solar window calculations
     */
    public ForecastDtoMapper(LunarPhaseService lunarPhaseService, SolarService solarService) {
        this.lunarPhaseService = lunarPhaseService;
        this.solarService = solarService;
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

        // Golden/blue hour window — elevation-based, not ±60 min
        LocalDateTime goldenHourStart = null;
        LocalDateTime goldenHourEnd = null;
        LocalDateTime blueHourStart = null;
        LocalDateTime blueHourEnd = null;
        if (entity.getTargetDate() != null && entity.getTargetType() != null
                && entity.getTargetType() != TargetType.HOURLY
                && entity.getLocationLat() != null && entity.getLocationLon() != null) {
            try {
                boolean isSunrise = entity.getTargetType() == TargetType.SUNRISE;
                SolarService.SolarWindow sw = solarService.goldenBlueWindow(
                        entity.getLocationLat().doubleValue(),
                        entity.getLocationLon().doubleValue(),
                        entity.getTargetDate(), isSunrise);
                goldenHourStart = sw.goldenHourStart();
                goldenHourEnd = sw.goldenHourEnd();
                blueHourStart = sw.blueHourStart();
                blueHourEnd = sw.blueHourEnd();
            } catch (Exception ignored) {
                // Graceful — leave null if calculation fails (e.g. polar edge case)
            }
        }

        // Bluebell fields — only populated during season for bluebell sites
        Integer bluebellScore = null;
        String bluebellSummary = null;
        String bluebellExposure = null;
        LocationEntity loc = entity.getLocation();
        if (loc != null && loc.getLocationType() != null
                && loc.getLocationType().contains(LocationType.BLUEBELL)
                && entity.getTargetDate() != null
                && SeasonalWindow.BLUEBELL.isActive(entity.getTargetDate())) {
            bluebellScore = entity.getBluebellScore();
            bluebellSummary = entity.getBluebellSummary();
            if (loc.getBluebellExposure() != null) {
                bluebellExposure = loc.getBluebellExposure().name();
            }
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
                lunarPhase,
                entity.getSurgeTotalMetres(),
                entity.getSurgePressureMetres(),
                entity.getSurgeWindMetres(),
                entity.getSurgeRiskLevel(),
                entity.getSurgeAdjustedRangeMetres(),
                entity.getSurgeAstronomicalRangeMetres(),
                entity.getInversionScore(),
                entity.getInversionPotential(),
                goldenHourStart,
                goldenHourEnd,
                blueHourStart,
                blueHourEnd,
                bluebellScore,
                bluebellSummary,
                bluebellExposure,
                entity.getTriageReason(),
                entity.getTriageMessage(),
                entity.getHeadline());
    }

    /**
     * Builds a sparse {@link ForecastEvaluationDto} from a merged evaluation view that came
     * from {@code cached_evaluation} only (i.e. no matching {@code forecast_evaluation} row
     * exists yet). Atmospheric, tide, surge and inversion fields are left {@code null} because
     * {@code cached_evaluation} stores only the per-region briefing scores, not the rich
     * per-location weather inputs.
     *
     * <p>This exists so the Map tab's date strip surfaces dates that the asynchronous batch
     * pipeline has scored but not yet persisted as full forecast rows. The Plan tab already
     * reads {@code cached_evaluation} via {@code BriefingService.enrichWithCachedScores}; this
     * keeps the two tabs in sync as the canonical {@code EvaluationViewService} Javadoc intends.
     *
     * <p>Solar event time, azimuth, golden/blue hour windows, lunar tide type and lunar phase
     * are computed from the location's lat/lon and the target date so the popup still renders
     * sensible scaffolding.
     *
     * <p>The {@code isLiteUser} flag is accepted for signature symmetry with
     * {@link #toDto(ForecastEvaluationEntity, boolean)} but is currently unused — cached
     * evaluations carry only one set of scores (no basic vs enhanced split).
     *
     * @param view       the merged evaluation view (source must be CACHED_EVALUATION)
     * @param location   the location entity, used for lat/lon and computed solar times
     * @param isLiteUser true if the caller is a LITE_USER (reserved for future use)
     * @return a sparse DTO suitable for the Map tab
     */
    @SuppressWarnings("PMD.UnusedFormalParameter")
    public ForecastEvaluationDto toSparseDto(LocationEvaluationView view, LocationEntity location,
            boolean isLiteUser) {
        LocalDate date = view.date();
        TargetType type = view.targetType();
        double lat = location.getLat();
        double lon = location.getLon();

        // Solar event time + azimuth — computed from lat/lon + date
        LocalDateTime solarEventTime = null;
        Integer azimuthDeg = null;
        if (date != null && type != null && type != TargetType.HOURLY) {
            try {
                boolean isSunrise = type == TargetType.SUNRISE;
                solarEventTime = isSunrise
                        ? solarService.sunriseUtc(lat, lon, date)
                        : solarService.sunsetUtc(lat, lon, date);
                azimuthDeg = isSunrise
                        ? solarService.sunriseAzimuthDeg(lat, lon, date)
                        : solarService.sunsetAzimuthDeg(lat, lon, date);
            } catch (Exception ignored) {
                // Graceful — leave null if calculation fails (e.g. polar edge case)
            }
        }

        // Golden/blue hour window
        LocalDateTime goldenHourStart = null;
        LocalDateTime goldenHourEnd = null;
        LocalDateTime blueHourStart = null;
        LocalDateTime blueHourEnd = null;
        if (date != null && type != null && type != TargetType.HOURLY) {
            try {
                boolean isSunrise = type == TargetType.SUNRISE;
                SolarService.SolarWindow sw = solarService.goldenBlueWindow(lat, lon, date, isSunrise);
                goldenHourStart = sw.goldenHourStart();
                goldenHourEnd = sw.goldenHourEnd();
                blueHourStart = sw.blueHourStart();
                blueHourEnd = sw.blueHourEnd();
            } catch (Exception ignored) {
                // Graceful — leave null if calculation fails
            }
        }

        LunarTideType lunarTideType = date != null ? lunarPhaseService.classifyTide(date) : null;
        String lunarPhase = date != null ? lunarPhaseService.getMoonPhase(date) : null;

        Integer daysAhead = date != null
                ? (int) ChronoUnit.DAYS.between(LocalDate.now(ZoneOffset.UTC), date)
                : null;

        LocalDateTime forecastRunAt = view.evaluatedAt() != null
                ? LocalDateTime.ofInstant(view.evaluatedAt(), ZoneOffset.UTC)
                : LocalDateTime.now(ZoneOffset.UTC);

        // Bluebell exposure — only surfaced during season for bluebell sites
        String bluebellExposure = null;
        if (location.getLocationType() != null
                && location.getLocationType().contains(LocationType.BLUEBELL)
                && date != null
                && SeasonalWindow.BLUEBELL.isActive(date)
                && location.getBluebellExposure() != null) {
            bluebellExposure = location.getBluebellExposure().name();
        }

        return new ForecastEvaluationDto(
                null,
                location.getName(),
                BigDecimal.valueOf(lat),
                BigDecimal.valueOf(lon),
                date,
                type,
                forecastRunAt,
                daysAhead,
                view.rating(),
                view.fierySkyPotential(),
                view.goldenHourPotential(),
                view.summary(),
                solarEventTime,
                azimuthDeg,
                null,
                null, null, null, null, null, null, null,
                null, null, null, null,
                null, null, null,
                null, null, null, null,
                null, null, null, null, null, null,
                null, null, null,
                null, null, null,
                null, null, null,
                null, null, null,
                lunarTideType, lunarPhase,
                null, null, null, null, null, null,
                null, null,
                goldenHourStart, goldenHourEnd, blueHourStart, blueHourEnd,
                null, null, bluebellExposure,
                view.triageReason(), view.triageMessage(),
                null);
    }
}
