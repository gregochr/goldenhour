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
        // Listed in its own right rather than inherited from "/api/briefing" above: the whitelist
        // is matched EXACTLY, never by prefix (pinned by whitelistIsExactMatchNotPrefix below), so
        // a projection of a revalidatable payload gets no revalidation of its own for free.
        "/api/briefing/digest",
        "/api/briefing/evaluate/scores",
        "/api/astro/conditions",
        "/api/astro/conditions/available-dates",
        "/api/aurora/status",
        // Role-free, dates only: the same body for every reader.
        "/api/aurora/nights",
        // Role-free alert level for the Lite banner: the same body for every reader.
        "/api/aurora/alert",
        "/api/nlc/sighting",
        // Met Office warnings in force: the same body for every signed-in reader, simulated rows excluded.
        "/api/warnings",
        "/api/almanac",
        // The shared region-base drive-time matrix. User-independent geography, which is the whole
        // reason it may be here at all — its per-user twin, /api/user/settings/reach, is pinned to
        // the exclusion list below.
        "/api/regions/drive-times",
        // Ask PhotoCast's Ready answers: user-less, identical for every reader of a scope.
        "/api/ask/ready",
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
        // Outcome rating: the caller's own ratings, and (admin) every user's for one window.
        "/api/outcome/ratings",
        "/api/outcome/ratings/all",
        "/api/travel-days",
        "/api/user/settings",
        // The window-first reach lens. Home-derived per-user data, and the reason it lives under
        // /api/user/settings rather than beside the briefing it is joined to on the client.
        "/api/user/settings/reach",
        // Today's light for the masthead. Names the caller's home postcode, so it belongs to this
        // family and not beside the almanac it superficially resembles.
        "/api/user/settings/light",
        // Map colour preferences (Stage 6). Write-only (PUT) today, so already excluded by the
        // GET-only filter — pinned anyway so a future GET added under this path cannot be missed.
        "/api/user/settings/map-colours",
        // The Map tab's phone tide mode (map-mobile-sheet-plan.md M4). Same shape and same reason
        // as map-colours, immediately above: write-only (PUT) today, pinned so a future GET cannot
        // be missed.
        "/api/user/settings/map-tide-mode",
        // The Coming up badge's last-seen write (plan D3/P5). Write-only (PUT) today, pinned for
        // the same reason as map-colours: per-user state must never gain a revalidatable GET here.
        "/api/user/settings/coming-up-seen",
        // Ask PhotoCast's allowance (B4): the caller's own count today. A GET, so unlike the entries
        // above it is the one that WOULD be filtered if the whitelist ever matched by prefix.
        "/api/user/settings/ask",
        // The one-time sign-in postcode prompt (login-registration plan §2 #3, R4a): the dismissal
        // write and the reverse lookup. Both POST/PUT today, pinned so a future GET under either
        // path cannot be missed — the reverse body is derived from the caller's own location.
        "/api/user/settings/postcode-prompt-dismissed",
        // The one-time Bookmark-places hint dismissal (screen-spec D8): a PUT, but pinned with its sibling.
        "/api/user/settings/places-hint-dismissed",
        "/api/user/settings/home/reverse",
        // The caller's sub-region picks (sub-regions-plan S2). A GET, so it is the one that WOULD be
        // filtered if the whitelist ever matched by prefix; the body is the reader's own picks.
        "/api/user/settings/sub-regions",
        "/api/user/settings/reach-verdicts",
        // The caller's favourites (find-and-favourites-plan F3): a GET of the reader's own starred places
        // and areas, merged. It would be filtered if the whitelist ever matched by prefix. Its per-item
        // write paths sit under it and are pinned below.
        "/api/user/settings/favourites",
        "/api/user/settings/favourites/places/7",
        "/api/user/settings/favourites/areas/7",
        // A watched almanac date (one-screen-plan D16): the key is the reader's own, so it is never filtered either.
        "/api/user/settings/favourites/dates/king-tide:2026-12-03",
        // Each area's figure for THIS reader (find-and-favourites-plan D3): gated members counted, a Lite
        // reader's picks implied by the counts. Shaped per reader, so it must never be ETag-shared.
        "/api/areas/outlook",
    })
    @DisplayName("Personal-data reads are never ETag-filtered")
    void personalDataPathsAreNeverFiltered(String path) {
        assertThat(filter.shouldNotFilter(get(path)))
                .as("%s carries user-authored or home-derived data and must stay no-store", path)
                .isTrue();
    }

    @Test
    @DisplayName("The admin unroutable-places read is never ETag-filtered (admin data stays no-store)")
    void adminUnroutableDriveTimesAreNeverFiltered() {
        assertThat(filter.shouldNotFilter(get("/api/admin/drive-times/unroutable"))).isTrue();
        assertThat(filter.shouldNotFilter(get("/api/admin/drive-times"))).isTrue();
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
    @DisplayName("Only the role-free area directory is revalidatable under /api/areas")
    void areaWhitelistIsTheDirectoryAlone() {
        assertThat(filter.shouldNotFilter(get("/api/areas/directory"))).isFalse();
        assertThat(filter.shouldNotFilter(get("/api/areas/outlook"))).isTrue();
        assertThat(filter.shouldNotFilter(get("/api/areas"))).isTrue();
        assertThat(filter.shouldNotFilter(get("/api/areas/uncovered-locations"))).isTrue();
        assertThat(filter.shouldNotFilter(get("/api/areas/directory/7"))).isTrue();
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("POST", "/api/areas/directory"))).isTrue();
    }

    @Test
    @DisplayName("Only the Ready read is revalidatable under /api/ask, and its scope query is ignored")
    void askWhitelistIsTheReadyReadAlone() {
        MockHttpServletRequest scoped = get("/api/ask/ready");
        scoped.setQueryString("scope=3");
        assertThat(filter.shouldNotFilter(scoped)).isFalse();
        // The typed endpoint is a write and personal; the admin lever is a write too.
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("POST", "/api/ask"))).isTrue();
        assertThat(filter.shouldNotFilter(get("/api/ask"))).isTrue();
        assertThat(filter.shouldNotFilter(get("/api/ask/ready/precompute"))).isTrue();
        assertThat(filter.shouldNotFilter(get("/api/admin/ask/ready/precompute"))).isTrue();
    }

    @Test
    @DisplayName("The typed endpoint is a POST and personal: never filtered, with or without a query string, "
            + "and the Ready read's entry cannot pull it in")
    void typedAskPostIsNeverFiltered() {
        MockHttpServletRequest plain = new MockHttpServletRequest("POST", "/api/ask");
        MockHttpServletRequest withQuery = new MockHttpServletRequest("POST", "/api/ask");
        withQuery.setQueryString("scope=3");
        MockHttpServletRequest readyPath = new MockHttpServletRequest("POST", "/api/ask/ready");

        assertThat(filter.shouldNotFilter(plain)).isTrue();
        assertThat(filter.shouldNotFilter(withQuery)).isTrue();
        assertThat(filter.shouldNotFilter(readyPath)).as("even the Ready path, as a POST").isTrue();
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
        assertThat(filter.shouldNotFilter(get("/api/locations/anything"))).isTrue();
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

    @Test
    @DisplayName("Under a servlet context path the path is matched without the context prefix")
    void contextPathIsStrippedBeforeMatching() {
        MockHttpServletRequest whitelisted = get("/photocast/api/briefing");
        whitelisted.setContextPath("/photocast");
        assertThat(filter.shouldNotFilter(whitelisted)).isFalse();

        MockHttpServletRequest personal = get("/photocast/api/user/settings");
        personal.setContextPath("/photocast");
        assertThat(filter.shouldNotFilter(personal)).isTrue();
    }

    @Test
    @DisplayName("A root or empty context path leaves the path untouched")
    void rootOrEmptyContextPath_leavesPathUntouched() {
        MockHttpServletRequest root = get("/api/briefing");
        root.setContextPath("/");
        assertThat(filter.shouldNotFilter(root)).isFalse();

        MockHttpServletRequest empty = get("/api/briefing");
        empty.setContextPath("");
        assertThat(filter.shouldNotFilter(empty)).isFalse();
    }

    @Test
    @DisplayName("A context path the URI does not start with is ignored, so the raw URI is matched")
    void contextPathNotPrefixingTheUri_isIgnored() {
        MockHttpServletRequest request = get("/api/briefing");
        request.setContextPath("/elsewhere");

        assertThat(filter.shouldNotFilter(request)).isFalse();
    }

    @Test
    @DisplayName("A revalidatable response is marked private, no-cache and gets an ETag from its body")
    void revalidatableResponse_carriesCacheControlAndEtag() throws Exception {
        MockHttpServletRequest request = get("/api/briefing");
        org.springframework.mock.web.MockHttpServletResponse response =
                new org.springframework.mock.web.MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            res.setContentType("application/json");
            res.getWriter().write("{\"ok\":true}");
        });

        assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-cache");
        assertThat(response.getHeader("ETag")).isNotBlank();
        assertThat(response.getContentAsString()).isEqualTo("{\"ok\":true}");
    }

    @Test
    @DisplayName("The almanac is revalidated on every request (private, no-cache, no max-age), so a warning entry "
            + "can never be held longer than one revalidation (the licence's ten minutes)")
    void almanacIsAlwaysRevalidatedNeverMaxAged() throws Exception {
        org.springframework.mock.web.MockHttpServletResponse response =
                new org.springframework.mock.web.MockHttpServletResponse();

        filter.doFilter(get("/api/almanac"), response, (req, res) -> {
            res.setContentType("application/json");
            res.getWriter().write("{\"entries\":[]}");
        });

        assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-cache")
                .doesNotContain("max-age").doesNotContain("stale-while-revalidate");
    }

    @Test
    @DisplayName("The almanac ETag follows the body: a warning entry added, changed or cancelled changes the "
            + "validator, and an identical body keeps it")
    void almanacEtagMovesWithTheWarningSet() throws Exception {
        String without = "{\"entries\":[{\"id\":\"spring-tide:1\"}]}";
        String withWarning = "{\"entries\":[{\"id\":\"spring-tide:1\"},{\"id\":\"weather-warning:W1\","
                + "\"meta\":{\"level\":\"AMBER\"}}]}";
        String raised = withWarning.replace("AMBER", "RED");

        String etagWithout = etagOf("/api/almanac", without);

        assertThat(etagOf("/api/almanac", without)).isEqualTo(etagWithout);
        assertThat(etagOf("/api/almanac", withWarning)).isNotEqualTo(etagWithout);
        assertThat(etagOf("/api/almanac", raised)).isNotEqualTo(etagOf("/api/almanac", withWarning));
    }

    private String etagOf(String path, String body) throws Exception {
        org.springframework.mock.web.MockHttpServletResponse response =
                new org.springframework.mock.web.MockHttpServletResponse();
        filter.doFilter(get(path), response, (req, res) -> {
            res.setContentType("application/json");
            res.getWriter().write(body);
        });
        return response.getHeader("ETag");
    }

    private static MockHttpServletRequest get(String path) {
        return new MockHttpServletRequest("GET", path);
    }
}
