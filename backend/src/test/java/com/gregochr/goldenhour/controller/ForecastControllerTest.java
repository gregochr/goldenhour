package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.ForecastEvaluationDto;
import com.gregochr.goldenhour.model.ForecastListDto;
import com.gregochr.goldenhour.model.LocationEvaluationView;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.service.FailedSlotRetryService;
import com.gregochr.goldenhour.service.ForecastCommandFactory;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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

    /**
     * A synchronous stand-in for the application's real, asynchronous {@code forecastExecutor}.
     *
     * <p>The controller starts every run with {@code CompletableFuture.runAsync(..., forecastExecutor)}.
     * With the real virtual-thread executor, a run started by one test could execute inside the
     * NEXT test (the lambda reaching the mocked {@code ForecastCommandExecutor} after that test's
     * mocks had been reset), which is how a {@code verify(..., never()).execute(...)} in a later test
     * once failed in CI. Running every started run inline, on the request thread, makes it finish
     * within its own test. The bean keeps its name, so the controller and the tide-refresh tests
     * (which pass this same instance to {@code startTideRefresh}) still see "the forecast executor".
     */
    @MockitoBean(name = "forecastExecutor")
    private Executor forecastExecutor;

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
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(forecastExecutor).execute(any(Runnable.class));
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
                null, null, null, null, DisplayVerdict.WORTH_IT,
                null, null, null, null);
        when(evaluationViewService.cachedOnlyViewsForDateRange(
                any(LocalDate.class), any(LocalDate.class), any(), any(), any()))
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
    @DisplayName("GET /api/forecast retracts a forecast_evaluation row superseded by a newer "
            + "nightly stability skip for its slot")
    void getForecasts_retractsRowSupersededByNewerStabilitySkip() throws Exception {
        // A RATED row — a stability skip retracts an opinion; see emptyRowRetractedWhenSkipStands
        // below for the sibling case where the row itself says nothing but the slot is STILL
        // retracted (no live evidence anywhere), and emptyRowWithNoSkipSurvives for the case where
        // no skip is recorded at all and the row is unconditionally kept. buildEntity's
        // forecastRunAt is 2026-02-20T12:00 (winter — GMT, so 12:00Z).
        ForecastEvaluationEntity entity = buildEntity(DURHAM, LocalDate.of(2026, 2, 20));
        entity.setRating(4);
        when(forecastEvaluationRepository
                .findLatestRunPerSlotByLocationIds(any(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(entity));
        // A later nightly cycle looked at this exact slot again and declined to re-score it — the
        // skip landed strictly after the row this test seeded, so the row is stale evidence and
        // must not reach the client, exactly as EvaluationViewService retracts it elsewhere.
        when(evaluationViewService.loadStabilitySkips(any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(java.util.Map.of("Durham UK|2026-02-20|SUNSET",
                        Instant.parse("2026-02-20T13:00:00Z")));
        when(dtoMapper.toListDtoList(any(), anyBoolean())).thenReturn(List.of());
        when(evaluationViewService.cachedOnlyViewsForDateRange(
                any(LocalDate.class), any(LocalDate.class), any(), any(), any()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/forecast"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ForecastEvaluationEntity>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(dtoMapper).toListDtoList(captor.capture(), anyBoolean());
        assertThat(captor.getValue())
                .as("the stale row must not reach the DTO mapper at all")
                .isEmpty();
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/forecast keeps a forecast_evaluation row written AFTER its slot's most "
            + "recent stability skip")
    void getForecasts_keepsRowNewerThanStabilitySkip() throws Exception {
        // Same slot, same skip — but this time the row postdates it (a later real evaluation).
        ForecastEvaluationEntity entity = buildEntity(DURHAM, LocalDate.of(2026, 2, 20));
        entity.setRating(4);
        when(forecastEvaluationRepository
                .findLatestRunPerSlotByLocationIds(any(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(entity));
        when(evaluationViewService.loadStabilitySkips(any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(java.util.Map.of("Durham UK|2026-02-20|SUNSET",
                        Instant.parse("2026-02-20T11:00:00Z")));
        when(dtoMapper.toListDtoList(any(), anyBoolean()))
                .thenReturn(List.of(buildListDto("Durham UK", 72, 80)));
        when(evaluationViewService.cachedOnlyViewsForDateRange(
                any(LocalDate.class), any(LocalDate.class), any(), any(), any()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/forecast"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].locationName").value("Durham UK"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ForecastEvaluationEntity>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(dtoMapper).toListDtoList(captor.capture(), anyBoolean());
        assertThat(captor.getValue()).containsExactly(entity);
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/forecast: a row with neither a rating nor a triage reason IS retracted "
            + "when a skip stands against its slot — a second Codex re-review of #940's fix, "
            + "reversing this test's own previous claim")
    void emptyRowRetractedWhenSkipStands() throws Exception {
        // buildEntity's fixture carries no rating and no triage — exactly the "nothing to say"
        // shape hasSomethingToSay exists to name, and exactly the shape an ABANDONED (or otherwise
        // closed-out-empty) PENDING row takes. This row genuinely POSTDATES the skip below (its
        // forecastRunAt is 2026-02-20T12:00Z, the skip 13:00Z), so per-source staleness alone would
        // call it "not stale" and serve it as an ordinary unscored slot — the exact gap
        // EvaluationViewService.isSlotRetracted exists to close: with a skip recorded and no live
        // evidence anywhere (no cache here at all, and this row says nothing), the slot must read
        // as retracted regardless of whether the row postdates the skip.
        ForecastEvaluationEntity entity = buildEntity(DURHAM, LocalDate.of(2026, 2, 20));
        when(forecastEvaluationRepository
                .findLatestRunPerSlotByLocationIds(any(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(entity));
        when(evaluationViewService.loadStabilitySkips(any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(java.util.Map.of("Durham UK|2026-02-20|SUNSET",
                        Instant.parse("2026-02-20T13:00:00Z")));
        when(dtoMapper.toListDtoList(any(), anyBoolean())).thenReturn(List.of());
        when(evaluationViewService.cachedOnlyViewsForDateRange(
                any(LocalDate.class), any(LocalDate.class), any(), any(), any()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/forecast"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ForecastEvaluationEntity>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(dtoMapper).toListDtoList(captor.capture(), anyBoolean());
        assertThat(captor.getValue())
                .as("no live evidence survives the skip, so the slot must not reach the DTO mapper")
                .isEmpty();
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/forecast: an ordinary unscored row with NO skip recorded is always kept "
            + "— isSlotRetracted is unaffected when there is no skip to apply")
    void emptyRowWithNoSkipSurvives() throws Exception {
        // The overwhelming majority of forecast_evaluation carries a null rating and no skip at
        // all — an ordinary base-forecast row nobody has evaluated yet. This must be entirely
        // unaffected by the fix above: isSlotRetracted returns false immediately whenever no skip
        // is recorded, regardless of whether the row has anything to say.
        ForecastEvaluationEntity entity = buildEntity(DURHAM, LocalDate.of(2026, 2, 20));
        when(forecastEvaluationRepository
                .findLatestRunPerSlotByLocationIds(any(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(entity));
        when(evaluationViewService.loadStabilitySkips(any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(java.util.Map.of());
        when(dtoMapper.toListDtoList(any(), anyBoolean()))
                .thenReturn(List.of(buildListDto("Durham UK", 72, 80)));
        when(evaluationViewService.cachedOnlyViewsForDateRange(
                any(LocalDate.class), any(LocalDate.class), any(), any(), any()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/forecast"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].locationName").value("Durham UK"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ForecastEvaluationEntity>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(dtoMapper).toListDtoList(captor.capture(), anyBoolean());
        assertThat(captor.getValue()).containsExactly(entity);
    }

    @Test
    @WithMockUser
    @DisplayName("Codex #940: a row run at 01:30 (naive UTC wall clock) survives a 01:00Z skip on a "
            + "BST date — this is the line Codex anchored the review on")
    void codex940_rowAt0130SurvivesA0100SkipOnBstDate() throws Exception {
        // EvaluationViewService.forecastRunInstant used to zone this naive value as Europe/London,
        // reading 01:30 as 00:30Z during BST — BEFORE the skip — and wrongly dropping a row the
        // pipeline had not, in fact, decided against. forecast_run_at is a naive UTC wall clock, so
        // 01:30 IS 01:30Z; the row must survive and be mapped normally.
        ForecastEvaluationEntity entity = buildEntity(DURHAM, LocalDate.of(2026, 4, 22));
        entity.setRating(4);
        entity.setForecastRunAt(LocalDateTime.of(2026, 4, 22, 1, 30));
        when(forecastEvaluationRepository
                .findLatestRunPerSlotByLocationIds(any(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(entity));
        when(evaluationViewService.loadStabilitySkips(any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(java.util.Map.of("Durham UK|2026-04-22|SUNSET",
                        Instant.parse("2026-04-22T01:00:00Z")));
        when(dtoMapper.toListDtoList(any(), anyBoolean()))
                .thenReturn(List.of(buildListDto("Durham UK", 72, 80)));
        when(evaluationViewService.cachedOnlyViewsForDateRange(
                any(LocalDate.class), any(LocalDate.class), any(), any(), any()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/forecast"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].locationName").value("Durham UK"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ForecastEvaluationEntity>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(dtoMapper).toListDtoList(captor.capture(), anyBoolean());
        assertThat(captor.getValue()).containsExactly(entity);
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

        // The window is anchored on the UK civil date. This expectation cannot prove that — it is
        // the same expression the controller evaluates, against the same real clock this shared
        // @SpringBootTest context injects, so both sides move together under any change of anchor.
        // What it pins is the window's *width*; ForecastWindowAnchorTest pins the anchor itself,
        // on a fixed clock, which is the only way to make that assertion capable of failing.
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
                3, "stale", 50, 60, null, null, null, null, DisplayVerdict.MAYBE,
                null, null, null, null);
        when(evaluationViewService.cachedOnlyViewsForDateRange(
                any(LocalDate.class), any(LocalDate.class), any(), any(), any()))
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
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET /api/forecast/history returns 200 for a valid date range with location filter")
    void getHistory_validRange_returnsEvaluations() throws Exception {
        ForecastEvaluationEntity entity = buildEntity(DURHAM, LocalDate.of(2026, 1, 15));
        when(forecastEvaluationRepository
                .findByLocationIdInAndTargetDateBetween(
                        eq(List.of(1L)), any(LocalDate.class), any(LocalDate.class)))
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
    @WithMockUser(roles = {"ADMIN"})
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
    @DisplayName("GET /api/forecast/history accepts a range exactly at the span cap")
    void getHistory_spanAtCap_returns200() throws Exception {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = from.plusDays(ForecastController.MAX_HISTORY_SPAN_DAYS - 1);
        when(forecastEvaluationRepository
                .findByLocationIdInAndTargetDateBetween(
                        eq(List.of(1L)), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of());
        when(dtoMapper.toDtoList(any(), anyBoolean())).thenReturn(List.of());

        mockMvc.perform(get("/api/forecast/history")
                        .param("from", from.toString())
                        .param("to", to.toString())
                        .param("location", "Durham UK"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET /api/forecast/history rejects a range one day over the span cap")
    void getHistory_spanOverCap_returns400() throws Exception {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = from.plusDays(ForecastController.MAX_HISTORY_SPAN_DAYS);

        mockMvc.perform(get("/api/forecast/history")
                        .param("from", from.toString())
                        .param("to", to.toString())
                        .param("location", "Durham UK"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/forecast/run as ADMIN returns 202 Accepted")
    void runForecast_asAdmin_noBody_returns202() throws Exception {
        com.gregochr.goldenhour.service.ForecastCommand command =
                new com.gregochr.goldenhour.service.ForecastCommand(
                        com.gregochr.goldenhour.entity.RunType.SHORT_TERM,
                        List.of(), null, null, true);
        when(commandFactory.create(eq(com.gregochr.goldenhour.entity.RunType.SHORT_TERM),
                eq(true), eq(List.of(DURHAM)), any(), eq(java.util.Set.of())))
                .thenReturn(command);

        mockMvc.perform(post("/api/forecast/run"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("Forecast run started"))
                .andExpect(jsonPath("$.runType").value("SHORT_TERM"));

        // The run executes inline now, so the started run is asserted rather than assumed.
        ArgumentCaptor<JobRunEntity> jobRun = ArgumentCaptor.forClass(JobRunEntity.class);
        verify(forecastCommandExecutor).execute(eq(command), jobRun.capture());
        assertThat(jobRun.getValue().getId()).isEqualTo(1L);
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
    @WithMockUser(roles = {"ADMIN"})
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
    @WithMockUser(roles = {"ADMIN"})
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
    @WithMockUser(roles = {"ADMIN"})
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
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET /api/forecast/compare returns 400 when required params are missing")
    void getCompare_missingParams_returns400() throws Exception {
        mockMvc.perform(get("/api/forecast/compare")
                        .param("location", "Durham UK"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = {"LITE_USER"})
    @DisplayName("GET /api/forecast/history as LITE_USER returns 403 without touching the repository")
    void getHistory_asLiteUser_returns403() throws Exception {
        mockMvc.perform(get("/api/forecast/history")
                        .param("from", "2026-01-01")
                        .param("to", "2026-01-31"))
                .andExpect(status().isForbidden());

        // The gate exists partly because the query is expensive (up to a year of rows across
        // every enabled location) — a denied caller must not reach it.
        verify(forecastEvaluationRepository, never())
                .findByLocationIdInAndTargetDateBetween(any(), any(LocalDate.class), any(LocalDate.class));
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("GET /api/forecast/history as PRO_USER returns 403 (admin backtesting surface)")
    void getHistory_asProUser_returns403() throws Exception {
        mockMvc.perform(get("/api/forecast/history")
                        .param("from", "2026-01-01")
                        .param("to", "2026-01-31"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"LITE_USER"})
    @DisplayName("GET /api/forecast/compare as LITE_USER returns 403 without touching the repository")
    void getCompare_asLiteUser_returns403() throws Exception {
        mockMvc.perform(get("/api/forecast/compare")
                        .param("location", "Durham UK")
                        .param("date", "2026-02-28")
                        .param("targetType", "SUNSET"))
                .andExpect(status().isForbidden());

        verify(forecastEvaluationRepository, never())
                .findByLocationIdAndTargetDateAndTargetTypeOrderByForecastRunAtAsc(
                        anyLong(), any(LocalDate.class), any(TargetType.class));
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("GET /api/forecast/compare as PRO_USER returns 403 (admin backtesting surface)")
    void getCompare_asProUser_returns403() throws Exception {
        mockMvc.perform(get("/api/forecast/compare")
                        .param("location", "Durham UK")
                        .param("date", "2026-02-28")
                        .param("targetType", "SUNSET"))
                .andExpect(status().isForbidden());
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
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET /api/forecast/history returns 200 for all locations when no location filter is given")
    void getHistory_validRange_noLocation_returnsEvaluationsForAllLocations() throws Exception {
        ForecastEvaluationEntity entity = buildEntity(DURHAM, LocalDate.of(2026, 1, 15));
        when(forecastEvaluationRepository
                .findByLocationIdInAndTargetDateBetween(
                        eq(List.of(1L)), any(LocalDate.class), any(LocalDate.class)))
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
    @DisplayName("GET /api/forecast/history with no location filter queries the repository exactly "
            + "once, regardless of how many locations are enabled")
    void getHistory_noLocation_queriesRepositoryExactlyOnce() throws Exception {
        LocationEntity edinburgh = LocationEntity.builder()
                .id(2L).name("Edinburgh UK").lat(55.9533).lon(-3.1883).build();
        LocationEntity keswick = LocationEntity.builder()
                .id(3L).name("Keswick UK").lat(54.6013).lon(-3.1359).build();
        when(locationService.findAllEnabled()).thenReturn(List.of(DURHAM, edinburgh, keswick));
        when(forecastEvaluationRepository
                .findByLocationIdInAndTargetDateBetween(
                        eq(List.of(1L, 2L, 3L)), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of());
        when(dtoMapper.toDtoList(any(), anyBoolean())).thenReturn(List.of());

        mockMvc.perform(get("/api/forecast/history")
                        .param("from", "2026-01-01")
                        .param("to", "2026-01-31"))
                .andExpect(status().isOk());

        // The old per-location loop would have called the repository three times (once per
        // enabled location); the fix calls it exactly once with the full id list.
        verify(forecastEvaluationRepository, times(1))
                .findByLocationIdInAndTargetDateBetween(any(), any(LocalDate.class), any(LocalDate.class));
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
        when(scheduledForecastService.startTideRefresh(forecastExecutor)).thenReturn(true);

        mockMvc.perform(post("/api/forecast/run/tide"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("Tide refresh started"))
                .andExpect(jsonPath("$.runType").value("TIDE"));

        verify(scheduledForecastService).startTideRefresh(forecastExecutor);
    }

    /**
     * A tide refresh already running — from the schedule, Run Now or an earlier press — must be
     * reported as such, not as "started": the admin screen shows this status verbatim.
     */
    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/forecast/run/tide returns 409 while a tide refresh is already running")
    void refreshTideData_whileRunning_returns409() throws Exception {
        when(scheduledForecastService.startTideRefresh(forecastExecutor)).thenReturn(false);

        mockMvc.perform(post("/api/forecast/run/tide"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("A tide refresh is already running"))
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
    @DisplayName("POST /api/forecast/run/{runId}/retry-failed returns 404 with no body when there is nothing to retry")
    void retryFailed_nothingToRetry_returns404() throws Exception {
        when(failedSlotRetryService.retry(99L)).thenReturn(new FailedSlotRetryService.NothingToRetry());

        mockMvc.perform(post("/api/forecast/run/99/retry-failed"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(""));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/forecast/run/{runId}/retry-failed returns 202 with the new run, the original run "
            + "type, how many slots it was given and which failed slots were left out")
    void retryFailed_started_returns202WithSlotsAndSkipped() throws Exception {
        when(failedSlotRetryService.retry(1L)).thenReturn(new FailedSlotRetryService.Started(
                8L, com.gregochr.goldenhour.entity.RunType.VERY_SHORT_TERM, 2,
                List.of(new FailedSlotRetryService.SkippedSlot("Bamburgh", "2026-10-04", "SUNRISE",
                        "The place is disabled or no longer exists."))));

        mockMvc.perform(post("/api/forecast/run/1/retry-failed"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("Retry run started"))
                .andExpect(jsonPath("$.runType").value("VERY_SHORT_TERM"))
                .andExpect(jsonPath("$.jobRunId").value(8))
                .andExpect(jsonPath("$.slots").value(2))
                .andExpect(jsonPath("$.skipped[0].locationName").value("Bamburgh"))
                .andExpect(jsonPath("$.skipped[0].date").value("2026-10-04"))
                .andExpect(jsonPath("$.skipped[0].targetType").value("SUNRISE"))
                .andExpect(jsonPath("$.skipped[0].reason").value("The place is disabled or no longer exists."));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/forecast/run/{runId}/retry-failed returns 409 with a plain {error} sentence "
            + "when the run's failures cannot be retried")
    void retryFailed_refused_returns409WithError() throws Exception {
        when(failedSlotRetryService.retry(1L)).thenReturn(new FailedSlotRetryService.Refused(
                "Light-pollution failures are retried by pressing Refresh Light Pollution again."));

        mockMvc.perform(post("/api/forecast/run/1/retry-failed"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error")
                        .value("Light-pollution failures are retried by pressing Refresh Light Pollution again."));
    }

    @Test
    @WithMockUser(roles = {"LITE_USER"})
    @DisplayName("POST /api/forecast/run/{runId}/retry-failed is forbidden for LITE_USER and starts nothing")
    void retryFailed_liteUser_returns403() throws Exception {
        mockMvc.perform(post("/api/forecast/run/1/retry-failed"))
                .andExpect(status().isForbidden());

        verify(failedSlotRetryService, never()).retry(anyLong());
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("POST /api/forecast/run/{runId}/retry-failed is forbidden for PRO_USER and starts nothing")
    void retryFailed_proUser_returns403() throws Exception {
        mockMvc.perform(post("/api/forecast/run/1/retry-failed"))
                .andExpect(status().isForbidden());

        verify(failedSlotRetryService, never()).retry(anyLong());
    }

    @Test
    @DisplayName("POST /api/forecast/run/{runId}/retry-failed is rejected for an anonymous caller and starts nothing")
    void retryFailed_anonymous_isRejected() throws Exception {
        mockMvc.perform(post("/api/forecast/run/1/retry-failed"))
                .andExpect(status().isUnauthorized());

        verify(failedSlotRetryService, never()).retry(anyLong());
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

    private static LocationEntity hide() {
        return LocationEntity.builder().id(2L).name("Reserve Hide").lat(55.1).lon(-1.6)
                .locationType(java.util.Set.of(com.gregochr.goldenhour.entity.LocationType.WILDLIFE))
                .build();
    }

    private static LocationEntity landscape() {
        return LocationEntity.builder().id(3L).name("Hadrian's Wall").lat(55.0).lon(-2.3)
                .locationType(java.util.Set.of(com.gregochr.goldenhour.entity.LocationType.LANDSCAPE))
                .build();
    }

    private static LocationEntity landscapeTwo() {
        return LocationEntity.builder().id(5L).name("Hadrian's Wall East").lat(55.0).lon(-2.1)
                .locationType(java.util.Set.of(com.gregochr.goldenhour.entity.LocationType.LANDSCAPE))
                .build();
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/forecast/run naming a wildlife-only location returns 400 and starts no run")
    void runForecast_namedWildlifeOnlyLocation_returns400AndStartsNothing() throws Exception {
        when(locationService.findAllEnabled()).thenReturn(List.of(DURHAM, hide()));

        mockMvc.perform(post("/api/forecast/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"location\":\"Reserve Hide\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "'Reserve Hide' is not a sky location: it has no sunrise or sunset forecast"));

        verify(jobRunService, never()).startRun(any(), anyBoolean(), any(), any());
        verify(commandFactory, never()).create(any(), anyBoolean(), any(), any(), any());
        verify(forecastCommandExecutor, never()).execute(any(), any());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/forecast/run naming a woodland-only location returns 400 and starts no run")
    void runForecast_namedWoodlandOnlyLocation_returns400AndStartsNothing() throws Exception {
        LocationEntity wood = LocationEntity.builder().id(4L).name("Canopy Wood").lat(55.2).lon(-1.9)
                .locationType(java.util.Set.of(com.gregochr.goldenhour.entity.LocationType.WOODLAND))
                .build();
        when(locationService.findAllEnabled()).thenReturn(List.of(DURHAM, wood));

        mockMvc.perform(post("/api/forecast/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"location\":\"Canopy Wood\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        "'Canopy Wood' is not a sky location: it has no sunrise or sunset forecast"));

        verify(jobRunService, never()).startRun(any(), anyBoolean(), any(), any());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/forecast/run naming a sky location still starts a run for exactly that place")
    void runForecast_namedSkyLocation_startsRunForThatPlace() throws Exception {
        LocationEntity wall = landscape();
        when(locationService.findAllEnabled()).thenReturn(List.of(DURHAM, hide(), wall));

        mockMvc.perform(post("/api/forecast/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"location\":\"Hadrian's Wall\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("Forecast run started"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LocationEntity>> locations = ArgumentCaptor.forClass(List.class);
        verify(commandFactory).create(eq(com.gregochr.goldenhour.entity.RunType.SHORT_TERM), eq(true),
                locations.capture(), any(), any());
        assertThat(locations.getValue()).containsExactly(wall);
        verify(jobRunService, times(1)).startRun(any(), anyBoolean(), any(), any());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/forecast/run for all locations offers only the sky ones "
            + "(the executor filters again as the enforcement point)")
    void runForecast_allLocations_offersOnlySkyLocations() throws Exception {
        when(locationService.findAllEnabled()).thenReturn(List.of(DURHAM, hide()));

        mockMvc.perform(post("/api/forecast/run"))
                .andExpect(status().isAccepted());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LocationEntity>> locations = ArgumentCaptor.forClass(List.class);
        verify(commandFactory).create(eq(com.gregochr.goldenhour.entity.RunType.SHORT_TERM), eq(true),
                locations.capture(), any(), any());
        assertThat(locations.getValue()).containsExactly(DURHAM);
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/forecast/run maxLocations=2 caps the sky list, not the whole roster")
    void runForecast_maxLocations_countsOnlySkyLocations() throws Exception {
        LocationEntity wall = landscape();
        LocationEntity wood = LocationEntity.builder().id(4L).name("Canopy Wood").lat(55.2).lon(-1.9)
                .locationType(java.util.Set.of(com.gregochr.goldenhour.entity.LocationType.WOODLAND))
                .build();
        // The first two entries are not sky subjects: a cap applied to the whole roster would
        // hand the command none of the sky places at all.
        when(locationService.findAllEnabled())
                .thenReturn(List.of(hide(), wood, DURHAM, wall, landscapeTwo()));

        mockMvc.perform(post("/api/forecast/run").param("maxLocations", "2"))
                .andExpect(status().isAccepted());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LocationEntity>> locations = ArgumentCaptor.forClass(List.class);
        verify(commandFactory).create(eq(com.gregochr.goldenhour.entity.RunType.SHORT_TERM), eq(true),
                locations.capture(), any(), any());
        assertThat(locations.getValue()).containsExactly(DURHAM, wall);
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/forecast/run for all locations returns 400 and starts no run "
            + "when no enabled location is a sky subject")
    void runForecast_noSkyLocationEnabled_returns400AndStartsNothing() throws Exception {
        when(locationService.findAllEnabled()).thenReturn(List.of(hide()));

        mockMvc.perform(post("/api/forecast/run"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("No enabled sky locations: there is nothing to run"));

        verify(jobRunService, never()).startRun(any(), anyBoolean(), any(), any());
        verify(commandFactory, never()).create(any(), anyBoolean(), any(), any(), any());
        verify(forecastCommandExecutor, never()).execute(any(), any());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/forecast/run returns 400 and starts no run when no location is enabled at all")
    void runForecast_noLocationsEnabled_returns400AndStartsNothing() throws Exception {
        when(locationService.findAllEnabled()).thenReturn(List.of());

        mockMvc.perform(post("/api/forecast/run"))
                .andExpect(status().isBadRequest());

        verify(jobRunService, never()).startRun(any(), anyBoolean(), any(), any());
        verify(forecastCommandExecutor, never()).execute(any(), any());
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
