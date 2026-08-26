package com.gregochr.goldenhour.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Unit tests for the path predicate that decides which responses may carry an ETag.
 *
 * <p>This predicate is the only thing standing between a future whitelist edit and a silently
 * buffered — and therefore silently broken — SSE stream, so it is tested directly rather than only
 * through the MockMvc suite.
 */
class HttpCachingConfigTest {

    private final HttpCachingConfig.RevalidatableReadEtagFilter filter =
            new HttpCachingConfig.RevalidatableReadEtagFilter();

    /**
     * The streaming endpoints must never be filtered: {@code ShallowEtagHeaderFilter} buffers the
     * whole response to hash it, which would break a stream that is meant to stay open.
     *
     * <p>{@code /api/briefing/evaluate} is included even though it is not currently an SSE mapping —
     * it guards the case where a streaming endpoint is (re)introduced directly beneath the
     * whitelisted {@code /api/briefing} literal.
     *
     * @param path a streaming or stream-adjacent path
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "/api/status/stream",
        "/api/forecast/run/notifications",
        "/api/forecast/run/123/progress",
        "/api/briefing/evaluate",
    })
    @DisplayName("Streaming (and stream-adjacent) paths are never ETag-filtered")
    void streamingPathsAreNeverFiltered(String path) {
        assertThat(filter.shouldNotFilter(get(path)))
                .as("%s must be excluded — buffering it to hash the body would break the stream", path)
                .isTrue();
    }

    /**
     * Every whitelisted literal, plus the numeric-id detail endpoint, must be filtered.
     *
     * @param path a revalidatable read path
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "/api/forecast",
        "/api/forecast/42",
        "/api/locations",
        "/api/tides",
        "/api/tides/stats",
        "/api/briefing",
        "/api/briefing/evaluate/scores",
        "/api/astro/conditions",
        "/api/astro/conditions/available-dates",
        "/api/aurora/status",
        "/api/nlc/sighting",
        "/api/almanac",
        // The shared region-base drive-time matrix. User-independent geography, which is the whole
        // reason it may be here at all — its per-user twin, /api/user/settings/reach, is pinned to
        // the exclusion list below.
        "/api/regions/drive-times",
    })
    @DisplayName("Whitelisted read paths are ETag-filtered")
    void whitelistedReadPathsAreFiltered(String path) {
        assertThat(filter.shouldNotFilter(get(path)))
                .as("%s should be revalidatable", path)
                .isFalse();
    }

    /**
     * Paths holding user-authored text or home-derived personal data stay on {@code no-store}: the
     * browser's on-disk HTTP cache cannot be cleared from JavaScript on logout.
     *
     * @param path a deliberately excluded path
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "/api/outcome",
        "/api/outcome/all",
        "/api/travel-days",
        "/api/user/settings",
        "/api/user/settings/drive-times",
        // The window-first reach lens. Home-derived per-user data, and the reason it lives under
        // /api/user/settings rather than beside the briefing it is joined to on the client.
        "/api/user/settings/reach",
        // Today's light for the masthead. Names the caller's home postcode, so it belongs to this
        // family and not beside the almanac it superficially resembles.
        "/api/user/settings/light",
        // Map colour preferences (Stage 6). Write-only (PUT) today, so already excluded by the
        // GET-only filter — pinned anyway so a future GET added under this path cannot be missed.
        "/api/user/settings/map-colours",
        // Close to home is derived from the caller's home postcode and their own drive times.
        // It sits UNDER /api/briefing, which IS revalidated — so this entry is the one that
        // proves the whitelist's exact-match semantics are doing real work here, not just in
        // principle.
        "/api/briefing/close-to-home",
    })
    @DisplayName("Personal-data reads are never ETag-filtered")
    void personalDataPathsAreNeverFiltered(String path) {
        assertThat(filter.shouldNotFilter(get(path)))
                .as("%s carries user-authored or home-derived data and must stay no-store", path)
                .isTrue();
    }

    @Test
    @DisplayName("Only the region drive-time matrix is revalidatable under /api/regions")
    void regionWhitelistIsTheMatrixAlone() {
        // Exact match, so the matrix's entry cannot pull the region list or a write path in with
        // it. The list is a small read nothing polls; the writes must keep Spring Security's
        // no-store default.
        assertThat(filter.shouldNotFilter(get("/api/regions/drive-times"))).isFalse();
        assertThat(filter.shouldNotFilter(get("/api/regions"))).isTrue();
        assertThat(filter.shouldNotFilter(get("/api/regions/7"))).isTrue();
        assertThat(filter.shouldNotFilter(get("/api/regions/7/base"))).isTrue();
        assertThat(filter.shouldNotFilter(get("/api/regions/drive-times/7"))).isTrue();
    }

    @Test
    @DisplayName("The detail regex is strict: only a bare numeric id matches")
    void detailRegexMatchesOnlyBareNumericIds() {
        // The strictness of the \d+ anchor is what keeps /api/forecast/run/... out of scope.
        assertThat(filter.shouldNotFilter(get("/api/forecast/42"))).isFalse();
        assertThat(filter.shouldNotFilter(get("/api/forecast/42/extra"))).isTrue();
        assertThat(filter.shouldNotFilter(get("/api/forecast/abc"))).isTrue();
        assertThat(filter.shouldNotFilter(get("/api/forecast/run"))).isTrue();
    }

    @Test
    @DisplayName("A whitelisted literal does not shadow paths beneath it")
    void whitelistIsExactMatchNotPrefix() {
        // Set.contains is exact string equality — there is no prefix/Ant-pattern semantics here.
        // If this ever fails, someone swapped the Set for a prefix matcher and the SSE exclusions
        // above are no longer guaranteed.
        assertThat(filter.shouldNotFilter(get("/api/briefing/anything"))).isTrue();
        assertThat(filter.shouldNotFilter(get("/api/locations/grid-cells"))).isTrue();
        assertThat(filter.shouldNotFilter(get("/api/tides/stats/all"))).isTrue();
    }

    @Test
    @DisplayName("Non-GET requests on a whitelisted path keep the secure no-store default")
    void nonGetRequestsAreNeverFiltered() {
        MockHttpServletRequest post = new MockHttpServletRequest("POST", "/api/briefing");
        assertThat(filter.shouldNotFilter(post)).isTrue();

        MockHttpServletRequest delete = new MockHttpServletRequest("DELETE", "/api/briefing");
        assertThat(filter.shouldNotFilter(delete)).isTrue();
    }

    @Test
    @DisplayName("A query string does not stop a path matching (the URI excludes it)")
    void queryStringDoesNotAffectMatching() {
        MockHttpServletRequest request = get("/api/astro/conditions");
        request.setQueryString("date=2026-07-26");
        assertThat(filter.shouldNotFilter(request)).isFalse();
    }

    private static MockHttpServletRequest get(String path) {
        return new MockHttpServletRequest("GET", path);
    }
}
