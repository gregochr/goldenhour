package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.exception.StaleCredentialsException;
import com.gregochr.goldenhour.service.AuthAttemptLimiter.Window;
import com.gregochr.goldenhour.service.CredentialChangeWriter.VersionExpectation;
import com.gregochr.goldenhour.service.DeletedAccount;
import com.gregochr.goldenhour.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link AccountController}: {@code DELETE /api/user/account}.
 *
 * <p>The service, the limiter and the mailer are mocks (the shared context rule); their own behaviour is
 * pinned in {@code UserServiceTest}, {@code AuthAttemptLimiterTest} and the {@code @DataJpaTest}
 * {@code UserServiceDeletionTest}.
 */
class AccountControllerTest extends AbstractControllerTest {

    private static final String BODY = "{\"password\":\"Correct1!\"}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        when(attemptLimiter.tryAcquire(anyString(), anyInt(), any())).thenReturn(true);
    }

    private void thePasswordIsCorrect() {
        when(userService.verifyPassword("reader", "Correct1!")).thenReturn(Optional.of("stored-hash"));
    }

    private void theDeletionSucceeds() {
        when(userService.deleteOwnAccount(eq("reader"), eq("stored-hash"), any()))
                .thenReturn(new DeletedAccount("reader", "stored@example.com"));
    }

    // ── success ─────────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "reader", roles = "PRO_USER")
    @DisplayName("204 No Content with no body for a PRO reader whose password is right; the email is sent to "
            + "the STORED address AFTER the transactional delete returns")
    void pro_correctPassword_returns204AndMailsAfterTheDelete() throws Exception {
        thePasswordIsCorrect();
        theDeletionSucceeds();

        mockMvc.perform(delete("/api/user/account").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        InOrder order = inOrder(userService, userEmailService);
        order.verify(userService).verifyPassword("reader", "Correct1!");
        order.verify(userService).deleteOwnAccount("reader", "stored-hash", VersionExpectation.legacyUnchanged());
        order.verify(userEmailService).sendAccountDeletedEmail("stored@example.com", "reader");
    }

    @Test
    @WithMockUser(username = "reader", roles = "LITE_USER")
    @DisplayName("a LITE reader may delete their own account: there is no role gate, the password is the gate")
    void lite_correctPassword_returns204() throws Exception {
        thePasswordIsCorrect();
        theDeletionSucceeds();

        mockMvc.perform(delete("/api/user/account").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNoContent());

        verify(userService).deleteOwnAccount(eq("reader"), eq("stored-hash"), any());
    }

    @Test
    @DisplayName("a token carrying cv holds the delete to EXACTLY that version, read from the filter's own "
            + "details (not a cast of Spring's)")
    void tokenWithCv_asksForTheExactVersion() throws Exception {
        AppUserEntity row = AppUserEntity.builder().id(7L).username("reader").password("stored-hash")
                .role(UserRole.PRO_USER).enabled(true).createdAt(LocalDateTime.of(2026, 1, 1, 12, 0)).build();
        row.setCredentialsVersion(3L);
        when(userRepository.findByUsername("reader")).thenReturn(Optional.of(row));
        String jwt = jwtService.generateAccessToken("reader", UserRole.PRO_USER, 7L, 3L);
        thePasswordIsCorrect();
        theDeletionSucceeds();

        mockMvc.perform(delete("/api/user/account").header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNoContent());

        verify(userService).deleteOwnAccount("reader", "stored-hash", VersionExpectation.exact(3L));
    }

    @Test
    @DisplayName("a token with NO cv (issued before the counter) is held to a never-changed row")
    void tokenWithoutCv_asksForTheLegacyExpectation() throws Exception {
        AppUserEntity row = AppUserEntity.builder().id(7L).username("reader").password("stored-hash")
                .role(UserRole.PRO_USER).enabled(true).createdAt(LocalDateTime.of(2026, 1, 1, 12, 0)).build();
        when(userRepository.findByUsername("reader")).thenReturn(Optional.of(row));
        String jwt = jwtService.generateAccessToken("reader", UserRole.PRO_USER);
        thePasswordIsCorrect();
        theDeletionSucceeds();

        mockMvc.perform(delete("/api/user/account").header("Authorization", "Bearer " + jwt)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isNoContent());

        verify(userService).deleteOwnAccount("reader", "stored-hash", VersionExpectation.legacyUnchanged());
    }

    @Test
    @WithMockUser(username = "Reader", roles = "PRO_USER")
    @DisplayName("the attempt is counted under delete:<normalised username>, five per quarter hour, BEFORE the "
            + "password is checked")
    void attemptIsCountedUnderTheNormalisedKey_beforeThePasswordCheck() throws Exception {
        when(userService.verifyPassword("Reader", "Correct1!")).thenReturn(Optional.empty());

        mockMvc.perform(delete("/api/user/account").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());

        InOrder order = inOrder(attemptLimiter, userService);
        order.verify(attemptLimiter).tryAcquire("delete:reader", 5, Window.QUARTER_HOUR);
        order.verify(userService).verifyPassword("Reader", "Correct1!");
    }

    // ── refusals ────────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "reader", roles = "PRO_USER")
    @DisplayName("403 'Password is incorrect' for a wrong password; nothing is deleted and nobody is mailed")
    void wrongPassword_returns403() throws Exception {
        when(userService.verifyPassword("reader", "Wrong1!x")).thenReturn(Optional.empty());

        mockMvc.perform(delete("/api/user/account").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"Wrong1!x\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Password is incorrect"));

        verify(userService, never()).deleteOwnAccount(any(), any(), any());
        verifyNoInteractions(userEmailService);
    }

    @Test
    @WithMockUser(username = "reader", roles = "PRO_USER")
    @DisplayName("429 with the shared 'Too many attempts' sentence once the bound is reached: the password is "
            + "not even checked")
    void limiterRefuses_returns429WithoutCheckingThePassword() throws Exception {
        when(attemptLimiter.tryAcquire("delete:reader", 5, Window.QUARTER_HOUR)).thenReturn(false);

        mockMvc.perform(delete("/api/user/account").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error")
                        .value("Too many attempts. Please wait a few minutes and try again."));

        verify(userService, never()).verifyPassword(any(), any());
        verify(userService, never()).deleteOwnAccount(any(), any(), any());
        verifyNoInteractions(userEmailService);
    }

    @Test
    @WithMockUser(username = "reader", roles = "PRO_USER")
    @DisplayName("400 'Password is required' for a missing, null or empty password, with no empty body: none of "
            + "them is counted or checked")
    void missingPassword_returns400() throws Exception {
        mockMvc.perform(delete("/api/user/account").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Password is required"));
        mockMvc.perform(delete("/api/user/account").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":null}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete("/api/user/account").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete("/api/user/account"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Password is required"));

        verifyNoInteractions(attemptLimiter);
        verifyNoInteractions(userService);
        verifyNoInteractions(userEmailService);
    }

    @Test
    @WithMockUser(username = "boss", roles = "ADMIN")
    @DisplayName("409 'Admin accounts are deleted by another admin' for an ADMIN, before anything else is "
            + "touched: the sole administrator is never deletable in-app")
    void admin_returns409() throws Exception {
        mockMvc.perform(delete("/api/user/account").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Admin accounts are deleted by another admin"));

        verifyNoInteractions(attemptLimiter);
        verifyNoInteractions(userService);
        verifyNoInteractions(userEmailService);
    }

    @Test
    @WithMockUser(username = "reader", roles = "PRO_USER")
    @DisplayName("409 too when an admin promoted the account while the password was being checked: the locked "
            + "row's role wins, and nobody is mailed")
    void promotedDuringTheCheck_returns409() throws Exception {
        thePasswordIsCorrect();
        when(userService.deleteOwnAccount(eq("reader"), eq("stored-hash"), any()))
                .thenThrow(new IllegalStateException("Admin accounts are deleted by another admin"));

        mockMvc.perform(delete("/api/user/account").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Admin accounts are deleted by another admin"));

        verifyNoInteractions(userEmailService);
    }

    @Test
    @WithMockUser(username = "reader", roles = "PRO_USER")
    @DisplayName("an IllegalStateException that is NOT the admin refusal is a bug, not a 409: it is not "
            + "mislabelled")
    void otherIllegalState_isNotMislabelledAsAdmin() throws Exception {
        thePasswordIsCorrect();
        when(userService.deleteOwnAccount(eq("reader"), eq("stored-hash"), any()))
                .thenThrow(new IllegalStateException("something else broke"));

        mockMvc.perform(delete("/api/user/account").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isInternalServerError());

        verifyNoInteractions(userEmailService);
    }

    @Test
    @WithMockUser(username = "reader", roles = "PRO_USER")
    @DisplayName("401 'Your session has changed. Sign in again.' when the credentials moved while the password "
            + "was being checked; nobody is mailed")
    void staleCredentials_returns401() throws Exception {
        thePasswordIsCorrect();
        when(userService.deleteOwnAccount(eq("reader"), eq("stored-hash"), any()))
                .thenThrow(new StaleCredentialsException());

        mockMvc.perform(delete("/api/user/account").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Your session has changed. Sign in again."));

        verifyNoInteractions(userEmailService);
    }

    @Test
    @WithMockUser(username = "reader", roles = "PRO_USER")
    @DisplayName("401 when the account vanished before the password could be checked")
    void accountGoneAtThePasswordCheck_returns401() throws Exception {
        when(userService.verifyPassword("reader", "Correct1!")).thenThrow(new StaleCredentialsException());

        mockMvc.perform(delete("/api/user/account").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Your session has changed. Sign in again."));

        verify(userService, never()).deleteOwnAccount(any(), any(), any());
    }

    @Test
    @DisplayName("401 and nothing touched when unauthenticated")
    void unauthenticated_returns401() throws Exception {
        mockMvc.perform(delete("/api/user/account").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(attemptLimiter);
        verifyNoInteractions(userService);
        verifyNoInteractions(userEmailService);
    }
}
