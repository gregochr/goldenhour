package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.AlmanacEvent;
import com.gregochr.goldenhour.model.AlmanacKind;
import com.gregochr.goldenhour.service.AlmanacService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link AlmanacController} — payload shape and, more importantly, who can
 * reach it.
 */
class AlmanacControllerTest extends AbstractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private static AlmanacEvent springRun() {
        return new AlmanacEvent(
                LocalDate.of(2026, 8, 16), LocalDate.of(2026, 8, 18),
                AlmanacKind.ALMANAC, "spring-tide", "Spring tide run",
                "The moon's alignment pulls the tide further out and further in than usual.",
                Map.of("range", "4.6 m"), List.of("Northumberland"));
    }

    private static AlmanacEvent datesOnly() {
        return new AlmanacEvent(
                LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 2),
                AlmanacKind.ALMANAC, "spring-tide", "Spring tide run", "detail",
                Map.of(), List.of());
    }

    @Test
    @DisplayName("GET /api/almanac returns the feed with spans and derived facts")
    @WithMockUser(roles = {"PRO_USER"})
    void returnsTheFeed() throws Exception {
        when(almanacService.getFeed(90)).thenReturn(List.of(springRun()));

        mockMvc.perform(get("/api/almanac"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].startDate").value("2026-08-16"))
                .andExpect(jsonPath("$[0].endDate").value("2026-08-18"))
                .andExpect(jsonPath("$[0].kind").value("ALMANAC"))
                .andExpect(jsonPath("$[0].type").value("spring-tide"))
                .andExpect(jsonPath("$[0].title").value("Spring tide run"))
                .andExpect(jsonPath("$[0].meta.range").value("4.6 m"))
                .andExpect(jsonPath("$[0].regions[0]").value("Northumberland"));
    }

    @Test
    @DisplayName("a degraded entry omits meta and regions entirely rather than sending empty "
            + "objects a client might render as a blank fact row")
    @WithMockUser(roles = {"PRO_USER"})
    void degradedEntriesOmitEmptyCollections() throws Exception {
        when(almanacService.getFeed(anyInt())).thenReturn(List.of(datesOnly()));

        mockMvc.perform(get("/api/almanac"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].meta").doesNotExist())
                .andExpect(jsonPath("$[0].regions").doesNotExist())
                .andExpect(jsonPath("$[0].startDate").value("2026-10-30"));
    }

    @Test
    @DisplayName("the days parameter is passed through to the service")
    @WithMockUser(roles = {"PRO_USER"})
    void passesTheRequestedHorizon() throws Exception {
        when(almanacService.getFeed(30)).thenReturn(List.of());

        mockMvc.perform(get("/api/almanac").param("days", "30"))
                .andExpect(status().isOk());

        verify(almanacService).getFeed(30);
    }

    @Test
    @DisplayName("defaults to 90 days when the parameter is absent")
    @WithMockUser(roles = {"PRO_USER"})
    void defaultsToNinetyDays() throws Exception {
        when(almanacService.getFeed(90)).thenReturn(List.of());

        mockMvc.perform(get("/api/almanac")).andExpect(status().isOk());

        verify(almanacService).getFeed(AlmanacService.DEFAULT_DAYS);
    }

    @Test
    @DisplayName("an out-of-range days value reaches the service to be clamped, rather than 400 — "
            + "a feed is more use to a miscoded client than a validation error")
    @WithMockUser(roles = {"PRO_USER"})
    void outOfRangeDaysIsNotRejected() throws Exception {
        when(almanacService.getFeed(anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/almanac").param("days", "100000"))
                .andExpect(status().isOk());

        verify(almanacService).getFeed(100000);
    }

    // -------------------------------------------------------------------------
    // Access — the Javadoc claims Bearer with no role gate. These pin that claim,
    // because "no @PreAuthorize" is the same source code as "someone forgot one".
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("a LITE user may read the feed — it is ephemeris, not premium detail")
    @WithMockUser(roles = {"LITE_USER"})
    void liteUsersMayRead() throws Exception {
        when(almanacService.getFeed(anyInt())).thenReturn(List.of(springRun()));

        mockMvc.perform(get("/api/almanac"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("an admin may read it too — no role is excluded")
    @WithMockUser(roles = {"ADMIN"})
    void adminsMayRead() throws Exception {
        when(almanacService.getFeed(anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/almanac")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("an unauthenticated request is rejected — Bearer is inherited from /api/**, so "
            + "this fails if that inheritance ever stops applying")
    void anonymousIsRejected() throws Exception {
        mockMvc.perform(get("/api/almanac")).andExpect(status().isUnauthorized());
    }
}
