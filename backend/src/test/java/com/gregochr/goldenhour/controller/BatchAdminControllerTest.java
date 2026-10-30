package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.service.batch.BatchSubmitResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link BatchAdminController}.
 */
class BatchAdminControllerTest extends AbstractControllerTest {

    @Autowired
    private MockMvc mockMvc;

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

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("POST /api/admin/batches/reset-guards returns 200 with reset flags")
    void resetBatchGuards_returnsOkWithFlags() throws Exception {
        mockMvc.perform(post("/api/admin/batches/reset-guards"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.forecastReset").value(true))
                .andExpect(jsonPath("$.auroraReset").value(true));

        verify(scheduledBatchEvaluationService).resetBatchGuards();
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("POST /api/admin/batches/reset-guards returns 403 for non-ADMIN")
    void resetBatchGuards_forbiddenForNonAdmin() throws Exception {
        mockMvc.perform(post("/api/admin/batches/reset-guards"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/admin/batches/reset-guards returns 401 when unauthenticated")
    void resetBatchGuards_unauthenticated() throws Exception {
        mockMvc.perform(post("/api/admin/batches/reset-guards"))
                .andExpect(status().isUnauthorized());
    }

    // ── LITE_USER access → 403 ──────────────────────────────────────────────

    @Test
    @WithMockUser(roles = {"LITE_USER"})
    @DisplayName("POST /api/admin/batches/reset-guards returns 403 for LITE_USER")
    void resetBatchGuards_liteUser_returns403() throws Exception {
        mockMvc.perform(post("/api/admin/batches/reset-guards"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"LITE_USER"})
    @DisplayName("GET /api/admin/batches/recent returns 403 for LITE_USER")
    void getRecentBatches_liteUser_returns403() throws Exception {
        mockMvc.perform(get("/api/admin/batches/recent"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = {"LITE_USER"})
    @DisplayName("GET /api/admin/batches/{id} returns 403 for LITE_USER")
    void getBatch_liteUser_returns403() throws Exception {
        mockMvc.perform(get("/api/admin/batches/1"))
                .andExpect(status().isForbidden());
    }

    // ── GET /regions ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/admin/batches/regions")
    class RegionsTests {

        @Test
        @WithMockUser(roles = {"ADMIN"})
        @DisplayName("returns regions with IDs, names, and counts sorted alphabetically")
        void returnsRegionsSorted() throws Exception {
            RegionEntity regionA = new RegionEntity();
            regionA.setId(1L);
            regionA.setName("Cumbria");

            RegionEntity regionB = new RegionEntity();
            regionB.setId(2L);
            regionB.setName("Ayrshire");

            LocationEntity loc1 = new LocationEntity();
            loc1.setRegion(regionA);
            LocationEntity loc2 = new LocationEntity();
            loc2.setRegion(regionA);
            LocationEntity loc3 = new LocationEntity();
            loc3.setRegion(regionB);

            when(locationService.findAllEnabled()).thenReturn(List.of(loc1, loc2, loc3));

            mockMvc.perform(get("/api/admin/batches/regions"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].id").value(2))
                    .andExpect(jsonPath("$[0].name").value("Ayrshire"))
                    .andExpect(jsonPath("$[0].locationCount").value(1))
                    .andExpect(jsonPath("$[1].id").value(1))
                    .andExpect(jsonPath("$[1].name").value("Cumbria"))
                    .andExpect(jsonPath("$[1].locationCount").value(2));

            verify(locationService).findAllEnabled();
        }

        @Test
        @WithMockUser(roles = {"ADMIN"})
        @DisplayName("case-insensitive sorting: lowercase name sorts correctly")
        void caseInsensitiveSorting() throws Exception {
            RegionEntity regionLower = new RegionEntity();
            regionLower.setId(10L);
            regionLower.setName("borders");

            RegionEntity regionUpper = new RegionEntity();
            regionUpper.setId(20L);
            regionUpper.setName("Angus");

            LocationEntity loc1 = new LocationEntity();
            loc1.setRegion(regionLower);
            LocationEntity loc2 = new LocationEntity();
            loc2.setRegion(regionUpper);

            when(locationService.findAllEnabled()).thenReturn(List.of(loc1, loc2));

            mockMvc.perform(get("/api/admin/batches/regions"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].name").value("Angus"))
                    .andExpect(jsonPath("$[1].name").value("borders"));
        }

        @Test
        @WithMockUser(roles = {"ADMIN"})
        @DisplayName("excludes locations with no region")
        void excludesOrphans() throws Exception {
            LocationEntity orphan = new LocationEntity();
            when(locationService.findAllEnabled()).thenReturn(List.of(orphan));

            mockMvc.perform(get("/api/admin/batches/regions"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }

        @Test
        @WithMockUser(roles = {"ADMIN"})
        @DisplayName("returns empty list when no enabled locations")
        void emptyLocations() throws Exception {
            when(locationService.findAllEnabled()).thenReturn(List.of());

            mockMvc.perform(get("/api/admin/batches/regions"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }

        @Test
        @WithMockUser(roles = {"PRO_USER"})
        @DisplayName("returns 403 for non-ADMIN")
        void forbidden() throws Exception {
            mockMvc.perform(get("/api/admin/batches/regions"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"LITE_USER"})
        @DisplayName("returns 403 for LITE_USER")
        void liteUserForbidden() throws Exception {
            mockMvc.perform(get("/api/admin/batches/regions"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("returns 401 when unauthenticated")
        void unauthenticated() throws Exception {
            mockMvc.perform(get("/api/admin/batches/regions"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ── POST /submit-scheduled ─────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/admin/batches/submit-scheduled")
    class SubmitScheduledTests {

        @Test
        @WithMockUser(roles = {"ADMIN"})
        @DisplayName("returns 409 when a batch is already in progress")
        void conflict() throws Exception {
            when(batchRepository.findByStatusOrderBySubmittedAtDesc(BatchStatus.SUBMITTED))
                    .thenReturn(List.of(new ForecastBatchEntity()));

            mockMvc.perform(post("/api/admin/batches/submit-scheduled")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"regionIds\":[1]}"))
                    .andExpect(status().isConflict());
        }

        @Test
        @WithMockUser(roles = {"ADMIN"})
        @DisplayName("409 does not call the batch evaluation service")
        void conflictDoesNotCallService() throws Exception {
            when(batchRepository.findByStatusOrderBySubmittedAtDesc(BatchStatus.SUBMITTED))
                    .thenReturn(List.of(new ForecastBatchEntity()));

            mockMvc.perform(post("/api/admin/batches/submit-scheduled")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"regionIds\":[1]}"))
                    .andExpect(status().isConflict());

            verify(scheduledBatchEvaluationService, never())
                    .submitScheduledBatchForRegions(eq(List.of(1L)));
        }

        @Test
        @WithMockUser(roles = {"ADMIN"})
        @DisplayName("returns 200 and passes exact regionIds to service")
        void success() throws Exception {
            when(batchRepository.findByStatusOrderBySubmittedAtDesc(BatchStatus.SUBMITTED))
                    .thenReturn(List.of());
            when(scheduledBatchEvaluationService
                    .submitScheduledBatchForRegions(eq(List.of(1L))))
                    .thenReturn(new BatchSubmitResult(null, "batch-123", 42));

            mockMvc.perform(post("/api/admin/batches/submit-scheduled")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"regionIds\":[1]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.batchId").value("batch-123"))
                    .andExpect(jsonPath("$.requestCount").value(42));

            verify(scheduledBatchEvaluationService)
                    .submitScheduledBatchForRegions(eq(List.of(1L)));
        }

        @Test
        @WithMockUser(roles = {"ADMIN"})
        @DisplayName("passes multiple regionIds to service in correct order")
        void multipleRegionIds() throws Exception {
            when(batchRepository.findByStatusOrderBySubmittedAtDesc(BatchStatus.SUBMITTED))
                    .thenReturn(List.of());
            when(scheduledBatchEvaluationService
                    .submitScheduledBatchForRegions(eq(List.of(3L, 7L, 12L))))
                    .thenReturn(new BatchSubmitResult(null, "batch-multi", 90));

            mockMvc.perform(post("/api/admin/batches/submit-scheduled")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"regionIds\":[3,7,12]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.batchId").value("batch-multi"))
                    .andExpect(jsonPath("$.requestCount").value(90));

            verify(scheduledBatchEvaluationService)
                    .submitScheduledBatchForRegions(eq(List.of(3L, 7L, 12L)));
        }

        @Test
        @WithMockUser(roles = {"ADMIN"})
        @DisplayName("null regionIds when body has null regionIds field")
        void nullRegionIds() throws Exception {
            when(batchRepository.findByStatusOrderBySubmittedAtDesc(BatchStatus.SUBMITTED))
                    .thenReturn(List.of());
            when(scheduledBatchEvaluationService
                    .submitScheduledBatchForRegions(isNull()))
                    .thenReturn(new BatchSubmitResult(null, "batch-all", 100));

            mockMvc.perform(post("/api/admin/batches/submit-scheduled")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"regionIds\":null}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.requestCount").value(100));

            verify(scheduledBatchEvaluationService)
                    .submitScheduledBatchForRegions(isNull());
        }

        @Test
        @WithMockUser(roles = {"ADMIN"})
        @DisplayName("null regionIds when body is empty JSON object")
        void emptyBody() throws Exception {
            when(batchRepository.findByStatusOrderBySubmittedAtDesc(BatchStatus.SUBMITTED))
                    .thenReturn(List.of());
            when(scheduledBatchEvaluationService
                    .submitScheduledBatchForRegions(isNull()))
                    .thenReturn(new BatchSubmitResult(null, "batch-empty", 50));

            mockMvc.perform(post("/api/admin/batches/submit-scheduled")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.requestCount").value(50));

            verify(scheduledBatchEvaluationService)
                    .submitScheduledBatchForRegions(isNull());
        }

        @Test
        @WithMockUser(roles = {"ADMIN"})
        @DisplayName("returns 422 when service returns null")
        void unprocessable() throws Exception {
            when(batchRepository.findByStatusOrderBySubmittedAtDesc(BatchStatus.SUBMITTED))
                    .thenReturn(List.of());
            when(scheduledBatchEvaluationService
                    .submitScheduledBatchForRegions(isNull()))
                    .thenReturn(null);

            mockMvc.perform(post("/api/admin/batches/submit-scheduled")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isUnprocessableEntity());
        }

        @Test
        @WithMockUser(roles = {"PRO_USER"})
        @DisplayName("returns 403 for non-ADMIN")
        void forbidden() throws Exception {
            mockMvc.perform(post("/api/admin/batches/submit-scheduled")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"LITE_USER"})
        @DisplayName("returns 403 for LITE_USER")
        void liteUserForbidden() throws Exception {
            mockMvc.perform(post("/api/admin/batches/submit-scheduled")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("returns 401 when unauthenticated")
        void unauthenticated() throws Exception {
            mockMvc.perform(post("/api/admin/batches/submit-scheduled")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ── POST /submit-jfdi ──────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/admin/batches/submit-jfdi")
    class SubmitJfdiTests {

        @Test
        @WithMockUser(roles = {"ADMIN"})
        @DisplayName("returns 409 when a batch is already in progress")
        void conflict() throws Exception {
            when(batchRepository.findByStatusOrderBySubmittedAtDesc(BatchStatus.SUBMITTED))
                    .thenReturn(List.of(new ForecastBatchEntity()));

            mockMvc.perform(post("/api/admin/batches/submit-jfdi")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"regionIds\":[2]}"))
                    .andExpect(status().isConflict());
        }

        @Test
        @WithMockUser(roles = {"ADMIN"})
        @DisplayName("409 does not call the JFDI batch service")
        void conflictDoesNotCallService() throws Exception {
            when(batchRepository.findByStatusOrderBySubmittedAtDesc(BatchStatus.SUBMITTED))
                    .thenReturn(List.of(new ForecastBatchEntity()));

            mockMvc.perform(post("/api/admin/batches/submit-jfdi")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"regionIds\":[2]}"))
                    .andExpect(status().isConflict());

            verify(forceSubmitBatchService, never())
                    .submitJfdiBatch(eq(List.of(2L)));
        }

        @Test
        @WithMockUser(roles = {"ADMIN"})
        @DisplayName("returns 200 and passes exact regionIds to service")
        void success() throws Exception {
            when(batchRepository.findByStatusOrderBySubmittedAtDesc(BatchStatus.SUBMITTED))
                    .thenReturn(List.of());
            when(forceSubmitBatchService.submitJfdiBatch(eq(List.of(2L))))
                    .thenReturn(new BatchSubmitResult(null, "jfdi-456", 80));

            mockMvc.perform(post("/api/admin/batches/submit-jfdi")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"regionIds\":[2]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.batchId").value("jfdi-456"))
                    .andExpect(jsonPath("$.requestCount").value(80));

            verify(forceSubmitBatchService).submitJfdiBatch(eq(List.of(2L)));
        }

        @Test
        @WithMockUser(roles = {"ADMIN"})
        @DisplayName("passes multiple regionIds to service in correct order")
        void multipleRegionIds() throws Exception {
            when(batchRepository.findByStatusOrderBySubmittedAtDesc(BatchStatus.SUBMITTED))
                    .thenReturn(List.of());
            when(forceSubmitBatchService.submitJfdiBatch(eq(List.of(5L, 10L))))
                    .thenReturn(new BatchSubmitResult(null, "jfdi-multi", 120));

            mockMvc.perform(post("/api/admin/batches/submit-jfdi")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"regionIds\":[5,10]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.batchId").value("jfdi-multi"))
                    .andExpect(jsonPath("$.requestCount").value(120));

            verify(forceSubmitBatchService)
                    .submitJfdiBatch(eq(List.of(5L, 10L)));
        }

        @Test
        @WithMockUser(roles = {"ADMIN"})
        @DisplayName("null regionIds when body has null regionIds field")
        void nullRegionIds() throws Exception {
            when(batchRepository.findByStatusOrderBySubmittedAtDesc(BatchStatus.SUBMITTED))
                    .thenReturn(List.of());
            when(forceSubmitBatchService.submitJfdiBatch(isNull()))
                    .thenReturn(new BatchSubmitResult(null, "jfdi-all", 200));

            mockMvc.perform(post("/api/admin/batches/submit-jfdi")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"regionIds\":null}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.requestCount").value(200));

            verify(forceSubmitBatchService).submitJfdiBatch(isNull());
        }

        @Test
        @WithMockUser(roles = {"ADMIN"})
        @DisplayName("null regionIds when body is empty JSON object")
        void emptyBody() throws Exception {
            when(batchRepository.findByStatusOrderBySubmittedAtDesc(BatchStatus.SUBMITTED))
                    .thenReturn(List.of());
            when(forceSubmitBatchService.submitJfdiBatch(isNull()))
                    .thenReturn(new BatchSubmitResult(null, "jfdi-empty", 60));

            mockMvc.perform(post("/api/admin/batches/submit-jfdi")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.requestCount").value(60));

            verify(forceSubmitBatchService).submitJfdiBatch(isNull());
        }

        @Test
        @WithMockUser(roles = {"ADMIN"})
        @DisplayName("returns 422 when service returns null")
        void unprocessable() throws Exception {
            when(batchRepository.findByStatusOrderBySubmittedAtDesc(BatchStatus.SUBMITTED))
                    .thenReturn(List.of());
            when(forceSubmitBatchService.submitJfdiBatch(isNull()))
                    .thenReturn(null);

            mockMvc.perform(post("/api/admin/batches/submit-jfdi")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isUnprocessableEntity());
        }

        @Test
        @WithMockUser(roles = {"PRO_USER"})
        @DisplayName("returns 403 for non-ADMIN")
        void forbidden() throws Exception {
            mockMvc.perform(post("/api/admin/batches/submit-jfdi")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @WithMockUser(roles = {"LITE_USER"})
        @DisplayName("returns 403 for LITE_USER")
        void liteUserForbidden() throws Exception {
            mockMvc.perform(post("/api/admin/batches/submit-jfdi")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("returns 401 when unauthenticated")
        void unauthenticated() throws Exception {
            mockMvc.perform(post("/api/admin/batches/submit-jfdi")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isUnauthorized());
        }
    }

    private ForecastBatchEntity buildBatch(String anthropicBatchId, BatchType type) {
        return new ForecastBatchEntity(anthropicBatchId, type, 5,
                Instant.parse("2026-04-07T06:00:00Z"));
    }
}
