package com.gregochr.goldenhour.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

/**
 * Registers HTTP conditional-request (ETag) support on the read-only GET endpoints the frontend
 * polls, so an unchanged payload revalidates as a cheap {@code 304 Not Modified} instead of
 * re-downloading the full body.
 *
 * <p>This complements the frontend stale-while-revalidate cache: the cache paints instantly from
 * localStorage, and the background revalidation it fires returns a 304 when nothing has changed —
 * the browser's own HTTP cache reconstructs the body below the XHR layer, so axios still sees a
 * full 200 with a populated body and no frontend change is needed.
 *
 * <p>The ETag is <em>body-derived</em> ({@link ShallowEtagHeaderFilter} hashes the rendered
 * response), which keeps it role-safe by construction: a LITE request revalidating with a PRO
 * client's stored ETag rebuilds the LITE body, whose hash differs, so it gets a fresh 200 rather
 * than a 304 into someone else's cached body.
 */
@Configuration
public class HttpCachingConfig {

    /**
     * Read GET endpoints whose responses are safe to revalidate with an ETag. Exact servlet paths
     * only — never a {@code /api/forecast/*} wildcard, so the SSE streams under
     * {@code /api/forecast/run/**} (which must never be buffered) and the write endpoints are
     * excluded. The one non-literal path, the lazy-detail endpoint, is matched by the strict
     * {@link #FORECAST_DETAIL_PATH} numeric-id pattern rather than by a wildcard.
     *
     * <p><strong>What is in, and why.</strong> Every entry is a read the app issues on the normal
     * load path — several on a poll and on every window focus — whose body carries only
     * system-generated content: Claude- or template-generated forecast prose, admin-managed location
     * config, and public tide/astronomical/space-weather data. None contains a user-authored field,
     * and role- or user-varying bodies are safe by construction because the ETag is body-derived (a
     * different body hashes differently, so it yields a fresh 200 rather than a 304 into someone
     * else's response).
     *
     * <p><strong>What is deliberately out, and why.</strong> Enabling an ETag requires
     * {@code Cache-Control: private, no-cache}, which lets the browser persist the body to its
     * on-disk HTTP cache (that is how a 304 reconstructs it) — and, unlike the localStorage SWR
     * cache, the browser HTTP cache cannot be evicted from JavaScript on logout. So anything
     * carrying user-authored text or home-derived personal data stays on Spring Security's
     * {@code no-store} default rather than lingering at rest on a shared machine:
     * <ul>
     *   <li>{@code /api/outcome}, {@code /api/outcome/all} — user-authored free-text notes.</li>
     *   <li>{@code /api/travel-days} — carries an optional free-text note plus absence dates.</li>
     *   <li>{@code /api/user/settings}, {@code /api/user/settings/drive-times} — home postcode /
     *       lat-lon and home-proximity data. Also fetched at most once, so there is nothing to save.</li>
     *   <li>{@code /api/user/settings/map-colours} — write-only (PUT), so it is excluded from this
     *       set by construction, but it lives under the same personal-data prefix and is pinned
     *       alongside its siblings so a later GET added here cannot be missed.</li>
     * </ul>
     * Admin-only and interaction-only reads are omitted too: they pay the response-buffering cost
     * with no repeat-fetch payoff, and every extra entry widens the surface that has to stay in
     * sync with the SSE exclusions.
     */
    private static final Set<String> REVALIDATABLE_READ_PATHS = Set.of(
            "/api/forecast",
            "/api/locations",
            "/api/tides",
            "/api/tides/stats",
            "/api/briefing",
            // The digest is a strict projection of "/api/briefing" above — same forecast,
            // same non-personal content, same revalidation. Listed in its own right because
            // this set is matched exactly, never by prefix, so a path cannot inherit the
            // treatment of the payload it derives from.
            "/api/briefing/digest",
            "/api/briefing/evaluate/scores",
            "/api/astro/conditions",
            "/api/astro/conditions/available-dates",
            "/api/aurora/status",
            "/api/nlc/sighting",
            // The almanac feed is ephemeris: identical for every user and stable for a whole day,
            // so it is the strongest revalidation candidate here. Safe to share because it carries
            // no per-user data — the rule that keeps "Close to home" off this list.
            "/api/almanac",
            // The region-base drive-time matrix: how far every location is from each region's base
            // town. Admin-managed geography, identical for every reader, and it changes only when
            // the nightly sweep runs — so it is the same kind of entry as `/api/locations`. It is
            // emphatically NOT the per-user reach map, which is the same numbers measured from the
            // reader's own house and stays on the never-revalidated `/api/user/settings/reach` for
            // the reason this list's own comment gives. The exact-match rule is what keeps those
            // two apart: no wildcard here could ever reach a `/api/user/settings*` path.
            "/api/regions/drive-times");

