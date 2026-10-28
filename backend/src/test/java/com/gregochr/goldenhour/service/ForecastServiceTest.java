package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.config.AppBuild;
import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.exception.WeatherDataFetchException;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.MistPotential;
import com.gregochr.goldenhour.model.CloudApproachData;
import com.gregochr.goldenhour.model.CloudPointCache;
import com.gregochr.goldenhour.model.DirectionalCloudData;
import com.gregochr.goldenhour.model.ForecastPreEvalResult;
import com.gregochr.goldenhour.model.ForecastRequest;
import com.gregochr.goldenhour.model.LocationTaskEvent;
import com.gregochr.goldenhour.model.LocationTaskState;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.SolarCloudTrend;
import com.gregochr.goldenhour.model.SolarCloudTrend.SolarCloudSlot;
import com.gregochr.goldenhour.model.StormSurgeBreakdown;
import com.gregochr.goldenhour.model.TideRiskLevel;
import com.gregochr.goldenhour.model.TideSnapshot;
import com.gregochr.goldenhour.model.TriageReason;
import com.gregochr.goldenhour.model.TriageResult;
import com.gregochr.goldenhour.model.TriageRule;
import com.gregochr.goldenhour.model.UpwindCloudSample;
import com.gregochr.goldenhour.model.WeatherExtractionResult;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ForecastService}.
 */
@ExtendWith(MockitoExtension.class)
class ForecastServiceTest {

    private static final double DURHAM_LAT = 54.7753;
    private static final double DURHAM_LON = -1.5849;
    private static final String DURHAM = "Durham UK";
    private static final LocationEntity DURHAM_LOCATION = LocationEntity.builder()
            .id(1L).name(DURHAM).lat(DURHAM_LAT).lon(DURHAM_LON).build();

    @Mock
    private SolarService solarService;
    @Mock
    private OpenMeteoService openMeteoService;
    @Mock
    private ForecastDataAugmentor augmentor;
    @Mock
    private ForecastEvaluationRepository repository;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private WeatherTriageEvaluator weatherTriageEvaluator;
    @Mock
    private com.gregochr.goldenhour.service.evaluation.SlotAtmosphereWriter
            slotAtmosphereWriter;

    /**
     * 2026-08-11 12:00 UTC — midday, where the UTC and Europe/London dates agree, so the
     * bulk of this class is unaffected by which calendar the horizon is derived on. The
     * hour where they disagree is exercised deliberately in {@link DaysAheadBasis}.
     *
     * <p>A real fixed clock rather than a mock: {@code daysAhead} is arithmetic on a
     * calendar, and stubbing the clock's own zone conversion would let the assertions
     * agree with a broken derivation.
     */
    @Spy
    private Clock clock = Clock.fixed(Instant.parse("2026-08-11T12:00:00Z"), java.time.ZoneOffset.UTC);

    /** The release every stamped row is expected to carry (V182). */
    private static final String BUILD = "v2.28.0";

    @Spy
    private AppBuild appBuild = new AppBuild(BUILD);

    @InjectMocks
    private ForecastService forecastService;

