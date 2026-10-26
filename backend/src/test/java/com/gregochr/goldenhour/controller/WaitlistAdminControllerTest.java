package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.WaitlistEmailEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link WaitlistAdminController}.
 */
class WaitlistAdminControllerTest extends AbstractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("ADMIN can GET /api/admin/waitlist and receives entries ordered by submittedAt")
    void listWaitlist_asAdmin_returnsOrderedEntries() throws Exception {
        WaitlistEmailEntity first = WaitlistEmailEntity.builder()
                .id(1L)
                .email("alice@example.com")
                .submittedAt(LocalDateTime.of(2026, 4, 5, 10, 0))
                .build();
        WaitlistEmailEntity second = WaitlistEmailEntity.builder()
                .id(2L)
                .email("bob@example.com")
                .submittedAt(LocalDateTime.of(2026, 4, 7, 14, 30))
                .build();
        when(waitlistEmailRepository.findAllByOrderBySubmittedAtAsc())
                .thenReturn(List.of(first, second));

        mockMvc.perform(get("/api/admin/waitlist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].email").value("alice@example.com"))
                .andExpect(jsonPath("$[0].submittedAt").value("2026-04-05T10:00:00"))
                .andExpect(jsonPath("$[1].id").value(2))
                .andExpect(jsonPath("$[1].email").value("bob@example.com"))
                .andExpect(jsonPath("$[1].submittedAt").value("2026-04-07T14:30:00"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /api/admin/waitlist returns empty array when no entries exist")
    void listWaitlist_empty_returnsEmptyArray() throws Exception {
        when(waitlistEmailRepository.findAllByOrderBySubmittedAtAsc())
                .thenReturn(List.of());

        mockMvc.perform(get("/api/admin/waitlist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @WithMockUser(roles = "LITE_USER")
    @DisplayName("LITE_USER receives 403 on GET /api/admin/waitlist")
    void listWaitlist_asLiteUser_returns403() throws Exception {
        mockMvc.perform(get("/api/admin/waitlist"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "PRO_USER")
    @DisplayName("PRO_USER receives 403 on GET /api/admin/waitlist")
    void listWaitlist_asProUser_returns403() throws Exception {
        mockMvc.perform(get("/api/admin/waitlist"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Unauthenticated request receives 401 on GET /api/admin/waitlist")
    void listWaitlist_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/admin/waitlist"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /api/admin/waitlist uses the ordered query method, not findAll")
    void listWaitlist_callsOrderedQueryMethod() throws Exception {
        when(waitlistEmailRepository.findAllByOrderBySubmittedAtAsc())
                .thenReturn(List.of());

        mockMvc.perform(get("/api/admin/waitlist"))
                .andExpect(status().isOk());

        verify(waitlistEmailRepository).findAllByOrderBySubmittedAtAsc();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /api/admin/waitlist response does not include password or other entity internals")
    void listWaitlist_responseDtoShape() throws Exception {
        WaitlistEmailEntity entry = WaitlistEmailEntity.builder()
                .id(1L)
                .email("alice@example.com")
                .submittedAt(LocalDateTime.of(2026, 4, 5, 10, 0))
                .build();
        when(waitlistEmailRepository.findAllByOrderBySubmittedAtAsc())
                .thenReturn(List.of(entry));

        String body = mockMvc.perform(get("/api/admin/waitlist"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/json"))
                .andReturn().getResponse().getContentAsString();

        // DTO should only contain id, email, submittedAt — no extra fields
        org.assertj.core.api.Assertions.assertThat(body)
                .contains("\"id\"", "\"email\"", "\"submittedAt\"")
                .doesNotContain("password", "hash", "token");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /api/admin/waitlist returns single entry with all fields populated")
    void listWaitlist_singleEntry_allFieldsPresent() throws Exception {
        WaitlistEmailEntity entry = WaitlistEmailEntity.builder()
                .id(42L)
                .email("test@photocast.online")
                .submittedAt(LocalDateTime.of(2026, 4, 8, 9, 15, 30))
                .build();
        when(waitlistEmailRepository.findAllByOrderBySubmittedAtAsc())
                .thenReturn(List.of(entry));

        mockMvc.perform(get("/api/admin/waitlist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(42))
                .andExpect(jsonPath("$[0].email").value("test@photocast.online"))
                .andExpect(jsonPath("$[0].submittedAt").value("2026-04-08T09:15:30"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /api/admin/waitlist serves invitedAt and inviteExpiresAt, and never the token hash")
    void listWaitlist_invitedEntry_hasDatesAndNoHash() throws Exception {
        WaitlistEmailEntity entry = WaitlistEmailEntity.builder()
                .id(7L)
                .email("alice@example.com")
                .submittedAt(LocalDateTime.of(2026, 4, 5, 10, 0))
                .inviteTokenHash("a".repeat(64))
                .invitedAt(LocalDateTime.of(2026, 4, 9, 8, 0))
                .inviteExpiresAt(LocalDateTime.of(2026, 4, 16, 8, 0))
                .inviteUsedAt(LocalDateTime.of(2026, 4, 10, 8, 0))
                .build();
        when(waitlistEmailRepository.findAllByOrderBySubmittedAtAsc()).thenReturn(List.of(entry));

        String body = mockMvc.perform(get("/api/admin/waitlist"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].invitedAt").value("2026-04-09T08:00:00"))
                .andExpect(jsonPath("$[0].inviteExpiresAt").value("2026-04-16T08:00:00"))
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("aaaa", "inviteTokenHash", "inviteUsedAt", "hash");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /api/admin/waitlist/{id}/invite stamps a hashed token and sends the mail, as ADMIN")
    void invite_asAdmin_returns200() throws Exception {
        WaitlistEmailEntity entry = WaitlistEmailEntity.builder().id(3L).email("alice@example.com")
                .submittedAt(LocalDateTime.of(2026, 4, 5, 10, 0)).build();
        when(waitlistEmailRepository.findById(3L)).thenReturn(Optional.of(entry));
        when(userEmailService.isConfigured()).thenReturn(true);
        when(waitlistEmailRepository.markInvited(org.mockito.ArgumentMatchers.eq(3L), anyString(),
                any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(1);

        mockMvc.perform(post("/api/admin/waitlist/3/invite"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Invitation sent"));

        verify(userEmailService).sendWaitlistInviteEmail(org.mockito.ArgumentMatchers.eq(3L),
                org.mockito.ArgumentMatchers.eq("alice@example.com"), anyString(), any(LocalDateTime.class));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /api/admin/waitlist/{id}/invite is a 404 for an unknown row and a 400 for a non-ASCII "
            + "address, mailing nothing either time")
    void invite_unknownOrNonAscii_refused() throws Exception {
        when(waitlistEmailRepository.findById(9L)).thenReturn(Optional.empty());
        when(waitlistEmailRepository.findById(4L)).thenReturn(Optional.of(WaitlistEmailEntity.builder()
                .id(4L).email("j\u00fcrgen@example.com").submittedAt(LocalDateTime.of(2026, 4, 5, 10, 0)).build()));

        mockMvc.perform(post("/api/admin/waitlist/9/invite")).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/admin/waitlist/4/invite")).andExpect(status().isBadRequest());

        verify(userEmailService, never()).sendWaitlistInviteEmail(any(), anyString(), anyString(),
                any(LocalDateTime.class));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /api/admin/waitlist/{id}/invite is a 503 when no mail sender is configured, storing nothing")
    void invite_mailNotConfigured_returns503() throws Exception {
        when(waitlistEmailRepository.findById(3L)).thenReturn(Optional.of(WaitlistEmailEntity.builder()
                .id(3L).email("alice@example.com").submittedAt(LocalDateTime.of(2026, 4, 5, 10, 0)).build()));
        when(userEmailService.isConfigured()).thenReturn(false);

        mockMvc.perform(post("/api/admin/waitlist/3/invite")).andExpect(status().isServiceUnavailable());

        verify(waitlistEmailRepository, never()).markInvited(any(), anyString(), any(LocalDateTime.class),
                any(LocalDateTime.class));
    }

    @Test
    @WithMockUser(roles = {"LITE_USER"})
    @DisplayName("LITE_USER receives 403 on invite and on delete")
    void inviteAndDelete_asLite_return403() throws Exception {
        mockMvc.perform(post("/api/admin/waitlist/3/invite")).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/admin/waitlist/3")).andExpect(status().isForbidden());
        verify(waitlistEmailRepository, never()).deleteById(any());
    }

    @Test
    @WithMockUser(roles = {"PRO_USER"})
    @DisplayName("PRO_USER receives 403 on invite and on delete")
    void inviteAndDelete_asPro_return403() throws Exception {
        mockMvc.perform(post("/api/admin/waitlist/3/invite")).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/admin/waitlist/3")).andExpect(status().isForbidden());
        verify(waitlistEmailRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("Unauthenticated invite and delete are 401")
    void inviteAndDelete_unauthenticated_return401() throws Exception {
        mockMvc.perform(post("/api/admin/waitlist/3/invite")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/admin/waitlist/3")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("DELETE /api/admin/waitlist/{id} removes the row (204) and is a 404 for an unknown one")
    void delete_asAdmin() throws Exception {
        when(waitlistEmailRepository.existsById(3L)).thenReturn(true);
        when(waitlistEmailRepository.existsById(9L)).thenReturn(false);

        mockMvc.perform(delete("/api/admin/waitlist/3")).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/admin/waitlist/9")).andExpect(status().isNotFound());

        verify(waitlistEmailRepository).deleteById(3L);
        verify(waitlistEmailRepository, never()).deleteById(9L);
    }
}
