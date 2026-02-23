package com.gregochr.goldenhour.config;

import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Servlet filter that validates a JWT Bearer token on every incoming request.
 *
 * <p>If a valid token is present the authenticated principal is placed into the
 * {@link SecurityContextHolder}. If the token is absent or invalid the filter
 * chain continues without setting an authentication — Spring Security will
 * then reject the request at the access-control layer.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** HTTP header carrying the bearer token. */
    private static final String AUTHORIZATION_HEADER = "Authorization";

    /** Required prefix for bearer tokens. */
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

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

        String authHeader = request.getHeader(AUTHORIZATION_HEADER);
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(BEARER_PREFIX.length());
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
        }

        filterChain.doFilter(request, response);
    }
}