    /**
     * The lazy popup-detail endpoint, {@code GET /api/forecast/{id}}. Matched by a strict
     * numeric-id pattern so it can be revalidated without a {@code /api/forecast/*} wildcard that
     * would also swallow the SSE streams at {@code /api/forecast/run/notifications} and
     * {@code /api/forecast/run/{runId}/progress}.
     */
    private static final Pattern FORECAST_DETAIL_PATH = Pattern.compile("/api/forecast/\\d+");

    /**
     * Registers the ETag filter outermost (so it wraps the whole chain, including Spring Security).
     * It is mapped broadly across {@code /api/*} and then narrowed by
     * {@link RevalidatableReadEtagFilter#shouldNotFilter}, which is the single source of truth for
     * which responses are revalidatable — one predicate to read rather than a URL-pattern list that
     * has to be kept in sync with a wildcard's blast radius.
     *
     * @return the filter registration
     */
    @Bean
    public FilterRegistrationBean<ShallowEtagHeaderFilter> revalidatableReadEtagFilter() {
        FilterRegistrationBean<ShallowEtagHeaderFilter> registration =
                new FilterRegistrationBean<>(new RevalidatableReadEtagFilter());
        registration.addUrlPatterns("/api/*");
        registration.setName("revalidatableReadEtagFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /**
     * Whether a request path may be answered with an ETag. Exactly the literal read paths plus the
     * numeric-id detail endpoint — everything else (writes, SSE streams, admin endpoints, the
     * free-text outcome reads) is excluded.
     *
     * @param path the servlet path, with any context path already stripped
     * @return true when the path is revalidatable
     */
    private static boolean isRevalidatablePath(String path) {
        return REVALIDATABLE_READ_PATHS.contains(path) || FORECAST_DETAIL_PATH.matcher(path).matches();
    }

    /**
     * A {@link ShallowEtagHeaderFilter} that handles only GET requests and marks the response
     * {@code Cache-Control: private, no-cache} so the browser stores the per-user response and
     * always revalidates it (which is what makes the 304 possible).
     *
     * <p>Without an explicit Cache-Control, Spring Security's default {@code no-store} would stop
     * the browser caching the response at all, so no {@code If-None-Match} would ever be sent and
     * the ETag would be dead weight. The header is set before the chain proceeds, so Spring
     * Security's {@code CacheControlHeadersWriter} — which writes its default only when Cache-Control
     * is absent — leaves it untouched. Non-GET requests are skipped entirely, so writes keep the
     * secure {@code no-store} default.
     */
    static final class RevalidatableReadEtagFilter extends ShallowEtagHeaderFilter {

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            // Only GET responses are revalidatable; leave writes to Spring Security's no-store default.
            if (!HttpMethod.GET.matches(request.getMethod())) {
                return true;
            }
            String path = request.getRequestURI();
            String contextPath = request.getContextPath();
            if (contextPath != null && !contextPath.isEmpty() && !"/".equals(contextPath)
                    && path.startsWith(contextPath)) {
                path = path.substring(contextPath.length());
            }
            return !isRevalidatablePath(path);
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain filterChain) throws ServletException, IOException {
            response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-cache");
            super.doFilterInternal(request, response, filterChain);
        }
    }
}
