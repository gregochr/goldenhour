package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.DriveTimeRefreshResponse;
import com.gregochr.goldenhour.model.PostcodeLookupResult;
import com.gregochr.goldenhour.model.ReachEntry;
import com.gregochr.goldenhour.model.TodaysLightResponse;
import com.gregochr.goldenhour.model.UserSettingsResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
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
                        null, Instant.parse("2026-04-01T10:00:00Z"), null, null));

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
                        "DH1 3LE", 54.7761, -1.5733, null, null, null, null, null));

        mockMvc.perform(put("/api/user/settings/home")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"postcode\": \"DH1 3LE\", \"latitude\": 54.7761, \"longitude\": -1.5733}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.homePostcode").value("DH1 3LE"));
    }

    @Test
    @WithMockUser
    @DisplayName("PUT /api/user/settings/map-colours saves the preference and returns settings")
    void saveMapColourPreferences_returnsUpdatedSettings() throws Exception {
        when(settingsService.saveMapColourPreferences(any(), any())).thenReturn(
                new UserSettingsResponse("testuser", "test@example.com", "PRO_USER",
                        null, null, null, null, null, null, "temp", null));

        mockMvc.perform(put("/api/user/settings/map-colours")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mapColourScale\": \"temp\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mapColourScale").value("temp"));
    }

    @Test
    @WithMockUser
    @DisplayName("PUT /api/user/settings/map-colours returns 400 for an invalid scale")
    void saveMapColourPreferences_invalidScale_returns400() throws Exception {
        when(settingsService.saveMapColourPreferences(any(), any())).thenThrow(
                new ResponseStatusException(BAD_REQUEST, "mapColourScale must be 'temp' or 'verdict'"));

        mockMvc.perform(put("/api/user/settings/map-colours")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mapColourScale\": \"rainbow\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT /api/user/settings/map-colours returns 401 without authentication")
    void saveMapColourPreferences_unauthenticated_returns401() throws Exception {
        mockMvc.perform(put("/api/user/settings/map-colours")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mapColourScale\": \"temp\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser
    @DisplayName("PUT /api/user/settings/coming-up-seen records the visit with no request body")
    void markComingUpSeen_returnsUpdatedSettings() throws Exception {
        when(settingsService.markComingUpSeen(any())).thenReturn(
                new UserSettingsResponse("testuser", "test@example.com", "PRO_USER",
                        null, null, null, null, null, null, null,
                        java.time.LocalDate.of(2026, 8, 29)));

        mockMvc.perform(put("/api/user/settings/coming-up-seen"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comingUpLastSeenDate").value("2026-08-29"));
    }

    @Test
    @DisplayName("PUT /api/user/settings/coming-up-seen returns 401 without authentication")
    void markComingUpSeen_unauthenticated_returns401() throws Exception {
        mockMvc.perform(put("/api/user/settings/coming-up-seen"))
                .andExpect(status().isUnauthorized());
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

    @Test
    @WithMockUser
    @DisplayName("GET /api/user/settings/reach returns an entry per location, omitting absent figures")
    void getReach_returnsRosterWithNullsOmitted() throws Exception {
        // The client joins this to the briefing on locationId, so the id is the one field that must
        // always be present. The two figures are independently absent, and NON_NULL keeps them off
        // the wire entirely rather than as nulls a consumer might render as a zero.
        when(reachService.getReach(any())).thenReturn(List.of(
                new ReachEntry(1L, 62, 44),
                new ReachEntry(2L, null, 24)));

        mockMvc.perform(get("/api/user/settings/reach"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].locationId").value(1))
                .andExpect(jsonPath("$[0].driveMinutes").value(62))
                .andExpect(jsonPath("$[0].distanceMiles").value(44))
                .andExpect(jsonPath("$[1].locationId").value(2))
                .andExpect(jsonPath("$[1].driveMinutes").doesNotExist())
                .andExpect(jsonPath("$[1].distanceMiles").value(24));
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/user/settings/reach returns the roster even with no home set")
    void getReach_noHome_stillReturnsTheRoster() throws Exception {
        // The normal first-run state. An empty body here would make the reach lens look broken to
        // exactly the user it most needs to be honest with.
        when(reachService.getReach(any())).thenReturn(List.of(
                new ReachEntry(1L, null, null),
                new ReachEntry(2L, null, null)));

        mockMvc.perform(get("/api/user/settings/reach"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].locationId").value(1))
                .andExpect(jsonPath("$[0].driveMinutes").doesNotExist())
                .andExpect(jsonPath("$[0].distanceMiles").doesNotExist());
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/user/settings/light returns the labelled row and the gradient stops")
    void getTodaysLight_returnsTheDaysLight() throws Exception {
        when(todaysLightService.getTodaysLight(any())).thenReturn(new TodaysLightResponse(
                "Home · NE66 1NG", "NE66 1NG", "05:32", "06:04", "19:58", "20:31",
                List.of(new TodaysLightResponse.Stop("NIGHT_START", 0),
                        new TodaysLightResponse.Stop("SUNRISE", 25.28),
                        new TodaysLightResponse.Stop("NIGHT_END", 100))));

        mockMvc.perform(get("/api/user/settings/light"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Home · NE66 1NG"))
                .andExpect(jsonPath("$.sunrise").value("06:04"))
                .andExpect(jsonPath("$.civilDusk").value("20:31"))
                .andExpect(jsonPath("$.stops.length()").value(3))
                .andExpect(jsonPath("$.stops[1].key").value("SUNRISE"))
                .andExpect(jsonPath("$.stops[1].position").value(25.28));
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/user/settings/light is 204 with no postcode saved, not an error")
    void getTodaysLight_noHome_returns204() throws Exception {
        // The masthead's empty state — a dim rule and a nudge. A 4xx here would make the first-run
        // state read as a fault in the console of every new user.
        when(todaysLightService.getTodaysLight(any())).thenReturn(null);

        mockMvc.perform(get("/api/user/settings/light"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("GET /api/user/settings/light returns 401 without authentication")
    void getTodaysLight_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/user/settings/light"))
                .andExpect(status().isUnauthorized());
    }
}
