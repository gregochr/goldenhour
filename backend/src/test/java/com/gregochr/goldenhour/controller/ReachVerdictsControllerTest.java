package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.Confidence;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.ReachVerdictResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The reach-verdicts endpoint (reach-scoped-card-plan R2 step 4): who may call it, what an away origin changes, how
 * a region with no base is refused, and the wire shape of the three tiers.
 */
class ReachVerdictsControllerTest extends AbstractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private static ReachVerdictResponse body() {
        ReachVerdictResponse.Window window = new ReachVerdictResponse.Window(
                LocalDate.of(2026, 10, 1), TargetType.SUNRISE, DisplayVerdict.WORTH_IT, 4.0, 4.1, Confidence.HIGH,
                false, null,
                new BriefingWindow.Pick(BriefingWindow.PickKind.BEST, "Northumberland", "Fiery", null, 4.0,
                        "Bamburgh", 7L),
                "Northumberland",
                List.of(new ReachVerdictResponse.RegionVerdict("Northumberland", DisplayVerdict.WORTH_IT, 4.0),
                        new ReachVerdictResponse.RegionVerdict("Lakes", DisplayVerdict.STAND_DOWN, null)));
        ReachVerdictResponse.Window poor = new ReachVerdictResponse.Window(
                LocalDate.of(2026, 10, 1), TargetType.SUNSET, DisplayVerdict.AWAITING, null, null, null,
                false, 3, null, null, List.of());
        return new ReachVerdictResponse(true, "abc123", Map.of("45", List.of(window), "90", List.of(window, poor),
                "150", List.of(window)));
    }

    private static Authentication named(String name) {
        return argThat(auth -> auth != null && name.equals(auth.getName()));
    }

    @Test
    @WithMockUser(username = "pro", roles = "PRO_USER")
    @DisplayName("a Pro reader gets the three tiers in one body, nullable figures omitted, and no caching")
    void proGetsAllThreeTiers() throws Exception {
        when(reachVerdictService.compute(named("pro"), eq((Long) null))).thenReturn(body());

        mockMvc.perform(get("/api/user/settings/reach-verdicts"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(header().doesNotExist("ETag"))
                .andExpect(jsonPath("$.measured").value(true))
                .andExpect(jsonPath("$.briefingVersion").value("abc123"))
                .andExpect(jsonPath("$.tiers['45'].length()").value(1))
                .andExpect(jsonPath("$.tiers['90'].length()").value(2))
                .andExpect(jsonPath("$.tiers['150'].length()").value(1))
                .andExpect(jsonPath("$.tiers['45'][0].date").value("2026-10-01"))
                .andExpect(jsonPath("$.tiers['45'][0].targetType").value("SUNRISE"))
                .andExpect(jsonPath("$.tiers['45'][0].displayVerdict").value("WORTH_IT"))
                .andExpect(jsonPath("$.tiers['45'][0].meanRating").value(4.0))
                .andExpect(jsonPath("$.tiers['45'][0].topMeanRating").value(4.1))
                .andExpect(jsonPath("$.tiers['45'][0].confidence").value("high"))
                .andExpect(jsonPath("$.tiers['45'][0].blanked").value(false))
                .andExpect(jsonPath("$.tiers['45'][0].thinCount").doesNotExist())
                .andExpect(jsonPath("$.tiers['45'][0].pick.kind").value("BEST"))
                .andExpect(jsonPath("$.tiers['45'][0].pick.locationName").value("Bamburgh"))
                .andExpect(jsonPath("$.tiers['45'][0].pickRegion").value("Northumberland"))
                .andExpect(jsonPath("$.tiers['45'][0].regions[0].regionName").value("Northumberland"))
                .andExpect(jsonPath("$.tiers['45'][0].regions[1].meanRating").doesNotExist())
                .andExpect(jsonPath("$.tiers['90'][1].meanRating").doesNotExist())
                .andExpect(jsonPath("$.tiers['90'][1].thinCount").value(3))
                .andExpect(jsonPath("$.tiers['90'][1].pick").doesNotExist())
                .andExpect(jsonPath("$.tiers['90'][1].pickRegion").doesNotExist())
                .andExpect(jsonPath("$.tiers['90'][1].regions.length()").value(0));
    }

    @Test
    @WithMockUser(username = "boss", roles = "ADMIN")
    @DisplayName("an Admin may call it, and ?origin= is passed to the engine as the away region's id")
    void adminWithAnOrigin() throws Exception {
        when(reachVerdictService.compute(named("boss"), eq(7L))).thenReturn(body());

        mockMvc.perform(get("/api/user/settings/reach-verdicts").param("origin", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measured").value(true));
    }

    @Test
    @WithMockUser(roles = "LITE_USER")
    @DisplayName("a Lite account is 403 and reaches no service")
    void liteIsForbidden() throws Exception {
        mockMvc.perform(get("/api/user/settings/reach-verdicts")).andExpect(status().isForbidden());

        verifyNoInteractions(reachVerdictService);
    }

    @Test
    @DisplayName("an anonymous caller is refused")
    void anonymousIsRefused() throws Exception {
        mockMvc.perform(get("/api/user/settings/reach-verdicts")).andExpect(status().isUnauthorized());

        verifyNoInteractions(reachVerdictService);
    }

    @Test
    @WithMockUser(username = "pro", roles = "PRO_USER")
    @DisplayName("a region with no base is a 400 carrying the engine's message")
    void regionWithoutBaseIs400() throws Exception {
        when(reachVerdictService.compute(named("pro"), eq(9L)))
                .thenThrow(new IllegalArgumentException("Region Nowhere has no base to plan from"));

        mockMvc.perform(get("/api/user/settings/reach-verdicts").param("origin", "9"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Region Nowhere has no base to plan from"));
    }

    @Test
    @WithMockUser(username = "pro", roles = "PRO_USER")
    @DisplayName("an unknown region is a 404")
    void unknownRegionIs404() throws Exception {
        when(reachVerdictService.compute(named("pro"), eq(10L)))
                .thenThrow(new NoSuchElementException("No region with id 10"));

        mockMvc.perform(get("/api/user/settings/reach-verdicts").param("origin", "10"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "pro", roles = "PRO_USER")
    @DisplayName("a reader with no measured drive gets measured false and three empty tiers")
    void unmeasuredReaderGetsEmptyTiers() throws Exception {
        when(reachVerdictService.compute(named("pro"), eq((Long) null))).thenReturn(
                new ReachVerdictResponse(false, null, Map.of("45", List.of(), "90", List.of(), "150", List.of())));

        mockMvc.perform(get("/api/user/settings/reach-verdicts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.measured").value(false))
                .andExpect(jsonPath("$.briefingVersion").doesNotExist())
                .andExpect(jsonPath("$.tiers['45'].length()").value(0))
                .andExpect(jsonPath("$.tiers['90'].length()").value(0))
                .andExpect(jsonPath("$.tiers['150'].length()").value(0));
    }
}
