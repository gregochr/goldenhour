package com.gregochr.goldenhour.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
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
     * only — deliberately <em>not</em> {@code /api/forecast/*}, so the SSE streams under
     * {@code /api/forecast/run/**} (which must never be buffered) and the write endpoints are
     * excluded. {@code /api/forecast/{id}} detail will join this list when the lazy-detail endpoint
     * ships.
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
    private static final String[] REVALIDATABLE_READ_PATHS = {
        "/api/forecast",
        "/api/forecast/history",
        "/api/forecast/compare",
        "/api/locations",
        "/api/tides",
        "/api/tides/stats",
    };

    /**
     * Registers the ETag filter outermost (so it wraps the whole chain, including Spring Security),
     * scoped to the read GET paths above.
     *
     * @return the filter registration
     */
    @Bean
    public FilterRegistrationBean<ShallowEtagHeaderFilter> revalidatableReadEtagFilter() {
        FilterRegistrationBean<ShallowEtagHeaderFilter> registration =
                new FilterRegistrationBean<>(new RevalidatableReadEtagFilter());
        registration.addUrlPatterns(REVALIDATABLE_READ_PATHS);
        registration.setName("revalidatableReadEtagFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
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
            return !HttpMethod.GET.matches(request.getMethod());
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain filterChain) throws ServletException, IOException {
            response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-cache");
            super.doFilterInternal(request, response, filterChain);
        }
    }
}
