package com.gregochr.goldenhour.controller;

import java.util.Map;

import com.gregochr.goldenhour.service.AuthAttemptLimiter;
import com.gregochr.goldenhour.service.UserService;
import com.gregochr.goldenhour.service.notification.UserEmailService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The 401 guard of {@link AccountController#deleteAccount} when the controller is reached without a real signed-in
 * principal. The security chain normally refuses these first, so the guard is exercised directly.
 */
class AccountControllerGuardTest {

    private final UserService userService = mock(UserService.class);
    private final UserEmailService userEmailService = mock(UserEmailService.class);
    private final AuthAttemptLimiter limiter = mock(AuthAttemptLimiter.class);
    private final AccountController controller = new AccountController(userService, userEmailService, limiter);

    private void assertRefusedUntouched(Authentication authentication) {
        ResponseEntity<Map<String, String>> response =
                controller.deleteAccount(authentication, Map.of("password", "Correct1!"));

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).isEqualTo(Map.of("error", "Not authenticated"));
        verifyNoInteractions(userService, userEmailService, limiter);
    }

    @Test
    @DisplayName("no authentication at all is 401 and nothing is checked, counted or sent")
    void nullAuthentication_is401() {
        assertRefusedUntouched(null);
    }

    @Test
    @DisplayName("an unauthenticated token is 401 and nothing is checked, counted or sent")
    void unauthenticatedToken_is401() {
        assertRefusedUntouched(UsernamePasswordAuthenticationToken.unauthenticated("reader", "Correct1!"));
    }

    @Test
    @DisplayName("an anonymous token is 401 and nothing is checked, counted or sent")
    void anonymousToken_is401() {
        assertRefusedUntouched(new AnonymousAuthenticationToken("key", "anonymous",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
    }
}
