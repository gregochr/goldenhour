package com.gregochr.goldenhour.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Enforces the {@code passwordChangeRequired} flag on the server: an account that signed in with a temporary
 * password may call only the endpoints that let it replace that password, and every other {@code /api/}
 * request is a 403 {@code {"error":"Password change required","code":"PASSWORD_CHANGE_REQUIRED"}}.
 *
 * <p>Before this filter the flag was honoured only by the client, so a flagged session (or a stolen token
 * for one) could use the whole API. It is a filter of its own, registered in {@code SecurityConfig} after
 * {@link JwtAuthenticationFilter}, so the authentication filter stays single-purpose. The flag arrives on the
 * authenticated principal's {@link JwtAuthenticationDetails}, read from the row the JWT filter already
 * loaded: there is no second database read and no token claim.
 *
 * <p>It applies when the request is authenticated, the flag is set and the path is under {@code /api/}
 * but NOT under {@code /api/auth/} or {@code /api/waitlist}. Those two are {@code permitAll} and the
 * client attaches its Bearer token to every call, so blocking them would break reset-password,
 * set-password and logout for a flagged user who is signed in. Paths are judged with Spring Security's
 * {@code PathPatternRequestMatcher}, as the security chain's own rules are, so percent-encoded spellings
 * such as {@code /%61pi/...} are matched by their decoded form, exactly as MVC routes them. Unauthenticated
 * requests pass untouched
 * (they meet the entry point's 401 as ever), as do paths outside {@code /api/} (actuator, static). Async
 * and error dispatches are skipped, as in the JWT filter. The body follows the {@code {error, code}} shape
 * of {@link AskAccessDeniedHandler}.
 */
@Component
public class PasswordChangeRequiredFilter extends OncePerRequestFilter {

    /** The machine-readable code the client keys its redirect to the change-password page on. */
    public static final String CODE = "PASSWORD_CHANGE_REQUIRED";

    /** The human sentence of the refusal. */
    public static final String MESSAGE = "Password change required";

    private static final String BODY = "{\"error\":\"" + MESSAGE + "\",\"code\":\"" + CODE + "\"}";

    // The same matcher API and path semantics as SecurityConfig's rules, so the filter judges the DECODED,
    // context-relative path that Spring MVC will route (a raw-URI prefix test is bypassed by /%61pi/...).
    // A pattern ending in /** also matches the bare prefix, so /api/waitlist itself is exempt.
    private static final RequestMatcher API = PathPatternRequestMatcher.withDefaults().matcher("/api/**");
    private static final RequestMatcher AUTH = PathPatternRequestMatcher.withDefaults().matcher("/api/auth/**");
    private static final RequestMatcher WAITLIST =
            PathPatternRequestMatcher.withDefaults().matcher("/api/waitlist/**");

    /**
     * Skips async dispatches (e.g. SSE emitter flushes): the initial request was already judged.
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Refuses an authenticated, flagged caller on any {@code /api/} path outside the exempt ones.
     */
    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {
        if (isFlagged() && isGuardedPath(request)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(BODY);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean isFlagged() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getDetails() instanceof JwtAuthenticationDetails details
                && details.passwordChangeRequired();
    }

    private static boolean isGuardedPath(HttpServletRequest request) {
        return API.matches(request) && !AUTH.matches(request) && !WAITLIST.matches(request);
    }
}
