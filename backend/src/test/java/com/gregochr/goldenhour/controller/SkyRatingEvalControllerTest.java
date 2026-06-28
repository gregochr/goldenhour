package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.SkyRatingEvalRunEntity;
import com.gregochr.goldenhour.entity.SkyRatingEvalStatus;
import com.gregochr.goldenhour.entity.SkyRatingEvalTrigger;
import com.gregochr.goldenhour.model.SkyRatingEvalTrendPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    @DisplayName("GET /trend returns the drift series for ADMIN")
    @WithMockUser(roles = "ADMIN")
    void trendReturnsSeries() throws Exception {
        SkyRatingEvalTrendPoint point = new SkyRatingEvalTrendPoint(
                7L, LocalDateTime.of(2026, 6, 28, 3, 0), EvaluationModel.SONNET, "abc1234",
                "angel-of-the-north-2mar-spectacular", 4, 5, 4.0, 55.0, 60.0, 8, 8);
        when(skyRatingEvalService.trend()).thenReturn(List.of(point));

        mockMvc.perform(get("/api/admin/sky-rating-eval/trend"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fixtureName").value("angel-of-the-north-2mar-spectacular"))
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
