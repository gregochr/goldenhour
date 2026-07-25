package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.CloudVerificationBucket;
import com.gregochr.goldenhour.model.CloudVerificationReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link CloudVerificationController}.
 */
class CloudVerificationControllerTest extends AbstractControllerTest {

    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 5, 31);

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET /api/admin/cloud-verification requires ADMIN role")
    @WithMockUser(roles = "PRO_USER")
    void getReport_requiresAdminRole() throws Exception {
        mockMvc.perform(get("/api/admin/cloud-verification")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/admin/cloud-verification/backfill requires ADMIN role")
    @WithMockUser(roles = "PRO_USER")
    void backfill_requiresAdminRole() throws Exception {
        mockMvc.perform(post("/api/admin/cloud-verification/backfill")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/admin/cloud-verification returns the veto splits for the window")
    @WithMockUser(roles = "ADMIN")
    void getReport_returnsVetoSplits() throws Exception {
        when(cloudVerificationService.report(FROM, TO)).thenReturn(report());

        mockMvc.perform(get("/api/admin/cloud-verification")
                .param("from", "2026-01-01")
                .param("to", "2026-05-31")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verifiedCount").value(120))
                .andExpect(jsonPath("$.vetoFired.sampleCount").value(40))
                .andExpect(jsonPath("$.vetoFired.gapActuallyOpen").value(11))
                .andExpect(jsonPath("$.vetoUncapped.sampleCount").value(9))
                .andExpect(jsonPath("$.vetoCapped.sampleCount").value(31))
                .andExpect(jsonPath("$.byWindSunAngle[0].key").value("aligned(<45)"));
    }

    @Test
    @DisplayName("POST backfill passes an explicit limit through and reports the count verified")
    @WithMockUser(roles = "ADMIN")
    void backfill_passesLimitThrough() throws Exception {
        when(cloudVerificationService.backfill(anyInt())).thenReturn(37);

        mockMvc.perform(post("/api/admin/cloud-verification/backfill")
                .param("limit", "50")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(37));

        verify(cloudVerificationService).backfill(50);
    }

    @Test
    @DisplayName("GET /api/admin/cloud-verification defaults to a trailing 180-day window")
    @WithMockUser(roles = "ADMIN")
    void getReport_noParams_usesTrailingWindow() throws Exception {
        when(cloudVerificationService.report(any(), any())).thenReturn(report());

        mockMvc.perform(get("/api/admin/cloud-verification")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        LocalDate today = LocalDate.now();
        verify(cloudVerificationService).report(eq(today.minusDays(180)), eq(today));
    }

    private CloudVerificationReport report() {
        // Shape of the D7 answer: the veto tracks reality when uncapped, not when clamped.
        CloudVerificationBucket overall =
                new CloudVerificationBucket("ALL", 120, -4.2, 18.6, 3.1, 30, 55);
        CloudVerificationBucket fired =
                new CloudVerificationBucket("VETO_FIRED", 40, -12.0, 26.0, 2.0, 11, 22);
        CloudVerificationBucket notFired =
                new CloudVerificationBucket("VETO_NOT_FIRED", 80, -0.4, 14.9, 3.6, 19, 33);
        CloudVerificationBucket uncapped =
                new CloudVerificationBucket("VETO_UNCAPPED", 9, -2.0, 11.0, 1.5, 1, 7);
        CloudVerificationBucket capped =
                new CloudVerificationBucket("VETO_CAPPED", 31, -15.0, 30.0, 2.2, 10, 15);
        CloudVerificationBucket aligned =
                new CloudVerificationBucket("aligned(<45)", 18, -19.0, 33.0, 2.4, 8, 6);
        return new CloudVerificationReport(FROM, TO, 120, overall, fired, notFired,
                uncapped, capped, List.of(aligned));
    }
}
