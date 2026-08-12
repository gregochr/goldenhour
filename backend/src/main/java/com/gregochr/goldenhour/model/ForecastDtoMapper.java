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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    /**
     * True when a location/date/event could carry a coastal sea-state: a non-HOURLY solar event at a
     * location with a tide preference. Shared by the per-row {@link #resolveWave} and the bulk map
     * lookup so both apply the same coastal gate — a location reclassified inland (tideType cleared)
     * whose stale {@code marine_wave} rows still exist must resolve to {@link WaveInfo#NONE} on both.
     */
    private boolean isCoastalWaveSlot(LocationEntity location, LocalDate date, TargetType type) {
        return location != null && location.getId() != null && date != null
                && type != null && type != TargetType.HOURLY
                && location.getTideType() != null && !location.getTideType().isEmpty();
    }

    private WaveInfo resolveWave(LocationEntity location, LocalDate date, TargetType type) {
        if (!isCoastalWaveSlot(location, date, type)) {
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
        // Preload the whole window's coastal sea-state in one query (avoids a marine_wave SELECT
        // per coastal row), and memoise the per-date lunar classification (identical for every row
        // on a given date) instead of recomputing it for all rows.
        Map<String, WaveInfo> waveByKey = preloadWaves(entities);
        Map<LocalDate, LunarInfo> lunarByDate = new HashMap<>();
        return entities.stream()
                .map(e -> toDto(e, isLiteUser, waveByKey, lunarByDate))
                .toList();
    }

    /**
     * Maps a list of entities to slim {@link ForecastListDto} projections for the map/list load path.
     * The heavy popup-only fields are omitted (fetched lazily per location via the detail endpoint);
     * this carries only the marker/grouping/comfort fields plus the role-selected scores.
     *
     * @param entities the forecast evaluation entities
     * @param isLiteUser true if the caller is a LITE_USER
     * @return the slim DTOs in the same order
     */
    public List<ForecastListDto> toListDtoList(List<ForecastEvaluationEntity> entities,
            boolean isLiteUser) {
        return entities.stream()
                .map(e -> toListDto(e, isLiteUser))
                .toList();
    }

    /**
     * Maps a single entity to a slim {@link ForecastListDto}. Scores are role-selected identically to
     * {@link #toDto(ForecastEvaluationEntity, boolean)}: LITE users get basic (observer-point) scores
     * from rich rows, others get enhanced (directional) scores. {@code summary}/{@code triageMessage}
     * are left {@code null} — a rich row has a persisted id, so the popup fetches them via the detail
     * endpoint rather than shipping the big text on every list row.
     *
     * @param entity the forecast evaluation entity
     * @param isLiteUser true if the caller is a LITE_USER
     * @return the slim DTO
     */
    private ForecastListDto toListDto(ForecastEvaluationEntity entity, boolean isLiteUser) {
        boolean useBasic = isLiteUser && entity.getBasicFierySkyPotential() != null;
        Integer fierySky = useBasic ? entity.getBasicFierySkyPotential() : entity.getFierySkyPotential();
        Integer goldenHour = useBasic
                ? entity.getBasicGoldenHourPotential() : entity.getGoldenHourPotential();
        TriageDetails triage = TriageDetails.orEmpty(entity.getTriage());
        return new ForecastListDto(
                entity.getId(),
                entity.getLocationName(),
                entity.getLocationLat(),
                entity.getLocationLon(),
                entity.getTargetDate(),
                entity.getTargetType(),
                entity.getForecastRunAt(),
                entity.getSolarEventTime(),
                entity.getAzimuthDeg(),
                entity.getRating(),
                fierySky,
                goldenHour,
                triage.getReason(),
                null,
                null,
                entity.getTemperatureCelsius(),
                entity.getApparentTemperatureCelsius(),
                entity.getWindSpeed(),
                entity.getWindDirection(),
                entity.getPrecipitation(),
                entity.getPrecipitationProbabilityPercent());
    }

    /**
     * Builds a slim {@link ForecastListDto} from a sparse cached-only view (no persisted id). Because
     * there is no by-id detail fetch path for a sparse row, its {@code summary} and
     * {@code triageMessage} are carried inline so the popup can still show the "why" text. Comfort
     * fields are {@code null} (a cached-only row has no atmospherics). Scores are a single set for
     * both roles.
     *
     * @param view the cached-only evaluation view
     * @param location the resolved location
     * @param isLiteUser true if the caller is a LITE_USER (sparse rows are role-agnostic)
     * @return the slim DTO with a null id
     */
    public ForecastListDto toSparseListDto(LocationEvaluationView view, LocationEntity location,
            boolean isLiteUser) {
        LocalDate date = view.date();
        TargetType type = view.targetType();
        double lat = location.getLat();
        double lon = location.getLon();

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

        LocalDateTime forecastRunAt = view.evaluatedAt() != null
                ? LocalDateTime.ofInstant(view.evaluatedAt(), ZoneOffset.UTC)
                : null;

        return new ForecastListDto(
                null,
                location.getName(),
                BigDecimal.valueOf(lat),
                BigDecimal.valueOf(lon),
                date,
                type,
                forecastRunAt,
                solarEventTime,
                azimuthDeg,
                view.rating(),
                view.fierySkyPotential(),
                view.goldenHourPotential(),
                view.triageReason(),
                view.summary(),
                view.triageMessage(),
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /**
     * Per-date lunar classification, memoised across the rows of a single mapping pass.
     *
     * @param tideType the astronomical tide classification for the date
     * @param phase    the human-readable moon phase for the date
     */
    private record LunarInfo(LunarTideType tideType, String phase) {
    }

    /**
     * Loads the coastal sea-state for every row's date range in one query and indexes it by
     * {@code locationId|date|eventType}, so {@link #toDto} can resolve waves with a map lookup
     * instead of a per-row {@code marine_wave} query. Reading the lazy {@code location} proxy's id
     * does not trigger a load (the FK is already known).
     *
     * @param entities the entities about to be mapped
     * @return a map from {@code locationId|date|eventType} to its {@link WaveInfo}
     */
    private Map<String, WaveInfo> preloadWaves(List<ForecastEvaluationEntity> entities) {
        LocalDate min = null;
        LocalDate max = null;
        for (ForecastEvaluationEntity e : entities) {
            LocalDate d = e.getTargetDate();
            if (d == null) {
                continue;
            }
            if (min == null || d.isBefore(min)) {
                min = d;
            }
            if (max == null || d.isAfter(max)) {
                max = d;
            }
        }
        if (min == null) {
            return Map.of();
        }
        Map<String, WaveInfo> byKey = new HashMap<>();
        for (MarineWaveEntity w : marineWaveRepository.findByEvaluationDateBetween(min, max)) {
            Double hs = w.getSignificantWaveHeightMetres();
            if (hs == null || w.getLocation() == null || w.getLocation().getId() == null) {
                continue;
            }
            String key = w.getLocation().getId() + "|" + w.getEvaluationDate() + "|" + w.getEventType();
            byKey.put(key, new WaveInfo(hs, SeaState.fromHs(hs).label()));
        }
        return byKey;
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
        // Single-row path: no preloaded wave map (falls back to a per-row query) and a fresh
        // one-entry lunar memo. The bulk path (toDtoList) shares preloaded/memoised maps.
        return toDto(entity, isLiteUser, null, new HashMap<>());
    }

    private ForecastEvaluationDto toDto(ForecastEvaluationEntity entity, boolean isLiteUser,
            Map<String, WaveInfo> waveByKey, Map<LocalDate, LunarInfo> lunarByDate) {
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

        // Lunar classification — deterministic from target date, so memoise it per date across rows.
        LunarTideType lunarTideType = null;
        String lunarPhase = null;
        if (entity.getTargetDate() != null) {
            LunarInfo li = lunarByDate.computeIfAbsent(entity.getTargetDate(),
                    d -> new LunarInfo(lunarPhaseService.classifyTide(d), lunarPhaseService.getMoonPhase(d)));
            lunarTideType = li.tideType();
            lunarPhase = li.phase();
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

        // Bulk path: for a coastal slot, look the wave up in the preloaded map (an absent key means
        // no wave was persisted — same as resolveWave). Non-coastal/HOURLY slots go through
        // resolveWave, which short-circuits to NONE without a query, so a stale marine_wave row on a
        // reclassified-inland location is never surfaced. Single-row path: direct query fallback.
        WaveInfo wave;
        if (waveByKey != null && isCoastalWaveSlot(loc, entity.getTargetDate(), entity.getTargetType())) {
            wave = waveByKey.getOrDefault(
                    loc.getId() + "|" + entity.getTargetDate() + "|" + entity.getTargetType(),
                    WaveInfo.NONE);
        } else {
            wave = resolveWave(loc, entity.getTargetDate(), entity.getTargetType());
        }

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
}
