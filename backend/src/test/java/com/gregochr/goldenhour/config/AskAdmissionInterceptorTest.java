package com.gregochr.goldenhour.config;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.service.ask.AskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Who {@link AskAdmissionInterceptor} admits: only an authenticated, non-anonymous POST is counted, and the
 * admitted user is left on the request for the controller.
 */
class AskAdmissionInterceptorTest {

    private final AskService askService = mock(AskService.class);
    private final AskAdmissionInterceptor interceptor = new AskAdmissionInterceptor(askService);
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("an authenticated POST is admitted and the admitted user is left on the request")
    void authenticatedPost_isAdmitted() {
        Authentication auth = UsernamePasswordAuthenticationToken.authenticated("reader", "n/a",
                AuthorityUtils.createAuthorityList("ROLE_PRO_USER"));
        SecurityContextHolder.getContext().setAuthentication(auth);
        AppUserEntity admitted = AppUserEntity.builder().username("reader").build();
        when(askService.admit(auth)).thenReturn(admitted);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ask");

        boolean proceed = interceptor.preHandle(request, response, new Object());

        assertThat(proceed).isTrue();
        assertThat(request.getAttribute(AskAdmissionInterceptor.ADMITTED_USER_ATTRIBUTE)).isSameAs(admitted);
    }

    @Test
    @DisplayName("a request with no authentication at all proceeds uncounted")
    void noAuthentication_isNotCounted() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ask");

        boolean proceed = interceptor.preHandle(request, response, new Object());

        assertThat(proceed).isTrue();
        assertThat(request.getAttribute(AskAdmissionInterceptor.ADMITTED_USER_ATTRIBUTE)).isNull();
        verifyNoInteractions(askService);
    }

    @Test
    @DisplayName("an unauthenticated token proceeds uncounted")
    void unauthenticatedToken_isNotCounted() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.unauthenticated("reader", "n/a"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ask");

        boolean proceed = interceptor.preHandle(request, response, new Object());

        assertThat(proceed).isTrue();
        assertThat(request.getAttribute(AskAdmissionInterceptor.ADMITTED_USER_ATTRIBUTE)).isNull();
        verifyNoInteractions(askService);
    }

    @Test
    @DisplayName("an anonymous token proceeds uncounted")
    void anonymousToken_isNotCounted() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("key", "anonymous",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ask");

        boolean proceed = interceptor.preHandle(request, response, new Object());

        assertThat(proceed).isTrue();
        assertThat(request.getAttribute(AskAdmissionInterceptor.ADMITTED_USER_ATTRIBUTE)).isNull();
        verifyNoInteractions(askService);
    }

    @Test
    @DisplayName("a GET is never counted, even when authenticated")
    void get_isNotCounted() {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "reader", "n/a", AuthorityUtils.createAuthorityList("ROLE_PRO_USER")));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/ask/ready");

        boolean proceed = interceptor.preHandle(request, response, new Object());

        assertThat(proceed).isTrue();
        verifyNoInteractions(askService);
    }
}
