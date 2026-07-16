package com.gregochr.goldenhour.model;

import com.gregochr.goldenhour.entity.CloudApproachDetails;
import com.gregochr.goldenhour.entity.DirectionalCloudDetails;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.ForecastScoreEntity;
import com.gregochr.goldenhour.entity.InversionDetails;
import com.gregochr.goldenhour.entity.StormSurgeDetails;
import com.gregochr.goldenhour.entity.TideDetails;
import com.gregochr.goldenhour.entity.TriageDetails;
import com.gregochr.goldenhour.entity.ForecastType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.MarineWaveEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.repository.ForecastScoreRepository;
import com.gregochr.goldenhour.repository.MarineWaveRepository;
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
    private final SeasonalWindow bluebellSeason;
    private final ForecastScoreRepository forecastScoreRepository;
    private final MarineWaveRepository marineWaveRepository;

    /**
     * Constructs a {@code ForecastDtoMapper}.
     *
     * @param lunarPhaseService       service for lunar phase and tide classification
     * @param solarService            service for solar window calculations
     * @param bluebellSeason          the configured bluebell season window
     * @param forecastScoreRepository source of the Claude BLUEBELL rating (1–5) for the DTO
     * @param marineWaveRepository    source of coastal sea-state (Hs) for the DTO
     */
    public ForecastDtoMapper(LunarPhaseService lunarPhaseService, SolarService solarService,
            SeasonalWindow bluebellSeason, ForecastScoreRepository forecastScoreRepository,
            MarineWaveRepository marineWaveRepository) {
        this.lunarPhaseService = lunarPhaseService;
        this.solarService = solarService;
        this.bluebellSeason = bluebellSeason;
        this.forecastScoreRepository = forecastScoreRepository;
        this.marineWaveRepository = marineWaveRepository;
    }

    /**
     * The coastal sea-state (significant wave height + WMO band) for a forecast row, or empty.
     *
     * @param waveHeightMetres significant wave height Hs in metres, or null
     * @param seaState         the WMO band label, or null
     */
    private record WaveInfo(Double waveHeightMetres, String seaState) {
        static final WaveInfo NONE = new WaveInfo(null, null);
    }

    /**
     * Resolves the coastal sea-state for a location/date/event from the shared {@code marine_wave}
     * carrier. Returns {@link WaveInfo#NONE} for inland locations (no tide preference), non-solar
     * events, or when no wave sample was persisted for that key. Mirrors the per-row bluebell lookup.
     *
     * @param location the forecast location (coastal check via tide preference)
     * @param date     the target date
     * @param type     the target event type
     * @return the sea-state info, or {@link WaveInfo#NONE}
     */
    private WaveInfo resolveWave(LocationEntity location, LocalDate date, TargetType type) {
        if (location == null || location.getId() == null || date == null
                || type == null || type == TargetType.HOURLY
                || location.getTideType() == null || location.getTideType().isEmpty()) {
            return WaveInfo.NONE;
        }
        return marineWaveRepository
                .findByLocation_IdAndEvaluationDateAndEventType(location.getId(), date, type)
                .map(MarineWaveEntity::getSignificantWaveHeightMetres)
                .filter(hs -> hs != null)
                .map(hs -> new WaveInfo(hs, SeaState.fromHs(hs).label()))
                .orElse(WaveInfo.NONE);
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

        // Bluebell fields — only populated during season for bluebell sites. The score is the
        // Claude BLUEBELL rating (1–5) read from forecast_score, not the legacy deterministic
        // 0–10 condition score (dropped in V112). The lookup is gated on in-season + BLUEBELL
        // type, so it runs only for the handful of bluebell-site rows in season.
        Integer bluebellScore = null;
        String bluebellSummary = null;
        String bluebellExposure = null;
        LocationEntity loc = entity.getLocation();
        if (loc != null && loc.getLocationType() != null
                && loc.getLocationType().contains(LocationType.BLUEBELL)
                && entity.getTargetDate() != null
                && bluebellSeason.isActive(entity.getTargetDate())) {
            if (loc.getId() != null && entity.getTargetType() != null) {
                ForecastScoreEntity bluebellRow = forecastScoreRepository.findComponent(
                        ForecastType.BLUEBELL, loc.getId(), entity.getTargetDate(),
                        entity.getTargetType()).orElse(null);
                if (bluebellRow != null) {
                    bluebellScore = bluebellRow.getScore();
                    bluebellSummary = bluebellRow.getSummary();
                }
            }
            if (loc.getBluebellExposure() != null) {
                bluebellExposure = loc.getBluebellExposure().name();
            }
        }

        WaveInfo wave = resolveWave(loc, entity.getTargetDate(), entity.getTargetType());

        TideDetails tide = TideDetails.orEmpty(entity.getTide());
        DirectionalCloudDetails dc = DirectionalCloudDetails.orEmpty(entity.getDirectionalCloud());
        CloudApproachDetails approach = CloudApproachDetails.orEmpty(entity.getCloudApproach());
        StormSurgeDetails surge = StormSurgeDetails.orEmpty(entity.getSurge());
        InversionDetails inversion = InversionDetails.orEmpty(entity.getInversion());
        TriageDetails triage = TriageDetails.orEmpty(entity.getTriage());

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
                tide.getState(),
                tide.getNextHighTime(),
                tide.getNextHighHeightMetres(),
                tide.getNextLowTime(),
                tide.getNextLowHeightMetres(),
                tide.getAligned(),
                dc.getSolarLow(),
                dc.getSolarMid(),
                dc.getSolarHigh(),
                dc.getAntisolarLow(),
                dc.getAntisolarMid(),
                dc.getAntisolarHigh(),
                approach.getSolarTrendEventLowCloud(),
                approach.getSolarTrendEarliestLowCloud(),
                approach.getSolarTrendBuilding(),
                approach.getUpwindCurrentLowCloud(),
                approach.getUpwindEventLowCloud(),
                approach.getUpwindDistanceKm(),
                lunarTideType,
                lunarPhase,
                surge.getTotalMetres(),
                surge.getPressureMetres(),
                surge.getWindMetres(),
                surge.getRiskLevel(),
                surge.getAdjustedRangeMetres(),
                surge.getAstronomicalRangeMetres(),
                inversion.getScore(),
                inversion.getPotential(),
                goldenHourStart,
                goldenHourEnd,
                blueHourStart,
                blueHourEnd,
                bluebellScore,
                bluebellSummary,
                bluebellExposure,
                triage.getReason(),
                triage.getMessage(),
                entity.getHeadline(),
                wave.waveHeightMetres(),
                wave.seaState());
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

        // The evaluation instant is the honest "forecast generated" time (when the batch/SSE job
        // scored this slot). Leave it null when unknown rather than stamping now() — the frontend
        // hides the footer for a null run time, which is preferable to reporting the request time.
        LocalDateTime forecastRunAt = view.evaluatedAt() != null
                ? LocalDateTime.ofInstant(view.evaluatedAt(), ZoneOffset.UTC)
                : null;

        // Bluebell exposure — only surfaced during season for bluebell sites
        String bluebellExposure = null;
        if (location.getLocationType() != null
                && location.getLocationType().contains(LocationType.BLUEBELL)
                && date != null
                && bluebellSeason.isActive(date)
                && location.getBluebellExposure() != null) {
            bluebellExposure = location.getBluebellExposure().name();
        }

        WaveInfo wave = resolveWave(location, date, type);

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
                null, wave.waveHeightMetres(), wave.seaState());
    }
}
