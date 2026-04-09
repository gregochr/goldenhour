package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.DriveTimeRefreshResponse;
import com.gregochr.goldenhour.model.PostcodeLookupResult;
import com.gregochr.goldenhour.model.UserSettingsResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link UserSettingsController}.
 */
class UserSettingsControllerTest extends AbstractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser
    @DisplayName("GET /api/user/settings returns user profile")
    void getSettings_returnsProfile() throws Exception {
        when(settingsService.getSettings(any())).thenReturn(
                new UserSettingsResponse("testuser", "test@example.com", "PRO_USER",
                        "DH1 3LE", 54.7761, -1.5733, "Durham, County Durham",
                        Instant.parse("2026-04-01T10:00:00Z")));

        mockMvc.perform(get("/api/user/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("testuser"))
                .andExpect(jsonPath("$.homePostcode").value("DH1 3LE"))
                .andExpect(jsonPath("$.homePlaceName").value("Durham, County Durham"));
    }

    @Test
    @DisplayName("GET /api/user/settings returns 401 without authentication")
    void getSettings_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/user/settings"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser
    @DisplayName("POST /api/user/settings/home/lookup returns geocoded result")
    void lookupPostcode_returnsResult() throws Exception {
        when(settingsService.lookupPostcode(anyString())).thenReturn(
                new PostcodeLookupResult("DH1 3LE", 54.7761, -1.5733, "Durham, County Durham"));

        mockMvc.perform(post("/api/user/settings/home/lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"postcode\": \"DH1 3LE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postcode").value("DH1 3LE"))
                .andExpect(jsonPath("$.latitude").value(54.7761))
                .andExpect(jsonPath("$.placeName").value("Durham, County Durham"));
    }

    @Test
    @WithMockUser
    @DisplayName("PUT /api/user/settings/home saves home location")
    void saveHome_returnsUpdatedSettings() throws Exception {
        when(settingsService.saveHome(any(), any())).thenReturn(
                new UserSettingsResponse("testuser", "test@example.com", "PRO_USER",
                        "DH1 3LE", 54.7761, -1.5733, null, null));

        mockMvc.perform(put("/api/user/settings/home")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"postcode\": \"DH1 3LE\", \"latitude\": 54.7761, \"longitude\": -1.5733}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.homePostcode").value("DH1 3LE"));
    }

    @Test
    @WithMockUser
    @DisplayName("POST /api/user/settings/drive-times/refresh returns update count")
    void refreshDriveTimes_returnsCount() throws Exception {
        when(settingsService.refreshDriveTimes(any())).thenReturn(
                new DriveTimeRefreshResponse(15, Instant.parse("2026-04-01T10:00:00Z")));

        mockMvc.perform(post("/api/user/settings/drive-times/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locationsUpdated").value(15));
    }

    @Test
    @WithMockUser
    @DisplayName("POST drive-times/refresh returns 400 when no home set")
    void refreshDriveTimes_noHome_returns400() throws Exception {
        when(settingsService.refreshDriveTimes(any())).thenThrow(
                new ResponseStatusException(BAD_REQUEST, "Set a home location"));

        mockMvc.perform(post("/api/user/settings/drive-times/refresh"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    @DisplayName("POST drive-times/refresh returns 429 when rate limited")
    void refreshDriveTimes_rateLimited_returns429() throws Exception {
        when(settingsService.refreshDriveTimes(any())).thenThrow(
                new ResponseStatusException(TOO_MANY_REQUESTS, "Recently refreshed"));

        mockMvc.perform(post("/api/user/settings/drive-times/refresh"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/user/settings/drive-times returns per-location map")
    void getDriveTimes_returnsMap() throws Exception {
        when(settingsService.getUserId(any())).thenReturn(42L);
        when(driveTimeResolver.getAllMinutes(42L)).thenReturn(Map.of(1L, 30, 2L, 90));

        mockMvc.perform(get("/api/user/settings/drive-times"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.1").value(30))
                .andExpect(jsonPath("$.2").value(90));
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/user/settings/drive-times returns empty map when none calculated")
    void getDriveTimes_noDriveTimes_returnsEmpty() throws Exception {
        when(settingsService.getUserId(any())).thenReturn(42L);
        when(driveTimeResolver.getAllMinutes(42L)).thenReturn(Map.of());

        mockMvc.perform(get("/api/user/settings/drive-times"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
