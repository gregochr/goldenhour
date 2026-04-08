package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.WaitlistEmailEntity;
import com.gregochr.goldenhour.repository.WaitlistEmailRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link WaitlistAdminController}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WaitlistAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WaitlistEmailRepository waitlistEmailRepository;

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
}
