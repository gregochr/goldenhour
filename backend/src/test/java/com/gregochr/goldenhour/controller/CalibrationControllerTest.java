package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.CalibrationBucket;
import com.gregochr.goldenhour.model.CalibrationReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link CalibrationController}.
 */
class CalibrationControllerTest extends AbstractControllerTest {

    private static final LocalDate FROM = LocalDate.of(2026, 5, 1);
    private static final LocalDate TO = LocalDate.of(2026, 5, 31);

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET /api/admin/calibration requires ADMIN role")
    @WithMockUser(roles = "PRO_USER")
    void getCalibration_requiresAdminRole() throws Exception {
        mockMvc.perform(get("/api/admin/calibration")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/admin/calibration returns the report for the requested window")
    @WithMockUser(roles = "ADMIN")
    void getCalibration_returnsReportForRequestedWindow() throws Exception {
        when(calibrationService.report(FROM, TO)).thenReturn(report());

        mockMvc.perform(get("/api/admin/calibration")
                .param("from", "2026-05-01")
                .param("to", "2026-05-31")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scoredPairs").value(12))
                .andExpect(jsonPath("$.overall.meanAbsoluteRatingError").value(0.75))
                .andExpect(jsonPath("$.overall.missedOpportunities").value(3))
                .andExpect(jsonPath("$.byDaysAhead[0].key").value("T+0"));
    }

    @Test
    @DisplayName("GET /api/admin/calibration defaults to a trailing 90-day window")
    @WithMockUser(roles = "ADMIN")
    void getCalibration_noParams_usesTrailingNinetyDays() throws Exception {
        when(calibrationService.report(any(), any())).thenReturn(report());

        mockMvc.perform(get("/api/admin/calibration")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        LocalDate today = LocalDate.now();
        verify(calibrationService).report(eq(today.minusDays(90)), eq(today));
    }

    private CalibrationReport report() {
        CalibrationBucket overall = new CalibrationBucket(
                "ALL", 12, 0.25, 0.75, 0.5, 0.83, 3, 1, 18.0);
        CalibrationBucket sameDay = new CalibrationBucket(
                "T+0", 6, 0.0, 0.5, 0.67, 1.0, 0, 0, 12.0);
        return new CalibrationReport(FROM, TO, 9, 12, overall,
                List.of(sameDay), List.of(overall));
    }
}
