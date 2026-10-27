package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.exception.AreaConflictException;
import com.gregochr.goldenhour.exception.SubRegionsLockedException;
import com.gregochr.goldenhour.model.SubRegionPolicyResponse;
import com.gregochr.goldenhour.model.SubRegionState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller tests for the sub-region picks API (sub-regions-plan S2): the caller's own picks under
 * {@code /api/user/settings/sub-regions}, the admin tools under {@code /api/users/{id}/sub-regions}, and the
 * admin limit under {@code /api/admin/sub-region-policy}. The change rule itself is proven in
 * {@code UserSubRegionServiceJpaTest}; here are the roles and the status codes.
 */
class UserSubRegionControllerTest extends AbstractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private static SubRegionState state(String status) {
        return new SubRegionState(true, 3,
                List.of(new SubRegionState.Pick(10L, "Wensleydale", "The Yorkshire Dales")),
                List.of(new SubRegionState.RegionOptions(1L, "The Yorkshire Dales", List.of(
                        new SubRegionState.Option(10L, "Wensleydale", 7, List.of("Aysgarth Falls", "Bolton Castle"))))),
                List.of(10L), status, LocalDate.of(2026, 11, 1), null, null, null, null);
    }

    // --- GET /api/user/settings/sub-regions ---

    @Test
    @WithMockUser(username = "anna", roles = "LITE_USER")
    @DisplayName("GET returns the state for a Lite reader, omitting absent optional fields")
    void get_asLite_returnsTheState() throws Exception {
        when(userSubRegionService.getState("anna")).thenReturn(state(SubRegionState.FREE_UNTIL));

        mockMvc.perform(get("/api/user/settings/sub-regions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enforced").value(true))
                .andExpect(jsonPath("$.limit").value(3))
                .andExpect(jsonPath("$.status").value("FREE_UNTIL"))
                .andExpect(jsonPath("$.freeUntil").value("2026-11-01"))
                .andExpect(jsonPath("$.picks[0].areaId").value(10))
                .andExpect(jsonPath("$.picks[0].name").value("Wensleydale"))
                .andExpect(jsonPath("$.options[0].regionName").value("The Yorkshire Dales"))
                .andExpect(jsonPath("$.options[0].subRegions[0].locationCount").value(7))
                .andExpect(jsonPath("$.options[0].subRegions[0].exampleLocations[1]").value("Bolton Castle"))
                .andExpect(jsonPath("$.suggested[0]").value(10))
                .andExpect(jsonPath("$.nextChangeOn").doesNotExist())
                .andExpect(jsonPath("$.freeUntilIfChosenToday").doesNotExist())
                .andExpect(jsonPath("$.nextChangeOnIfSavedToday").doesNotExist())
                .andExpect(jsonPath("$.overLimit").doesNotExist());
    }

    @Test
    @WithMockUser(username = "anna", roles = "LITE_USER")
    @DisplayName("GET serialises the two prospective dates as ISO dates when the service states them")
    void get_serialisesTheProspectiveDates() throws Exception {
        when(userSubRegionService.getState("anna")).thenReturn(new SubRegionState(true, 3, List.of(), List.of(),
                List.of(), SubRegionState.FIRST_CHOICE, null, null, null, LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 25)));

        mockMvc.perform(get("/api/user/settings/sub-regions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.freeUntilIfChosenToday").value("2026-11-01"))
                .andExpect(jsonPath("$.nextChangeOnIfSavedToday").value("2026-11-25"));
    }

    @Test
    @WithMockUser(username = "pat", roles = "PRO_USER")
    @DisplayName("GET is open to Pro: the service answers NOT_APPLICABLE")
    void get_asPro_isNotApplicable() throws Exception {
        when(userSubRegionService.getState("pat")).thenReturn(new SubRegionState(true, 3, List.of(), List.of(),
                List.of(), SubRegionState.NOT_APPLICABLE, null, null, null, null, null));

        mockMvc.perform(get("/api/user/settings/sub-regions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_APPLICABLE"));
    }

    @Test
    @DisplayName("GET without a login is 401")
    void get_anonymous_is401() throws Exception {
        mockMvc.perform(get("/api/user/settings/sub-regions")).andExpect(status().isUnauthorized());
    }

    // --- PUT /api/user/settings/sub-regions ---

    @Test
    @WithMockUser(username = "anna", roles = "LITE_USER")
    @DisplayName("PUT replaces the picks and answers 200 with the new state")
    void put_asLite_returnsTheNewState() throws Exception {
        when(userSubRegionService.replacePicks("anna", List.of(10L, 11L))).thenReturn(state(SubRegionState.OPEN));

        mockMvc.perform(put("/api/user/settings/sub-regions")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"areaIds\":[10,11]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    @WithMockUser(username = "pat", roles = "PRO_USER")
    @DisplayName("PUT is 403 for a Pro reader, and the service is never reached")
    void put_asPro_is403() throws Exception {
        mockMvc.perform(put("/api/user/settings/sub-regions")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"areaIds\":[10]}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(userSubRegionService);
    }

    @Test
    @WithMockUser(username = "boss", roles = "ADMIN")
    @DisplayName("PUT is 403 for an Admin, and the service is never reached")
    void put_asAdmin_is403() throws Exception {
        mockMvc.perform(put("/api/user/settings/sub-regions")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"areaIds\":[10]}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(userSubRegionService);
    }

    @Test
    @WithMockUser(username = "anna", roles = "LITE_USER")
    @DisplayName("PUT is 400 with the service's message for an empty list, an over-limit list, a duplicate or "
            + "a non-sub-region id")
    void put_badRequest_is400() throws Exception {
        when(userSubRegionService.replacePicks("anna", List.of()))
                .thenThrow(new IllegalArgumentException("Choose at least one sub-region"));

        mockMvc.perform(put("/api/user/settings/sub-regions")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"areaIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Choose at least one sub-region"));
    }

    @Test
    @WithMockUser(username = "anna", roles = "LITE_USER")
    @DisplayName("PUT inside the lock is 409 SUB_REGIONS_LOCKED with nextChangeOn")
    void put_locked_is409WithNextChangeOn() throws Exception {
        when(userSubRegionService.replacePicks("anna", List.of(12L)))
                .thenThrow(new SubRegionsLockedException(LocalDate.of(2026, 11, 25)));

        mockMvc.perform(put("/api/user/settings/sub-regions")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"areaIds\":[12]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUB_REGIONS_LOCKED"))
                .andExpect(jsonPath("$.nextChangeOn").value("2026-11-25"))
                .andExpect(jsonPath("$.error").value("Your sub-regions can next be changed on 2026-11-25"));
    }

    @Test
    @WithMockUser(username = "anna", roles = "LITE_USER")
    @DisplayName("PUT that loses a duplicate-key race is a plain 409")
    void put_conflict_is409() throws Exception {
        when(userSubRegionService.replacePicks("anna", List.of(10L)))
                .thenThrow(new AreaConflictException("changed at the same moment"));

        mockMvc.perform(put("/api/user/settings/sub-regions")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"areaIds\":[10]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("changed at the same moment"));
    }

    // --- admin: /api/users/{id}/sub-regions ---

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("an admin reads a reader's picks")
    void admin_get() throws Exception {
        when(userSubRegionService.getStateForAdmin(7L)).thenReturn(state(SubRegionState.OPEN));

        mockMvc.perform(get("/api/users/7/sub-regions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.picks[0].name").value("Wensleydale"))
                .andExpect(jsonPath("$.options[0].regionName").value("The Yorkshire Dales"))
                .andExpect(jsonPath("$.options[0].subRegions[0].exampleLocations[1]").value("Bolton Castle"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("an admin sets a reader's picks")
    void admin_put() throws Exception {
        when(userSubRegionService.adminReplacePicks(7L, List.of(10L))).thenReturn(state(SubRegionState.OPEN));

        mockMvc.perform(put("/api/users/7/sub-regions")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"areaIds\":[10]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("an admin's bad id list is 400 and an unknown user is 404")
    void admin_putErrors() throws Exception {
        when(userSubRegionService.adminReplacePicks(7L, List.of(99L)))
                .thenThrow(new IllegalArgumentException("Not a sub-region: 99"));
        when(userSubRegionService.adminReplacePicks(8L, List.of(10L)))
                .thenThrow(new NoSuchElementException("No user with id 8"));

        mockMvc.perform(put("/api/users/7/sub-regions")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"areaIds\":[99]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Not a sub-region: 99"));
        mockMvc.perform(put("/api/users/8/sub-regions")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"areaIds\":[10]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("an admin lifts a lock: 204; an unknown user is 404")
    void admin_clearLock() throws Exception {
        org.mockito.Mockito.doThrow(new NoSuchElementException("No user with id 9"))
                .when(userSubRegionService).clearLock(9L);

        mockMvc.perform(delete("/api/users/7/sub-regions/lock")).andExpect(status().isNoContent())
                .andExpect(content().string(""));
        mockMvc.perform(delete("/api/users/9/sub-regions/lock")).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "LITE_USER")
    @DisplayName("the admin tools are 403 for a Lite reader")
    void admin_tools_areForbiddenToLite() throws Exception {
        mockMvc.perform(get("/api/users/7/sub-regions")).andExpect(status().isForbidden());
        mockMvc.perform(put("/api/users/7/sub-regions")
                .contentType(MediaType.APPLICATION_JSON).content("{\"areaIds\":[10]}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/users/7/sub-regions/lock")).andExpect(status().isForbidden());

        verifyNoInteractions(userSubRegionService);
    }

    // --- admin: /api/admin/sub-region-policy ---

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("an admin reads the policy, with the over-limit count for a candidate")
    void policy_get() throws Exception {
        when(subRegionPolicyService.describe(null)).thenReturn(new SubRegionPolicyResponse(3, 24, 0, 3,
                LocalDateTime.of(2026, 10, 26, 12, 0), "migration"));
        when(subRegionPolicyService.describe(2)).thenReturn(new SubRegionPolicyResponse(3, 24, 5, 2,
                LocalDateTime.of(2026, 10, 26, 12, 0), "migration"));

        mockMvc.perform(get("/api/admin/sub-region-policy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.liteLimit").value(3))
                .andExpect(jsonPath("$.maxLimit").value(24))
                .andExpect(jsonPath("$.liteUsersOverLimit").value(0));
        mockMvc.perform(get("/api/admin/sub-region-policy").param("candidate", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.liteUsersOverLimit").value(5))
                .andExpect(jsonPath("$.candidateLimit").value(2));
    }

    @Test
    @WithMockUser(username = "chris", roles = "ADMIN")
    @DisplayName("an admin saves a limit; an out-of-range one is 400")
    void policy_put() throws Exception {
        when(subRegionPolicyService.update(2, "chris")).thenReturn(new SubRegionPolicyResponse(2, 24, 4, 2,
                LocalDateTime.of(2026, 10, 26, 12, 0), "chris"));
        when(subRegionPolicyService.update(99, "chris"))
                .thenThrow(new IllegalArgumentException("The limit must be between 1 and 24"));

        mockMvc.perform(put("/api/admin/sub-region-policy")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"liteLimit\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.liteLimit").value(2))
                .andExpect(jsonPath("$.liteUsersOverLimit").value(4))
                .andExpect(jsonPath("$.updatedBy").value("chris"));
        mockMvc.perform(put("/api/admin/sub-region-policy")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"liteLimit\":99}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("The limit must be between 1 and 24"));
    }

    @Test
    @WithMockUser(roles = "PRO_USER")
    @DisplayName("the policy is 403 for anyone but an admin")
    void policy_forbiddenToPro() throws Exception {
        mockMvc.perform(get("/api/admin/sub-region-policy")).andExpect(status().isForbidden());
        mockMvc.perform(put("/api/admin/sub-region-policy")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"liteLimit\":2}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(subRegionPolicyService);
    }
}
