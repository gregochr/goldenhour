package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.client.PostcodeLookupException;
import com.gregochr.goldenhour.client.PostcodeLookupUnavailableException;
import com.gregochr.goldenhour.model.DriveTimeRefreshResponse;
import com.gregochr.goldenhour.model.PostcodeLookupResult;
import com.gregochr.goldenhour.model.ReachEntry;
import com.gregochr.goldenhour.model.TodaysLightResponse;
import com.gregochr.goldenhour.model.UserSettingsResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
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
                        null, Instant.parse("2026-04-01T10:00:00Z"), null, null, null, false, false));

        mockMvc.perform(get("/api/user/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("testuser"))
                .andExpect(jsonPath("$.homePostcode").value("DH1 3LE"))
                .andExpect(jsonPath("$.homePlaceName").value("Durham, County Durham"));
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/user/settings serves marketingEmailOptIn=true as a JSON boolean")
    void getSettings_servesMarketingEmailOptInTrue() throws Exception {
        when(settingsService.getSettings(argThat(auth -> auth != null && "user".equals(auth.getName())))).thenReturn(
                new UserSettingsResponse("testuser", "test@example.com", "PRO_USER",
                        null, null, null, null, null, null, null, null, null, false, true));

        mockMvc.perform(get("/api/user/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marketingEmailOptIn").value(true));
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
                        "DH1 3LE", 54.7761, -1.5733, null, null, null, null, null, null, false, false));

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
                        null, null, null, null, null, null, "temp", null, null, false, false));

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
    @DisplayName("PUT /api/user/settings/map-tide-mode saves the mode and returns settings")
    void saveMapTideMode_returnsUpdatedSettings() throws Exception {
        when(settingsService.saveMapTideMode(any(), any())).thenReturn(
                new UserSettingsResponse("testuser", "test@example.com", "PRO_USER",
                        null, null, null, null, null, null, null, null, "always", false, false));

        mockMvc.perform(put("/api/user/settings/map-tide-mode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mapTideMode\": \"always\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mapTideMode").value("always"));
    }

    @Test
    @WithMockUser
    @DisplayName("PUT /api/user/settings/map-tide-mode returns 400 for an invalid mode")
    void saveMapTideMode_invalidMode_returns400() throws Exception {
        when(settingsService.saveMapTideMode(any(), any())).thenThrow(
                new ResponseStatusException(BAD_REQUEST, "mapTideMode must be 'auto', 'always' or 'off'"));

        mockMvc.perform(put("/api/user/settings/map-tide-mode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mapTideMode\": \"sometimes\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT /api/user/settings/map-tide-mode returns 401 without authentication")
    void saveMapTideMode_unauthenticated_returns401() throws Exception {
        mockMvc.perform(put("/api/user/settings/map-tide-mode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mapTideMode\": \"always\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser
    @DisplayName("PUT /api/user/settings/coming-up-seen records the visit with no request body")
    void markComingUpSeen_returnsUpdatedSettings() throws Exception {
        when(settingsService.markComingUpSeen(any())).thenReturn(
                new UserSettingsResponse("testuser", "test@example.com", "PRO_USER",
                        null, null, null, null, null, null, null,
                        java.time.LocalDate.of(2026, 8, 29), null, false, false));

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
    @WithMockUser(roles = "PRO_USER")
    @DisplayName("POST /api/user/settings/drive-times/refresh returns update count")
    void refreshDriveTimes_returnsCount() throws Exception {
        when(settingsService.refreshDriveTimes(any())).thenReturn(
                new DriveTimeRefreshResponse(15, Instant.parse("2026-04-01T10:00:00Z")));

        mockMvc.perform(post("/api/user/settings/drive-times/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locationsUpdated").value(15));
    }

    @Test
    @WithMockUser(roles = "PRO_USER")
    @DisplayName("POST drive-times/refresh returns 400 when no home set")
    void refreshDriveTimes_noHome_returns400() throws Exception {
        when(settingsService.refreshDriveTimes(any())).thenThrow(
                new ResponseStatusException(BAD_REQUEST, "Set a home location"));

        mockMvc.perform(post("/api/user/settings/drive-times/refresh"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "PRO_USER")
    @DisplayName("POST drive-times/refresh returns 429 when rate limited")
    void refreshDriveTimes_rateLimited_returns429() throws Exception {
        when(settingsService.refreshDriveTimes(any())).thenThrow(
                new ResponseStatusException(TOO_MANY_REQUESTS, "Recently refreshed"));

        mockMvc.perform(post("/api/user/settings/drive-times/refresh"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @WithMockUser(roles = "PRO_USER")
    @DisplayName("POST drive-times/refresh returns 409, with its reason, when the home moved while "
            + "drive times were measured")
    void refreshDriveTimes_homeMoved_returns409WithReason() throws Exception {
        // The status the settings dialog receives when a postcode save landed under its spinner.
        // It renders any status but 429 and 400 as "Something went wrong — please try again",
        // which is the remedy: the save released the cooldown, so the retry is allowed.
        when(settingsService.refreshDriveTimes(any())).thenThrow(
                new ResponseStatusException(CONFLICT, "Your home location changed while drive times "
                        + "were being calculated, so nothing was saved."));

        mockMvc.perform(post("/api/user/settings/drive-times/refresh"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Your home location changed while drive times "
                        + "were being calculated, so nothing was saved."));
    }

    @Test
    @WithMockUser(roles = "PRO_USER")
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
    @WithMockUser(roles = "PRO_USER")
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
    @WithMockUser(roles = "ADMIN")
    @DisplayName("The two drive-time routes answer an Admin")
    void driveTimeRoutes_adminAllowed() throws Exception {
        when(settingsService.refreshDriveTimes(any(Authentication.class))).thenReturn(
                new DriveTimeRefreshResponse(3, Instant.parse("2026-04-01T10:00:00Z")));
        when(reachService.getReach(any(Authentication.class))).thenReturn(List.of());

        mockMvc.perform(post("/api/user/settings/drive-times/refresh")).andExpect(status().isOk());
        mockMvc.perform(get("/api/user/settings/reach")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "LITE_USER")
    @DisplayName("The two drive-time routes are 403 for a Lite account and reach no service")
    void driveTimeRoutes_liteForbidden() throws Exception {
        mockMvc.perform(post("/api/user/settings/drive-times/refresh"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/user/settings/reach")).andExpect(status().isForbidden());

        verifyNoInteractions(settingsService, driveTimeResolver, reachService);
    }

    @Test
    @WithMockUser(roles = "LITE_USER")
    @DisplayName("GET /api/user/settings stays open to a Lite account")
    void getSettings_liteAllowed() throws Exception {
        when(settingsService.getSettings(any(Authentication.class))).thenReturn(
                new UserSettingsResponse("lite", "lite@example.com", "LITE_USER", null, null, null, null,
                        null, null, null, null, null, false, false));

        mockMvc.perform(get("/api/user/settings")).andExpect(status().isOk());
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

    // ---- postcode lookup failures (login-registration plan §1 #11, §2 #5) ----

    @Test
    @WithMockUser
    @DisplayName("POST /home/lookup answers 400 with the fixed message for a postcode that does not "
            + "exist (it answered 500 through the catch-all before R4a)")
    void lookupPostcode_notFound_returns400() throws Exception {
        when(settingsService.lookupPostcode(anyString()))
                .thenThrow(new PostcodeLookupException("Invalid postcode"));

        mockMvc.perform(post("/api/user/settings/home/lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"postcode\": \"ZZ9 9ZZ\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid postcode"));
    }

    @Test
    @WithMockUser
    @DisplayName("POST /home/lookup answers 502 with the fixed message when postcodes.io is down, "
            + "never echoing an upstream message")
    void lookupPostcode_unavailable_returns502() throws Exception {
        when(settingsService.lookupPostcode(anyString()))
                .thenThrow(new PostcodeLookupUnavailableException());

        mockMvc.perform(post("/api/user/settings/home/lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"postcode\": \"DH1 3LE\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("Postcode lookup is unavailable right now"));
    }

    // ---- POST /home/reverse ----

    @Test
    @WithMockUser
    @DisplayName("POST /home/reverse hands the point to the service and answers the postcode")
    void reverseLookup_returnsResult() throws Exception {
        when(settingsService.reverseLookupPostcode(54.776, -1.575)).thenReturn(
                new PostcodeLookupResult("DH1 3LE", 54.7761, -1.5733, "Durham, County Durham"));

        mockMvc.perform(post("/api/user/settings/home/reverse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\": 54.776, \"longitude\": -1.575}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postcode").value("DH1 3LE"))
                .andExpect(jsonPath("$.latitude").value(54.7761))
                .andExpect(jsonPath("$.longitude").value(-1.5733))
                .andExpect(jsonPath("$.placeName").value("Durham, County Durham"));
    }

    @Test
    @WithMockUser(roles = "LITE_USER")
    @DisplayName("A LITE account may use /home/reverse and /home/lookup — light times are free")
    void reverseAndLookup_liteAllowed() throws Exception {
        when(settingsService.reverseLookupPostcode(54.776, -1.575)).thenReturn(
                new PostcodeLookupResult("DH1 3LE", 54.7761, -1.5733, "Durham, County Durham"));
        when(settingsService.lookupPostcode(anyString())).thenReturn(
                new PostcodeLookupResult("DH1 3LE", 54.7761, -1.5733, "Durham, County Durham"));

        mockMvc.perform(post("/api/user/settings/home/reverse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\": 54.776, \"longitude\": -1.575}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/user/settings/home/lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"postcode\": \"DH1 3LE\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser
    @DisplayName("POST /home/reverse answers 400 with the service's fixed message for a bad value")
    void reverseLookup_badValue_returns400() throws Exception {
        when(settingsService.reverseLookupPostcode(any(), any()))
                .thenThrow(new IllegalArgumentException("latitude must be between -90 and 90"));

        mockMvc.perform(post("/api/user/settings/home/reverse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\": 91, \"longitude\": 0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("latitude must be between -90 and 90"));
    }

    @Test
    @WithMockUser
    @DisplayName("POST /home/reverse answers 400 'No UK postcode found' for a point with none near it")
    void reverseLookup_notFound_returns400() throws Exception {
        when(settingsService.reverseLookupPostcode(any(), any()))
                .thenThrow(new PostcodeLookupException("No UK postcode found for that location"));

        mockMvc.perform(post("/api/user/settings/home/reverse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\": 0, \"longitude\": 0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("No UK postcode found for that location"));
    }

    @Test
    @WithMockUser
    @DisplayName("POST /home/reverse answers 502 with a fixed message when postcodes.io is down")
    void reverseLookup_unavailable_returns502() throws Exception {
        when(settingsService.reverseLookupPostcode(any(), any()))
                .thenThrow(new PostcodeLookupUnavailableException());

        mockMvc.perform(post("/api/user/settings/home/reverse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\": 54.776, \"longitude\": -1.575}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("Postcode lookup is unavailable right now"));
    }

    @Test
    @WithMockUser
    @DisplayName("POST /home/reverse answers 400 for a body that is not JSON")
    void reverseLookup_unreadableBody_returns400() throws Exception {
        mockMvc.perform(post("/api/user/settings/home/reverse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /home/reverse returns 401 without authentication")
    void reverseLookup_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/user/settings/home/reverse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\": 54.776, \"longitude\": -1.575}"))
                .andExpect(status().isUnauthorized());
    }

    // ---- PUT /postcode-prompt-dismissed ----

    @Test
    @WithMockUser
    @DisplayName("PUT /postcode-prompt-dismissed takes no body and answers the settings with "
            + "postcodePromptDue false")
    void markPostcodePromptDismissed_returnsUpdatedSettings() throws Exception {
        when(settingsService.markPostcodePromptDismissed(any())).thenReturn(
                new UserSettingsResponse("testuser", "test@example.com", "PRO_USER",
                        null, null, null, null, null, null, null, null, null,
                        false, false));

        mockMvc.perform(put("/api/user/settings/postcode-prompt-dismissed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postcodePromptDue").value(false));
    }

    @Test
    @WithMockUser(roles = "LITE_USER")
    @DisplayName("A LITE account may dismiss the prompt — it is asked of every role")
    void markPostcodePromptDismissed_liteAllowed() throws Exception {
        when(settingsService.markPostcodePromptDismissed(any())).thenReturn(
                new UserSettingsResponse("testuser", "test@example.com", "LITE_USER",
                        null, null, null, null, null, null, null, null, null,
                        false, false));

        mockMvc.perform(put("/api/user/settings/postcode-prompt-dismissed"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("PUT /postcode-prompt-dismissed returns 401 without authentication")
    void markPostcodePromptDismissed_unauthenticated_returns401() throws Exception {
        mockMvc.perform(put("/api/user/settings/postcode-prompt-dismissed"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/user/settings carries postcodePromptDue true for a caller with neither "
            + "a postcode nor a dismissal")
    void getSettings_carriesThePromptFlag() throws Exception {
        when(settingsService.getSettings(any())).thenReturn(
                new UserSettingsResponse("testuser", "test@example.com", "PRO_USER",
                        null, null, null, null, null, null, null, null, null, true, false));

        mockMvc.perform(get("/api/user/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postcodePromptDue").value(true));
    }
}