    @BeforeEach
    void setUp() {
        // Augmentor acts as pass-through by default — returns the input data unchanged.
        // Lenient because not every test exercises both augmentation paths.
        lenient().when(augmentor.augmentWithDirectionalCloud(any(), anyDouble(), anyDouble(),
                anyInt(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(augmentor.augmentWithDirectionalCloud(any(), anyDouble(), anyDouble(),
                anyInt(), any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(augmentor.augmentWithCloudApproach(any(), anyDouble(), anyDouble(),
                anyInt(), any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(augmentor.augmentWithCloudApproach(any(), anyDouble(), anyDouble(),
                anyInt(), any(), any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(augmentor.augmentWithTideData(
                any(), any(), any(), any(), anyDouble(), anyDouble(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(augmentor.augmentWithLocationOrientation(any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(augmentor.augmentWithStormSurge(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(augmentor.augmentWithInversionScore(any(), any(), anyBoolean()))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(augmentor.augmentWithBluebellConditions(any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(augmentor.augmentWithValleyMist(any(), anyBoolean()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // --- fetchWeatherAndTriage tests ---

    @Test
    @DisplayName("fetchWeatherAndTriage() returns non-triaged result when conditions are suitable")
    void fetchWeatherAndTriage_suitableConditions_returnsNonTriagedResult() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);
        AtmosphericData data = buildAtmosphericData(sunset, TargetType.SUNSET);

        OpenMeteoForecastResponse resp = new OpenMeteoForecastResponse();
        when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
        when(solarService.sunsetAzimuthDeg(DURHAM_LAT, DURHAM_LON, date)).thenReturn(310);
        when(openMeteoService.getAtmosphericDataWithResponse(any(ForecastRequest.class), any(), any()))
                .thenReturn(new WeatherExtractionResult(data, resp));
        when(weatherTriageEvaluator.evaluate(any())).thenReturn(Optional.empty());

        ForecastPreEvalResult result = forecastService.fetchWeatherAndTriage(
                DURHAM_LOCATION, date, TargetType.SUNSET, Set.of(),
                EvaluationModel.SONNET, null);

        assertThat(result.triaged()).isFalse();
        assertThat(result.triageReason()).isNull();
        assertThat(result.atmosphericData()).isNotNull();
        assertThat(result.location()).isEqualTo(DURHAM_LOCATION);
        assertThat(result.targetType()).isEqualTo(TargetType.SUNSET);
        assertThat(result.azimuth()).isEqualTo(310);
        assertThat(result.forecastResponse()).isSameAs(resp);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("fetchWeatherAndTriage() triages and persists canned entity when conditions unsuitable")
    void fetchWeatherAndTriage_unsuitableConditions_triagesAndPersists() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunrise = LocalDateTime.of(2026, 6, 21, 3, 30);
        AtmosphericData data = buildAtmosphericData(sunrise, TargetType.SUNRISE);
        ForecastEvaluationEntity savedEntity = ForecastEvaluationEntity.builder().id(10L).build();

        when(solarService.sunriseUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunrise);
        when(solarService.sunriseAzimuthDeg(DURHAM_LAT, DURHAM_LON, date)).thenReturn(65);
        when(openMeteoService.getAtmosphericDataWithResponse(any(ForecastRequest.class), any(), any()))
                .thenReturn(new WeatherExtractionResult(data, null));
        when(weatherTriageEvaluator.evaluate(any()))
                .thenReturn(Optional.of(new TriageResult("Low cloud 85%", TriageRule.HIGH_CLOUD)));
        when(repository.save(any())).thenReturn(savedEntity);

        ForecastPreEvalResult result = forecastService.fetchWeatherAndTriage(
                DURHAM_LOCATION, date, TargetType.SUNRISE, Set.of(),
                EvaluationModel.SONNET, null);

        assertThat(result.triaged()).isTrue();
        assertThat(result.triageReason()).isEqualTo("Low cloud 85%");
        ArgumentCaptor<ForecastEvaluationEntity> triageCaptor =
                ArgumentCaptor.forClass(ForecastEvaluationEntity.class);
        verify(repository).save(triageCaptor.capture());
        ForecastEvaluationEntity triaged = triageCaptor.getValue();
        assertThat(triaged.getLocationName()).isEqualTo(DURHAM);
        assertThat(triaged.getTargetType()).isEqualTo(TargetType.SUNRISE);
        assertThat(triaged.getSummary()).isNull();
        assertThat(triaged.getRating()).isNull();
        assertThat(triaged.getTriage().getReason()).isEqualTo(TriageReason.HIGH_CLOUD);
        assertThat(triaged.getTriage().getMessage()).contains("Low cloud 85%");
    }

    @Test
    @DisplayName("fetchWeatherAndTriage() throws WeatherDataFetchException when weather fetch fails")
    void fetchWeatherAndTriage_weatherFetchFails_throwsException() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);

        when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
        when(openMeteoService.getAtmosphericDataWithResponse(any(ForecastRequest.class), any(), any()))
                .thenThrow(new RuntimeException("API timeout"));

        assertThatThrownBy(() -> forecastService.fetchWeatherAndTriage(
                DURHAM_LOCATION, date, TargetType.SUNSET, Set.of(),
                EvaluationModel.SONNET, null))
                .isInstanceOf(WeatherDataFetchException.class)
                .hasMessageContaining("Weather data fetch failed");
        // Record conditions for every place (P1 fix, 2026-09-30): a slot whose weather fetch
        // itself failed never got atmospheric data at all, so there is nothing to record.
        verifyNoInteractions(slotAtmosphereWriter);
    }

    @Test
    @DisplayName("fetchWeatherAndTriage() throws WeatherDataFetchException when weather returns null")
    void fetchWeatherAndTriage_weatherReturnsNull_throwsException() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);

        when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
        when(openMeteoService.getAtmosphericDataWithResponse(any(ForecastRequest.class), any(), any()))
                .thenReturn(new WeatherExtractionResult(null, null));

        assertThatThrownBy(() -> forecastService.fetchWeatherAndTriage(
                DURHAM_LOCATION, date, TargetType.SUNSET, Set.of(),
                EvaluationModel.SONNET, null))
                .isInstanceOf(WeatherDataFetchException.class)
                .hasMessageContaining("Weather service returned null");
        verifyNoInteractions(slotAtmosphereWriter);
    }

    @Test
    @DisplayName("fetchWeatherAndTriage() with an empty prefetch (the weather batch failed) publishes FAILED "
            + "saying that, instead of the old no-pre-fetched-data text")
    void fetchWeatherAndTriage_emptyPrefetch_failsSayingWeatherCouldNotBeFetched() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);
        JobRunEntity jobRun = new JobRunEntity();
        jobRun.setId(42L);
        when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);

        assertThatThrownBy(() -> forecastService.fetchWeatherAndTriage(
                DURHAM_LOCATION, date, TargetType.SUNSET, Set.of(), EvaluationModel.SONNET, jobRun,
                Map.of(), new CloudPointCache(Map.of())))
                .isInstanceOf(WeatherDataFetchException.class);

        ArgumentCaptor<LocationTaskEvent> eventCaptor = ArgumentCaptor.forClass(LocationTaskEvent.class);
        verify(eventPublisher, times(2)).publishEvent(eventCaptor.capture());
        LocationTaskEvent failed = eventCaptor.getAllValues().get(1);
        assertThat(failed.getState()).isEqualTo(LocationTaskState.FAILED);
        assertThat(failed.getFailedStep()).isEqualTo("FETCHING_WEATHER");
        assertThat(failed.getErrorMessage()).isEqualTo("Weather data fetch failed for " + DURHAM
                + " SUNSET: Weather data could not be fetched.");
        verify(openMeteoService, never()).getAtmosphericDataWithResponse(any(ForecastRequest.class), any(), any());
    }

    @Test
    @DisplayName("fetchWeatherAndTriage() publishes a raw exception message capped at 200 characters, "
            + "while the thrown exception keeps the whole of it")
    void fetchWeatherAndTriage_longExceptionMessage_publishedTruncated() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);
        JobRunEntity jobRun = new JobRunEntity();
        jobRun.setId(42L);
        String raw = "x".repeat(500);
        when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
        when(openMeteoService.getAtmosphericDataWithResponse(any(ForecastRequest.class), any(), any()))
                .thenThrow(new RuntimeException(raw));

        assertThatThrownBy(() -> forecastService.fetchWeatherAndTriage(
                DURHAM_LOCATION, date, TargetType.SUNSET, Set.of(), EvaluationModel.SONNET, jobRun))
                .isInstanceOf(WeatherDataFetchException.class)
                .hasMessageContaining(raw);

        ArgumentCaptor<LocationTaskEvent> eventCaptor = ArgumentCaptor.forClass(LocationTaskEvent.class);
        verify(eventPublisher, times(2)).publishEvent(eventCaptor.capture());
        String published = eventCaptor.getAllValues().get(1).getErrorMessage();
        assertThat(published).hasSize(200).startsWith("Weather data fetch failed for " + DURHAM + " SUNSET: xxx")
                .endsWith("xxx...");
    }

    @Test
    @DisplayName("truncateForPanel() leaves 200 characters alone, shortens 201 to 200 ending '...', "
            + "and passes null through")
    void truncateForPanel_boundaries() {
        assertThat(ForecastService.truncateForPanel("a".repeat(199))).isEqualTo("a".repeat(199));
        assertThat(ForecastService.truncateForPanel("a".repeat(200))).isEqualTo("a".repeat(200));
        assertThat(ForecastService.truncateForPanel("a".repeat(201))).isEqualTo("a".repeat(197) + "...");
        assertThat(ForecastService.truncateForPanel(null)).isNull();
    }

    // ── Record conditions for every place — the seam (P1 fix, 2026-09-30) ──────────────────────
    // A Codex review of the first cut (commit 9c01491f) found the write placed at three call
    // sites (ForecastTaskCollector's scheduled path, ForceSubmitBatchService's two entry points)
    // while two other real callers of this method — ForecastTaskCollector's
    // collectRegionFilteredBatches and the synchronous engine's ForecastCommandExecutor
    // .runTriagePhase — reached fetchWeatherAndTriage and got no write at all. The fix moves the
    // write inside fetchWeatherAndTriage itself, immediately after the atmospheric data is
    // assembled and before either triage check, so every caller — present, and any future one —
    // is covered by the one seam. These tests would FAIL against commit b8fe0fa6, where this
    // method's body never called slotAtmosphereWriter.write on any branch at all (the write
    // lived only in evaluateAndPersist, downstream, and at three collector/JFDI call sites a
    // triaged candidate never reaches).

    @Test
    @DisplayName("fetchWeatherAndTriage() records the candidate's atmospheric readings when "
            + "conditions are suitable (non-triaged)")
    void fetchWeatherAndTriage_nonTriaged_recordsReadings() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);
        AtmosphericData data = buildAtmosphericData(sunset, TargetType.SUNSET);

        when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
        when(solarService.sunsetAzimuthDeg(DURHAM_LAT, DURHAM_LON, date)).thenReturn(310);
        when(openMeteoService.getAtmosphericDataWithResponse(any(ForecastRequest.class), any(), any()))
                .thenReturn(new WeatherExtractionResult(data, null));
        when(weatherTriageEvaluator.evaluate(any())).thenReturn(Optional.empty());

        forecastService.fetchWeatherAndTriage(
                DURHAM_LOCATION, date, TargetType.SUNSET, Set.of(),
                EvaluationModel.SONNET, null);

        verify(slotAtmosphereWriter, times(1))
                .write(DURHAM_LOCATION, date, TargetType.SUNSET, data);
    }

    @Test
    @DisplayName("fetchWeatherAndTriage() still records the candidate's atmospheric readings "
            + "when weather triage stands the slot down — the case a triaged-away candidate "
            + "never reaches evaluateAndPersist, so the write cannot live there")
    void fetchWeatherAndTriage_weatherTriaged_stillRecordsReadings() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunrise = LocalDateTime.of(2026, 6, 21, 3, 30);
        AtmosphericData data = buildAtmosphericData(sunrise, TargetType.SUNRISE);
        ForecastEvaluationEntity savedEntity = ForecastEvaluationEntity.builder().id(10L).build();

        when(solarService.sunriseUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunrise);
        when(solarService.sunriseAzimuthDeg(DURHAM_LAT, DURHAM_LON, date)).thenReturn(65);
        when(openMeteoService.getAtmosphericDataWithResponse(any(ForecastRequest.class), any(), any()))
                .thenReturn(new WeatherExtractionResult(data, null));
        when(weatherTriageEvaluator.evaluate(any()))
                .thenReturn(Optional.of(new TriageResult("Low cloud 85%", TriageRule.HIGH_CLOUD)));
        when(repository.save(any())).thenReturn(savedEntity);

        ForecastPreEvalResult result = forecastService.fetchWeatherAndTriage(
                DURHAM_LOCATION, date, TargetType.SUNRISE, Set.of(),
                EvaluationModel.SONNET, null);

        assertThat(result.triaged()).isTrue();
        verify(slotAtmosphereWriter, times(1))
                .write(DURHAM_LOCATION, date, TargetType.SUNRISE, data);
    }

    @Test
    @DisplayName("fetchWeatherAndTriage() scores valley mist for a mist_prone site BEFORE the readings "
            + "write, so a weather-triaged slot still records the score (V185)")
    void fetchWeatherAndTriage_mistProneSite_scoreReachesTheWriterEvenWhenTriaged() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunrise = LocalDateTime.of(2026, 6, 21, 3, 30);
        LocationEntity mistProne = LocationEntity.builder()
                .id(3L).name("Brathay").lat(DURHAM_LAT).lon(DURHAM_LON).mistProne(true).build();
        AtmosphericData data = buildAtmosphericData(sunrise, TargetType.SUNRISE);
        AtmosphericData scored = data.withValleyMist(6.5, MistPotential.LIKELY);
        ForecastEvaluationEntity savedEntity = ForecastEvaluationEntity.builder().id(12L).build();

        when(solarService.sunriseUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunrise);
        when(solarService.sunriseAzimuthDeg(DURHAM_LAT, DURHAM_LON, date)).thenReturn(65);
        when(openMeteoService.getAtmosphericDataWithResponse(any(ForecastRequest.class), any(), any()))
                .thenReturn(new WeatherExtractionResult(data, null));
        when(augmentor.augmentWithValleyMist(any(), eq(true))).thenReturn(scored);
        when(augmentor.augmentWithBluebellConditions(eq(scored), any(), any(), any())).thenReturn(scored);
        when(weatherTriageEvaluator.evaluate(any()))
                .thenReturn(Optional.of(new TriageResult("Low cloud 85%", TriageRule.HIGH_CLOUD)));
        when(repository.save(any())).thenReturn(savedEntity);

        ForecastPreEvalResult result = forecastService.fetchWeatherAndTriage(
                mistProne, date, TargetType.SUNRISE, Set.of(),
                EvaluationModel.SONNET, null);

        assertThat(result.triaged()).isTrue();
        verify(augmentor).augmentWithValleyMist(any(), eq(true));
        verify(slotAtmosphereWriter, times(1))
                .write(mistProne, date, TargetType.SUNRISE, scored);
    }

    @Test
    @DisplayName("fetchWeatherAndTriage() hands the gate false for a site that is not mist_prone")
    void fetchWeatherAndTriage_unflaggedSite_gateIsFalse() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);
        AtmosphericData data = buildAtmosphericData(sunset, TargetType.SUNSET);

        when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
        when(solarService.sunsetAzimuthDeg(DURHAM_LAT, DURHAM_LON, date)).thenReturn(310);
        when(openMeteoService.getAtmosphericDataWithResponse(any(ForecastRequest.class), any(), any()))
                .thenReturn(new WeatherExtractionResult(data, null));
        when(weatherTriageEvaluator.evaluate(any())).thenReturn(Optional.empty());

        forecastService.fetchWeatherAndTriage(
                DURHAM_LOCATION, date, TargetType.SUNSET, Set.of(),
                EvaluationModel.SONNET, null);

        verify(augmentor).augmentWithValleyMist(any(), eq(false));
    }

    @Test
    @DisplayName("fetchWeatherAndTriage() isolates a readings-write failure — the fetch/triage "
            + "still completes and the caller still gets a result")
    void fetchWeatherAndTriage_readingsWriteFails_fetchStillProceeds() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);
        AtmosphericData data = buildAtmosphericData(sunset, TargetType.SUNSET);

        when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
        when(solarService.sunsetAzimuthDeg(DURHAM_LAT, DURHAM_LON, date)).thenReturn(310);
        when(openMeteoService.getAtmosphericDataWithResponse(any(ForecastRequest.class), any(), any()))
                .thenReturn(new WeatherExtractionResult(data, null));
        when(weatherTriageEvaluator.evaluate(any())).thenReturn(Optional.empty());
        doThrow(new RuntimeException("DB unavailable"))
                .when(slotAtmosphereWriter).write(any(), any(), any(), any());

        ForecastPreEvalResult result = forecastService.fetchWeatherAndTriage(
                DURHAM_LOCATION, date, TargetType.SUNSET, Set.of(),
                EvaluationModel.SONNET, null);

        assertThat(result.triaged()).isFalse();
        assertThat(result.atmosphericData()).isNotNull();
    }

    @Test
    @DisplayName("fetchWeatherAndTriage() writes no reading when the "
            + "photocast.slot-atmosphere.write flag is off — the same flag every caller "
            + "shares through the one seam")
    void fetchWeatherAndTriage_readingsFlagOff_writesNothing() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);
        AtmosphericData data = buildAtmosphericData(sunset, TargetType.SUNSET);

        com.gregochr.goldenhour.repository.SlotAtmosphereRepository realRepo =
                org.mockito.Mockito.mock(
                        com.gregochr.goldenhour.repository.SlotAtmosphereRepository.class);
        com.gregochr.goldenhour.service.evaluation.SlotAtmosphereWriter flagOffWriter =
                new com.gregochr.goldenhour.service.evaluation.SlotAtmosphereWriter(
                        realRepo, clock, false);
        ForecastService serviceWithFlagOff = new ForecastService(
                solarService, openMeteoService, augmentor, repository, eventPublisher,
                weatherTriageEvaluator, flagOffWriter, clock, new AppBuild(BUILD));

        when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
        when(solarService.sunsetAzimuthDeg(DURHAM_LAT, DURHAM_LON, date)).thenReturn(310);
        when(openMeteoService.getAtmosphericDataWithResponse(any(ForecastRequest.class), any(), any()))
                .thenReturn(new WeatherExtractionResult(data, null));
        when(weatherTriageEvaluator.evaluate(any())).thenReturn(Optional.empty());

        serviceWithFlagOff.fetchWeatherAndTriage(
                DURHAM_LOCATION, date, TargetType.SUNSET, Set.of(),
                EvaluationModel.SONNET, null);

        verifyNoInteractions(realRepo);
    }

