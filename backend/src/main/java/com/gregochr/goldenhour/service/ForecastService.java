package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.AppBuild;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.BatchState;
import com.gregochr.goldenhour.entity.CloudApproachDetails;
import com.gregochr.goldenhour.entity.DirectionalCloudDetails;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.InversionDetails;
import com.gregochr.goldenhour.entity.StormSurgeDetails;
import com.gregochr.goldenhour.entity.TideDetails;
import com.gregochr.goldenhour.entity.TriageDetails;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.exception.WeatherDataFetchException;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.CloudPointCache;
import com.gregochr.goldenhour.model.ForecastPreEvalResult;
import com.gregochr.goldenhour.model.ForecastRequest;
import com.gregochr.goldenhour.model.LocationTaskEvent;
import com.gregochr.goldenhour.model.LocationTaskState;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.model.TriageReason;
import com.gregochr.goldenhour.model.TriageResult;
import com.gregochr.goldenhour.model.WeatherExtractionResult;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Fetches and triages a single location/date/target for the batch pipeline, and owns the
 * {@code forecast_evaluation} row lifecycle that pipeline uses.
 *
 * <p>The batch collectors ({@code ForecastTaskCollector}, {@code ForceSubmitBatchService},
 * {@code BatchRetryService}) call {@link #fetchWeatherAndTriage} to assemble the atmospheric data and
 * apply the weather triage heuristic, {@link #persistPendingEvaluation} to write the {@code PENDING}
 * row the batch result later scores in place, and {@link #markAbandoned} to close a superseded row.
 * The synchronous per-slot engine that used to evaluate and notify from here was retired in
 * 2026-10 (see {@code docs/engineering/sync-engine-retirement-plan.md}).
 */
@Service
public class ForecastService {

    private static final Logger LOG = LoggerFactory.getLogger(ForecastService.class);

    /** Longest task error message published to the progress panel (the full text stays in the log). */
    static final int PANEL_MESSAGE_LIMIT = 200;

    /** Marks a published message that was cut to {@link #PANEL_MESSAGE_LIMIT}. */
    private static final String ELLIPSIS = "...";

    private final SolarService solarService;
    private final OpenMeteoService openMeteoService;
    private final ForecastDataAugmentor augmentor;
    private final ForecastEvaluationRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final WeatherTriageEvaluator weatherTriageEvaluator;
    private final com.gregochr.goldenhour.service.evaluation.SlotAtmosphereWriter
            slotAtmosphereWriter;
    private final Clock clock;
    private final AppBuild appBuild;

    /**
     * Constructs a {@code ForecastService} with all required dependencies.
     *
     * @param solarService            calculates solar event times
     * @param openMeteoService        retrieves Open-Meteo forecast data
     * @param augmentor               enriches atmospheric data with directional cloud and tide information
     * @param repository              persists forecast evaluation results
     * @param eventPublisher          publishes location task state transition events
     * @param weatherTriageEvaluator  heuristic triage evaluator for skipping unsuitable conditions
     * @param slotAtmosphereWriter    records atmospheric readings for every candidate whose
     *                                weather is fetched — called from inside
     *                                {@link #fetchWeatherAndTriage}, the one seam every caller
     *                                of this service shares
     * @param clock                   supplies "today" for the {@code daysAhead} horizon, resolved in
     *                                {@code Europe/London} by {@link ForecastHorizon}
     * @param appBuild                the running build, stamped on every row that gets a rating or a
     *                                triage reason (V182)
     */
    public ForecastService(SolarService solarService, OpenMeteoService openMeteoService,
            ForecastDataAugmentor augmentor,
            ForecastEvaluationRepository repository,
            ApplicationEventPublisher eventPublisher, WeatherTriageEvaluator weatherTriageEvaluator,
            com.gregochr.goldenhour.service.evaluation.SlotAtmosphereWriter
                    slotAtmosphereWriter,
            Clock clock, AppBuild appBuild) {
        this.solarService = solarService;
        this.openMeteoService = openMeteoService;
        this.augmentor = augmentor;
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.weatherTriageEvaluator = weatherTriageEvaluator;
        this.slotAtmosphereWriter = slotAtmosphereWriter;
        this.clock = clock;
        this.appBuild = appBuild;
    }

    /**
     * Fetches weather data and applies heuristic triage for a single location/date/targetType.
     *
     * <p>If triage determines conditions are unsuitable, a canned entity (rating=1) is persisted
     * and the result is marked as triaged. Otherwise, the atmospheric data is returned ready for
     * Claude evaluation. Every call records the candidate's atmospheric readings to
     * {@code slot_atmosphere} before the triage check runs — see the 8-argument
     * overload's javadoc for why this is the one shared seam.
     *
     * @param location              the location entity
     * @param date                  the forecast date
     * @param targetType            SUNRISE or SUNSET
     * @param tideTypes             tide preferences
     * @param model                 evaluation model
     * @param jobRun                parent job run for metrics
     * @return the pre-evaluation result
     */
    public ForecastPreEvalResult fetchWeatherAndTriage(LocationEntity location,
            LocalDate date, TargetType targetType, Set<TideType> tideTypes,
            EvaluationModel model, JobRunEntity jobRun) {
        return fetchWeatherAndTriage(location, date, targetType, tideTypes, model,
                jobRun, null, null);
    }

    /**
     * Fetches weather data and applies triage heuristics, optionally using pre-fetched data.
     *
     * <p>⚠️ <b>The one seam every caller of this method shares — the record-conditions-for-
     * every-place write.</b> Immediately after the atmospheric data is fully assembled and before
     * the triage check below, this method calls {@code SlotAtmosphereWriter.write} for the
     * candidate — whatever the triage verdict or any later Gate 4 decision turns out to be. This
     * method already persists a {@code forecast_evaluation} row as a side effect on the triage
     * branches (a fact CLAUDE.md records), so adding the readings write at the same point costs
     * no new call site and reaches every present and future caller in one place: the batch
     * collector's {@code collectScheduledBatches} and {@code collectRegionFilteredBatches},
     * {@code ForceSubmitBatchService}'s JFDI and admin force-submit, {@code BatchRetryService}'s
     * failed-request reconstruction. Isolated in its own try/catch so a carrier write failure
     * never aborts the fetch/triage this method exists to perform.
     *
     * @param location              the location entity
     * @param date                  the target date
     * @param targetType            SUNRISE or SUNSET
     * @param tideTypes             tide preferences for the location
     * @param model                 evaluation model to use
     * @param jobRun                parent job run for metrics
     * @param prefetchedWeather     pre-fetched weather data keyed by coord key, or null to fetch individually
     * @param cloudCache            pre-fetched cloud point cache, or null to fetch individually
     * @return the pre-evaluation result
     */
    public ForecastPreEvalResult fetchWeatherAndTriage(LocationEntity location,
            LocalDate date, TargetType targetType, Set<TideType> tideTypes,
            EvaluationModel model, JobRunEntity jobRun,
            Map<String, WeatherExtractionResult> prefetchedWeather,
            CloudPointCache cloudCache) {
        String locationName = location.getName();
        double lat = location.getLat();
        double lon = location.getLon();
        Long locationId = location.getId();

        int daysAhead = ForecastHorizon.daysAhead(date, clock);
        String taskKey = locationName + "|" + date + "|" + targetType;
        Long runId = jobRun != null ? jobRun.getId() : null;

        LocalDateTime eventTime = targetType == TargetType.SUNRISE
                ? solarService.sunriseUtc(lat, lon, date)
                : solarService.sunsetUtc(lat, lon, date);

        ForecastRequest request = new ForecastRequest(lat, lon, locationName, date, targetType);

        // Fetch weather data (from cache if available, otherwise individual API call)
        publishEvent(runId, taskKey, locationName, date.toString(), targetType.name(),
                LocationTaskState.FETCHING_WEATHER);
        WeatherExtractionResult extraction;
        try {
            if (prefetchedWeather != null) {
                extraction = openMeteoService.getAtmosphericDataFromCache(
                        request, eventTime, prefetchedWeather);
                if (extraction == null) {
                    throw new RuntimeException(
                            "Weather data could not be fetched.");
                }
            } else {
                extraction = openMeteoService.getAtmosphericDataWithResponse(
                        request, eventTime, jobRun);
            }
        } catch (Exception e) {
            String msg = "Weather data fetch failed for " + locationName + " " + targetType
                    + ": " + e.getMessage();
            LOG.error(msg);
            publishEvent(runId, taskKey, locationName, date.toString(), targetType.name(),
                    LocationTaskState.FAILED, truncateForPanel(msg), "FETCHING_WEATHER");
            throw new WeatherDataFetchException(msg, locationName, targetType.name(), e);
        }

        AtmosphericData baseData = extraction.atmosphericData();
        OpenMeteoForecastResponse forecastResponse = extraction.forecastResponse();

        if (baseData == null) {
            String msg = "Weather service returned null for " + locationName + " " + targetType;
            LOG.error(msg);
            publishEvent(runId, taskKey, locationName, date.toString(), targetType.name(),
                    LocationTaskState.FAILED, msg, "FETCHING_WEATHER");
            throw new WeatherDataFetchException(msg, locationName, targetType.name(), null);
        }

        int azimuth = targetType == TargetType.SUNRISE
                ? solarService.sunriseAzimuthDeg(lat, lon, date)
                : solarService.sunsetAzimuthDeg(lat, lon, date);

        // Augment with directional cloud and cloud approach
        publishEvent(runId, taskKey, locationName, date.toString(), targetType.name(),
                LocationTaskState.FETCHING_CLOUD);
        AtmosphericData withDirectional = augmentor.augmentWithDirectionalCloud(
                baseData, lat, lon, azimuth, eventTime, jobRun, cloudCache);
        AtmosphericData withApproach = augmentor.augmentWithCloudApproach(
                withDirectional, lat, lon, azimuth, eventTime,
                LocalDateTime.now(ZoneOffset.UTC), jobRun, cloudCache);

        // Augment with tide data (skip state transition for non-coastal locations)
        if (tideTypes != null && !tideTypes.isEmpty()) {
            publishEvent(runId, taskKey, locationName, date.toString(), targetType.name(),
                    LocationTaskState.FETCHING_TIDES);
        }
        AtmosphericData withTide = augmentor.augmentWithTideData(
                withApproach, locationId, eventTime, tideTypes, lat, lon, targetType);
        AtmosphericData withOrientation = augmentor.augmentWithLocationOrientation(
                withTide, location.getSolarEventType());
        AtmosphericData withSurge = augmentor.augmentWithStormSurge(
                withOrientation, location.toCoastalParameters(), locationId, locationName,
                forecastResponse);
        AtmosphericData withInversion = augmentor.augmentWithInversionScore(
                withSurge, location.getElevationMetres(), location.isOverlooksWater());
        // Valley mist (V185, valley-mist-plan M1a): scored for a mist_prone site only, here and
        // before the readings write and the triage check below, exactly like the inversion score
        // above — so a stood-down candidate still records it (the two-question rule).
        AtmosphericData withMist = augmentor.augmentWithValleyMist(
                withInversion, location.isMistProne());
        AtmosphericData forecastData = augmentor.augmentWithBluebellConditions(
                withMist, location.getLocationType(), location.getBluebellExposure(), date);

        // Record conditions for every place whose weather was fetched — the ONE seam every
        // caller of fetchWeatherAndTriage shares (the batch collector's scheduled AND
        // region-filtered paths, ForceSubmitBatchService's JFDI and force-submit,
        // BatchRetryService's reconstruction), so every present and future caller is
        // covered by one write rather than one write per call site. Runs here, before either
        // triage check below, so a triaged-out or later-Gate-4-skipped candidate still gets its
        // reading — the two-question rule (see SlotAtmosphereWriter's own javadoc) requires
        // it regardless of what the caller goes on to decide about the verdict. Isolated so a
        // carrier write failure never aborts the fetch/triage this method exists to perform.
        try {
            slotAtmosphereWriter.write(location, date, targetType, forecastData);
        } catch (Exception e) {
            LOG.error("slot_atmosphere write FAILED for {} {} {}; fetch/triage proceeds: {}",
                    locationName, date, targetType, e.getMessage(), e);
        }

        // Apply weather triage heuristic
        Optional<TriageResult> triageResult = weatherTriageEvaluator.evaluate(forecastData);
        if (triageResult.isPresent()) {
            TriageResult tr = triageResult.get();
            String reason = tr.reason();
            SunsetEvaluation emptyEval = new SunsetEvaluation(null, null, null, null);
            ForecastEvaluationEntity entity = buildEntity(
                    location, lat, lon, date, targetType, daysAhead, eventTime, azimuth,
                    forecastData, emptyEval, model);
            standDown(entity, tr.triageReason(), reason);
            repository.save(entity);
            publishEvent(runId, taskKey, locationName, date.toString(), targetType.name(),
                    LocationTaskState.TRIAGED);
            LOG.info("Forecast triaged: {} {} {} (T+{}) — {}", locationName, targetType,
                    date, daysAhead, reason);
            return new ForecastPreEvalResult(true, reason, tr.triageReason(), forecastData,
                    location, date, targetType, eventTime, azimuth, daysAhead, model, tideTypes,
                    taskKey, forecastResponse);
        }

        return new ForecastPreEvalResult(false, null, forecastData, location, date,
                targetType, eventTime, azimuth, daysAhead, model, tideTypes, taskKey,
                forecastResponse);
    }

    /**
     * Persists a {@code PENDING} placeholder row for a slot about to be submitted to the batch
     * API (prompted-row persistence plan, R4).
     *
     * <p>The row is the durable carrier of the 66-field forecast snapshot used to build the
     * prompt — unavailable at result time and lost on restart otherwise. {@code
     * ForecastResultHandler} scores it in place by primary key when the batch result lands (R5),
     * or the abandonment sweep closes it out {@code ABANDONED} if the batch never resolves (R7).
     * Invisible to every serve path while {@code PENDING} (R1) — see the two exclusions on
     * {@link com.gregochr.goldenhour.repository.ForecastEvaluationRepository}.
     *
     * <p>Scope is the {@code fc-} sky lane only (R8) — callers are the sky-lane batch task
     * construction seams (scheduled, region-filtered admin, JFDI, force-submit, retry). Bluebell
     * and woodland tasks do not call this.
     *
     * @param preEval the pre-evaluation result about to be submitted for Claude evaluation
     * @return the persisted row's primary key, embedded in the batch custom_id (R3)
     */
    public Long persistPendingEvaluation(ForecastPreEvalResult preEval) {
        SunsetEvaluation emptyEval = new SunsetEvaluation(null, null, null, null);
        ForecastEvaluationEntity entity = buildEntity(
                preEval.location(), preEval.location().getLat(), preEval.location().getLon(),
                preEval.date(), preEval.targetType(), preEval.daysAhead(), preEval.eventTime(),
                preEval.azimuth(), preEval.atmosphericData(), emptyEval, preEval.model());
        entity.setBatchState(BatchState.PENDING);
        return repository.save(entity).getId();
    }

    /**
     * Stamps a {@code PENDING} row {@code ABANDONED} (prompted-row persistence plan, R6).
     *
     * <p>Used when a retry re-fetches weather and inserts a fresh pending row for the same slot:
     * the precursor's snapshot is stale (the retry's prompt was built from different weather), so
     * a rating must never be stapled onto it. A no-op if the row is no longer {@code PENDING}
     * (already scored or already abandoned) or no longer exists.
     *
     * @param rowId the precursor row's primary key
     */
    public void markAbandoned(Long rowId) {
        repository.findById(rowId).ifPresent(row -> {
            if (row.getBatchState() == BatchState.PENDING) {
                row.setBatchState(BatchState.ABANDONED);
                repository.save(row);
            }
        });
    }

    /**
     * Caps a task error message at {@link #PANEL_MESSAGE_LIMIT} characters before it is published,
     * because it can carry a raw exception message (an HTTP client's can run to hundreds of
     * characters). The full message stays in the server log.
     *
     * @param message the full message
     * @return the message, shortened with a trailing ellipsis if it was over the limit
     */
    static String truncateForPanel(String message) {
        if (message == null || message.length() <= PANEL_MESSAGE_LIMIT) {
            return message;
        }
        return message.substring(0, PANEL_MESSAGE_LIMIT - ELLIPSIS.length()) + ELLIPSIS;
    }

    /**
     * Publishes a per-location progress event for a task that has not failed.
     *
     * @param runId        the parent job run ID; {@code null} suppresses the event
     * @param taskKey      unique key identifying the per-location task
     * @param locationName the location the task is forecasting
     * @param targetDate   the forecast target date, ISO-formatted
     * @param targetType   name of the {@link TargetType} being forecast
     * @param state        the state the task has just entered
     */
    private void publishEvent(Long runId, String taskKey, String locationName,
            String targetDate, String targetType, LocationTaskState state) {
        publishEvent(runId, taskKey, locationName, targetDate, targetType, state, null, null);
    }

    /**
     * Publishes a per-location progress event onto the SSE stream for a run.
     *
     * <p>Delivery is best-effort: a browser that has closed its {@code EventSource} must not
     * fail the forecast run, so a publish failure is logged at debug level and swallowed.
     *
     * @param runId        the parent job run ID; {@code null} suppresses the event
     * @param taskKey      unique key identifying the per-location task
     * @param locationName the location the task is forecasting
     * @param targetDate   the forecast target date, ISO-formatted
     * @param targetType   name of the {@link TargetType} being forecast
     * @param state        the state the task has just entered
     * @param errorMessage failure detail, or {@code null} if the task did not fail
     * @param failedStep   the pipeline step that failed, or {@code null} if the task did not fail
     */
    private void publishEvent(Long runId, String taskKey, String locationName,
            String targetDate, String targetType, LocationTaskState state,
            String errorMessage, String failedStep) {
        if (runId == null) {
            return;
        }
        try {
            eventPublisher.publishEvent(new LocationTaskEvent(
                    this, runId, taskKey, locationName, targetDate, targetType,
                    state, errorMessage, failedStep));
        } catch (Exception e) {
            LOG.debug("SSE event publish failed (emitter likely closed): {}", e.getMessage());
        }
    }

    /**
     * Marks an unsaved row as a stand-down: attaches the triage reason and stamps {@code scoredAt}
     * (V178) and the running build (V182), because a triage reason is something to say — the reader
     * saw it as the slot's decision — and the row is saved with it, never without. Every stand-down
     * goes through here so none can forget the stamp.
     */
    private void standDown(ForecastEvaluationEntity entity, TriageReason reason, String message) {
        entity.setTriage(new TriageDetails(reason, message));
        entity.setScoredAt(clock.instant());
        entity.setAppBuild(appBuild.value());
    }

    /**
     * Builds an unsaved {@link ForecastEvaluationEntity} from the evaluated forecast data.
     *
     * <p>Sole site at which the durable per-evaluation {@code confidence} is derived, so every
     * persisted evaluation — scored, canned or wildlife — carries the horizon-derived value.
     *
     * @param location   the location entity
     * @param lat        latitude in decimal degrees
     * @param lon        longitude in decimal degrees
     * @param date       the calendar date of the forecast
     * @param type       sunrise, sunset, or {@code HOURLY} for a wildlife comfort slot
     * @param daysAhead  number of days from today to {@code date}
     * @param eventTime  UTC time of the solar event, or of the hour for a wildlife slot
     * @param azimuth    solar azimuth in degrees, or {@code null} when not applicable
     * @param data       atmospheric data (with tide fields if coastal)
     * @param evaluation Claude's scores and summary; all-null fields for skipped and wildlife rows
     * @param model      which Claude model produced the evaluation
     * @return the unsaved entity
     */
    private ForecastEvaluationEntity buildEntity(LocationEntity location, double lat, double lon,
            LocalDate date, TargetType type, int daysAhead, LocalDateTime eventTime, Integer azimuth,
            AtmosphericData data, SunsetEvaluation evaluation, EvaluationModel model) {
        Instant scoredAt = evaluation.rating() != null ? clock.instant() : null;
        return ForecastEvaluationEntity.builder()
                .scoredAt(scoredAt)
                .appBuild(scoredAt != null ? appBuild.value() : null)
                .locationLat(BigDecimal.valueOf(lat))
                .locationLon(BigDecimal.valueOf(lon))
                .location(location)
                .targetDate(date)
                .targetType(type)
                .forecastRunAt(LocalDateTime.now(ZoneOffset.UTC))
                .daysAhead(daysAhead)
                .confidence(ConfidenceDeriver.fromHorizon(daysAhead).name())
                .lowCloud(data.cloud().lowCloudPercent())
                .midCloud(data.cloud().midCloudPercent())
                .highCloud(data.cloud().highCloudPercent())
                .visibility(data.weather().visibilityMetres())
                .windSpeed(data.weather().windSpeedMs())
                .windDirection(data.weather().windDirectionDegrees())
                .precipitation(data.weather().precipitationMm())
                .humidity(data.weather().humidityPercent())
                .weatherCode(data.weather().weatherCode())
                .boundaryLayerHeight(data.aerosol().boundaryLayerHeightMetres())
                .shortwaveRadiation(data.weather().shortwaveRadiationWm2())
                .pm25(data.aerosol().pm25())
                .dust(data.aerosol().dustUgm3())
                .aerosolOpticalDepth(data.aerosol().aerosolOpticalDepth())
                .temperatureCelsius(data.comfort().temperatureCelsius())
                .apparentTemperatureCelsius(data.comfort().apparentTemperatureCelsius())
                .precipitationProbabilityPercent(data.comfort().precipitationProbability())
                .dewPointCelsius(data.weather().dewPointCelsius())
                .tide(TideDetails.from(data.tide()))
                .directionalCloud(DirectionalCloudDetails.from(data.directionalCloud()))
                .evaluationModel(model)
                .rating(evaluation.rating())
                .fierySkyPotential(evaluation.fierySkyPotential())
                .goldenHourPotential(evaluation.goldenHourPotential())
                .summary(evaluation.summary())
                .headline(evaluation.headline())
                .basicFierySkyPotential(evaluation.basicFierySkyPotential())
                .basicGoldenHourPotential(evaluation.basicGoldenHourPotential())
                .basicSummary(evaluation.basicSummary())
                .solarEventTime(eventTime)
                .azimuthDeg(azimuth)
                .cloudApproach(CloudApproachDetails.from(data.cloudApproach()))
                .surge(StormSurgeDetails.from(data.surge(),
                        data.adjustedRangeMetres(), data.astronomicalRangeMetres()))
                .inversion(InversionDetails.from(data.inversionScore() != null,
                        evaluation.inversionScore(), evaluation.inversionPotential()))
                // Claude's valley mist reading, kept only for a site whose prompt carried the
                // VALLEY MIST CONDITIONS block (data.mistPotential() non-null): the echo of a
                // question that was asked (valley-mist-plan M2).
                .mistPotential(data.mistPotential() != null ? evaluation.mistPotential() : null)
                .build();
    }
}
