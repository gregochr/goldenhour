package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.WaitlistEmailEntity;
import com.gregochr.goldenhour.service.AuthAttemptLimiter.Window;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link WaitlistController}.
 */
class WaitlistControllerTest extends AbstractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void admitByDefault() {
        when(attemptLimiter.tryAcquire("waitlist:global", 60, Window.HOUR)).thenReturn(true);
        when(attemptLimiter.tryAcquire("waitlist:client:127.0.0.1", 5, Window.QUARTER_HOUR)).thenReturn(true);
        when(turnstileService.verify("tok")).thenReturn(true);
    }

    @Test
    @DisplayName("POST /api/waitlist saves email and returns 200")
    void submitWaitlist_valid_returns200() throws Exception {
        when(waitlistEmailRepository.existsByEmail("alice@example.com")).thenReturn(false);
        when(waitlistEmailRepository.save(argThat(e -> "alice@example.com".equals(e.getEmail()))))
                .thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(post("/api/waitlist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.com\",\"turnstileToken\":\"tok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("You're on the list"));

        verify(waitlistEmailRepository).save(argThat(
                (WaitlistEmailEntity e) -> "alice@example.com".equals(e.getEmail())));
    }

    @Test
    @DisplayName("POST /api/waitlist lowercases and trims email before saving")
    void submitWaitlist_normalisesEmail() throws Exception {
        when(waitlistEmailRepository.existsByEmail("bob@example.com")).thenReturn(false);
        when(waitlistEmailRepository.save(argThat(e -> "bob@example.com".equals(e.getEmail()))))
                .thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(post("/api/waitlist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"  Bob@Example.COM  \",\"turnstileToken\":\"tok\"}"))
                .andExpect(status().isOk());

        verify(waitlistEmailRepository).save(argThat(
                (WaitlistEmailEntity e) -> "bob@example.com".equals(e.getEmail())));
    }

    @Test
    @DisplayName("POST /api/waitlist is idempotent for duplicate emails")
    void submitWaitlist_duplicate_stillReturns200() throws Exception {
        when(waitlistEmailRepository.existsByEmail("alice@example.com")).thenReturn(true);

        mockMvc.perform(post("/api/waitlist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.com\",\"turnstileToken\":\"tok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("You're on the list"));

        verify(waitlistEmailRepository, never()).save(argThat(
                (WaitlistEmailEntity e) -> true));
    }

    @Test
    @DisplayName("POST /api/waitlist returns 400 for missing email")
    void submitWaitlist_missingEmail_returns400() throws Exception {
        mockMvc.perform(post("/api/waitlist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("A valid email address is required"));
    }

    @Test
    @DisplayName("POST /api/waitlist returns 400 for blank email")
    void submitWaitlist_blankEmail_returns400() throws Exception {
        mockMvc.perform(post("/api/waitlist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"   \",\"turnstileToken\":\"tok\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("A valid email address is required"));
    }

    @Test
    @DisplayName("POST /api/waitlist returns 400 for invalid email format")
    void submitWaitlist_invalidFormat_returns400() throws Exception {
        mockMvc.perform(post("/api/waitlist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"turnstileToken\":\"tok\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("A valid email address is required"));
    }

    @Test
    @DisplayName("POST /api/waitlist does not require authentication")
    void submitWaitlist_noAuth_stillAllowed() throws Exception {
        when(waitlistEmailRepository.existsByEmail("anon@example.com")).thenReturn(false);
        when(waitlistEmailRepository.save(argThat(e -> "anon@example.com".equals(e.getEmail()))))
                .thenAnswer(inv -> inv.getArgument(0));

        // No @WithMockUser — request is unauthenticated
        mockMvc.perform(post("/api/waitlist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"anon@example.com\",\"turnstileToken\":\"tok\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /api/waitlist with a failing CAPTCHA token is a 400 and saves nothing")
    void submitWaitlist_captchaFails_returns400() throws Exception {
        when(turnstileService.verify("bad")).thenReturn(false);

        mockMvc.perform(post("/api/waitlist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.com\",\"turnstileToken\":\"bad\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("CAPTCHA verification failed. Please try again."));

        verify(waitlistEmailRepository, never()).save(argThat((WaitlistEmailEntity e) -> true));
    }

    @Test
    @DisplayName("POST /api/waitlist with no CAPTCHA token is a 400 when Turnstile is configured")
    void submitWaitlist_noToken_returns400() throws Exception {
        when(turnstileService.verify(null)).thenReturn(false);

        mockMvc.perform(post("/api/waitlist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.com\"}"))
                .andExpect(status().isBadRequest());

        verify(waitlistEmailRepository, never()).save(argThat((WaitlistEmailEntity e) -> true));
    }

    @Test
    @DisplayName("POST /api/waitlist is a 429 once the client address bound is spent, and saves nothing")
    void submitWaitlist_clientBoundSpent_returns429() throws Exception {
        when(attemptLimiter.tryAcquire("waitlist:client:127.0.0.1", 5, Window.QUARTER_HOUR)).thenReturn(false);

        mockMvc.perform(post("/api/waitlist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.com\",\"turnstileToken\":\"tok\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value(AuthController.TOO_MANY_ATTEMPTS));

        verify(waitlistEmailRepository, never()).save(argThat((WaitlistEmailEntity e) -> true));
    }

    @Test
    @DisplayName("POST /api/waitlist is a 429 once the global hourly cap is spent, before the client is counted")
    void submitWaitlist_globalCapSpent_returns429() throws Exception {
        when(attemptLimiter.tryAcquire("waitlist:global", 60, Window.HOUR)).thenReturn(false);

        mockMvc.perform(post("/api/waitlist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.com\",\"turnstileToken\":\"tok\"}"))
                .andExpect(status().isTooManyRequests());

        verify(attemptLimiter, never()).tryAcquire("waitlist:client:127.0.0.1", 5, Window.QUARTER_HOUR);
    }

    @Test
    @DisplayName("POST /api/waitlist answers the same 200 when a concurrent identical sign-up wins the unique key")
    void submitWaitlist_duplicateRace_returns200() throws Exception {
        when(waitlistEmailRepository.existsByEmail("alice@example.com")).thenReturn(false, true);
        when(waitlistEmailRepository.save(argThat((WaitlistEmailEntity e) -> "alice@example.com".equals(e.getEmail()))))
                .thenThrow(new DataIntegrityViolationException("uk_waitlist_email"));

        mockMvc.perform(post("/api/waitlist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.com\",\"turnstileToken\":\"tok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("You're on the list"));
    }
}
