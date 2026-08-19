package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.CloseToHomeResponse;
import com.gregochr.goldenhour.service.UserSettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link CloseToHomeController}.
 *
 * <p>The controller's whole job is to hand the service the RIGHT caller's home and id, so that is
 * what these assert — plus the two properties that make this endpoint different from its Plan-tab
 * neighbours: it requires authentication, and it returns an empty panel rather than an error when
 * the caller has set no home.
 */
class CloseToHomeControllerTest extends AbstractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private static UserSettingsService.HomeLocation home(Double lat, Double lon) {
        return new UserSettingsService.HomeLocation(7L, lat, lon, 30, "NE66 1NG");
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/briefing/close-to-home returns the panel")
    void closeToHome_returnsPanel() throws Exception {
        when(settingsService.getHomeLocation(any())).thenReturn(home(54.7753, -1.5849));
        CloseToHomeResponse.Card card = new CloseToHomeResponse.Card(
                1L, "Penshaw Monument", "Tyne and Wear", Set.of(LocationType.SEASCAPE),
                4, 9, 25, null, true);
        CloseToHomeResponse.Window window = new CloseToHomeResponse.Window(
                LocalDate.of(2026, 4, 22), TargetType.SUNSET,
                LocalDateTime.of(2026, 4, 22, 19, 30), 4, 2,
                true, "Tyne and Wear", true, "Tyne and Wear", List.of(card));
        when(closeToHomeService.build(any(), any(), any(), any())).thenReturn(
                new CloseToHomeResponse(22, 3, List.of(window),
                        new CloseToHomeResponse.Breadcrumb(true, LocalDate.of(2026, 4, 22),
                                TargetType.SUNSET, LocalDateTime.of(2026, 4, 22, 19, 30),
                                "Penshaw Monument", 4, "Good light", null, null, null)));

        mockMvc.perform(get("/api/briefing/close-to-home"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.radiusMiles").value(22))
                .andExpect(jsonPath("$.windows[0].targetType").value("SUNSET"))
                .andExpect(jsonPath("$.windows[0].notInBriefing").value(true))
                .andExpect(jsonPath("$.windows[0].sameWindowAsBestBet").value(true))
                .andExpect(jsonPath("$.windows[0].cards[0].locationName").value("Penshaw Monument"))
                .andExpect(jsonPath("$.windows[0].cards[0].locationTypes[0]").value("SEASCAPE"))
                .andExpect(jsonPath("$.windows[0].cards[0].lead").value(true))
                .andExpect(jsonPath("$.breadcrumb.worthIt").value(true));
    }

    @Test
    @WithMockUser
    @DisplayName("passes THIS caller's home and id to the service, not a default")
    void closeToHome_passesCallersOwnHomeAndId() throws Exception {
        when(settingsService.getHomeLocation(any())).thenReturn(home(54.7753, -1.5849));
        when(closeToHomeService.build(any(), any(), any(), any()))
                .thenReturn(CloseToHomeResponse.empty(22, 3));

        mockMvc.perform(get("/api/briefing/close-to-home")).andExpect(status().isOk());

        // Per-user data throughout: the id, the home AND the radius all belong to this caller.
        // Handing the service the wrong id would serve one user another's home-area forecast,
        // which is the failure mode this endpoint's whole design guards.
        verify(closeToHomeService).build(eq(7L), eq(54.7753), eq(-1.5849), eq(30));
    }

    @Test
    @WithMockUser
    @DisplayName("no home set returns an empty panel, not an error")
    void closeToHome_noHome_returnsEmptyPanel() throws Exception {
        when(settingsService.getHomeLocation(any())).thenReturn(home(null, null));
        when(closeToHomeService.build(any(), any(), any(), any()))
                .thenReturn(CloseToHomeResponse.empty(22, 3));

        mockMvc.perform(get("/api/briefing/close-to-home"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windows").isEmpty())
                .andExpect(jsonPath("$.breadcrumb.worthIt").value(false));
    }

    @Test
    @DisplayName("requires authentication — it is per-user data")
    void closeToHome_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/briefing/close-to-home"))
                .andExpect(status().isUnauthorized());
    }
}
