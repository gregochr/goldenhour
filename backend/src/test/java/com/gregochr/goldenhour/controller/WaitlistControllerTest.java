package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.WaitlistEmailEntity;
import com.gregochr.goldenhour.repository.WaitlistEmailRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
@SpringBootTest
@AutoConfigureMockMvc
class WaitlistControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WaitlistEmailRepository waitlistEmailRepository;

    @Test
    @DisplayName("POST /api/waitlist saves email and returns 200")
    void submitWaitlist_valid_returns200() throws Exception {
        when(waitlistEmailRepository.existsByEmail("alice@example.com")).thenReturn(false);
        when(waitlistEmailRepository.save(argThat(e -> "alice@example.com".equals(e.getEmail()))))
                .thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(post("/api/waitlist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.com\"}"))
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
                        .content("{\"email\":\"  Bob@Example.COM  \"}"))
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
                        .content("{\"email\":\"alice@example.com\"}"))
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
                        .content("{\"email\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("A valid email address is required"));
    }

    @Test
    @DisplayName("POST /api/waitlist returns 400 for invalid email format")
    void submitWaitlist_invalidFormat_returns400() throws Exception {
        mockMvc.perform(post("/api/waitlist")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\"}"))
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
                        .content("{\"email\":\"anon@example.com\"}"))
                .andExpect(status().isOk());
    }
}
