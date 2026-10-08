package com.gregochr.goldenhour.config;

import com.gregochr.goldenhour.service.ask.AskProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * The one place "Ask is switched off" becomes a 404: while {@code photocast.ask.enabled} is false
 * every Ask request is answered 404 before it reaches a controller, the body is read or any guard
 * runs, so a switched-off feature has no surface. (The exception is
 * {@code GET /api/user/settings/ask}, which is outside this interceptor's paths and always answers,
 * so the client can tell "off" from "unreachable".)
 *
 * <p><b>Precedence is 401, then 403, then 404.</b> Interceptors run after Spring Security's filter
 * chain, so an anonymous caller is already 401. The admin routes are guarded by method security
 * ({@code @PreAuthorize}), which runs <em>inside</em> the controller and therefore after this
 * interceptor: an instance built with {@code adminRoute = true} stands down unless the caller holds
 * {@code ROLE_ADMIN}, so a non-admin gets the 403 {@code @PreAuthorize} gives them whatever the flag
 * says, and the 404 is only ever shown to someone who could have used the endpoint.
 *
 * <p>Work-level checks stay where the work is: {@code AskReadyPrecompute} (a precompute started by the
 * pipeline, not a request) and {@code AskSpendGuard} (typed spend) read the flag themselves.
 */
public class AskFlagInterceptor implements HandlerInterceptor {

    private static final String ADMIN_AUTHORITY = "ROLE_ADMIN";

    private final AskProperties properties;
    private final boolean adminRoute;

    /**
     * Creates the interceptor.
     *
     * @param properties the Ask settings (the {@code enabled} flag)
     * @param adminRoute true for the admin routes, whose non-admin callers are left to
     *                   {@code @PreAuthorize}'s 403
     */
    public AskFlagInterceptor(AskProperties properties, boolean adminRoute) {
        this.properties = properties;
        this.adminRoute = adminRoute;
    }

    /**
     * Answers 404 when Ask is off.
     *
     * @param request  the request (unused)
     * @param response the response
     * @param handler  the chosen handler (unused)
     * @return false after answering 404; true to carry on
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (properties.isEnabled() || (adminRoute && !isAdmin())) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        return false;
    }

    private static boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).anyMatch(ADMIN_AUTHORITY::equals);
    }
}
