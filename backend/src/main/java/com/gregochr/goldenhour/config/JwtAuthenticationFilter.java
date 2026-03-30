package com.gregochr.goldenhour.config;

import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Servlet filter that validates a JWT Bearer token on every incoming request.
 *
 * <p>If a valid token is present the authenticated principal is placed into the
 * {@link SecurityContextHolder}. If the token is absent or invalid the filter
 * chain continues without setting an authentication — Spring Security will
 * then reject the request at the access-control layer.
 *
 * <p>Also updates the user's {@code lastActiveAt} timestamp, throttled to at most
 * once per hour to avoid excessive database writes.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    /** HTTP header carrying the bearer token. */
    private static final String AUTHORIZATION_HEADER = "Authorization";

    /** Required prefix for bearer tokens. */
    private static final String BEARER_PREFIX = "Bearer ";

    /** Minimum interval between lastActiveAt updates per user. */
    static final long ACTIVITY_UPDATE_INTERVAL_MINUTES = 60;

    private final JwtService jwtService;
    private final AppUserRepository userRepository;

    /**
     * Skips this filter on async dispatches (e.g. SSE emitter flushes). The initial
     * request already authenticated and set the security context; re-running the
     * filter on the async dispatch would fail because the query-param token is not
     * present on internal dispatches.
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Reads the {@code Authorization} header, extracts and validates the JWT,
     * and populates the security context when the token is valid.
     */
    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String token = extractToken(request);
        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!jwtService.validateToken(token)) {
            filterChain.doFilter(request, response);
            return;
        }

        String username = jwtService.extractUsername(token);
        UserRole role = jwtService.extractRole(token);

        if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            SimpleGrantedAuthority authority = new SimpleGrantedAuthority("ROLE_" + role.name());
            UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                    username, null, List.of(authority));
            SecurityContextHolder.getContext().setAuthentication(authToken);

            updateLastActiveIfStale(username);
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Updates the user's {@code lastActiveAt} timestamp if it is null or older than
     * {@link #ACTIVITY_UPDATE_INTERVAL_MINUTES} minutes. Failures are logged but do not
     * block the request.
     *
     * @param username the authenticated username
     */
    /**
     * Extracts the JWT token from the Authorization header, or from the {@code token}
     * query parameter for SSE paths (EventSource API cannot set HTTP headers).
     *
     * @param request the HTTP request
     * @return the JWT token string, or null if not present
     */
    private String extractToken(HttpServletRequest request) {
        String authHeader = request.getHeader(AUTHORIZATION_HEADER);
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            return authHeader.substring(BEARER_PREFIX.length());
        }

        // SSE paths: allow token as query parameter (EventSource cannot set headers)
        String path = request.getRequestURI();
        if (isSsePath(path)) {
            return request.getParameter("token");
        }

        return null;
    }

    /**
     * Returns {@code true} if the request path is an SSE endpoint that supports
     * query-param authentication.
     *
     * @param path the request URI
     * @return true for SSE paths
     */
    private boolean isSsePath(String path) {
        return path.matches("/api/forecast/run/\\d+/progress")
                || path.equals("/api/forecast/run/notifications")
                || path.equals("/api/status/stream");
    }

    private void updateLastActiveIfStale(String username) {
        try {
            userRepository.findByUsername(username).ifPresent(user -> {
                LocalDateTime now = LocalDateTime.now();
                LocalDateTime lastActive = user.getLastActiveAt();
                if (lastActive == null
                        || ChronoUnit.MINUTES.between(lastActive, now) >= ACTIVITY_UPDATE_INTERVAL_MINUTES) {
                    user.setLastActiveAt(now);
                    userRepository.save(user);
                }
            });
        } catch (Exception e) {
            LOG.warn("Failed to update lastActiveAt for user '{}': {}", username, e.getMessage());
        }
    }
}
