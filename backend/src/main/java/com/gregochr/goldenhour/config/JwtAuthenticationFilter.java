package com.gregochr.goldenhour.config;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.service.JwtService;
import com.gregochr.goldenhour.util.Authorities;
import com.gregochr.goldenhour.util.LogSanitizer;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Servlet filter that validates a JWT Bearer token on every incoming request.
 *
 * <p>A token authenticates only against the account row it names (login-registration plan §2 #13), so a
 * deleted, disabled, demoted or re-credentialed account stops authenticating at its next request rather
 * than when its 24-hour token expires. After {@code validateToken}, the account is loaded ONCE — the
 * query the {@code lastActiveAt} stamp already made — and the {@code SecurityContext} is set only when:
 * <ol>
 *   <li>the row exists;</li>
 *   <li>it is enabled;</li>
 *   <li>the token's {@code uid} claim, when present, is the row's id (a username freed by a deletion and
 *       taken again does not inherit an old token); and</li>
 *   <li>the token's {@code cv} claim EQUALS the row's {@code credentials_version}. A token with no
 *       {@code cv} (issued before the counter existed) falls back to the second-truncated {@code iat}
 *       rule, {@code credentialsChangedAt == null || !iat.isBefore(stamp.truncatedTo(SECONDS))}, for the
 *       token lifetime only.</li>
 * </ol>
 * The authority is built from the LOADED ROW's role, never from the token's {@code role} claim: a
 * demotion changes only the row. When any rule fails the context stays empty, the entry point answers
 * 401, and one INFO line says why. The token's {@code cv} rides on the {@code Authentication}'s details
 * as a {@link JwtAuthenticationDetails}, for the endpoint that must compare it under a row lock.
 *
 * <p>Also updates the user's {@code lastActiveAt} timestamp with a column-scoped update, throttled to at
 * most once per hour to avoid excessive database writes.
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
     * request already authenticated and set the security context for the async dispatch,
     * so re-running the filter there is unnecessary.
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Reads the {@code Authorization} header, extracts and validates the JWT,
     * and populates the security context when the token is valid for a live account.
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

        if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            AppUserEntity user = userRepository.findByUsername(username).orElse(null);
            Long tokenCv = jwtService.extractCredentialsVersion(token);
            String rejection = rejectionReason(token, tokenCv, user);
            if (rejection != null) {
                LOG.info("JWT rejected for user '{}': {}", LogSanitizer.sanitize(username), rejection);
            } else {
                SimpleGrantedAuthority authority =
                        new SimpleGrantedAuthority(Authorities.ROLE_PREFIX + user.getRole().name());
                UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                        username, null, List.of(authority));
                authToken.setDetails(new JwtAuthenticationDetails(tokenCv));
                SecurityContextHolder.getContext().setAuthentication(authToken);

                updateLastActiveIfStale(user);
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Decides whether a signature-valid token may authenticate against the account row it names.
     *
     * @param token   the validated token
     * @param tokenCv the token's {@code cv} claim, or null for a token that predates it
     * @param user    the account the token's subject names, or null if there is none
     * @return why the token is refused, or null when it may authenticate
     */
    private String rejectionReason(String token, Long tokenCv, AppUserEntity user) {
        if (user == null) {
            return "no such account";
        }
        if (!user.isEnabled()) {
            return "account disabled";
        }
        Long tokenUid = jwtService.extractUserId(token);
        if (tokenUid != null && !tokenUid.equals(user.getId())) {
            return "token belongs to another account id";
        }
        if (tokenCv != null) {
            return tokenCv == user.getCredentialsVersion() ? null : "credentials changed since issue";
        }
        Instant stamp = user.getCredentialsChangedAt();
        if (stamp == null) {
            return null;
        }
        Instant issuedAt = jwtService.extractIssuedAt(token);
        return issuedAt.isBefore(stamp.truncatedTo(ChronoUnit.SECONDS))
                ? "credentials changed since issue (legacy token)" : null;
    }

    /**
     * Extracts the JWT token from the {@code Authorization} header only. A token in the URL (query
     * string) is never read: URLs are logged by proxies and servers, and the event streams now send
     * the header like every other call.
     *
     * @param request the HTTP request
     * @return the JWT token string, or null if there is no Bearer header
     */
    private String extractToken(HttpServletRequest request) {
        String authHeader = request.getHeader(AUTHORIZATION_HEADER);
        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            return authHeader.substring(BEARER_PREFIX.length());
        }
        return null;
    }

    /**
     * Updates the user's {@code lastActiveAt} timestamp if it is null or older than
     * {@link #ACTIVITY_UPDATE_INTERVAL_MINUTES} minutes, with a column-scoped update. Failures are
     * logged but do not block the request.
     *
     * @param user the authenticated account, as loaded for this request
     */
    private void updateLastActiveIfStale(AppUserEntity user) {
        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime lastActive = user.getLastActiveAt();
            if (lastActive == null
                    || ChronoUnit.MINUTES.between(lastActive, now) >= ACTIVITY_UPDATE_INTERVAL_MINUTES) {
                userRepository.touchLastActiveAt(user.getId(), now);
            }
        } catch (Exception e) {
            LOG.warn("Failed to update lastActiveAt for user id {}: {}", user.getId(),
                    LogSanitizer.sanitize(e.getMessage()));
        }
    }
}
