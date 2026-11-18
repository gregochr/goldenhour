package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.AreaKind;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AreaDirectoryEntry;
import com.gregochr.goldenhour.model.AreaOutlookEntry;
import com.gregochr.goldenhour.model.AreaWindowOutlook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link AreaDirectoryController}: every signed-in role may read both endpoints
 * (the admin {@code AreaController} stays ADMIN-only), an unauthenticated caller is refused, and the
 * outlook is shaped and uncacheable for a Lite reader.
 */
class AreaDirectoryControllerTest extends AbstractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private static boolean isCaller(Authentication auth, String name) {
        return auth != null && name.equals(auth.getName());
    }

    private void stubDirectory(String caller) {
        when(areaDirectoryService.directoryFor(argThat(auth -> isCaller(auth, caller)))).thenReturn(List.of(
                new AreaDirectoryEntry(7L, "Hadrian's Wall", AreaKind.NAMED, List.of("Roman Wall"), null, null,
                        List.of(1L, 2L)),
                new AreaDirectoryEntry(9L, "Northumberland Coast", AreaKind.SUB_REGION, List.of(), 3L,
                        "North East", List.of(4L))));
    }

    @ParameterizedTest(name = "{0} may read the directory")
    @ValueSource(strings = {"ADMIN", "PRO_USER", "LITE_USER"})
    @DisplayName("GET /api/areas/directory is open to every signed-in role")
    void directory_everySignedInRole(String role) throws Exception {
        stubDirectory("reader");

        mockMvc.perform(get("/api/areas/directory")
                        .with(user("reader").roles(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(7))
                .andExpect(jsonPath("$[0].name").value("Hadrian's Wall"))
                .andExpect(jsonPath("$[0].kind").value("NAMED"))
                .andExpect(jsonPath("$[0].aliases[0]").value("Roman Wall"))
                .andExpect(jsonPath("$[0].locationIds.length()").value(2))
                .andExpect(jsonPath("$[1].kind").value("SUB_REGION"))
                .andExpect(jsonPath("$[1].regionId").value(3))
                .andExpect(jsonPath("$[1].regionName").value("North East"));
    }

    @Test
    @DisplayName("GET /api/areas/directory is 401 without a token and never reaches the service")
    void directory_unauthenticated() throws Exception {
        mockMvc.perform(get("/api/areas/directory")).andExpect(status().isUnauthorized());

        verifyNoInteractions(areaDirectoryService);
    }

    @Test
    @DisplayName("The admin area list is still ADMIN-only beside the new reader endpoint")
    @WithMockUser(roles = "LITE_USER")
    void adminAreaListStaysAdminOnly() throws Exception {
        mockMvc.perform(get("/api/areas")).andExpect(status().isForbidden());
    }

    private void stubOutlook(String caller) {
        when(areaOutlookService.outlookFor(argThat(auth -> isCaller(auth, caller)))).thenReturn(List.of(
                new AreaOutlookEntry(7L, List.of(new AreaWindowOutlook(LocalDate.of(2026, 11, 19),
                        TargetType.SUNRISE, 2, 5, 3, 4, 11L)))));
    }

    @ParameterizedTest(name = "{0} may read the outlook")
    @ValueSource(strings = {"ADMIN", "PRO_USER"})
    @DisplayName("GET /api/areas/outlook serves Pro and Admin the figure per window")
    void outlook_proAndAdmin(String role) throws Exception {
        stubOutlook("reader");

        mockMvc.perform(get("/api/areas/outlook")
                        .with(user("reader").roles(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].areaId").value(7))
                .andExpect(jsonPath("$[0].windows[0].date").value("2026-11-19"))
                .andExpect(jsonPath("$[0].windows[0].targetType").value("SUNRISE"))
                .andExpect(jsonPath("$[0].windows[0].ratedMembers").value(2))
                .andExpect(jsonPath("$[0].windows[0].totalMembers").value(5))
                .andExpect(jsonPath("$[0].windows[0].gatedMembers").value(3))
                .andExpect(jsonPath("$[0].windows[0].topRating").value(4))
                .andExpect(jsonPath("$[0].windows[0].topLocationId").value(11));
    }

    @Test
    @DisplayName("GET /api/areas/outlook for a Lite reader with enforcement on is no-store and has no ETag")
    @WithMockUser(username = "anna", roles = "LITE_USER")
    void outlook_liteShapedIsNoStore() throws Exception {
        when(subRegionProperties.isEnforce()).thenReturn(true);
        stubOutlook("anna");

        mockMvc.perform(get("/api/areas/outlook"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("ETag"))
                .andExpect(jsonPath("$[0].windows[0].gatedMembers").value(3));
    }

    @Test
    @DisplayName("GET /api/areas/outlook is open to a Lite reader with enforcement off")
    @WithMockUser(username = "anna", roles = "LITE_USER")
    void outlook_liteUnshaped() throws Exception {
        stubOutlook("anna");

        mockMvc.perform(get("/api/areas/outlook"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].areaId").value(7));
    }

    @Test
    @DisplayName("GET /api/areas/outlook is 401 without a token and never reaches the service")
    void outlook_unauthenticated() throws Exception {
        mockMvc.perform(get("/api/areas/outlook")).andExpect(status().isUnauthorized());

        verifyNoInteractions(areaOutlookService);
    }
}
