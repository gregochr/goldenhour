package com.gregochr.goldenhour.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Integration tests for {@link com.gregochr.goldenhour.config.HttpCachingConfig} — the ETag +
 * {@code Cache-Control} behaviour on the read GET endpoints, exercised through the full filter
 * chain (including Spring Security) so the header interaction is verified as it runs in production.
 */
class HttpCachingIntegrationTest extends AbstractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser
    @DisplayName("A whitelisted read GET carries an ETag and a revalidatable Cache-Control (not no-store)")
    void readEndpointCarriesEtagAndRevalidatableCacheControl() throws Exception {
        when(locationService.findAll()).thenReturn(List.of(loc(1L, "Ambleside")));

        MvcResult result = mockMvc.perform(get("/api/locations"))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andReturn();

        String cacheControl = result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL);
        // "no-cache" = store but always revalidate; must NOT be Spring Security's default "no-store"
        // (which would stop the browser caching the response, so no conditional request is ever sent).
        assertThat(cacheControl).contains("no-cache");
        assertThat(cacheControl).doesNotContain("no-store");
    }

    @Test
    @WithMockUser
    @DisplayName("An unchanged read revalidates as a 304 Not Modified")
    void unchangedReadRevalidatesAs304() throws Exception {
        when(locationService.findAll()).thenReturn(List.of(loc(1L, "Ambleside")));

        String etag = mockMvc.perform(get("/api/locations"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getHeader(HttpHeaders.ETAG);

        // Same body -> same body-derived ETag -> a matching If-None-Match short-circuits to 304.
        mockMvc.perform(get("/api/locations").header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified());
    }

    @Test
    @WithMockUser
    @DisplayName("A changed read returns a fresh 200 rather than a stale 304")
    void changedReadReturnsFresh200() throws Exception {
        when(locationService.findAll()).thenReturn(List.of(loc(1L, "Ambleside")));
        String etag = mockMvc.perform(get("/api/locations"))
                .andReturn()
                .getResponse()
                .getHeader(HttpHeaders.ETAG);

        // Body changes -> different hash -> the old ETag no longer matches -> fresh 200 with the new body.
        when(locationService.findAll()).thenReturn(List.of(loc(1L, "Ambleside"), loc(2L, "Durham UK")));
        mockMvc.perform(get("/api/locations").header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    @WithMockUser
    @DisplayName("A body-derived ETag is role-safe: a changed (role) body never 304s into a cached one")
    void bodyDerivedEtagIsRoleSafe() throws Exception {
        // The ETag hashes the rendered body, so any content difference (including role-varying scores)
        // yields a different validator — the mechanism that prevents a LITE request 304ing into a PRO
        // body. Model that here as a straightforward body difference under the same URL.
        when(locationService.findAll()).thenReturn(List.of(loc(1L, "Ambleside")));
        String etagA = mockMvc.perform(get("/api/locations"))
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        when(locationService.findAll()).thenReturn(List.of(loc(1L, "Bamburgh")));
        String etagB = mockMvc.perform(get("/api/locations"))
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        assertThat(etagA).isNotEqualTo(etagB);
    }

    @Test
    @WithMockUser
    @DisplayName("A non-whitelisted read GET keeps Spring Security's no-store and gets no ETag")
    void nonWhitelistedGetKeepsNoStoreAndNoEtag() throws Exception {
        when(regionService.findAll()).thenReturn(List.of());

        MvcResult result = mockMvc.perform(get("/api/regions"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.ETAG))
                .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    }

    @Test
    @WithMockUser
    @DisplayName("The outcome endpoint is deliberately excluded — free-text notes stay no-store, no ETag")
    void outcomeEndpointStaysNoStoreForPrivacy() throws Exception {
        // Outcomes carry user-authored free-text notes. They are kept on no-store so their bodies are
        // never persisted to the browser's on-disk HTTP cache (which JS can't evict on logout).
        when(outcomeService.queryAll(any(), any())).thenReturn(List.of());

        MvcResult result = mockMvc.perform(get("/api/outcome/all")
                        .param("from", "2026-01-01")
                        .param("to", "2026-01-07"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.ETAG))
                .andReturn();

        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    }

    @Test
    @WithMockUser
    @DisplayName("The lazy popup-detail endpoint is revalidatable and 304s when unchanged")
    void forecastDetailEndpointRevalidates() throws Exception {
        var entity = com.gregochr.goldenhour.entity.ForecastEvaluationEntity.builder()
                .id(42L)
                .targetDate(java.time.LocalDate.of(2026, 3, 8))
                .build();
        when(forecastEvaluationRepository.findById(42L)).thenReturn(java.util.Optional.of(entity));
        when(dtoMapper.toDto(org.mockito.ArgumentMatchers.eq(entity),
                org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(null);

        MvcResult first = mockMvc.perform(get("/api/forecast/42"))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andReturn();
        assertThat(first.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-cache");

        mockMvc.perform(get("/api/forecast/42")
                        .header(HttpHeaders.IF_NONE_MATCH, first.getResponse().getHeader(HttpHeaders.ETAG)))
                .andExpect(status().isNotModified());
    }

    @Test
    @WithMockUser(roles = {"ADMIN"})
    @DisplayName("The SSE stream paths are never given an ETag (they must not be buffered)")
    void sseStreamPathsAreNeverFiltered() throws Exception {
        // The detail endpoint is matched by a strict /api/forecast/\d+ pattern rather than a
        // /api/forecast/* wildcard precisely so these streaming paths stay untouched. Buffering an
        // SSE response to hash it would break the stream, so assert no ETag reaches them.
        mockMvc.perform(get("/api/forecast/run/notifications"))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG));

        mockMvc.perform(get("/api/forecast/run/1/progress"))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG));
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/briefing revalidates, and two unchanged reads produce the SAME ETag")
    void briefingIsRevalidatableAndByteStable() throws Exception {
        // The highest-value assertion in this class. The briefing is re-derived per request (hot
        // topics, aurora overlay); if any part of that leaked a now()-derived value the body would
        // differ on every call, so every poll would pay the hashing cost for a guaranteed 200 and
        // the whole optimisation would be worthless.
        when(briefingService.getCachedBriefingForApi()).thenReturn(briefingResponse());

        MvcResult first = mockMvc.perform(get("/api/briefing"))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andReturn();
        assertThat(first.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-cache");

        String etag = first.getResponse().getHeader(HttpHeaders.ETAG);
        MvcResult second = mockMvc.perform(get("/api/briefing")).andReturn();
        assertThat(second.getResponse().getHeader(HttpHeaders.ETAG))
                .as("an unchanged briefing must hash identically, or it can never 304")
                .isEqualTo(etag);

        mockMvc.perform(get("/api/briefing").header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified());
    }

    @Test
    @WithMockUser
    @DisplayName("GET /api/briefing still returns 204 before the first briefing is built")
    void briefingColdStartStillReturns204() throws Exception {
        // ShallowEtagHeaderFilter treats any 2xx GET as eligible and would hash an empty body;
        // the cold-start 204 (which the frontend branches on) must survive untouched.
        when(briefingService.getCachedBriefingForApi()).thenReturn(null);

        mockMvc.perform(get("/api/briefing"))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser
    @DisplayName("The Plan tab's batch-score endpoint revalidates and 304s when unchanged")
    void briefingEvaluateScoresRevalidates() throws Exception {
        when(evaluationViewService.forDateRange(any(), any(), any())).thenReturn(List.of());

        MvcResult first = mockMvc.perform(get("/api/briefing/evaluate/scores"))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andReturn();
        assertThat(first.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-cache");

        mockMvc.perform(get("/api/briefing/evaluate/scores")
                        .header(HttpHeaders.IF_NONE_MATCH, first.getResponse().getHeader(HttpHeaders.ETAG)))
                .andExpect(status().isNotModified());
    }

    @Test
    @WithMockUser
    @DisplayName("Reads holding personal data keep no-store and get no ETag")
    void personalDataReadsStayNoStore() throws Exception {
        // Encodes the exclusion decisions so a later reviewer can't quietly reverse them: the
        // browser's on-disk HTTP cache can't be evicted from JS on logout, so user-authored text
        // (outcome notes, travel-day notes) and home-derived data must never be stored there.
        when(outcomeService.queryAll(any(), any())).thenReturn(List.of());

        MvcResult outcomes = mockMvc.perform(get("/api/outcome/all")
                        .param("from", "2026-01-01").param("to", "2026-01-07"))
                .andExpect(header().doesNotExist(HttpHeaders.ETAG))
                .andReturn();
        assertThat(outcomes.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");

        MvcResult settings = mockMvc.perform(get("/api/user/settings")).andReturn();
        assertThat(settings.getResponse().getHeader(HttpHeaders.ETAG)).isNull();
    }

    private static DailyBriefingResponse briefingResponse() {
        return new DailyBriefingResponse(
                LocalDateTime.of(2026, 7, 25, 4, 0),
                "Worth a look at the coast tonight.",
                List.of(),
                List.of(),
                null,
                null,
                false,
                false,
                0,
                "SONNET",
                List.of(),
                List.of(),
                null);
    }

    private static LocationEntity loc(Long id, String name) {
        return LocationEntity.builder()
                .id(id)
                .name(name)
                .lat(54.4)
                .lon(-2.9)
                .createdAt(LocalDateTime.of(2026, 2, 22, 12, 0))
                .build();
    }
}
