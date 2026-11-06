package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.UnroutableLocationDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link AdminDriveTimesController}: admin-only, and never cacheable.
 */
class AdminDriveTimesControllerTest extends AbstractControllerTest {

    private static final String PATH = "/api/admin/drive-times/unroutable";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("ADMIN receives the unroutable locations, with no ETag and a no-store Cache-Control")
    void unroutable_asAdmin_returnsListUncached() throws Exception {
        when(unroutableLocationService.findUnroutable()).thenReturn(List.of(
                new UnroutableLocationDto(7L, "Helvellyn", 3, 3, 2, 4)));

        mockMvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("ETag"))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].locationId").value(7))
                .andExpect(jsonPath("$[0].name").value("Helvellyn"))
                .andExpect(jsonPath("$[0].unroutableHomes").value(3))
                .andExpect(jsonPath("$[0].judgedHomes").value(3))
                .andExpect(jsonPath("$[0].unroutableBases").value(2))
                .andExpect(jsonPath("$[0].judgedBases").value(4));
    }

    @Test
    @WithMockUser(roles = "PRO_USER")
    @DisplayName("PRO_USER receives 403 and the service is never read")
    void unroutable_asPro_isForbidden() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isForbidden());

        verifyNoInteractions(unroutableLocationService);
    }

    @Test
    @WithMockUser(roles = "LITE_USER")
    @DisplayName("LITE_USER receives 403 and the service is never read")
    void unroutable_asLite_isForbidden() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isForbidden());

        verifyNoInteractions(unroutableLocationService);
    }

    @Test
    @DisplayName("an unauthenticated request receives 401")
    void unroutable_anonymous_isUnauthorized() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    }
}