    @Test
    @DisplayName("fetchWeatherAndTriage() publishes events when jobRun is provided")
    void fetchWeatherAndTriage_withJobRun_publishesEvents() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);
        AtmosphericData data = buildAtmosphericData(sunset, TargetType.SUNSET);
        JobRunEntity jobRun = new JobRunEntity();
        jobRun.setId(42L);

        when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
        when(solarService.sunsetAzimuthDeg(DURHAM_LAT, DURHAM_LON, date)).thenReturn(310);
        when(openMeteoService.getAtmosphericDataWithResponse(any(ForecastRequest.class), any(), any()))
                .thenReturn(new WeatherExtractionResult(data, null));
        when(weatherTriageEvaluator.evaluate(any())).thenReturn(Optional.empty());

        forecastService.fetchWeatherAndTriage(
                DURHAM_LOCATION, date, TargetType.SUNSET, Set.of(),
                EvaluationModel.SONNET, jobRun);

        // Should publish FETCHING_WEATHER, FETCHING_CLOUD (no FETCHING_TIDES — empty tideTypes)
        ArgumentCaptor<LocationTaskEvent> eventCaptor =
                ArgumentCaptor.forClass(LocationTaskEvent.class);
        verify(eventPublisher, times(2)).publishEvent(eventCaptor.capture());
        List<LocationTaskEvent> events = eventCaptor.getAllValues();
        assertThat(events.get(0).getState()).isEqualTo(LocationTaskState.FETCHING_WEATHER);
        assertThat(events.get(0).getJobRunId()).isEqualTo(42L);
        assertThat(events.get(0).getLocationName()).isEqualTo(DURHAM);
        assertThat(events.get(1).getState()).isEqualTo(LocationTaskState.FETCHING_CLOUD);
    }

    // --- tide alignment optimisation strategy tests ---

    // --- evaluateAndPersist tests ---

    // --- forecast_evaluation.scored_at (V178): the availability stamp's write sites ---

    private static final Instant STAMP = Instant.parse("2026-08-11T12:00:00Z");

    @Test
    @DisplayName("V178: a triage stand-down row is stamped at insert — a triage reason is something to say")
    void fetchWeatherAndTriage_triageRowIsStampedAtInsert() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunrise = LocalDateTime.of(2026, 6, 21, 3, 30);
        when(solarService.sunriseUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunrise);
        when(solarService.sunriseAzimuthDeg(DURHAM_LAT, DURHAM_LON, date)).thenReturn(65);
        when(openMeteoService.getAtmosphericDataWithResponse(any(ForecastRequest.class), any(), any()))
                .thenReturn(new WeatherExtractionResult(
                        buildAtmosphericData(sunrise, TargetType.SUNRISE), null));
        when(weatherTriageEvaluator.evaluate(any()))
                .thenReturn(Optional.of(new TriageResult("Low cloud 85%", TriageRule.HIGH_CLOUD)));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        forecastService.fetchWeatherAndTriage(DURHAM_LOCATION, date, TargetType.SUNRISE, Set.of(),
                EvaluationModel.SONNET, null);

        ArgumentCaptor<ForecastEvaluationEntity> saved = ArgumentCaptor.forClass(ForecastEvaluationEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getRating()).isNull();
        assertThat(saved.getValue().getScoredAt()).isEqualTo(STAMP);
        assertThat(saved.getValue().getAppBuild()).isEqualTo(BUILD);
    }

    @Test
    @DisplayName("V178: a PENDING batch row is inserted unstamped, and abandoning it never stamps it")
    void pendingRowIsUnstampedAndAbandonedRowStaysUnstamped() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);
        ForecastPreEvalResult preEval = new ForecastPreEvalResult(
                false, null, buildAtmosphericData(sunset, TargetType.SUNSET), DURHAM_LOCATION, date,
                TargetType.SUNSET, sunset, 310, 0, EvaluationModel.SONNET, Set.of(),
                DURHAM + "|" + date + "|SUNSET", null);
        when(repository.save(any())).thenAnswer(inv -> {
            ForecastEvaluationEntity e = inv.getArgument(0);
            e.setId(55L);
            return e;
        });

        Long id = forecastService.persistPendingEvaluation(preEval);

        ArgumentCaptor<ForecastEvaluationEntity> saved = ArgumentCaptor.forClass(ForecastEvaluationEntity.class);
        verify(repository).save(saved.capture());
        assertThat(id).isEqualTo(55L);
        assertThat(saved.getValue().getBatchState()).isEqualTo(com.gregochr.goldenhour.entity.BatchState.PENDING);
        assertThat(saved.getValue().getScoredAt()).isNull();
        assertThat(saved.getValue().getAppBuild()).as("a PENDING row is not yet scored").isNull();

        when(repository.findById(55L)).thenReturn(Optional.of(saved.getValue()));
        forecastService.markAbandoned(55L);
        assertThat(saved.getValue().getBatchState()).isEqualTo(com.gregochr.goldenhour.entity.BatchState.ABANDONED);
        assertThat(saved.getValue().getScoredAt()).isNull();
    }

    // --- persistCannedResult tests ---

    // --- Batch prefetch cache integration tests ---

    @Test
    @DisplayName("fetchWeatherAndTriage() with non-null prefetched map uses cache, not individual API")
    void fetchWeatherAndTriage_withPrefetched_usesCache() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);
        AtmosphericData data = buildAtmosphericData(sunset, TargetType.SUNSET);
        WeatherExtractionResult cached = new WeatherExtractionResult(data, null);

        java.util.Map<String, WeatherExtractionResult> prefetched = new java.util.HashMap<>();
        prefetched.put(DURHAM_LAT + "," + DURHAM_LON, cached);

        when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
        when(solarService.sunsetAzimuthDeg(DURHAM_LAT, DURHAM_LON, date)).thenReturn(310);
        ForecastRequest expectedRequest = new ForecastRequest(
                DURHAM_LAT, DURHAM_LON, DURHAM, date, TargetType.SUNSET);
        when(openMeteoService.getAtmosphericDataFromCache(
                eq(expectedRequest), eq(sunset), eq(prefetched)))
                .thenReturn(cached);
        when(weatherTriageEvaluator.evaluate(any())).thenReturn(java.util.Optional.empty());

        forecastService.fetchWeatherAndTriage(
                DURHAM_LOCATION, date, TargetType.SUNSET, Set.of(),
                EvaluationModel.SONNET, null, prefetched, null);

        verify(openMeteoService).getAtmosphericDataFromCache(
                eq(expectedRequest), eq(sunset), eq(prefetched));
        verify(openMeteoService, never()).getAtmosphericDataWithResponse(any(), any(), any());
    }

    @Test
    @DisplayName("fetchWeatherAndTriage() with null prefetched map falls back to individual API")
    void fetchWeatherAndTriage_withNullPrefetched_fallsToIndividual() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);
        AtmosphericData data = buildAtmosphericData(sunset, TargetType.SUNSET);

        when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
        when(solarService.sunsetAzimuthDeg(DURHAM_LAT, DURHAM_LON, date)).thenReturn(310);
        when(openMeteoService.getAtmosphericDataWithResponse(any(ForecastRequest.class), any(), any()))
                .thenReturn(new WeatherExtractionResult(data, null));
        when(weatherTriageEvaluator.evaluate(any())).thenReturn(java.util.Optional.empty());

        forecastService.fetchWeatherAndTriage(
                DURHAM_LOCATION, date, TargetType.SUNSET, Set.of(),
                EvaluationModel.SONNET, null, null, null);

        verify(openMeteoService).getAtmosphericDataWithResponse(
                any(ForecastRequest.class), any(), any());
        verify(openMeteoService, never()).getAtmosphericDataFromCache(any(), any(), any());
    }

    @Test
    @DisplayName("fetchWeatherAndTriage() throws when cache miss occurs")
    void fetchWeatherAndTriage_cacheMiss_throwsWeatherDataFetchException() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);

        java.util.Map<String, WeatherExtractionResult> prefetched = new java.util.HashMap<>();

        when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
        when(openMeteoService.getAtmosphericDataFromCache(any(), any(), eq(prefetched)))
                .thenReturn(null);

        assertThatThrownBy(() -> forecastService.fetchWeatherAndTriage(
                DURHAM_LOCATION, date, TargetType.SUNSET, Set.of(),
                EvaluationModel.SONNET, null, prefetched, null))
                .isInstanceOf(WeatherDataFetchException.class)
                .hasMessageContaining("Weather data fetch failed");
    }

    @Test
    @DisplayName("fetchWeatherAndTriage() continues when SSE event publish throws")
    void fetchWeatherAndTriage_ssePublishThrows_continuesNormally() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);
        AtmosphericData data = buildAtmosphericData(sunset, TargetType.SUNSET);
        JobRunEntity jobRun = new JobRunEntity();
        jobRun.setId(99L);

        when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
        when(solarService.sunsetAzimuthDeg(DURHAM_LAT, DURHAM_LON, date)).thenReturn(310);
        when(openMeteoService.getAtmosphericDataWithResponse(any(ForecastRequest.class), any(), any()))
                .thenReturn(new WeatherExtractionResult(data, null));
        when(weatherTriageEvaluator.evaluate(any())).thenReturn(Optional.empty());

        // Simulate SSE emitter closed — publishEvent throws on every call
        doThrow(new IllegalStateException("ResponseBodyEmitter has already completed"))
                .when(eventPublisher).publishEvent(any(LocationTaskEvent.class));

        ForecastPreEvalResult result = forecastService.fetchWeatherAndTriage(
                DURHAM_LOCATION, date, TargetType.SUNSET, Set.of(),
                EvaluationModel.SONNET, jobRun);

        // Triage should complete successfully despite SSE failures
        assertThat(result).isNotNull();
        assertThat(result.triaged()).isFalse();
        assertThat(result.location().getName()).isEqualTo(DURHAM);
        assertThat(result.atmosphericData()).isEqualTo(data);
    }

    @Test
    @DisplayName("fetchWeatherAndTriage() does not publish events when jobRun is null")
    void fetchWeatherAndTriage_nullJobRun_noEvents() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);
        AtmosphericData data = buildAtmosphericData(sunset, TargetType.SUNSET);

        when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
        when(solarService.sunsetAzimuthDeg(DURHAM_LAT, DURHAM_LON, date)).thenReturn(310);
        when(openMeteoService.getAtmosphericDataWithResponse(any(ForecastRequest.class), any(), any()))
                .thenReturn(new WeatherExtractionResult(data, null));
        when(weatherTriageEvaluator.evaluate(any())).thenReturn(Optional.empty());

        forecastService.fetchWeatherAndTriage(
                DURHAM_LOCATION, date, TargetType.SUNSET, Set.of(),
                EvaluationModel.SONNET, null);

        verify(eventPublisher, never()).publishEvent(any(LocationTaskEvent.class));
    }

    // --- runForecasts: single target type ---

    // --- evaluateAndPersist: event publishing ---

    // --- evaluateAndPersist: entity field preservation ---

    // --- persistCannedResult: event publishing ---

    // --- fetchWeatherAndTriage: sunrise path uses civilDawn ---

    @Test
    @DisplayName("fetchWeatherAndTriage() SUNRISE uses sunriseUtc/sunriseAzimuthDeg (not sunset)")
    void fetchWeatherAndTriage_sunrise_usesSunriseService() {
        LocalDate date = LocalDate.of(2026, 6, 21);
        LocalDateTime sunrise = LocalDateTime.of(2026, 6, 21, 3, 30);
        AtmosphericData data = buildAtmosphericData(sunrise, TargetType.SUNRISE);

        when(solarService.sunriseUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunrise);
        when(solarService.sunriseAzimuthDeg(DURHAM_LAT, DURHAM_LON, date)).thenReturn(52);
        when(openMeteoService.getAtmosphericDataWithResponse(any(ForecastRequest.class), any(), any()))
                .thenReturn(new WeatherExtractionResult(data, null));
        when(weatherTriageEvaluator.evaluate(any())).thenReturn(Optional.empty());

        ForecastPreEvalResult result = forecastService.fetchWeatherAndTriage(
                DURHAM_LOCATION, date, TargetType.SUNRISE, Set.of(),
                EvaluationModel.SONNET, null);

        assertThat(result.targetType()).isEqualTo(TargetType.SUNRISE);
        assertThat(result.azimuth()).isEqualTo(52);
        assertThat(result.eventTime()).isEqualTo(sunrise);
        verify(solarService).sunriseUtc(DURHAM_LAT, DURHAM_LON, date);
        verify(solarService).sunriseAzimuthDeg(DURHAM_LAT, DURHAM_LON, date);
        verify(solarService, never()).sunsetUtc(anyDouble(), anyDouble(), any());
        verify(solarService, never()).sunsetAzimuthDeg(anyDouble(), anyDouble(), any());
    }

    // --- fetchWeatherAndTriage: weather passes but tide fails ---

    @Nested
    @DisplayName("Event publishing")
    class EventPublishingTests {

        @Test
        @DisplayName("fetchWeatherAndTriage() publishes FAILED event when weather fetch throws and jobRun is set")
        void fetchWeatherAndTriage_weatherThrows_withJobRun_publishesFailedEvent() {
            LocalDate date = LocalDate.of(2026, 6, 21);
            LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);
            JobRunEntity jobRun = new JobRunEntity();
            jobRun.setId(33L);

            when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
            when(openMeteoService.getAtmosphericDataWithResponse(any(), any(), any()))
                    .thenThrow(new RuntimeException("API timeout"));

            assertThatThrownBy(() -> forecastService.fetchWeatherAndTriage(
                    DURHAM_LOCATION, date, TargetType.SUNSET, Set.of(),
                    EvaluationModel.SONNET, jobRun))
                    .isInstanceOf(WeatherDataFetchException.class);

            ArgumentCaptor<LocationTaskEvent> captor = ArgumentCaptor.forClass(LocationTaskEvent.class);
            verify(eventPublisher, org.mockito.Mockito.atLeastOnce()).publishEvent(captor.capture());
            assertThat(captor.getAllValues())
                    .anyMatch(e -> e.getState() == LocationTaskState.FAILED);
        }

        @Test
        @DisplayName("fetchWeatherAndTriage() publishes FAILED event when weather data is null and jobRun is set")
        void fetchWeatherAndTriage_nullWeather_withJobRun_publishesFailedEvent() {
            LocalDate date = LocalDate.of(2026, 6, 21);
            LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);
            JobRunEntity jobRun = new JobRunEntity();
            jobRun.setId(44L);

            when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
            when(openMeteoService.getAtmosphericDataWithResponse(any(), any(), any()))
                    .thenReturn(new WeatherExtractionResult(null, null));

            assertThatThrownBy(() -> forecastService.fetchWeatherAndTriage(
                    DURHAM_LOCATION, date, TargetType.SUNSET, Set.of(),
                    EvaluationModel.SONNET, jobRun))
                    .isInstanceOf(WeatherDataFetchException.class);

            ArgumentCaptor<LocationTaskEvent> captor = ArgumentCaptor.forClass(LocationTaskEvent.class);
            verify(eventPublisher, org.mockito.Mockito.atLeastOnce()).publishEvent(captor.capture());
            assertThat(captor.getAllValues())
                    .anyMatch(e -> e.getState() == LocationTaskState.FAILED);
        }

        @Test
        @DisplayName("fetchWeatherAndTriage() publishes FETCHING_TIDES event when tideTypes is non-empty")
        void fetchWeatherAndTriage_nonEmptyTideTypes_withJobRun_publishesFetchingTidesEvent() {
            LocalDate date = LocalDate.of(2026, 6, 21);
            LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);
            AtmosphericData data = buildAtmosphericData(sunset, TargetType.SUNSET);
            JobRunEntity jobRun = new JobRunEntity();
            jobRun.setId(55L);

            when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
            when(solarService.sunsetAzimuthDeg(DURHAM_LAT, DURHAM_LON, date)).thenReturn(300);
            when(openMeteoService.getAtmosphericDataWithResponse(any(), any(), any()))
                    .thenReturn(new WeatherExtractionResult(data, null));
            when(weatherTriageEvaluator.evaluate(any())).thenReturn(Optional.empty());

            forecastService.fetchWeatherAndTriage(
                    DURHAM_LOCATION, date, TargetType.SUNSET, Set.of(TideType.HIGH),
                    EvaluationModel.SONNET, jobRun);

            ArgumentCaptor<LocationTaskEvent> captor = ArgumentCaptor.forClass(LocationTaskEvent.class);
            verify(eventPublisher, org.mockito.Mockito.atLeastOnce()).publishEvent(captor.capture());
            assertThat(captor.getAllValues())
                    .anyMatch(e -> e.getState() == LocationTaskState.FETCHING_TIDES);
        }

        @Test
        @DisplayName("fetchWeatherAndTriage() publishes TRIAGED event when weather triage fires and jobRun is set")
        void fetchWeatherAndTriage_weatherTriaged_withJobRun_publishesTriagedEvent() {
            LocalDate date = LocalDate.of(2026, 6, 21);
            LocalDateTime sunset = LocalDateTime.of(2026, 6, 21, 20, 47);
            AtmosphericData data = buildAtmosphericData(sunset, TargetType.SUNSET);
            JobRunEntity jobRun = new JobRunEntity();
            jobRun.setId(66L);

            when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
            when(solarService.sunsetAzimuthDeg(DURHAM_LAT, DURHAM_LON, date)).thenReturn(300);
            when(openMeteoService.getAtmosphericDataWithResponse(any(), any(), any()))
                    .thenReturn(new WeatherExtractionResult(data, null));
            when(weatherTriageEvaluator.evaluate(any()))
                    .thenReturn(Optional.of(new TriageResult("100% low cloud", TriageRule.HIGH_CLOUD)));
            when(repository.save(any())).thenReturn(ForecastEvaluationEntity.builder().id(100L).build());

            forecastService.fetchWeatherAndTriage(
                    DURHAM_LOCATION, date, TargetType.SUNSET, Set.of(),
                    EvaluationModel.SONNET, jobRun);

            ArgumentCaptor<LocationTaskEvent> captor = ArgumentCaptor.forClass(LocationTaskEvent.class);
            verify(eventPublisher, org.mockito.Mockito.atLeastOnce()).publishEvent(captor.capture());
            assertThat(captor.getAllValues())
                    .anyMatch(e -> e.getState() == LocationTaskState.TRIAGED);
        }
    }

    // --- buildEntity field mapping, via persistPendingEvaluation() ---

    @Nested
    @DisplayName("buildEntity() field mapping via persistPendingEvaluation()")
    class BuildEntityFieldMappingTests {

        private static final LocalDate DATE = LocalDate.of(2026, 6, 21);
        private static final LocalDateTime SUNSET = LocalDateTime.of(2026, 6, 21, 20, 47);

        private ForecastPreEvalResult preEvalWith(AtmosphericData data) {
            return new ForecastPreEvalResult(
                    false, null, data, DURHAM_LOCATION, DATE,
                    TargetType.SUNSET, SUNSET, 310, 0, EvaluationModel.SONNET, Set.of(),
                    DURHAM + "|" + DATE + "|SUNSET", null);
        }

        private ForecastEvaluationEntity persisted(AtmosphericData data) {
            when(repository.save(any())).thenAnswer(inv -> {
                ForecastEvaluationEntity e = inv.getArgument(0);
                e.setId(9L);
                return e;
            });
            forecastService.persistPendingEvaluation(preEvalWith(data));
            ArgumentCaptor<ForecastEvaluationEntity> captor =
                    ArgumentCaptor.forClass(ForecastEvaluationEntity.class);
            verify(repository).save(captor.capture());
            return captor.getValue();
        }

        @Test
        @DisplayName("non-null TideSnapshot maps tide fields into entity")
        void persistPending_withTide_tideFieldsMapped() {
            LocalDateTime highTideTime = LocalDateTime.of(2026, 6, 21, 18, 30);
            LocalDateTime lowTideTime = LocalDateTime.of(2026, 6, 22, 0, 45);
            TideSnapshot tide = new TideSnapshot(
                    TideState.HIGH, highTideTime, new BigDecimal("4.5"),
                    lowTideTime, new BigDecimal("0.8"), true,
                    null, null, null, null, null, null);
            AtmosphericData data = TestAtmosphericData.builder()
                    .solarEventTime(SUNSET).targetType(TargetType.SUNSET).tide(tide).build();

            ForecastEvaluationEntity result = persisted(data);

            assertThat(result.getTide().getState()).isEqualTo(TideState.HIGH);
            assertThat(result.getTide().getAligned()).isTrue();
            assertThat(result.getTide().getNextHighTime()).isEqualTo(highTideTime);
            assertThat(result.getTide().getNextHighHeightMetres())
                    .isEqualByComparingTo(new BigDecimal("4.5"));
        }

        @Test
        @DisplayName("data with no tide, directional cloud, cloud approach or surge leaves all four null")
        void persistPending_withoutOptionalSections_leavesThemNull() {
            AtmosphericData data = TestAtmosphericData.builder()
                    .solarEventTime(SUNSET).targetType(TargetType.SUNSET).build();

            ForecastEvaluationEntity result = persisted(data);

            assertThat(result.getTide()).isNull();
            assertThat(result.getDirectionalCloud()).isNull();
            assertThat(result.getCloudApproach()).isNull();
            assertThat(result.getSurge()).isNull();
        }

        @Test
        @DisplayName("non-null DirectionalCloudData maps solar cloud fields into entity")
        void persistPending_withDirectionalCloud_solarCloudFieldsMapped() {
            // solarLow=35, solarMid=45, solarHigh=20, antisolarLow=40, antisolarMid=50, antisolarHigh=15,
            // farSolarLow=55
            DirectionalCloudData dc = new DirectionalCloudData(35, 45, 20, 40, 50, 15, 55);
            AtmosphericData data = TestAtmosphericData.builder()
                    .solarEventTime(SUNSET).targetType(TargetType.SUNSET)
                    .directionalCloud(dc).build();

            ForecastEvaluationEntity result = persisted(data);

            assertThat(result.getDirectionalCloud().getSolarLow()).isEqualTo(35);
            assertThat(result.getDirectionalCloud().getSolarMid()).isEqualTo(45);
            assertThat(result.getDirectionalCloud().getFarSolarLow()).isEqualTo(55);
        }

        @Test
        @DisplayName("non-null CloudApproachData with slots maps trend and upwind fields into entity")
        void persistPending_withCloudApproach_trendAndUpwindFieldsMapped() {
            SolarCloudTrend trend = new SolarCloudTrend(List.of(
                    new SolarCloudSlot(3, 10),
                    new SolarCloudSlot(2, 25),
                    new SolarCloudSlot(0, 40)));
            UpwindCloudSample upwind = new UpwindCloudSample(80, 225, 65, 35);
            AtmosphericData data = TestAtmosphericData.builder()
                    .solarEventTime(SUNSET).targetType(TargetType.SUNSET)
                    .cloudApproach(new CloudApproachData(trend, upwind)).build();

            ForecastEvaluationEntity result = persisted(data);

            // solarTrend: first slot=10 (earliest), last slot=40 (event time)
            // isBuilding: peak(40) - earliest(10) = 30 >= 20 -> true
            assertThat(result.getCloudApproach().getSolarTrendEarliestLowCloud()).isEqualTo(10);
            assertThat(result.getCloudApproach().getSolarTrendEventLowCloud()).isEqualTo(40);
            assertThat(result.getCloudApproach().getSolarTrendBuilding()).isTrue();
            assertThat(result.getCloudApproach().getUpwindCurrentLowCloud()).isEqualTo(65);
            assertThat(result.getCloudApproach().getUpwindEventLowCloud()).isEqualTo(35);
            assertThat(result.getCloudApproach().getUpwindDistanceKm()).isEqualTo(80);
        }

        @Test
        @DisplayName("non-null StormSurgeBreakdown maps surge fields into entity")
        void persistPending_withSurge_surgeFieldsMapped() {
            StormSurgeBreakdown surge = new StormSurgeBreakdown(
                    0.08, 0.05, 0.13, 1000.0, 8.0, 270.0, 0.9, TideRiskLevel.LOW, "Low surge");
            AtmosphericData data = TestAtmosphericData.builder()
                    .solarEventTime(SUNSET).targetType(TargetType.SUNSET)
                    .build().withSurge(surge, 3.5, 3.3);

            ForecastEvaluationEntity result = persisted(data);

            assertThat(result.getSurge().getTotalMetres()).isEqualTo(0.13);
            assertThat(result.getSurge().getPressureMetres()).isEqualTo(0.08);
            assertThat(result.getSurge().getWindMetres()).isEqualTo(0.05);
            assertThat(result.getSurge().getRiskLevel()).isEqualTo("LOW");
            assertThat(result.getSurge().getAdjustedRangeMetres()).isEqualTo(3.5);
        }
    }

    private AtmosphericData buildAtmosphericData(LocalDateTime eventTime, TargetType targetType) {
        return TestAtmosphericData.builder()
                .solarEventTime(eventTime)
                .targetType(targetType)
                .lowCloud(15)
                .midCloud(55)
                .visibility(22000)
                .windSpeed(new BigDecimal("4.20"))
                .temperature(12.5)
                .apparentTemperature(9.8)
                .precipProbability(20)
                .build();
    }

    /**
     * The calendar {@code daysAhead} is derived on, at the hour where the choice is visible.
     *
     * <p>A forecast target date names a solar event at a UK location, so "today" is the UK
     * civil date. Under BST those two calendars part company between 23:00 and 00:00 UTC,
     * where UTC is a day behind and every horizon it produces is one too many. The horizon
     * is not cosmetic: it gates the intraday and nightly eligibility policies, and it is
     * persisted on {@code forecast_evaluation.days_ahead} along with the confidence band
     * derived from it.
     *
     * <p>These cases build their own service against a clock inside that band, because the
     * enclosing class deliberately sits at midday where the two calendars agree.
     */
    @Nested
    @DisplayName("daysAhead timezone basis")
    class DaysAheadBasis {

        /** 2026-08-11 23:30 UTC — BST, so the UK civil date is already the 12th. */
        private final Clock bstPreMidnight =
                Clock.fixed(Instant.parse("2026-08-11T23:30:00Z"), java.time.ZoneOffset.UTC);

        @Test
        @DisplayName("a sunset on the UK's today is T+0, not the T+1 the UTC date implies")
        void tonightsSunset_isTodayOnTheUkCalendar() {
            // 2026-08-12 is "today" in London and "tomorrow" in UTC at this instant. The
            // whole defect lives in that one day of difference.
            LocalDate ukToday = LocalDate.of(2026, 8, 12);
            assertThat(LocalDate.now(bstPreMidnight.withZone(java.time.ZoneOffset.UTC)))
                    .as("premise: UTC is still on the previous date")
                    .isEqualTo(ukToday.minusDays(1));

            ForecastPreEvalResult result = fetchAt(bstPreMidnight, ukToday);

            assertThat(result.daysAhead())
                    .as("tonight's sunset is today's, whatever the UTC date says")
                    .isZero();
        }

        @Test
        @DisplayName("the horizon still counts forward correctly from the UK date")
        void furtherDates_countFromTheUkToday() {
            // Guards against "fix" by subtracting one: the basis moves, the arithmetic does not.
            assertThat(fetchAt(bstPreMidnight, LocalDate.of(2026, 8, 14)).daysAhead()).isEqualTo(2);
        }

        @Test
        @DisplayName("outside the divergent hour both calendars agree, so nothing moves")
        void middayIsUnaffected() {
            Clock midday = Clock.fixed(Instant.parse("2026-08-11T12:00:00Z"), java.time.ZoneOffset.UTC);
            assertThat(fetchAt(midday, LocalDate.of(2026, 8, 12)).daysAhead()).isEqualTo(1);
        }

        @Test
        @DisplayName("a triaged row persists the UK horizon, and the confidence derived from it")
        void triagedRow_persistsTheUkHorizonAndItsConfidence() {
            // The cases above assert the returned value; this one asserts the *stored* one, which
            // is the half nothing pinned. It matters because triage is the only branch of
            // fetchWeatherAndTriage that writes a row, and it writes two horizon-derived columns:
            // days_ahead, and confidence via ConfidenceDeriver.fromHorizon. A UTC-anchored caller
            // handing this method the UK's yesterday stored -1 there — and fromHorizon(-1) takes
            // the `daysAhead <= 1` branch, so a day already over was filed as HIGH confidence.
            ForecastEvaluationEntity stored = triageAt(bstPreMidnight, LocalDate.of(2026, 8, 12));

            assertThat(stored.getDaysAhead()).isZero();
            assertThat(stored.getConfidence()).isEqualTo("HIGH");
        }

        @Test
        @DisplayName("the stored confidence tracks the stored horizon rather than being constant")
        void triagedRow_confidenceFollowsTheHorizon() {
            // Without this, the assertion above passes against a column hard-wired to HIGH — which
            // is exactly what the -1 row looked like from the outside.
            ForecastEvaluationEntity stored = triageAt(bstPreMidnight, LocalDate.of(2026, 8, 16));

            assertThat(stored.getDaysAhead()).isEqualTo(4);
            assertThat(stored.getConfidence()).isEqualTo("LOW");
        }

        /**
         * Drives {@code fetchWeatherAndTriage} down its triage branch and returns the entity it
         * saved, so the persisted horizon columns can be asserted directly.
         */
        private ForecastEvaluationEntity triageAt(Clock at, LocalDate date) {
            LocalDateTime sunset = date.atTime(20, 30);
            when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
            when(solarService.sunsetAzimuthDeg(DURHAM_LAT, DURHAM_LON, date)).thenReturn(310);
            when(openMeteoService.getAtmosphericDataWithResponse(
                    any(ForecastRequest.class), any(), any()))
                    .thenReturn(new WeatherExtractionResult(
                            buildAtmosphericData(sunset, TargetType.SUNSET),
                            new OpenMeteoForecastResponse()));
            when(weatherTriageEvaluator.evaluate(any()))
                    .thenReturn(Optional.of(new TriageResult("Low cloud 85%", TriageRule.HIGH_CLOUD)));

            ForecastService service = new ForecastService(
                    solarService, openMeteoService, augmentor, repository, eventPublisher,
                    weatherTriageEvaluator, slotAtmosphereWriter, at, new AppBuild(BUILD));

            service.fetchWeatherAndTriage(DURHAM_LOCATION, date, TargetType.SUNSET,
                    Set.of(), EvaluationModel.SONNET, null);

            ArgumentCaptor<ForecastEvaluationEntity> captor =
                    ArgumentCaptor.forClass(ForecastEvaluationEntity.class);
            verify(repository).save(captor.capture());
            return captor.getValue();
        }

        /**
         * Runs the real horizon derivation for one date against one clock, with every
         * collaborator stubbed to the minimum {@code fetchWeatherAndTriage} needs.
         */
        private ForecastPreEvalResult fetchAt(Clock at, LocalDate date) {
            LocalDateTime sunset = date.atTime(20, 30);
            when(solarService.sunsetUtc(DURHAM_LAT, DURHAM_LON, date)).thenReturn(sunset);
            when(solarService.sunsetAzimuthDeg(DURHAM_LAT, DURHAM_LON, date)).thenReturn(310);
            when(openMeteoService.getAtmosphericDataWithResponse(
                    any(ForecastRequest.class), any(), any()))
                    .thenReturn(new WeatherExtractionResult(
                            buildAtmosphericData(sunset, TargetType.SUNSET),
                            new OpenMeteoForecastResponse()));
            when(weatherTriageEvaluator.evaluate(any())).thenReturn(Optional.empty());

            ForecastService service = new ForecastService(
                    solarService, openMeteoService, augmentor, repository, eventPublisher,
                    weatherTriageEvaluator, slotAtmosphereWriter, at, new AppBuild(BUILD));

            return service.fetchWeatherAndTriage(DURHAM_LOCATION, date, TargetType.SUNSET,
                    Set.of(), EvaluationModel.SONNET, null);
        }
    }
}
