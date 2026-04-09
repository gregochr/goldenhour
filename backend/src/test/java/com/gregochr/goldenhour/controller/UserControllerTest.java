package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.service.PasswordResetResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link UserController}.
 */
class UserControllerTest extends AbstractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("ADMIN can GET /api/users and receives 200")
    void getUsers_asAdmin_returns200() throws Exception {
        when(userService.listAllUsers()).thenReturn(List.of(buildUser(1L, "alice", UserRole.ADMIN)));

        mockMvc.perform(get("/api/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("alice"));
    }

    @Test
    @WithMockUser(roles = "LITE_USER")
    @DisplayName("USER role receives 403 on GET /api/users")
    void getUsers_asUser_returns403() throws Exception {
        mockMvc.perform(get("/api/users"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Unauthenticated request receives 401 on GET /api/users")
    void getUsers_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("ADMIN can POST /api/users to create a new user and receives 201")
    void createUser_asAdmin_returns201() throws Exception {
        AppUserEntity created = buildUser(2L, "bob", UserRole.LITE_USER);
        when(userService.createUser(anyString(), anyString(), any(UserRole.class), anyString()))
                .thenReturn(created);

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bob\",\"password\":\"pass123\","
                                + "\"role\":\"LITE_USER\",\"email\":\"bob@example.com\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("bob"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("ADMIN can toggle user enabled flag and receives 200")
    void setEnabled_asAdmin_returns200() throws Exception {
        mockMvc.perform(put("/api/users/1/enabled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Updated"));
    }

    @Test
    @WithMockUser(roles = "LITE_USER")
    @DisplayName("USER role receives 403 on PUT /api/users/{id}/enabled")
    void setEnabled_asUser_returns403() throws Exception {
        mockMvc.perform(put("/api/users/1/enabled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /api/users returns 400 when required fields are missing")
    void createUser_missingFields_returns400() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bob\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /api/users returns 400 when role is invalid")
    void createUser_invalidRole_returns400() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bob\",\"password\":\"pass\","
                                + "\"role\":\"SUPERUSER\",\"email\":\"bob@example.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid role: SUPERUSER"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /api/users returns 400 when email is invalid")
    void createUser_invalidEmail_returns400() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"pass\","
                                + "\"role\":\"LITE_USER\",\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid email address: not-an-email"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /api/users returns 400 when username already exists")
    void createUser_duplicateUsername_returns400() throws Exception {
        when(userService.createUser(anyString(), anyString(), any(UserRole.class), anyString()))
                .thenThrow(new IllegalArgumentException("Username already exists"));

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"pass\","
                                + "\"role\":\"LITE_USER\",\"email\":\"alice@example.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Username already exists"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT /api/users/{id}/enabled returns 400 when enabled field is missing")
    void setEnabled_missingField_returns400() throws Exception {
        mockMvc.perform(put("/api/users/1/enabled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT /api/users/{id}/enabled returns 400 when user does not exist")
    void setEnabled_userNotFound_returns400() throws Exception {
        doThrow(new IllegalArgumentException("User not found"))
                .when(userService).setEnabled(anyLong(), any(Boolean.class));

        mockMvc.perform(put("/api/users/99/enabled")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("User not found"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT /api/users/{id}/role returns 200 when role is valid")
    void setRole_asAdmin_returns200() throws Exception {
        mockMvc.perform(put("/api/users/1/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"PRO_USER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Updated"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT /api/users/{id}/role returns 400 when role field is missing")
    void setRole_missingField_returns400() throws Exception {
        mockMvc.perform(put("/api/users/1/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT /api/users/{id}/role returns 400 when role is invalid")
    void setRole_invalidRole_returns400() throws Exception {
        mockMvc.perform(put("/api/users/1/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"SUPERUSER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid role: SUPERUSER"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT /api/users/{id}/role returns 400 when user does not exist")
    void setRole_userNotFound_returns400() throws Exception {
        doThrow(new IllegalArgumentException("User not found"))
                .when(userService).setRole(anyLong(), any(UserRole.class));

        mockMvc.perform(put("/api/users/99/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"PRO_USER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("User not found"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT /api/users/{id}/email returns 200 when email is valid")
    void setEmail_asAdmin_returns200() throws Exception {
        mockMvc.perform(put("/api/users/1/email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Updated"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT /api/users/{id}/email returns 400 when email field is missing")
    void setEmail_missingField_returns400() throws Exception {
        mockMvc.perform(put("/api/users/1/email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("email field required"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT /api/users/{id}/email returns 400 when email format is invalid")
    void setEmail_invalidFormat_returns400() throws Exception {
        mockMvc.perform(put("/api/users/1/email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid email address: not-an-email"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT /api/users/{id}/email returns 400 when user does not exist")
    void setEmail_userNotFound_returns400() throws Exception {
        doThrow(new IllegalArgumentException("User not found: 99"))
                .when(userService).setEmail(anyLong(), anyString());

        mockMvc.perform(put("/api/users/99/email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"valid@example.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("User not found: 99"));
    }

    @Test
    @WithMockUser(roles = "LITE_USER")
    @DisplayName("PUT /api/users/{id}/email returns 403 for LITE_USER")
    void setEmail_asLiteUser_returns403() throws Exception {
        mockMvc.perform(put("/api/users/1/email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new@example.com\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT /api/users/{id}/reset-password returns 200 with temporaryPassword for ADMIN")
    void resetPassword_asAdmin_returns200WithTemporaryPassword() throws Exception {
        when(userService.resetPassword(1L)).thenReturn(
                new PasswordResetResult("Abc1!xyz9Qr2", "alice", "alice@example.com"));

        mockMvc.perform(put("/api/users/1/reset-password"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.temporaryPassword").value("Abc1!xyz9Qr2"));
    }

    @Test
    @WithMockUser(roles = "LITE_USER")
    @DisplayName("PUT /api/users/{id}/reset-password returns 403 for LITE_USER")
    void resetPassword_asLiteUser_returns403() throws Exception {
        mockMvc.perform(put("/api/users/1/reset-password"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PUT /api/users/{id}/reset-password returns 401 when unauthenticated")
    void resetPassword_unauthenticated_returns401() throws Exception {
        mockMvc.perform(put("/api/users/1/reset-password"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT /api/users/{id}/reset-password returns 400 when user does not exist")
    void resetPassword_userNotFound_returns400() throws Exception {
        when(userService.resetPassword(99L)).thenThrow(new IllegalArgumentException("User not found: 99"));

        mockMvc.perform(put("/api/users/99/reset-password"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("User not found: 99"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /api/users includes lastActiveAt in response")
    void getUsers_includesLastActiveAt() throws Exception {
        AppUserEntity user = buildUser(1L, "alice", UserRole.ADMIN);
        user.setLastActiveAt(LocalDateTime.of(2026, 3, 1, 14, 30));
        when(userService.listAllUsers()).thenReturn(List.of(user));

        mockMvc.perform(get("/api/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].lastActiveAt").value("2026-03-01T14:30"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /api/users returns empty lastActiveAt for users who never logged in")
    void getUsers_lastActiveAtEmpty_whenNeverLoggedIn() throws Exception {
        when(userService.listAllUsers()).thenReturn(List.of(buildUser(1L, "bob", UserRole.LITE_USER)));

        mockMvc.perform(get("/api/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].lastActiveAt").value(""));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /api/users/{id}/resend-verification returns 200 for valid user")
    void resendVerification_validUser_returns200() throws Exception {
        AppUserEntity user = buildUser(1L, "alice", UserRole.LITE_USER);
        user.setEmail("alice@example.com");
        user.setEnabled(false);
        when(userService.listAllUsers()).thenReturn(List.of(user));

        mockMvc.perform(post("/api/users/1/resend-verification"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Verification email sent"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /api/users/{id}/resend-verification returns 400 for unknown user")
    void resendVerification_unknownUser_returns400() throws Exception {
        when(userService.listAllUsers()).thenReturn(List.of());

        mockMvc.perform(post("/api/users/99/resend-verification"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("User not found"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /api/users/{id}/resend-verification returns 400 when user has no email")
    void resendVerification_noEmail_returns400() throws Exception {
        AppUserEntity user = buildUser(1L, "alice", UserRole.LITE_USER);
        when(userService.listAllUsers()).thenReturn(List.of(user));

        mockMvc.perform(post("/api/users/1/resend-verification"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("User has no email address"));
    }

    @Test
    @WithMockUser(roles = "LITE_USER")
    @DisplayName("POST /api/users/{id}/resend-verification returns 403 for LITE_USER")
    void resendVerification_asLiteUser_returns403() throws Exception {
        mockMvc.perform(post("/api/users/1/resend-verification"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /api/users includes termsAcceptedAt, termsVersion, homePostcode, and marketingEmailOptIn")
    void getUsers_includesNewDetailFields() throws Exception {
        AppUserEntity user = buildUser(1L, "alice", UserRole.PRO_USER);
        user.setTermsAcceptedAt(Instant.parse("2026-04-07T10:00:00Z"));
        user.setTermsVersion("April 2026");
        user.setHomePostcode("NE1 7RU");
        user.setMarketingEmailOptIn(true);
        when(userService.listAllUsers()).thenReturn(List.of(user));

        mockMvc.perform(get("/api/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].termsAcceptedAt").value("2026-04-07T10:00:00Z"))
                .andExpect(jsonPath("$[0].termsVersion").value("April 2026"))
                .andExpect(jsonPath("$[0].homePostcode").value("NE1 7RU"))
                .andExpect(jsonPath("$[0].marketingEmailOptIn").value(true));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /api/users returns null detail fields when not set")
    void getUsers_nullDetailFields_whenNotSet() throws Exception {
        when(userService.listAllUsers()).thenReturn(List.of(buildUser(1L, "bob", UserRole.LITE_USER)));

        mockMvc.perform(get("/api/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].termsAcceptedAt").doesNotExist())
                .andExpect(jsonPath("$[0].termsVersion").doesNotExist())
                .andExpect(jsonPath("$[0].homePostcode").doesNotExist())
                .andExpect(jsonPath("$[0].marketingEmailOptIn").value(true));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /api/users response includes detail fields from toSummary")
    void createUser_responseIncludesDetailFields() throws Exception {
        AppUserEntity created = buildUser(3L, "carol", UserRole.PRO_USER);
        created.setTermsAcceptedAt(Instant.parse("2026-04-08T09:00:00Z"));
        created.setTermsVersion("April 2026");
        created.setHomePostcode("SW1A 1AA");
        created.setMarketingEmailOptIn(false);
        when(userService.createUser(eq("carol"), eq("secret"), eq(UserRole.PRO_USER), eq("carol@example.com")))
                .thenReturn(created);

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"carol\",\"password\":\"secret\","
                                + "\"role\":\"PRO_USER\",\"email\":\"carol@example.com\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("carol"))
                .andExpect(jsonPath("$.termsAcceptedAt").value("2026-04-08T09:00:00Z"))
                .andExpect(jsonPath("$.termsVersion").value("April 2026"))
                .andExpect(jsonPath("$.homePostcode").value("SW1A 1AA"))
                .andExpect(jsonPath("$.marketingEmailOptIn").value(false));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /api/users with partial detail fields — terms set but no postcode")
    void getUsers_partialDetailFields() throws Exception {
        AppUserEntity user = buildUser(1L, "diana", UserRole.LITE_USER);
        user.setTermsAcceptedAt(Instant.parse("2026-03-15T12:00:00Z"));
        user.setTermsVersion("March 2026");
        // homePostcode deliberately left null
        user.setMarketingEmailOptIn(false);
        when(userService.listAllUsers()).thenReturn(List.of(user));

        mockMvc.perform(get("/api/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].termsAcceptedAt").value("2026-03-15T12:00:00Z"))
                .andExpect(jsonPath("$[0].termsVersion").value("March 2026"))
                .andExpect(jsonPath("$[0].homePostcode").doesNotExist())
                .andExpect(jsonPath("$[0].marketingEmailOptIn").value(false));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /api/users returns detail fields for multiple users with mixed data")
    void getUsers_multipleUsersWithMixedDetailFields() throws Exception {
        AppUserEntity alice = buildUser(1L, "alice", UserRole.ADMIN);
        alice.setTermsAcceptedAt(Instant.parse("2026-04-01T08:00:00Z"));
        alice.setTermsVersion("April 2026");
        alice.setHomePostcode("NE1 7RU");
        alice.setMarketingEmailOptIn(true);

        AppUserEntity bob = buildUser(2L, "bob", UserRole.LITE_USER);
        // bob has no terms, no postcode, default marketing opt-in

        when(userService.listAllUsers()).thenReturn(List.of(alice, bob));

        mockMvc.perform(get("/api/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].termsAcceptedAt").value("2026-04-01T08:00:00Z"))
                .andExpect(jsonPath("$[0].homePostcode").value("NE1 7RU"))
                .andExpect(jsonPath("$[0].marketingEmailOptIn").value(true))
                .andExpect(jsonPath("$[1].termsAcceptedAt").doesNotExist())
                .andExpect(jsonPath("$[1].homePostcode").doesNotExist())
                .andExpect(jsonPath("$[1].marketingEmailOptIn").value(true));
    }

    // --- DELETE /api/users/{id} endpoint ---

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    @DisplayName("DELETE /api/users/{id} returns 200 when user is successfully deleted")
    void deleteUser_asAdmin_returns200() throws Exception {
        doNothing().when(userService).deleteUser(eq(2L), eq("admin"));

        mockMvc.perform(delete("/api/users/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("User deleted"));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    @DisplayName("DELETE /api/users/{id} returns 409 when admin tries to delete themselves")
    void deleteUser_selfDeletion_returns409() throws Exception {
        doThrow(new IllegalStateException("Cannot delete your own account"))
                .when(userService).deleteUser(eq(1L), eq("admin"));

        mockMvc.perform(delete("/api/users/1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Cannot delete your own account"));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    @DisplayName("DELETE /api/users/{id} returns 400 when user does not exist")
    void deleteUser_notFound_returns400() throws Exception {
        doThrow(new IllegalArgumentException("User not found: 99"))
                .when(userService).deleteUser(eq(99L), eq("admin"));

        mockMvc.perform(delete("/api/users/99"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("User not found: 99"));
    }

    @Test
    @WithMockUser(roles = "LITE_USER")
    @DisplayName("DELETE /api/users/{id} returns 403 for non-ADMIN")
    void deleteUser_nonAdmin_returns403() throws Exception {
        mockMvc.perform(delete("/api/users/1"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DELETE /api/users/{id} returns 401 when unauthenticated")
    void deleteUser_unauthenticated_returns401() throws Exception {
        mockMvc.perform(delete("/api/users/1"))
                .andExpect(status().isUnauthorized());
    }

    private AppUserEntity buildUser(Long id, String username, UserRole role) {
        return AppUserEntity.builder()
                .id(id)
                .username(username)
                .password("hashed")
                .role(role)
                .enabled(true)
                .createdAt(LocalDateTime.of(2026, 2, 1, 9, 0))
                .build();
    }
}
