package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.ForecastEvaluationDto;
import com.gregochr.goldenhour.model.ForecastListDto;
import com.gregochr.goldenhour.model.LocationEvaluationView;
import com.gregochr.goldenhour.model.LocationTaskSnapshot;
import com.gregochr.goldenhour.model.LocationTaskState;
import com.gregochr.goldenhour.model.RunProgress;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.service.ForecastCommandFactory;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link ForecastController}.
 *
 * <p>Loads the full application context with test configuration and mocks only
 * the direct service dependencies.
 */
class ForecastControllerTest extends AbstractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private static final LocationEntity DURHAM = LocationEntity.builder()
            .id(1L).name("Durham UK").lat(54.7753).lon(-1.5849).build();

    @BeforeEach
    void setUp() {
        when(locationService.findAllEnabled()).thenReturn(List.of(DURHAM));
        when(locationService.findByName(eq("Durham UK"))).thenReturn(DURHAM);
        when(commandFactory.create(any(), any(boolean.class)))
                .thenReturn(new com.gregochr.goldenhour.service.ForecastCommand(
                        com.gregochr.goldenhour.entity.RunType.SHORT_TERM,
                        List.of(), null, null, true));
        when(commandFactory.create(any(), any(boolean.class), any(), any()))
                .thenReturn(new com.gregochr.goldenhour.service.ForecastCommand(
                        com.gregochr.goldenhour.entity.RunType.SHORT_TERM,
                        List.of(), null, null, true));
        JobRunEntity stubJobRun = new JobRunEntity();
        stubJobRun.setId(1L);
        when(jobRunService.startRun(any(), any(boolean.class), any(), any()))
                .thenReturn(stubJobRun);
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/forecast returns 200 with DTOs for all configured locations")
    void getForecasts_returnsDtosForConfiguredLocations() throws Exception {
        ForecastEvaluationEntity entity = buildEntity(DURHAM, LocalDate.of(2026, 2, 20));
        when(forecastEvaluationRepository
                .findLatestRunPerSlotByLocationIds(any(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(entity));
        when(dtoMapper.toListDtoList(any(), anyBoolean()))
                .thenReturn(List.of(buildListDto("Durham UK", 72, 80)));

        mockMvc.perform(get("/api/forecast"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].locationName").value("Durham UK"));
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/forecast surfaces cached_evaluation rows when forecast_evaluation has none")
    void getForecasts_surfacesCachedOnlyRows() throws Exception {
        when(forecastEvaluationRepository
                .findLatestRunPerSlotByLocationIds(any(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of());
        when(dtoMapper.toListDtoList(any(), anyBoolean())).thenReturn(List.of());

        LocalDate futureDate = LocalDate.of(2999, 1, 3);
        LocationEvaluationView cachedView = new LocationEvaluationView(
                1L, "Durham UK", null, null, futureDate, TargetType.SUNSET,
                LocationEvaluationView.Source.CACHED_EVALUATION,
                4, "Patchy mid cloud — could pop colour.", 72, 80,
                null, null, null, null, DisplayVerdict.WORTH_IT);
        when(evaluationViewService.cachedOnlyViewsForDateRange(
                any(LocalDate.class), any(LocalDate.class), any(), any()))
                .thenReturn(List.of(cachedView));
        when(dtoMapper.toSparseListDto(eq(cachedView), eq(DURHAM), anyBoolean()))
                .thenReturn(buildListDto("Durham UK", 72, 80));

        mockMvc.perform(get("/api/forecast"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].locationName").value("Durham UK"))
                .andExpect(jsonPath("$[0].fierySkyPotential").value(72));

        verify(dtoMapper).toSparseListDto(eq(cachedView), eq(DURHAM), anyBoolean());
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/forecast passes enabled location ids and the date window to the repository")
    @SuppressWarnings("unchecked")
    void getForecasts_passesLocationIdsAndDateWindowToRepository() throws Exception {
        when(forecastEvaluationRepository
                .findLatestRunPerSlotByLocationIds(any(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of());
        when(dtoMapper.toListDtoList(any(), anyBoolean())).thenReturn(List.of());

        // The window is anchored on the UK civil date, not UTC — the two disagree between 23:00
        // and 00:00 UTC under BST, and a UTC-based expectation here would fail for that hour.
        LocalDate today = ForecastHorizon.today(Clock.systemUTC());
        mockMvc.perform(get("/api/forecast")).andExpect(status().isOk());

        ArgumentCaptor<Collection<Long>> idsCaptor = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<LocalDate> fromCaptor = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> toCaptor = ArgumentCaptor.forClass(LocalDate.class);
        verify(forecastEvaluationRepository).findLatestRunPerSlotByLocationIds(
                idsCaptor.capture(), fromCaptor.capture(), toCaptor.capture());

        // setUp() stubs findAllEnabled() -> [DURHAM] (id 1)
        assertThat(idsCaptor.getValue()).containsExactly(1L);
        // Asserted against the constant, not a literal 2: the value is a product decision that
        // may move again, but the invariant — the query window is the declared past window, not
        // some other number — is what this pins. A literal here would have to be edited in step
        // with the constant, which is how a test stops testing anything.
        assertThat(fromCaptor.getValue())
                .isEqualTo(today.minusDays(ForecastController.PAST_WINDOW_DAYS));
        assertThat(toCaptor.getValue())
                .isEqualTo(today.plusDays(ForecastCommandFactory.FORECAST_HORIZON_DAYS));
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/forecast prefers forecast_evaluation rows over cached_evaluation duplicates")
    void getForecasts_prefersForecastRowOverCachedDuplicate() throws Exception {
        ForecastEvaluationEntity entity = buildEntity(DURHAM, LocalDate.of(2026, 2, 20));
        when(forecastEvaluationRepository
                .findLatestRunPerSlotByLocationIds(any(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(entity));
        when(dtoMapper.toListDtoList(any(), anyBoolean()))
                .thenReturn(List.of(buildListDto("Durham UK", 72, 80)));

        // Cached view for the same (loc, date, type) — should be skipped as a duplicate
        LocationEvaluationView duplicateView = new LocationEvaluationView(
                1L, "Durham UK", null, null, LocalDate.of(2026, 2, 20), TargetType.SUNSET,
                LocationEvaluationView.Source.CACHED_EVALUATION,
                3, "stale", 50, 60, null, null, null, null, DisplayVerdict.MAYBE);
        when(evaluationViewService.cachedOnlyViewsForDateRange(
                any(LocalDate.class), any(LocalDate.class), any(), any()))
                .thenReturn(List.of(duplicateView));

        mockMvc.perform(get("/api/forecast"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].fierySkyPotential").value(72));
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/forecast returns empty and skips the repository when no locations are enabled")
    void getForecasts_noEnabledLocations_skipsRepositoryQuery() throws Exception {
        when(locationService.findAllEnabled()).thenReturn(List.of());
        when(dtoMapper.toListDtoList(any(), anyBoolean())).thenReturn(List.of());

        mockMvc.perform(get("/api/forecast"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));

        verify(forecastEvaluationRepository, never())
                .findLatestRunPerSlotByLocationIds(any(), any(LocalDate.class), any(LocalDate.class));
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/forecast/{id} returns the full evaluation DTO with the popup-only fields")
    void getForecastDetail_returnsFullDto() throws Exception {
        ForecastEvaluationEntity entity = buildEntity(DURHAM, LocalDate.of(2026, 2, 20));
        when(forecastEvaluationRepository.findById(42L)).thenReturn(Optional.of(entity));
        when(dtoMapper.toDto(entity, false)).thenReturn(buildDto("Durham UK", 72, 80));

        mockMvc.perform(get("/api/forecast/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locationName").value("Durham UK"))
                .andExpect(jsonPath("$.summary").value("Good colour potential."));
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/forecast/{id} returns 404 when the evaluation does not exist")
    void getForecastDetail_notFound_returns404() throws Exception {
        when(forecastEvaluationRepository.findById(999L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/forecast/999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = {"LITE_USER"})
    @DisplayName("GET /api/forecast/{id} as LITE_USER passes isLiteUser=true (no freemium bypass)")
    void getForecastDetail_asLiteUser_passesLiteFlag() throws Exception {
        ForecastEvaluationEntity entity = buildEntity(DURHAM, LocalDate.of(2026, 2, 20));
        when(forecastEvaluationRepository.findById(42L)).thenReturn(Optional.of(entity));
        when(dtoMapper.toDto(entity, true)).thenReturn(buildDto("Durham UK", 50, 55));

        mockMvc.perform(get("/api/forecast/42"))
                .andExpect(status().isOk());

        verify(dtoMapper).toDto(entity, true);
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/forecast/history returns 200 for a valid date range with location filter")
    void getHistory_validRange_returnsEvaluations() throws Exception {
        ForecastEvaluationEntity entity = buildEntity(DURHAM, LocalDate.of(2026, 1, 15));
        when(forecastEvaluationRepository
                .findByLocationIdAndTargetDateBetweenOrderByTargetDateAscTargetTypeAsc(
                        eq(1L), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(entity));
        when(dtoMapper.toDtoList(any(), anyBoolean()))
                .thenReturn(List.of(buildDto("Durham UK", 72, 80)));

        mockMvc.perform(get("/api/forecast/history")
                        .param("from", "2026-01-01")
                        .param("to", "2026-01-31")
                        .param("location", "Durham UK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].locationName").value("Durham UK"));
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/forecast/history returns 400 when 'from' is after 'to'")
    void getHistory_fromAfterTo_returns400() throws Exception {
        mockMvc.perform(get("/api/forecast/history")
                        .param("from", "2026-02-01")
                        .param("to", "2026-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/forecast/run as ADMIN returns 202 Accepted")
    void runForecast_asAdmin_noBody_returns202() throws Exception {
        mockMvc.perform(post("/api/forecast/run"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("Forecast run started"))
                .andExpect(jsonPath("$.runType").value("SHORT_TERM"));
    }

    @Test
    @WithMockUser
    @DisplayName("POST /api/forecast/run as non-admin returns 403")
    void runForecast_asNonAdmin_returns403() throws Exception {
        mockMvc.perform(post("/api/forecast/run"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/forecast/run returns 400 when the specified location is not configured")
    void runForecast_unknownLocation_returns400() throws Exception {
        mockMvc.perform(post("/api/forecast/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"location\":\"Unknown City\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/forecast/run with multiple dates returns 202 Accepted")
    void runForecast_multipleDates_returns202() throws Exception {
        mockMvc.perform(post("/api/forecast/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dates\":[\"2026-03-01\",\"2026-03-02\"]}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("Forecast run started"));
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/forecast/compare returns 200 with DTOs for valid params")
    void getCompare_validParams_returnsEvaluations() throws Exception {
        ForecastEvaluationEntity entity = buildEntity(DURHAM, LocalDate.of(2026, 2, 28));
        when(forecastEvaluationRepository
                .findByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtAsc(
                        eq(1L), eq(LocalDate.of(2026, 2, 28)), eq(TargetType.SUNSET)))
                .thenReturn(List.of(entity));
        when(dtoMapper.toDtoList(any(), anyBoolean()))
                .thenReturn(List.of(buildDto("Durham UK", 72, 80)));

        mockMvc.perform(get("/api/forecast/compare")
                        .param("location", "Durham UK")
                        .param("date", "2026-02-28")
                        .param("targetType", "SUNSET"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].locationName").value("Durham UK"))
                .andExpect(jsonPath("$[0].fierySkyPotential").value(72))
                .andExpect(jsonPath("$[0].goldenHourPotential").value(80));
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/forecast/history with unknown location returns 404")
    void getHistory_unknownLocation_returns404() throws Exception {
        when(locationService.findByName(eq("Nowhere")))
                .thenThrow(new NoSuchElementException("No location named 'Nowhere'"));

        mockMvc.perform(get("/api/forecast/history")
                        .param("from", "2026-01-01")
                        .param("to", "2026-01-31")
                        .param("location", "Nowhere"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("No location named 'Nowhere'"));
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/forecast/compare with unknown location returns 404")
    void getCompare_unknownLocation_returns404() throws Exception {
        when(locationService.findByName(eq("Nowhere")))
                .thenThrow(new NoSuchElementException("No location named 'Nowhere'"));

        mockMvc.perform(get("/api/forecast/compare")
                        .param("location", "Nowhere")
                        .param("date", "2026-02-28")
                        .param("targetType", "SUNSET"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("No location named 'Nowhere'"));
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/forecast/compare returns 400 when required params are missing")
    void getCompare_missingParams_returns400() throws Exception {
        mockMvc.perform(get("/api/forecast/compare")
                        .param("location", "Durham UK"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/forecast/run/very-short-term as ADMIN returns 202 Accepted")
    void runVeryShortTermForecast_asAdmin_returns202() throws Exception {
        mockMvc.perform(post("/api/forecast/run/very-short-term"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("Forecast run started"))
                .andExpect(jsonPath("$.runType").value("VERY_SHORT_TERM"));
    }

    @Test
    @WithMockUser
    @DisplayName("POST /api/forecast/run/very-short-term as non-admin returns 403")
    void runVeryShortTermForecast_asNonAdmin_returns403() throws Exception {
        mockMvc.perform(post("/api/forecast/run/very-short-term"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/forecast/run/short-term as ADMIN returns 202 Accepted")
    void runShortTermForecast_asAdmin_returns202() throws Exception {
        mockMvc.perform(post("/api/forecast/run/short-term"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("Forecast run started"))
                .andExpect(jsonPath("$.runType").value("SHORT_TERM"));
    }

    @Test
    @WithMockUser
    @DisplayName("POST /api/forecast/run/short-term as non-admin returns 403")
    void runShortTermForecast_asNonAdmin_returns403() throws Exception {
        mockMvc.perform(post("/api/forecast/run/short-term"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/forecast/run/long-term as ADMIN returns 202 Accepted")
    void runLongTermForecast_asAdmin_returns202() throws Exception {
        mockMvc.perform(post("/api/forecast/run/long-term"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("Forecast run started"))
                .andExpect(jsonPath("$.runType").value("LONG_TERM"));
    }

    @Test
    @WithMockUser
    @DisplayName("POST /api/forecast/run/long-term as non-admin returns 403")
    void runLongTermForecast_asNonAdmin_returns403() throws Exception {
        mockMvc.perform(post("/api/forecast/run/long-term"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/forecast/history returns 200 for all locations when no location filter is given")
    void getHistory_validRange_noLocation_returnsEvaluationsForAllLocations() throws Exception {
        ForecastEvaluationEntity entity = buildEntity(DURHAM, LocalDate.of(2026, 1, 15));
        when(forecastEvaluationRepository
                .findByLocationIdAndTargetDateBetweenOrderByTargetDateAscTargetTypeAsc(
                        eq(1L), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(entity));
        when(dtoMapper.toDtoList(any(), anyBoolean()))
                .thenReturn(List.of(buildDto("Durham UK", 72, 80)));

        mockMvc.perform(get("/api/forecast/history")
                        .param("from", "2026-01-01")
                        .param("to", "2026-01-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].locationName").value("Durham UK"));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/forecast/run with maxDays returns 202 Accepted")
    void runForecast_withMaxDays_returns202() throws Exception {
        mockMvc.perform(post("/api/forecast/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dates\":[\"2026-03-01\",\"2026-03-02\",\"2026-03-03\"]}")
                        .param("maxDays", "1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("Forecast run started"));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/forecast/run with maxLocations returns 202 Accepted")
    void runForecast_withMaxLocations_returns202() throws Exception {
        mockMvc.perform(post("/api/forecast/run")
                        .param("maxLocations", "1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("Forecast run started"));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/forecast/run/tide as ADMIN returns 202 Accepted")
    void refreshTideData_asAdmin_returns202() throws Exception {
        mockMvc.perform(post("/api/forecast/run/tide"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("Tide refresh started"))
                .andExpect(jsonPath("$.runType").value("TIDE"));
    }

    @Test
    @WithMockUser
    @DisplayName("POST /api/forecast/run/tide as non-admin returns 403")
    void refreshTideData_asNonAdmin_returns403() throws Exception {
        mockMvc.perform(post("/api/forecast/run/tide"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/forecast/run/tide/backfill as ADMIN returns 202 Accepted")
    void backfillTideData_asAdmin_returns202() throws Exception {
        mockMvc.perform(post("/api/forecast/run/tide/backfill"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("Tide backfill started (12 months, SEASCAPE locations)"))
                .andExpect(jsonPath("$.runType").value("TIDE"));
    }

    @Test
    @WithMockUser
    @DisplayName("POST /api/forecast/run/tide/backfill as non-admin returns 403")
    void backfillTideData_asNonAdmin_returns403() throws Exception {
        mockMvc.perform(post("/api/forecast/run/tide/backfill"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"LITE_USER"})
    @DisplayName("GET /api/forecast as LITE_USER passes isLiteUser=true to mapper")
    void getForecasts_asLiteUser_passesLiteFlag() throws Exception {
        when(forecastEvaluationRepository
                .findLatestRunPerSlotByLocationIds(any(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of());
        when(dtoMapper.toListDtoList(any(), eq(true))).thenReturn(List.of());

        mockMvc.perform(get("/api/forecast"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        verify(dtoMapper).toListDtoList(any(), eq(true));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET /api/forecast as ADMIN passes isLiteUser=false to mapper")
    void getForecasts_asAdmin_passesNonLiteFlag() throws Exception {
        when(forecastEvaluationRepository
                .findLatestRunPerSlotByLocationIds(any(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of());
        when(dtoMapper.toListDtoList(any(), eq(false))).thenReturn(List.of());

        mockMvc.perform(get("/api/forecast"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        verify(dtoMapper).toListDtoList(any(), eq(false));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/forecast/run/{runId}/retry-failed returns 404 when run not found")
    void retryFailed_nullProgress_returns404() throws Exception {
        when(progressTracker.getProgress(anyLong())).thenReturn(null);

        mockMvc.perform(post("/api/forecast/run/99/retry-failed"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/forecast/run/{runId}/retry-failed returns 404 when run has no failures")
    void retryFailed_emptyFailedTasks_returns404() throws Exception {
        RunProgress progress = mock(RunProgress.class);
        when(progress.getFailedTasks()).thenReturn(List.of());
        when(progressTracker.getProgress(1L)).thenReturn(progress);

        mockMvc.perform(post("/api/forecast/run/1/retry-failed"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/forecast/run/{runId}/retry-failed returns 202 when run has failures")
    void retryFailed_withFailedTasks_returns202() throws Exception {
        LocationTaskSnapshot failedTask = new LocationTaskSnapshot(
                "Durham UK|2026-03-20|SUNSET", "Durham UK", "2026-03-20", "SUNSET",
                LocationTaskState.FAILED, "error", "step", Instant.now());
        RunProgress progress = mock(RunProgress.class);
        when(progress.getFailedTasks()).thenReturn(List.of(failedTask));
        when(progressTracker.getProgress(1L)).thenReturn(progress);

        mockMvc.perform(post("/api/forecast/run/1/retry-failed"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("Retry run started"))
                .andExpect(jsonPath("$.runType").value("SHORT_TERM"));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET /api/forecast/run/{runId}/progress returns SSE stream for ADMIN")
    void getRunProgress_asAdmin_returnsSse() throws Exception {
        when(progressTracker.subscribe(anyLong())).thenReturn(new SseEmitter());

        mockMvc.perform(get("/api/forecast/run/1/progress"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/forecast/run/notifications returns SSE stream")
    void getRunNotifications_returnsOk() throws Exception {
        when(progressTracker.subscribeNotifications()).thenReturn(new SseEmitter());

        mockMvc.perform(get("/api/forecast/run/notifications"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/forecast/run with a known location name returns 202 Accepted")
    void runForecast_knownLocation_returns202() throws Exception {
        mockMvc.perform(post("/api/forecast/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"location\":\"Durham UK\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("Forecast run started"));
    }

    private ForecastEvaluationEntity buildEntity(LocationEntity location, LocalDate targetDate) {
        return ForecastEvaluationEntity.builder()
                .id(1L)
                .location(location)
                .locationLat(BigDecimal.valueOf(54.7753))
                .locationLon(BigDecimal.valueOf(-1.5849))
                .targetDate(targetDate)
                .targetType(TargetType.SUNSET)
                .forecastRunAt(LocalDateTime.of(2026, 2, 20, 12, 0))
                .daysAhead(0)
                .evaluationModel(EvaluationModel.SONNET)
                .fierySkyPotential(72)
                .goldenHourPotential(80)
                .summary("Good colour potential.")
                .solarEventTime(LocalDateTime.of(2026, 2, 20, 16, 45))
                .build();
    }

    /** Builds a slim list DTO (what {@code GET /api/forecast} now returns). */
    private ForecastListDto buildListDto(String locationName, int fierySky, int goldenHour) {
        return new ForecastListDto(
                1L, locationName, null, null,
                LocalDate.of(2026, 2, 20), TargetType.SUNSET,
                null, LocalDateTime.of(2026, 2, 20, 16, 45), null,
                4, fierySky, goldenHour, null, null, null,
                null, null, null, null, null, null);
    }

    @SuppressWarnings("all")
    private ForecastEvaluationDto buildDto(String locationName, int fierySky, int goldenHour) {
        // id, locationName, lat, lon, targetDate, targetType, forecastRunAt, daysAhead,
        // rating, fierySky, goldenHour, summary, solarEventTime, azimuthDeg, evaluationModel,
        // lowCloud, midCloud, highCloud, visibility, windSpeed, windDirection, precipitation,
        // humidity, weatherCode, boundaryLayerHeight, shortwaveRadiation, pm25, dust, aod,
        // temperatureCelsius, apparentTemp, precipProb, dewPoint,
        // tideState, nextHighTideTime, nextHighTideHeight, nextLowTideTime, nextLowTideHeight,
        // tideAligned, solarLowCloud, solarMidCloud, solarHighCloud,
        // antisolarLowCloud, antisolarMidCloud, antisolarHighCloud,
        // solarTrendEventLow, solarTrendEarliestLow, solarTrendBuilding,
        // upwindCurrentLow, upwindEventLow, upwindDistanceKm,
        // surgeTotalMetres, surgePressureMetres, surgeWindMetres, surgeRiskLevel,
        // surgeAdjustedRange, surgeAstronomicalRange
        return new ForecastEvaluationDto(
                1L, locationName,
                BigDecimal.valueOf(54.7753), BigDecimal.valueOf(-1.5849),
                LocalDate.of(2026, 2, 20), TargetType.SUNSET,
                LocalDateTime.of(2026, 2, 20, 12, 0), 0,
                null, fierySky, goldenHour, "Good colour potential.",
                LocalDateTime.of(2026, 2, 20, 16, 45), null,
                EvaluationModel.SONNET,
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null, null,
                null, null,
                null, null, null, null, null, null,
                null, null,
                null, null, null, null, null, null, null,
                null, null, null, null, null);
    }
}
