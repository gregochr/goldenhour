package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.model.DrivePointCandidate;
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

    private static final String PLACES = "/api/admin/drive-times/places";

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("ADMIN's place lookup returns the candidates uncached, and an empty match is 200 with []")
    void places_asAdmin_returnsCandidatesUncached() throws Exception {
        when(drivePointLookupService.search("Glenridding", 54.5275, -3.0162)).thenReturn(List.of(
                new DrivePointCandidate("Glenridding", "Village, Cumberland", 54.5442, -2.9505, 4.6)));
        when(drivePointLookupService.search("Nowhere", 54.5275, -3.0162)).thenReturn(List.of());

        mockMvc.perform(get(PLACES).param("q", "Glenridding").param("lat", "54.5275").param("lon", "-3.0162"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("ETag"))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$[0].name").value("Glenridding"))
                .andExpect(jsonPath("$[0].detail").value("Village, Cumberland"))
                .andExpect(jsonPath("$[0].lat").value(54.5442))
                .andExpect(jsonPath("$[0].lon").value(-2.9505))
                .andExpect(jsonPath("$[0].distanceKm").value(4.6));
        mockMvc.perform(get(PLACES).param("q", "Nowhere").param("lat", "54.5275").param("lon", "-3.0162"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("a blank query is a 400 with the service's sentence")
    void places_blankQuery_is400() throws Exception {
        when(drivePointLookupService.search("  ", 54.5, -3.0))
                .thenThrow(new IllegalArgumentException("Type a village name or a postcode"));

        mockMvc.perform(get(PLACES).param("q", "  ").param("lat", "54.5").param("lon", "-3.0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Type a village name or a postcode"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("an unavailable postcode service is a 502 with the fixed sentence")
    void places_upstreamDown_is502() throws Exception {
        when(drivePointLookupService.search("Glen", 54.5, -3.0))
                .thenThrow(new com.gregochr.goldenhour.client.PostcodeLookupUnavailableException());

        mockMvc.perform(get(PLACES).param("q", "Glen").param("lat", "54.5").param("lon", "-3.0"))
                .andExpect(status().isBadGateway());
    }

    @Test
    @WithMockUser(roles = "PRO_USER")
    @DisplayName("PRO_USER and LITE_USER receive 403 from the place lookup and the service is never read")
    void places_nonAdmin_isForbidden() throws Exception {
        mockMvc.perform(get(PLACES).param("q", "Glen").param("lat", "54.5").param("lon", "-3.0"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(drivePointLookupService);
    }

    @Test
    @WithMockUser(roles = "LITE_USER")
    @DisplayName("LITE_USER is also forbidden")
    void places_lite_isForbidden() throws Exception {
        mockMvc.perform(get(PLACES).param("q", "Glen").param("lat", "54.5").param("lon", "-3.0"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(drivePointLookupService);
    }

    @Test
    @DisplayName("an unauthenticated place lookup receives 401")
    void places_anonymous_isUnauthorized() throws Exception {
        mockMvc.perform(get(PLACES).param("q", "Glen").param("lat", "54.5").param("lon", "-3.0"))
                .andExpect(status().isUnauthorized());
    }
}
