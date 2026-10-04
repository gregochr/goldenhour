package com.gregochr.goldenhour.config;

import com.gregochr.goldenhour.service.RewindEventService;
import com.gregochr.goldenhour.util.LogSanitizer;
import com.gregochr.goldenhour.util.Rewind;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Honours the admin-only {@value #HEADER} request header: for the span of one GET it sets the
 * {@link Rewind} instant the application {@link java.time.Clock} answers with, so the payload
 * renders as it would have at that moment — a sunrise that has gone reads as still ahead, with its
 * verdict, stars and best bet in place.
 *
 * <p>Runs inside the security chain, after {@link JwtAuthenticationFilter}, so it can read the
 * caller's role. Three refusals, all deliberate:
 * <ul>
 *   <li><b>Non-admins are ignored, not refused.</b> The header is not theirs to send; the request
 *       is served at the real time and the attempt is logged once at INFO.</li>
 *   <li><b>Only GET.</b> A rewind is a way of looking, never a way of writing: a POST that ran
 *       under a rewound clock would stamp a job, a setting or a pick with a moment that never
 *       happened.</li>
 *   <li><b>A malformed instant is a 400</b>, for an admin — this is an admin tool, and a header
 *       that is silently ignored would hand back the live page as if it were the rewound one.</li>
 *   <li><b>Never under {@code /api/admin/}.</b> The {@code /api/admin/}-prefixed screens, the
 *       rewind menu itself among them, always read the real clock.</li>
 *   <li><b>Never into the future, and never further back than the data goes</b> — a 400 either
 *       way. The bound is not cosmetic: a few caches stamp their entries from the clock bean
 *       ({@code NoaaSwpcClient}'s NOAA snapshots among them), so a request rewound to a FUTURE
 *       instant would write an entry whose fetch time is ahead of the real clock, and that entry
 *       would read as fresh to every other user and to the aurora poller until that instant
 *       passed — a rewound value persisting server-side for people who never asked for one. A
 *       PAST instant stamps an entry that the next real request simply finds stale and
 *       refetches, which costs one request and nothing else. The far bound,
 *       {@link RewindEventService#PAST_DAYS} plus a day, is where the forecast serve window ends:
 *       beyond it there is nothing to render.</li>
 * </ul>
 *
 * <p>The instant is cleared in a {@code finally}: a thread that kept it would serve it to the next
 * request it handled, which under virtual threads is the next request from anyone.
 *
 * <p>⚠️ <b>Must stay un-{@code @Order}ed.</b> As a {@code @Component} it is registered twice —
 * by Boot as a plain servlet filter and by {@code SecurityConfig} inside the security chain, the
 * same shape {@link JwtAuthenticationFilter} has. An unordered filter bean sorts at
 * {@code LOWEST_PRECEDENCE}, after the security chain, so the in-chain run (which sees the
 * authenticated {@code SecurityContext}) executes first, marks the request filtered, and the outer
 * run is skipped by {@link OncePerRequestFilter}. An {@code @Order} below the chain's would make
 * the OUTER run go first with an empty context, log "non-admin", mark the request filtered — and
 * the rewind would silently never apply.
 */
@Component
public class RewindFilter extends OncePerRequestFilter {

    /** The request header carrying an ISO-8601 instant, e.g. {@code 2026-10-04T05:15:00Z}. */
    public static final String HEADER = "X-Rewind-To";

    /**
     * How far in the future an instant may lie and still count as "now": an admin's browser clock
     * set from a {@code datetime-local} input can run a few seconds ahead of the server's.
     */
    public static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(1);

    /** The furthest back a rewind may go: the forecast serve window plus a day. */
    public static final Duration MAX_AGE = Duration.ofDays(RewindEventService.PAST_DAYS + 1L);

    private static final Logger LOG = LoggerFactory.getLogger(RewindFilter.class);
    private static final String ADMIN_AUTHORITY = "ROLE_ADMIN";
    private static final String ADMIN_PREFIX = "/api/admin/";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String raw = request.getHeader(HEADER);
        if (raw == null || raw.isBlank()) {
            chain.doFilter(request, response);
            return;
        }
        String path = LogSanitizer.sanitize(request.getRequestURI());
        if (request.getRequestURI().startsWith(ADMIN_PREFIX)) {
            // The admin surfaces — the rewind menu itself among them — always see the real clock:
            // a rewound "passed" flag or "now" on the very screen that sets the rewind would be
            // circular, and nothing under this prefix is a page a photographer sees.
            chain.doFilter(request, response);
            return;
        }
        if (!"GET".equals(request.getMethod())) {
            LOG.info("Ignoring {} on a {} request to {} — a rewind only reads", HEADER,
                    request.getMethod(), path);
            chain.doFilter(request, response);
            return;
        }
        if (!isAdmin()) {
            LOG.info("Ignoring {} from a non-admin request to {}", HEADER, path);
            chain.doFilter(request, response);
            return;
        }
        Instant rewindTo;
        try {
            rewindTo = Instant.parse(raw.trim());
        } catch (DateTimeParseException e) {
            LOG.info("Refusing {} '{}' on {}: not an ISO-8601 instant", HEADER,
                    LogSanitizer.sanitize(raw), path);
            refuse(response, HEADER + " must be an ISO-8601 instant such as 2026-10-04T05:15:00Z");
            return;
        }
        // The REAL clock, deliberately not the bean: this filter is what makes the bean answer
        // otherwise, and a bound measured against the thing it bounds would measure nothing.
        Instant now = Instant.now();
        if (rewindTo.isAfter(now.plus(FUTURE_TOLERANCE))) {
            LOG.info("Refusing {} {} on {}: in the future", HEADER, rewindTo, path);
            refuse(response, HEADER + " must not be in the future");
            return;
        }
        if (rewindTo.isBefore(now.minus(MAX_AGE))) {
            LOG.info("Refusing {} {} on {}: more than {} days ago", HEADER, rewindTo, path,
                    MAX_AGE.toDays());
            refuse(response, HEADER + " must be within the last " + MAX_AGE.toDays() + " days");
            return;
        }
        Rewind.set(rewindTo);
        try {
            LOG.debug("Rewinding {} to {}", path, rewindTo);
            chain.doFilter(request, response);
        } finally {
            Rewind.clear();
        }
    }

    private static void refuse(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }

    private static boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(ADMIN_AUTHORITY::equals);
    }
}
