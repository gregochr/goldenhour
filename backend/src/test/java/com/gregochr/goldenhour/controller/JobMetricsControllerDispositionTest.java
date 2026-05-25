package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.DispositionBreakdownResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the V101 disposition breakdown endpoint exposed on
 * {@code JobMetricsController}. The endpoint is the read API behind the Job
 * Run detail UI's "Disposition Breakdown" section.
 */
class JobMetricsControllerDispositionTest extends AbstractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET /api/metrics/disposition-breakdown: returns totals + counts + entries")
    void getDispositionBreakdown_populatedRun_returnsCountsAndEntries() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("EVALUATED", 163L);
        counts.put("SKIPPED_HARD_CONSTRAINT", 48L);
        counts.put("SKIPPED_TRIAGED", 41L);
        counts.put("SKIPPED_CACHED", 2L);
        counts.put("SKIPPED_PAST_DATE", 1L);
        DispositionBreakdownResponse.DispositionEntry evaluatedEntry =
                new DispositionBreakdownResponse.DispositionEntry(
                        42L, "Durham UK", LocalDate.parse("2026-05-26"),
                        "SUNRISE", 1, "EVALUATED", null);
        DispositionBreakdownResponse.DispositionEntry triagedEntry =
                new DispositionBreakdownResponse.DispositionEntry(
                        43L, "Newcastle", LocalDate.parse("2026-05-26"),
                        "SUNRISE", 1, "SKIPPED_TRIAGED",
                        "Solar horizon low cloud 94% — sun blocked");
        DispositionBreakdownResponse response = new DispositionBreakdownResponse(
                348L, 255L, counts, List.of(evaluatedEntry, triagedEntry));
        when(dispositionService.getBreakdownForJobRun(eq(348L))).thenReturn(response);

        try {
            mockMvc.perform(get("/api/metrics/disposition-breakdown")
                            .param("jobRunId", "348"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.jobRunId").value(348))
                    .andExpect(jsonPath("$.totalCount").value(255))
                    .andExpect(jsonPath("$.countsByDisposition.EVALUATED").value(163))
                    .andExpect(jsonPath("$.countsByDisposition.SKIPPED_TRIAGED").value(41))
                    .andExpect(jsonPath("$.entries.length()").value(2))
                    .andExpect(jsonPath("$.entries[0].disposition").value("EVALUATED"))
                    .andExpect(jsonPath("$.entries[0].locationName").value("Durham UK"))
                    .andExpect(jsonPath("$.entries[0].detail").doesNotExist())
                    .andExpect(jsonPath("$.entries[1].disposition").value("SKIPPED_TRIAGED"))
                    .andExpect(jsonPath("$.entries[1].detail")
                            .value("Solar horizon low cloud 94% — sun blocked"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET /api/metrics/disposition-breakdown: empty run returns zero counts")
    void getDispositionBreakdown_emptyRun_returnsZeroCountsAndEmptyEntries() {
        DispositionBreakdownResponse empty = new DispositionBreakdownResponse(
                999L, 0L, Map.of(), List.of());
        when(dispositionService.getBreakdownForJobRun(eq(999L))).thenReturn(empty);

        try {
            mockMvc.perform(get("/api/metrics/disposition-breakdown")
                            .param("jobRunId", "999"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.jobRunId").value(999))
                    .andExpect(jsonPath("$.totalCount").value(0))
                    .andExpect(jsonPath("$.entries.length()").value(0));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("GET /api/metrics/disposition-breakdown: PRO_USER gets 403")
    void getDispositionBreakdown_proUser_forbidden() {
        try {
            mockMvc.perform(get("/api/metrics/disposition-breakdown")
                            .param("jobRunId", "348"))
                    .andExpect(status().isForbidden());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @WithMockUser(roles = {"LITE_USER"})
    @DisplayName("GET /api/metrics/disposition-breakdown: LITE_USER gets 403")
    void getDispositionBreakdown_liteUser_forbidden() {
        try {
            mockMvc.perform(get("/api/metrics/disposition-breakdown")
                            .param("jobRunId", "348"))
                    .andExpect(status().isForbidden());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
