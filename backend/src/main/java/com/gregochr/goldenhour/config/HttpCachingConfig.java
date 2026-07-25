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
     * <p>Note the deliberate omission of the outcome endpoints ({@code /api/outcome},
     * {@code /api/outcome/all}). Enabling an ETag requires {@code Cache-Control: private, no-cache},
     * which lets the browser persist the body to its on-disk HTTP cache (that is how a 304
     * reconstructs the body) — and, unlike the localStorage SWR cache, the browser HTTP cache cannot
     * be evicted from JavaScript on logout. Outcome responses carry user-authored free-text notes, so
     * they stay on Spring Security's {@code no-store} default rather than lingering at rest on a
     * shared machine after logout. These endpoints are small and change when the user records an
     * outcome, so the lost revalidation saving is negligible. The remaining endpoints are
     * system-generated forecast data, the user's own location config, and public tide/astronomical
     * data — acceptable in the browser's private per-profile cache.
     */
    private static final Set<String> REVALIDATABLE_READ_PATHS = Set.of(
            "/api/forecast",
            "/api/forecast/history",
            "/api/forecast/compare",
            "/api/locations",
            "/api/tides",
            "/api/tides/stats");

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
