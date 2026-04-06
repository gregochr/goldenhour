package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link BatchAdminController}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BatchAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ForecastBatchRepository batchRepository;

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET /api/admin/batches/recent returns 200 with batch list")
    void getRecentBatches_returnsOk() throws Exception {
        ForecastBatchEntity batch = buildBatch("msgbatch_abc123", BatchType.FORECAST);
        when(batchRepository.findTop20ByOrderBySubmittedAtDesc()).thenReturn(List.of(batch));

        mockMvc.perform(get("/api/admin/batches/recent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].anthropicBatchId").value("msgbatch_abc123"))
                .andExpect(jsonPath("$[0].batchType").value("FORECAST"))
                .andExpect(jsonPath("$[0].status").value("SUBMITTED"));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET /api/admin/batches/recent returns empty list when no batches")
    void getRecentBatches_emptyList() throws Exception {
        when(batchRepository.findTop20ByOrderBySubmittedAtDesc()).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/batches/recent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET /api/admin/batches/{id} returns 200 when batch exists")
    void getBatch_found() throws Exception {
        ForecastBatchEntity batch = buildBatch("msgbatch_xyz999", BatchType.AURORA);
        when(batchRepository.findById(1L)).thenReturn(Optional.of(batch));

        mockMvc.perform(get("/api/admin/batches/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.anthropicBatchId").value("msgbatch_xyz999"))
                .andExpect(jsonPath("$.batchType").value("AURORA"));
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET /api/admin/batches/{id} returns 404 when not found")
    void getBatch_notFound() throws Exception {
        when(batchRepository.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/admin/batches/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("GET /api/admin/batches/recent returns 403 for non-ADMIN")
    void getRecentBatches_forbiddenForNonAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/batches/recent"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/admin/batches/recent returns 401 when unauthenticated")
    void getRecentBatches_unauthenticated() throws Exception {
        mockMvc.perform(get("/api/admin/batches/recent"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET /api/admin/batches/recent returns completed batch with correct counts")
    void getRecentBatches_completedBatchHasCounts() throws Exception {
        ForecastBatchEntity batch = buildBatch("msgbatch_done", BatchType.FORECAST);
        batch.setStatus(BatchStatus.COMPLETED);
        batch.setSucceededCount(12);
        batch.setErroredCount(0);
        batch.setEndedAt(Instant.parse("2026-04-06T06:30:00Z"));
        when(batchRepository.findTop20ByOrderBySubmittedAtDesc()).thenReturn(List.of(batch));

        mockMvc.perform(get("/api/admin/batches/recent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$[0].succeededCount").value(12))
                .andExpect(jsonPath("$[0].erroredCount").value(0));
    }

    private ForecastBatchEntity buildBatch(String anthropicBatchId, BatchType type) {
        return new ForecastBatchEntity(anthropicBatchId, type, 5,
                Instant.parse("2026-04-07T06:00:00Z"));
    }
}
