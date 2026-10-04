package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.matchesRegex;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link RewindController} through the real security chain and the real {@code RewindEventService}
 * (only the roster repository and {@code BriefingService} are mocked by the base class), so the
 * wire shape — ISO instants, a {@code YYYY-MM-DD} date, newest first — is the one the Operations tab
 * will read.
 */
class RewindControllerTest extends AbstractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private static LocationEntity place(String name, double lat, double lon, LocationType... types) {
        LocationEntity e = new LocationEntity();
        e.setName(name);
        e.setLat(lat);
        e.setLon(lon);
        e.setLocationType(Set.of(types));
        e.setEnabled(true);
        return e;
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("GET /api/admin/rewind/events — 200, six events newest first, instants on the wire")
    void events_admin() throws Exception {
        when(locationRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(
                place("Durham", 54.78, -1.58, LocationType.LANDSCAPE),
                place("Lizard", 49.96, -5.20, LocationType.SEASCAPE),
                place("Hide", 55.0, -1.6, LocationType.WILDLIFE)));
        when(briefingService.getCachedGeneratedAt()).thenReturn(LocalDateTime.of(2026, 10, 4, 6, 42, 10));

        mockMvc.perform(get("/api/admin/rewind/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.now").value(endsWith("Z")))
                .andExpect(jsonPath("$.briefingGeneratedAt").value("2026-10-04T06:42:10Z"))
                .andExpect(jsonPath("$.events.length()").value(6))
                .andExpect(jsonPath("$.events[0].eventType").value("SUNSET"))
                .andExpect(jsonPath("$.events[1].eventType").value("SUNRISE"))
                .andExpect(jsonPath("$.events[0].date").value(matchesRegex("\\d{4}-\\d{2}-\\d{2}")))
                .andExpect(jsonPath("$.events[0].rewindTo").value(endsWith("Z")))
                .andExpect(jsonPath("$.events[0].earliest").value(endsWith("Z")))
                .andExpect(jsonPath("$.events[0].latest").value(endsWith("Z")))
                // The hide is not a sky location, so two of the three are measured.
                .andExpect(jsonPath("$.events[0].locationCount").value(2))
                // Two days ago is over whatever the clock says now.
                .andExpect(jsonPath("$.events[5].passed").value(true));
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("GET /api/admin/rewind/events — 403 for a non-admin")
    void events_nonAdmin_forbidden() throws Exception {
        mockMvc.perform(get("/api/admin/rewind/events")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/admin/rewind/events — 401 without authentication")
    void events_unauthenticated() throws Exception {
        mockMvc.perform(get("/api/admin/rewind/events")).andExpect(status().isUnauthorized());
    }
}
