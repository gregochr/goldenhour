package com.gregochr.goldenhour.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link PasswordChangeRequiredFilter}: a flagged, authenticated caller is refused on every
 * {@code /api/} path except {@code /api/auth/**} and {@code /api/waitlist}, with the {@code {error, code}}
 * body; everyone else passes.
 */
class PasswordChangeRequiredFilterTest {

    private static final String EXPECTED_BODY =
            "{\"error\":\"Password change required\",\"code\":\"PASSWORD_CHANGE_REQUIRED\"}";

    private final PasswordChangeRequiredFilter filter = new PasswordChangeRequiredFilter();

    private FilterChain chain;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        chain = mock(FilterChain.class);
        response = new MockHttpServletResponse();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void signedIn(Object details) {
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken("alice", null,
                List.of(new SimpleGrantedAuthority("ROLE_LITE_USER")));
        token.setDetails(details);
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private void run(String method, String path) throws Exception {
        filter.doFilter(new MockHttpServletRequest(method, path), response, chain);
    }

    @Test
    @DisplayName("a flagged caller on /api/briefing gets a 403 with exactly the code body, and the chain stops")
    void flagged_apiPath_isRefused() throws Exception {
        signedIn(new JwtAuthenticationDetails(2L, true));

        run("GET", "/api/briefing");

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString()).isEqualTo(EXPECTED_BODY);
        verify(chain, never()).doFilter(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/auth/change-password", "/api/auth/logout", "/api/auth/reset-password",
            "/api/auth/refresh", "/api/waitlist", "/api/waitlist/", "/api/auth"})
    @DisplayName("a flagged caller passes on the exempt paths, so change-password, logout and reset still work")
    void flagged_exemptPaths_pass(String path) throws Exception {
        signedIn(new JwtAuthenticationDetails(2L, true));

        run("POST", path);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/authentication", "/api/waitlisted", "/api/waitlistx", "/api/users", "/api/ask"})
    @DisplayName("a path that merely starts with an exempt name is NOT exempt")
    void flagged_siblingOfAnExemptPath_isRefused(String path) throws Exception {
        signedIn(new JwtAuthenticationDetails(2L, true));

        run("GET", path);

        assertThat(response.getStatus()).isEqualTo(403);
        verify(chain, never()).doFilter(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/index.html", "/assets/app.js", "/"})
    @DisplayName("a flagged caller passes outside /api/ (actuator, static)")
    void flagged_nonApiPaths_pass(String path) throws Exception {
        signedIn(new JwtAuthenticationDetails(2L, true));

        run("GET", path);

        verify(chain).doFilter(any(), any());
    }

    @Test
    @DisplayName("a percent-encoded spelling of /api/ cannot dodge the filter: /%61pi/... is judged decoded")
    void flagged_encodedApiPath_isRefused() throws Exception {
        signedIn(new JwtAuthenticationDetails(2L, true));

        run("GET", "/%61pi/user/settings/ask");

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).isEqualTo(EXPECTED_BODY);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("an encoded spelling of an exempt path is exempt, as it decodes to /api/auth/logout")
    void flagged_encodedExemptPath_passes() throws Exception {
        signedIn(new JwtAuthenticationDetails(2L, true));

        run("POST", "/api/%61uth/logout");

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    @Test
    @DisplayName("an unflagged caller passes on /api/briefing")
    void unflagged_passes() throws Exception {
        signedIn(new JwtAuthenticationDetails(2L, false));

        run("GET", "/api/briefing");

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    @Test
    @DisplayName("an unauthenticated request passes untouched, so the entry point's 401 is unchanged")
    void unauthenticated_passes() throws Exception {
        run("GET", "/api/briefing");

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    @Test
    @DisplayName("an authentication whose details are not the JWT filter's type is not treated as flagged")
    void foreignDetails_pass() throws Exception {
        signedIn("some other details");

        run("GET", "/api/briefing");

        verify(chain).doFilter(any(), any());
    }

    @Test
    @DisplayName("the context path is stripped before the /api/ and exemption tests")
    void contextPath_isStripped() throws Exception {
        signedIn(new JwtAuthenticationDetails(2L, true));
        MockHttpServletRequest exempt = new MockHttpServletRequest("POST", "/app/api/auth/change-password");
        exempt.setContextPath("/app");
        filter.doFilter(exempt, response, chain);
        verify(chain).doFilter(any(), any());

        MockHttpServletResponse refused = new MockHttpServletResponse();
        MockHttpServletRequest guarded = new MockHttpServletRequest("GET", "/app/api/briefing");
        guarded.setContextPath("/app");
        filter.doFilter(guarded, refused, mock(FilterChain.class));
        assertThat(refused.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("async dispatches are skipped, as in the JWT filter")
    void asyncDispatch_isSkipped() {
        assertThat(filter.shouldNotFilterAsyncDispatch()).isTrue();
    }
}
