package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.SkyRatingEvalRunEntity;
import com.gregochr.goldenhour.entity.SkyRatingEvalStatus;
import com.gregochr.goldenhour.entity.SkyRatingEvalTrigger;
import com.gregochr.goldenhour.model.ScheduledModelView;
import com.gregochr.goldenhour.model.SkyRatingEvalTrendPoint;
import com.gregochr.goldenhour.service.SkyRatingEvalService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link SkyRatingEvalController} — 202 async run trigger, the read endpoints,
 * and ADMIN gating.
 */
class SkyRatingEvalControllerTest extends AbstractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("POST /run returns 202 and defaults to Sonnet x8 for ADMIN")
    @WithMockUser(roles = "ADMIN")
    void runReturns202AndDefaultsToSonnetEight() throws Exception {
        SkyRatingEvalRunEntity run = SkyRatingEvalRunEntity.builder()
                .id(7L)
                .runTimestamp(LocalDateTime.of(2026, 6, 28, 3, 0))
                .startedAt(LocalDateTime.of(2026, 6, 28, 3, 0))
                .model(EvaluationModel.SONNET)
                .runsPerFixture(8)
                .triggerSource(SkyRatingEvalTrigger.MANUAL)
                .status(SkyRatingEvalStatus.RUNNING)
                .build();
        when(skyRatingEvalService.startRun(EvaluationModel.SONNET, SkyRatingEvalTrigger.MANUAL, 8))
                .thenReturn(run);

        mockMvc.perform(post("/api/admin/sky-rating-eval/run"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.model").value("SONNET"));

        verify(skyRatingEvalService).startRun(EvaluationModel.SONNET, SkyRatingEvalTrigger.MANUAL, 8);
    }

    @Test
    @DisplayName("POST /run honours an explicit model and runsPerFixture")
    @WithMockUser(roles = "ADMIN")
    void runHonoursExplicitParams() throws Exception {
        SkyRatingEvalRunEntity run = SkyRatingEvalRunEntity.builder()
                .id(8L).runTimestamp(LocalDateTime.now()).startedAt(LocalDateTime.now())
                .model(EvaluationModel.HAIKU).runsPerFixture(4)
                .triggerSource(SkyRatingEvalTrigger.MANUAL).status(SkyRatingEvalStatus.RUNNING)
                .build();
        when(skyRatingEvalService.startRun(EvaluationModel.HAIKU, SkyRatingEvalTrigger.MANUAL, 4))
                .thenReturn(run);

        mockMvc.perform(post("/api/admin/sky-rating-eval/run?model=HAIKU&runsPerFixture=4"))
                .andExpect(status().isAccepted());

        verify(skyRatingEvalService).startRun(EvaluationModel.HAIKU, SkyRatingEvalTrigger.MANUAL, 4);
    }

    @Test
    @DisplayName("POST /run accepts a Haiku 5.5 run")
    @WithMockUser(roles = "ADMIN")
    void runAcceptsHaiku55() throws Exception {
        SkyRatingEvalRunEntity run = SkyRatingEvalRunEntity.builder()
                .id(9L).runTimestamp(LocalDateTime.now()).startedAt(LocalDateTime.now())
                .model(EvaluationModel.HAIKU_55).runsPerFixture(8)
                .triggerSource(SkyRatingEvalTrigger.MANUAL).status(SkyRatingEvalStatus.RUNNING)
                .build();
        when(skyRatingEvalService.startRun(EvaluationModel.HAIKU_55, SkyRatingEvalTrigger.MANUAL, 8))
                .thenReturn(run);

        mockMvc.perform(post("/api/admin/sky-rating-eval/run?model=HAIKU_55"))
                .andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("POST /run refuses a non-selectable model with 400")
    @WithMockUser(roles = "ADMIN")
    void runRefusesNonSelectableModels() throws Exception {
        for (EvaluationModel model : List.of(
                EvaluationModel.WILDLIFE, EvaluationModel.SONNET_ET, EvaluationModel.OPUS_ET)) {
            mockMvc.perform(post("/api/admin/sky-rating-eval/run?model=" + model.name()))
                    .andExpect(status().isBadRequest());
            verify(skyRatingEvalService, org.mockito.Mockito.never()).startRun(
                    model, SkyRatingEvalTrigger.MANUAL, SkyRatingEvalService.DEFAULT_RUNS_PER_FIXTURE);
        }
        org.mockito.Mockito.verifyNoMoreInteractions(skyRatingEvalService);
    }

    @Test
    @DisplayName("GET /schedule returns one row per selectable model for ADMIN")
    @WithMockUser(roles = "ADMIN")
    void scheduleReturnsRows() throws Exception {
        when(skyRatingEvalService.schedule()).thenReturn(List.of(
                new ScheduledModelView(EvaluationModel.HAIKU, true),
                new ScheduledModelView(EvaluationModel.OPUS, false)));

        mockMvc.perform(get("/api/admin/sky-rating-eval/schedule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].model").value("HAIKU"))
                .andExpect(jsonPath("$[0].scheduled").value(true))
                .andExpect(jsonPath("$[1].model").value("OPUS"))
                .andExpect(jsonPath("$[1].scheduled").value(false));
    }

    @Test
    @DisplayName("PUT /schedule/{model} switches a model and returns only its row")
    @WithMockUser(roles = "ADMIN")
    void putScheduleReturnsToggledRow() throws Exception {
        when(skyRatingEvalService.setScheduled(EvaluationModel.OPUS, false))
                .thenReturn(new ScheduledModelView(EvaluationModel.OPUS, false));

        mockMvc.perform(put("/api/admin/sky-rating-eval/schedule/OPUS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("OPUS"))
                .andExpect(jsonPath("$.scheduled").value(false));

        verify(skyRatingEvalService).setScheduled(EvaluationModel.OPUS, false);
    }

    @Test
    @DisplayName("PUT /schedule/{model} is 400 for an unknown model name")
    @WithMockUser(roles = "ADMIN")
    void putScheduleUnknownModelIs400() throws Exception {
        mockMvc.perform(put("/api/admin/sky-rating-eval/schedule/BOGUS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduled\":false}"))
                .andExpect(status().isBadRequest());

        verifyNoMoreInteractions(skyRatingEvalService);
    }

    @Test
    @DisplayName("PUT /schedule/{model} is 400 when the service rejects a non-selectable model")
    @WithMockUser(roles = "ADMIN")
    void putScheduleNonSelectableIs400() throws Exception {
        when(skyRatingEvalService.setScheduled(EvaluationModel.WILDLIFE, false))
                .thenThrow(new IllegalArgumentException("not selectable"));

        mockMvc.perform(put("/api/admin/sky-rating-eval/schedule/WILDLIFE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduled\":false}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT /schedule/{model} is 400 when the body has no scheduled field")
    @WithMockUser(roles = "ADMIN")
    void putScheduleMissingFieldIs400() throws Exception {
        mockMvc.perform(put("/api/admin/sky-rating-eval/schedule/OPUS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verifyNoMoreInteractions(skyRatingEvalService);
    }

    @Test
    @DisplayName("non-ADMIN is forbidden from reading or changing the schedule")
    @WithMockUser(roles = "PRO_USER")
    void scheduleForbiddenForNonAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/sky-rating-eval/schedule"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/admin/sky-rating-eval/schedule/OPUS")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scheduled\":false}"))
                .andExpect(status().isForbidden());

        verifyNoMoreInteractions(skyRatingEvalService);
    }

    @Test
    @DisplayName("GET /trend returns the drift series for ADMIN")
    @WithMockUser(roles = "ADMIN")
    void trendReturnsSeries() throws Exception {
        SkyRatingEvalTrendPoint point = new SkyRatingEvalTrendPoint(
                7L, LocalDateTime.of(2026, 6, 28, 3, 0), EvaluationModel.SONNET, "abc1234",
                "st-marys-10mar-moderate", 4, 4, 4.0, 55.0, 60.0, 8, 8);
        when(skyRatingEvalService.trend()).thenReturn(List.of(point));

        mockMvc.perform(get("/api/admin/sky-rating-eval/trend"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fixtureName").value("st-marys-10mar-moderate"))
                .andExpect(jsonPath("$[0].expectedMin").value(4))
                .andExpect(jsonPath("$[0].avgRating").value(4.0))
                .andExpect(jsonPath("$[0].passes").value(8));
    }

    @Test
    @DisplayName("GET /runs/{id} returns a run for ADMIN")
    @WithMockUser(roles = "ADMIN")
    void getRunReturnsRun() throws Exception {
        SkyRatingEvalRunEntity run = SkyRatingEvalRunEntity.builder()
                .id(7L).runTimestamp(LocalDateTime.now()).startedAt(LocalDateTime.now())
                .model(EvaluationModel.SONNET).runsPerFixture(8)
                .triggerSource(SkyRatingEvalTrigger.SCHEDULED).status(SkyRatingEvalStatus.COMPLETED)
                .passRate(1.0).build();
        when(skyRatingEvalService.getRun(7L)).thenReturn(run);

        mockMvc.perform(get("/api/admin/sky-rating-eval/runs/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.passRate").value(1.0));
    }

    @Test
    @DisplayName("non-ADMIN is forbidden from triggering a run")
    @WithMockUser(roles = "PRO_USER")
    void nonAdminForbidden() throws Exception {
        mockMvc.perform(post("/api/admin/sky-rating-eval/run"))
                .andExpect(status().isForbidden());

        verify(skyRatingEvalService, org.mockito.Mockito.never())
                .startRun(eq(EvaluationModel.SONNET), eq(SkyRatingEvalTrigger.MANUAL), org.mockito.Mockito.anyInt());
    }
}
