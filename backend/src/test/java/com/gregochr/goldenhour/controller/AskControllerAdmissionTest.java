package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.config.AskAdmissionInterceptor;
import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.repository.RegionRepository;
import com.gregochr.goldenhour.service.ask.AskReadyServing;
import com.gregochr.goldenhour.service.ask.AskRequest;
import com.gregochr.goldenhour.service.ask.AskResponse;
import com.gregochr.goldenhour.service.ask.AskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Direct tests of how {@link AskController#ask} obtains the admitted user: the one the interceptor left on the
 * request when it ran, or, when none is there, one it admits itself, so a request is counted exactly once.
 */
class AskControllerAdmissionTest {

    private final AskService askService = mock(AskService.class);
    private final AskController controller =
            new AskController(mock(AskReadyServing.class), mock(RegionRepository.class), askService);
    private final Authentication auth = UsernamePasswordAuthenticationToken.authenticated("reader", "n/a",
            AuthorityUtils.createAuthorityList("ROLE_PRO_USER"));
    private final AskRequest request = mock(AskRequest.class);
    private final AskResponse response = mock(AskResponse.class);

    @Test
    @DisplayName("uses the user the interceptor admitted and does not admit again")
    void ask_withInterceptorAttribute_doesNotAdmitAgain() {
        AppUserEntity admitted = AppUserEntity.builder().username("reader").build();
        MockHttpServletRequest http = new MockHttpServletRequest("POST", "/api/ask");
        http.setAttribute(AskAdmissionInterceptor.ADMITTED_USER_ATTRIBUTE, admitted);
        when(askService.ask(admitted, request)).thenReturn(response);

        ResponseEntity<?> result = controller.ask(request, auth, http);

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody()).isSameAs(response);
        verify(askService, never()).admit(auth);
    }

    @Test
    @DisplayName("admits the caller itself when the interceptor left no user on the request")
    void ask_withoutInterceptorAttribute_admitsItself() {
        AppUserEntity admitted = AppUserEntity.builder().username("reader").build();
        MockHttpServletRequest http = new MockHttpServletRequest("POST", "/api/ask");
        when(askService.admit(auth)).thenReturn(admitted);
        when(askService.ask(admitted, request)).thenReturn(response);

        ResponseEntity<?> result = controller.ask(request, auth, http);

        assertThat(result.getBody()).isSameAs(response);
        verify(askService).admit(auth);
    }

    @Test
    @DisplayName("a request attribute that is not a user is ignored and the caller is admitted afresh")
    void ask_withForeignAttribute_admitsItself() {
        AppUserEntity admitted = AppUserEntity.builder().username("reader").build();
        MockHttpServletRequest http = new MockHttpServletRequest("POST", "/api/ask");
        http.setAttribute(AskAdmissionInterceptor.ADMITTED_USER_ATTRIBUTE, "not a user");
        when(askService.admit(auth)).thenReturn(admitted);
        when(askService.ask(admitted, request)).thenReturn(response);

        ResponseEntity<?> result = controller.ask(request, auth, http);

        assertThat(result.getBody()).isSameAs(response);
        verify(askService).admit(auth);
    }
}
