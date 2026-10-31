package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.ForecastEvaluationDto;
import com.gregochr.goldenhour.model.ForecastListDto;
import com.gregochr.goldenhour.model.LocationEvaluationView;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
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

    /**
     * A synchronous stand-in for the application's real, asynchronous {@code forecastExecutor}.
     *
     * <p>The controller starts the tide backfill with {@code CompletableFuture.runAsync(...,
     * forecastExecutor)}. With the real virtual-thread executor, work started by one test could
     * execute inside the NEXT test, after that test's mocks had been reset. Running it inline, on the
     * request thread, makes it finish within its own test. The bean keeps its name, so the controller
     * and the tide-refresh tests (which pass this same instance to {@code startTideRefresh}) still see
     * "the forecast executor".
     */
    @MockitoBean(name = "forecastExecutor")
    private Executor forecastExecutor;

    private static final LocationEntity DURHAM = LocationEntity.builder()
            .id(1L).name("Durham UK").lat(54.7753).lon(-1.5849).build();

    @BeforeEach
    void setUp() {
        when(locationService.findAllEnabled()).thenReturn(List.of(DURHAM));
        when(locationService.findByName(eq("Durham UK"))).thenReturn(DURHAM);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(forecastExecutor).execute(any(Runnable.class));
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
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
    @WithMockUser(roles = {"PRO_USER"})
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
    @WithMockUser(roles = {"PRO_USER"})
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
    @WithMockUser(roles = {"PRO_USER"})
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
    @WithMockUser(roles = {"PRO_USER"})
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
    @WithMockUser(roles = {"PRO_USER"})
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
    @WithMockUser(roles = {"PRO_USER"})
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
    @WithMockUser(roles = {"PRO_USER"})
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
                .isEqualTo(today.minusDays(ForecastHorizon.SERVE_PAST_DAYS));
        assertThat(toCaptor.getValue())
                .isEqualTo(today.plusDays(RunType.FORECAST_HORIZON_DAYS));
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
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
    @WithMockUser(roles = {"PRO_USER"})
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
    @WithMockUser(roles = {"PRO_USER"})
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
    @WithMockUser(roles = {"PRO_USER"})
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
    @WithMockUser(roles = {"USER"})
    @DisplayName("GET /api/forecast/{id} fails closed: a caller with no PRO_USER or ADMIN role is served as LITE")
    void getForecastDetail_asUnrecognisedRole_failsClosedToLite() throws Exception {
        ForecastEvaluationEntity entity = buildEntity(DURHAM, LocalDate.of(2026, 2, 20));
        when(forecastEvaluationRepository.findById(42L)).thenReturn(Optional.of(entity));
        when(dtoMapper.toDto(entity, true)).thenReturn(buildDto("Durham UK", 50, 55));

        mockMvc.perform(get("/api/forecast/42"))
                .andExpect(status().isOk());

        verify(dtoMapper).toDto(entity, true);
    }

    @Test
    @WithMockUser(roles = {"USER"})
    @DisplayName("GET /api/forecast fails closed: a caller with no PRO_USER or ADMIN role is served as LITE")
    void getForecasts_asUnrecognisedRole_failsClosedToLite() throws Exception {
        // No enabled locations: the repository is never read and the mapper gets the empty list.
        when(dtoMapper.toListDtoList(List.of(), true)).thenReturn(List.of());

        mockMvc.perform(get("/api/forecast"))
                .andExpect(status().isOk());

        verify(dtoMapper).toListDtoList(List.of(), true);
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET /api/forecast/{id} as ADMIN passes isLiteUser=false (the full prose and scores)")
    void getForecastDetail_asAdmin_passesProFlag() throws Exception {
        ForecastEvaluationEntity entity = buildEntity(DURHAM, LocalDate.of(2026, 2, 20));
        when(forecastEvaluationRepository.findById(42L)).thenReturn(Optional.of(entity));
        when(dtoMapper.toDto(entity, false)).thenReturn(buildDto("Durham UK", 50, 55));

        mockMvc.perform(get("/api/forecast/42"))
                .andExpect(status().isOk());

        verify(dtoMapper).toDto(entity, false);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "/api/forecast/run",
            "/api/forecast/run/very-short-term",
            "/api/forecast/run/short-term",
            "/api/forecast/run/long-term",
            "/api/forecast/run/1/retry-failed"})
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("the retired manual-run endpoints are no longer mapped, even for an ADMIN")
    void retiredManualRunEndpoints_areNotMapped(String path) throws Exception {
        mockMvc.perform(post(path))
                .andExpect(status().isNotFound())
                .andExpect(result -> assertThat(result.getResolvedException())
                        .isInstanceOf(NoResourceFoundException.class));
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
    @WithMockUser(roles = {"PRO_USER"})
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
    @WithMockUser(roles = {"PRO_USER"})
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

    // ── GET /api/forecast?date= — one stored past day (outcome-rating plan §2 #4, O2) ────────

    private static LocalDate ukToday() {
        return ForecastHorizon.today(Clock.systemUTC());
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("GET /api/forecast?date= admits yesterday and T-4: 200, and the repository is read for "
            + "exactly that one day")
    void getForecastsForDate_admitsYesterdayAndT4() throws Exception {
        when(forecastEvaluationRepository
                .findLatestRunPerSlotByLocationIds(any(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of());
        when(dtoMapper.toListDtoList(any(), anyBoolean())).thenReturn(List.of());

        for (LocalDate day : List.of(ukToday().minusDays(1),
                ukToday().minusDays(ForecastHorizon.MAX_PAST_DAYS))) {
            mockMvc.perform(get("/api/forecast").param("date", day.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray());
            verify(forecastEvaluationRepository)
                    .findLatestRunPerSlotByLocationIds(any(), eq(day), eq(day));
        }
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("GET /api/forecast?date= refuses today, tomorrow, T-5 and a malformed date with a 400 "
            + "{error} and reads nothing")
    void getForecastsForDate_refusesOutOfWindowDates() throws Exception {
        for (String refused : List.of(ukToday().toString(), ukToday().plusDays(1).toString(),
                ukToday().minusDays(ForecastHorizon.MAX_PAST_DAYS + 1).toString(), "not-a-date",
                "2026-13-45")) {
            mockMvc.perform(get("/api/forecast").param("date", refused))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("date must be between T-4 and yesterday"));
        }

        verify(forecastEvaluationRepository, never())
                .findLatestRunPerSlotByLocationIds(any(), any(LocalDate.class), any(LocalDate.class));
    }

    @Test
    @WithMockUser(roles = {"LITE_USER"})
    @DisplayName("GET /api/forecast?date= as LITE_USER passes isLiteUser=true to the mapper (score gating "
            + "applies to the one-day read too)")
    void getForecastsForDate_asLiteUser_passesLiteFlag() throws Exception {
        when(forecastEvaluationRepository
                .findLatestRunPerSlotByLocationIds(any(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of());
        when(dtoMapper.toListDtoList(any(), eq(true))).thenReturn(List.of());

        mockMvc.perform(get("/api/forecast").param("date", ukToday().minusDays(1).toString()))
                .andExpect(status().isOk());

        verify(dtoMapper).toListDtoList(any(), eq(true));
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("GET /api/forecast?date= never reaches a run, a batch or a model")
    void getForecastsForDate_runsNothing() throws Exception {
        when(forecastEvaluationRepository
                .findLatestRunPerSlotByLocationIds(any(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of());
        when(dtoMapper.toListDtoList(any(), anyBoolean())).thenReturn(List.of());

        mockMvc.perform(get("/api/forecast").param("date", ukToday().minusDays(1).toString()))
                .andExpect(status().isOk());

        verifyNoInteractions(scheduledForecastService, forceSubmitBatchService, scheduledBatchEvaluationService);
    }

    @Test
    @DisplayName("GET /api/forecast?date= without a token is refused (the path is not opened up)")
    void getForecastsForDate_unauthenticated_isRefused() throws Exception {
        mockMvc.perform(get("/api/forecast").param("date", ukToday().minusDays(1).toString()))
                .andExpect(status().isUnauthorized());
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
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("GET /api/forecast/run/notifications is no longer mapped (the run-complete stream was retired)")
    void getRunNotifications_isNoLongerMapped() throws Exception {
        mockMvc.perform(get("/api/forecast/run/notifications"))
                .andExpect(status().isNotFound());
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
                null, null, null, null, null, null, null);
    }
}
