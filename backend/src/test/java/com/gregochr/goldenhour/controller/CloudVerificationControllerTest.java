package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.BackfillStatus;
import com.gregochr.goldenhour.model.CloudVerificationBucket;
import com.gregochr.goldenhour.model.CloudVerificationReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
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
    @DisplayName("POST backfill returns 202 immediately rather than holding the request open")
    @WithMockUser(roles = "ADMIN")
    void backfill_startsInBackgroundAndReturnsAccepted() throws Exception {
        // The backlog is far longer than any proxy timeout, so the request must not wait on it.
        when(cloudVerificationBackfillRunner.start()).thenReturn(true);
        when(cloudVerificationBackfillRunner.status())
                .thenReturn(BackfillStatus.started(LocalDateTime.of(2026, 7, 26, 10, 0), 24638));

        mockMvc.perform(post("/api/admin/cloud-verification/backfill")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.running").value(true))
                .andExpect(jsonPath("$.remaining").value(24638));

        verify(cloudVerificationBackfillRunner).start();
    }

    @Test
    @DisplayName("POST backfill returns 409 when a run is already in progress")
    @WithMockUser(roles = "ADMIN")
    void backfill_alreadyRunning_returnsConflict() throws Exception {
        // Two concurrent runs would select overlapping candidates and collide on the unique id.
        when(cloudVerificationBackfillRunner.start()).thenReturn(false);
        when(cloudVerificationBackfillRunner.status())
                .thenReturn(BackfillStatus.started(LocalDateTime.of(2026, 7, 26, 10, 0), 500));

        mockMvc.perform(post("/api/admin/cloud-verification/backfill")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.running").value(true));
    }

    @Test
    @DisplayName("GET backfill/status reports progress of the current run")
    @WithMockUser(roles = "ADMIN")
    void backfillStatus_reportsProgress() throws Exception {
        when(cloudVerificationBackfillRunner.status()).thenReturn(
                BackfillStatus.started(LocalDateTime.of(2026, 7, 26, 10, 0), 24638)
                        .withBatch(100, 24538));

        mockMvc.perform(get("/api/admin/cloud-verification/backfill/status")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(100))
                .andExpect(jsonPath("$.batches").value(1))
                .andExpect(jsonPath("$.remaining").value(24538));
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
                .andExpect(jsonPath("$.vetoSeparation").value(23.6))
                .andExpect(jsonPath("$.capSeparation").value(8.0))
                .andExpect(jsonPath("$.vetoUncapped.sampleCount").value(9))
                .andExpect(jsonPath("$.vetoCapped.sampleCount").value(31))
                .andExpect(jsonPath("$.byWindSunAngle[0].key").value("aligned(<45)"))
                .andExpect(jsonPath("$.byConeStructure[0].key").value("gapped(>=40)"))
                .andExpect(jsonPath("$.byConeStructure[0].meanConeSpread").value(55.0))
                .andExpect(jsonPath("$.byCorridor[0].key").value("farClearer&highCanvas"))
                .andExpect(jsonPath("$.byCorridor[0].meanFarDrop").value(48.0))
                // Additive: the under-the-cut variants ride the same payload, and both means are
                // served, since the under-cut veto separation is the reader's own subtraction.
                .andExpect(jsonPath("$.byTriageCut[0].key").value("vetoFired&underTriageCut(<=80)"))
                .andExpect(jsonPath("$.byTriageCut[0].meanObservedGapLow").value(68.0))
                .andExpect(jsonPath("$.byTriageCut[1].meanObservedGapLow").value(51.0));
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
                new CloudVerificationBucket("ALL", 120, -4.2, 18.6, 3.1, 55.0, 59.2,
                        24.0, 6.0, -3.5);
        CloudVerificationBucket fired =
                new CloudVerificationBucket("VETO_FIRED", 40, -12.0, 26.0, 2.0, 62.0, 74.0,
                        null, null, null);
        CloudVerificationBucket notFired =
                new CloudVerificationBucket("VETO_NOT_FIRED", 80, -0.4, 14.9, 3.6, 50.0, 50.4,
                        null, null, null);
        CloudVerificationBucket uncapped =
                new CloudVerificationBucket("VETO_UNCAPPED", 9, -2.0, 11.0, 1.5, 78.0, 80.0,
                        null, null, null);
        CloudVerificationBucket capped =
                new CloudVerificationBucket("VETO_CAPPED", 31, -15.0, 30.0, 2.2, 57.0, 72.0,
                        null, null, null);
        CloudVerificationBucket aligned =
                new CloudVerificationBucket("aligned(<45)", 18, -19.0, 33.0, 2.4, 55.0, 74.0,
                        null, null, null);
        CloudVerificationBucket gapped =
                new CloudVerificationBucket("gapped(>=40)", 14, -22.0, 35.0, 2.8, 58.0, 61.0,
                        55.0, null, null);
        CloudVerificationBucket clearerHigh =
                new CloudVerificationBucket("farClearer&highCanvas", 7, -18.0, 28.0, 1.9,
                        66.0, 71.0, null, 48.0, -6.0);
        // The veto split re-read over the slots a prompt could have been built for. Carried as a
        // pair because the under-cut separation is derived from their two meanObservedGapLow
        // values — 68.0 - 51.0 — rather than served as a scalar of its own.
        CloudVerificationBucket firedUnderCut =
                new CloudVerificationBucket("vetoFired&underTriageCut(<=80)", 11, -9.0, 21.0, 1.7,
                        70.0, 68.0, null, null, null);
        CloudVerificationBucket notFiredUnderCut =
                new CloudVerificationBucket("vetoNotFired&underTriageCut(<=80)", 62, -1.0, 15.0,
                        3.4, 48.0, 51.0, null, null, null);
        return new CloudVerificationReport(FROM, TO, 120L, overall, fired, notFired,
                uncapped, capped, List.of(aligned), List.of(gapped), List.of(clearerHigh),
                List.of(firedUnderCut, notFiredUnderCut), 23.6, 8.0);
    }
}
