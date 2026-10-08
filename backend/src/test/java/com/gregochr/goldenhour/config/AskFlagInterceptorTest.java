package com.gregochr.goldenhour.config;

import com.gregochr.goldenhour.service.ask.AskProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AskFlagInterceptor}: the flag decides, and the admin-route instance leaves a
 * caller without {@code ROLE_ADMIN} to method security's 403. The precedence through the real chain is
 * {@code AskFlagOffPrecedenceTest}'s.
 */
class AskFlagInterceptorTest {

    private final AskProperties properties = new AskProperties();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ask/ready");
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static void callerWith(String... roles) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("u", "n/a", roles));
    }

    @Test
    @DisplayName("with Ask on, both instances let every request through and answer nothing")
    void on_passesThrough() {
        properties.setEnabled(true);

        assertThat(new AskFlagInterceptor(properties, false).preHandle(request, response, new Object())).isTrue();
        assertThat(new AskFlagInterceptor(properties, true).preHandle(request, response, new Object())).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("with Ask off, the public instance answers 404 and stops the request, whoever the caller is")
    void off_publicRoute_is404() {
        callerWith("ROLE_LITE_USER");

        assertThat(new AskFlagInterceptor(properties, false).preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("with Ask off, the admin instance answers 404 for an admin")
    void off_adminRoute_admin_is404() {
        callerWith("ROLE_ADMIN");

        assertThat(new AskFlagInterceptor(properties, true).preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("with Ask off, the admin instance stands down for a non-admin and for no caller at all, so "
            + "the role check answers first")
    void off_adminRoute_nonAdmin_standsDown() {
        AskFlagInterceptor interceptor = new AskFlagInterceptor(properties, true);

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        callerWith("ROLE_PRO_USER");
        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
