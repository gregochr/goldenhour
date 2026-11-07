package com.gregochr.goldenhour.controller;

import java.util.Map;

import com.gregochr.goldenhour.config.JwtAuthenticationDetails;
import com.gregochr.goldenhour.exception.InvalidCredentialsException;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.service.AuthAttemptLimiter;
import com.gregochr.goldenhour.service.AuthAttemptLimiter.Window;
import com.gregochr.goldenhour.service.AuthenticationService;
import com.gregochr.goldenhour.service.CredentialChangeWriter.VersionExpectation;
import com.gregochr.goldenhour.service.JwtService;
import com.gregochr.goldenhour.service.PasswordResetService;
import com.gregochr.goldenhour.service.RegistrationService;
import com.gregochr.goldenhour.service.RegistrationService.Activation;
import com.gregochr.goldenhour.service.TurnstileGate;
import com.gregochr.goldenhour.service.TurnstileGate.Endpoint;
import com.gregochr.goldenhour.service.TurnstileGate.Outcome;
import com.gregochr.goldenhour.service.TurnstileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AuthController} driven directly, for the paths the security chain and the real services make awkward to
 * reach: a token issuer that refuses after a committed change, and the caller-identity helpers when the
 * security context holds nothing, something unauthenticated, or details of another kind.
 */
class AuthControllerDirectTest {

    private final AuthenticationService authenticationService = mock(AuthenticationService.class);
    private final AppUserRepository userRepository = mock(AppUserRepository.class);
    private final RegistrationService registrationService = mock(RegistrationService.class);
    private final TurnstileGate turnstileGate = mock(TurnstileGate.class);
    private final AuthAttemptLimiter attemptLimiter = mock(AuthAttemptLimiter.class);
    private final JwtService jwtService = mock(JwtService.class);
    private final AuthController controller = new AuthController(authenticationService, userRepository,
            registrationService, mock(TurnstileService.class), turnstileGate, attemptLimiter, jwtService,
            mock(PasswordResetService.class));

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void signInAs(String username) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                username, "n/a", AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
    }

    @Test
    @DisplayName("change-password is a 401 carrying the issuer's message when the account vanished mid-change")
    void changePassword_issuerRefuses_is401() {
        signInAs("admin");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/change-password");
        when(turnstileGate.admit(null, "admin", "127.0.0.1", Endpoint.CHANGE_PASSWORD))
                .thenReturn(Outcome.OK_VERIFIED);
        when(authenticationService.changePassword("admin", "golden2026", "NewPass1!",
                VersionExpectation.legacyUnchanged())).thenThrow(new InvalidCredentialsException("User not found"));

        ResponseEntity<Map<String, Object>> response = controller.changePassword(
                Map.of("currentPassword", "golden2026", "newPassword", "NewPass1!"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).isEqualTo(Map.of("error", "User not found"));
    }

    @Test
    @DisplayName("set-password is a 401 carrying the issuer's message when the activated account cannot be signed in")
    void setPassword_issuerRefuses_is401() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/set-password");
        when(jwtService.hashToken("verify-token")).thenReturn("abcdef0123456789-rest-of-the-hash");
        when(attemptLimiter.tryAcquire("set-password:global", 200, Window.HOUR)).thenReturn(true);
        when(attemptLimiter.tryAcquire("set-password:client:127.0.0.1", 20, Window.QUARTER_HOUR)).thenReturn(true);
        when(attemptLimiter.tryAcquire("set-password:token:abcdef0123456789", 10, Window.QUARTER_HOUR))
                .thenReturn(true);
        when(registrationService.setPasswordAndActivate("verify-token", "MyP@ssw0rd!"))
                .thenReturn(new Activation(42L, "activated-hash"));
        when(authenticationService.issueTokensFor(42L, "activated-hash"))
                .thenThrow(new InvalidCredentialsException("Invalid username or password"));

        ResponseEntity<Map<String, Object>> response = controller.setPassword(
                Map.of("token", "verify-token", "password", "MyP@ssw0rd!"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).isEqualTo(Map.of("error", "Invalid username or password"));
    }

    @Test
    @DisplayName("an empty security context is not a user: marketing opt-in is a 401 and touches no account")
    void marketingEmails_emptyContext_is401() {
        ResponseEntity<Map<String, Object>> response = controller.updateMarketingEmails(Map.of("optIn", true));

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).isEqualTo(Map.of("error", "Not authenticated"));
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("an unauthenticated token is not a user: marketing opt-in is a 401 and touches no account")
    void marketingEmails_unauthenticatedToken_is401() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.unauthenticated("admin", "x"));

        ResponseEntity<Map<String, Object>> response = controller.updateMarketingEmails(Map.of("optIn", true));

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("the session-version expectation is exact only for JWT details that carry a credential version")
    void sessionVersionExpectation_followsTheDetails() {
        assertThat(AuthController.sessionVersionExpectation()).isEqualTo(VersionExpectation.legacyUnchanged());

        signInAs("admin");
        assertThat(AuthController.sessionVersionExpectation()).isEqualTo(VersionExpectation.legacyUnchanged());

        UsernamePasswordAuthenticationToken foreign = UsernamePasswordAuthenticationToken.authenticated(
                "admin", "n/a", AuthorityUtils.createAuthorityList("ROLE_ADMIN"));
        foreign.setDetails(new WebAuthenticationDetails(new MockHttpServletRequest()));
        SecurityContextHolder.getContext().setAuthentication(foreign);
        assertThat(AuthController.sessionVersionExpectation()).isEqualTo(VersionExpectation.legacyUnchanged());

        UsernamePasswordAuthenticationToken legacyJwt = UsernamePasswordAuthenticationToken.authenticated(
                "admin", "n/a", AuthorityUtils.createAuthorityList("ROLE_ADMIN"));
        legacyJwt.setDetails(new JwtAuthenticationDetails(null, false));
        SecurityContextHolder.getContext().setAuthentication(legacyJwt);
        assertThat(AuthController.sessionVersionExpectation()).isEqualTo(VersionExpectation.legacyUnchanged());

        UsernamePasswordAuthenticationToken versioned = UsernamePasswordAuthenticationToken.authenticated(
                "admin", "n/a", AuthorityUtils.createAuthorityList("ROLE_ADMIN"));
        versioned.setDetails(new JwtAuthenticationDetails(5L, false));
        SecurityContextHolder.getContext().setAuthentication(versioned);
        assertThat(AuthController.sessionVersionExpectation()).isEqualTo(VersionExpectation.exact(5L));
    }
}
