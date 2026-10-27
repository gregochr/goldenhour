package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.SubRegionState;
import com.gregochr.goldenhour.model.UserSubRegionSummary;
import com.gregochr.goldenhour.service.DeletedAccount;
import com.gregochr.goldenhour.service.PasswordResetResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    @DisplayName("GET /api/users carries each reader's picks by name and standing: status, free-until, "
            + "next-change-on and over-limit")
    void getUsers_includesSubRegionStanding() throws Exception {
        AppUserEntity alice = buildUser(1L, "alice", UserRole.LITE_USER);
        AppUserEntity bob = buildUser(2L, "bob", UserRole.LITE_USER);
        AppUserEntity pat = buildUser(3L, "pat", UserRole.PRO_USER);
        List<AppUserEntity> all = List.of(alice, bob, pat);
        when(userService.listAllUsers()).thenReturn(all);
        when(userSubRegionService.adminSummaries(all)).thenReturn(Map.of(
                1L, new UserSubRegionSummary(List.of("Swaledale", "Wensleydale"), SubRegionState.LOCKED, null,
                        LocalDate.of(2026, 11, 25), true),
                2L, new UserSubRegionSummary(List.of(), SubRegionState.FIRST_CHOICE, null, null, false),
                3L, new UserSubRegionSummary(List.of("Swaledale"), SubRegionState.NOT_APPLICABLE, null, null,
                        false)));

        mockMvc.perform(get("/api/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].subRegions[0]").value("Swaledale"))
                .andExpect(jsonPath("$[0].subRegions[1]").value("Wensleydale"))
                .andExpect(jsonPath("$[0].subRegionStatus").value("LOCKED"))
                .andExpect(jsonPath("$[0].nextChangeOn").value("2026-11-25"))
                .andExpect(jsonPath("$[0].freeUntil").doesNotExist())
                .andExpect(jsonPath("$[0].overLimit").value(true))
                .andExpect(jsonPath("$[1].subRegions").isEmpty())
                .andExpect(jsonPath("$[1].subRegionStatus").value("FIRST_CHOICE"))
                .andExpect(jsonPath("$[1].overLimit").value(false))
                .andExpect(jsonPath("$[2].subRegionStatus").value("NOT_APPLICABLE"))
                .andExpect(jsonPath("$[2].subRegions[0]").value("Swaledale"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /api/users tells a pending registration (disabled, empty password) from an admin-disabled "
            + "account, and dates the link only for the pending one")
    void getUsers_awaitingVerificationIsNotDisabled() throws Exception {
        AppUserEntity pending = buildUser(1L, "hollie", UserRole.LITE_USER);
        pending.setEnabled(false);
        pending.setPassword("");
        AppUserEntity switchedOff = buildUser(2L, "stormchaser", UserRole.LITE_USER);
        switchedOff.setEnabled(false);
        AppUserEntity active = buildUser(3L, "tom", UserRole.LITE_USER);
        List<AppUserEntity> all = List.of(pending, switchedOff, active);
        when(userService.listAllUsers()).thenReturn(all);
        when(registrationService.verificationSentAtByUser()).thenReturn(Map.of(
                1L, Instant.parse("2026-10-27T08:02:00Z"), 2L, Instant.parse("2026-10-01T08:02:00Z")));

        mockMvc.perform(get("/api/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].awaitingVerification").value(true))
                .andExpect(jsonPath("$[0].enabled").value(false))
                .andExpect(jsonPath("$[0].verificationSentAt").value("2026-10-27T08:02:00Z"))
                .andExpect(jsonPath("$[1].awaitingVerification").value(false))
                .andExpect(jsonPath("$[1].verificationSentAt").doesNotExist())
                .andExpect(jsonPath("$[2].awaitingVerification").value(false));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /api/users carries the last active time already tracked, and an empty string for a "
            + "reader never seen")
    void getUsers_carriesLastActive() throws Exception {
        AppUserEntity seen = buildUser(1L, "seen", UserRole.LITE_USER);
        seen.setLastActiveAt(LocalDateTime.of(2026, 10, 27, 9, 14));
        AppUserEntity never = buildUser(2L, "never", UserRole.LITE_USER);
        when(userService.listAllUsers()).thenReturn(List.of(seen, never));

        mockMvc.perform(get("/api/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].lastActiveAt").value("2026-10-27T09:14"))
                .andExpect(jsonPath("$[1].lastActiveAt").value(""));
    }

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
    @DisplayName("POST /api/users: a lost race on the email reservation is a 409 'Email already registered', "
            + "the admin's welcome email is not sent")
    void createUser_emailReservationViolation_returns409() throws Exception {
        when(userService.createUser(anyString(), anyString(), any(UserRole.class), anyString()))
                .thenThrow(new DataIntegrityViolationException("could not execute statement",
                        new SQLException("duplicate key value violates unique constraint "
                                + "\"pk_app_user_email_claim\"")));

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"pass\","
                                + "\"role\":\"LITE_USER\",\"email\":\"alice@example.com\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Email already registered"));

        verify(userEmailService, never()).sendWelcomeEmail(anyString(), anyString(), anyString());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /api/users: a lost race on the username key is a 409 'Username already taken'")
    void createUser_usernameViolation_returns409() throws Exception {
        when(userService.createUser(anyString(), anyString(), any(UserRole.class), anyString()))
                .thenThrow(new DataIntegrityViolationException("could not execute statement",
                        new SQLException("duplicate key value violates unique constraint "
                                + "\"app_user_username_key\"")));

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"pass\","
                                + "\"role\":\"LITE_USER\",\"email\":\"alice@example.com\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Username already taken"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /api/users: any other integrity violation is not dressed up as a 409")
    void createUser_otherViolation_isNotAConflict() throws Exception {
        when(userService.createUser(anyString(), anyString(), any(UserRole.class), anyString()))
                .thenThrow(new DataIntegrityViolationException("could not execute statement",
                        new SQLException("null value in column \"role\" violates not-null constraint")));

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"pass\","
                                + "\"role\":\"LITE_USER\",\"email\":\"alice@example.com\"}"))
                .andExpect(status().is5xxServerError());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT /api/users/{id}/email: an address another account holds (unique violation) is a 409")
    void setEmail_violation_returns409() throws Exception {
        doThrow(new DataIntegrityViolationException("could not execute statement",
                new SQLException("duplicate key value violates unique constraint \"idx_app_user_email\"")))
                .when(userService).setEmail(eq(1L), anyString());

        mockMvc.perform(put("/api/users/1/email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"taken@example.com\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Email already registered"));
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
    @DisplayName("PUT /api/users/{id}/email on a PENDING registration says its old verification link was "
            + "voided and the admin must resend")
    void setEmail_pendingAccount_saysToResend() throws Exception {
        when(userService.setEmail(1L, "new@example.com")).thenReturn(true);

        mockMvc.perform(put("/api/users/1/email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Updated. The old verification link no longer works: "
                        + "resend verification to the new address."));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT /api/users/{id}/email: a case variant another account holds (the claim table's refusal) "
            + "is a 409 'Email already registered', not a 400")
    void setEmail_caseVariantOfAnotherAddress_returns409() throws Exception {
        doThrow(new IllegalArgumentException("Email already registered"))
                .when(userService).setEmail(eq(1L), anyString());

        mockMvc.perform(put("/api/users/1/email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"TAKEN@example.com\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Email already registered"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("PUT /api/users/{id}/email: a non-ASCII address is a 400 with the sentence the service gives")
    void setEmail_nonAscii_returns400() throws Exception {
        doThrow(new IllegalArgumentException(
                "Please use an email address without accented or non-Latin characters"))
                .when(userService).setEmail(eq(1L), anyString());

        mockMvc.perform(put("/api/users/1/email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"bob@gmaıl.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error")
                        .value("Please use an email address without accented or non-Latin characters"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /api/users: a non-ASCII address is a 400 with the sentence the service gives")
    void createUser_nonAsciiEmail_returns400() throws Exception {
        when(userService.createUser(anyString(), anyString(), any(UserRole.class), anyString()))
                .thenThrow(new IllegalArgumentException(
                        "Please use an email address without accented or non-Latin characters"));

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bobby\",\"password\":\"pass\","
                                + "\"role\":\"LITE_USER\",\"email\":\"bob@gmaıl.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error")
                        .value("Please use an email address without accented or non-Latin characters"));
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
        when(registrationService.adminResendVerification(user)).thenReturn(true);

        mockMvc.perform(post("/api/users/1/resend-verification"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Verification email sent"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /api/users/{id}/resend-verification returns 400 when the account is no longer awaiting "
            + "verification (activated, or its address moved and the link was voided): the issuer sent nothing")
    void resendVerification_notPending_returns400() throws Exception {
        AppUserEntity user = buildUser(1L, "alice", UserRole.LITE_USER);
        user.setEmail("alice@example.com");
        when(userService.listAllUsers()).thenReturn(List.of(user));
        when(registrationService.adminResendVerification(user)).thenReturn(false);

        mockMvc.perform(post("/api/users/1/resend-verification"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Account is not awaiting email verification"));
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
        when(userService.deleteUser(eq(2L), eq("admin")))
                .thenReturn(new DeletedAccount("bob", "bob@example.com"));

        mockMvc.perform(delete("/api/users/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("User deleted"));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    @DisplayName("DELETE /api/users/{id} sends the 'account removed' email AFTER the service's transaction "
            + "has returned, to the STORED address the service read under the lock")
    void deleteUser_sendsTheEmailAfterTheServiceReturns_toTheStoredAddress() throws Exception {
        when(userService.deleteUser(eq(2L), eq("admin")))
                .thenReturn(new DeletedAccount("bob", "stored@example.com"));

        mockMvc.perform(delete("/api/users/2")).andExpect(status().isOk());

        InOrder order = inOrder(userService, userEmailService);
        order.verify(userService).deleteUser(2L, "admin");
        order.verify(userEmailService).sendAccountDeletedEmail("stored@example.com", "bob");
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    @DisplayName("DELETE /api/users/{id} sends NO email when the delete fails: a refused self-delete and a "
            + "missing user mail nobody")
    void deleteUser_failure_sendsNoEmail() throws Exception {
        doThrow(new IllegalStateException("Cannot delete your own account"))
                .when(userService).deleteUser(eq(1L), eq("admin"));
        doThrow(new IllegalArgumentException("User not found: 99"))
                .when(userService).deleteUser(eq(99L), eq("admin"));

        mockMvc.perform(delete("/api/users/1")).andExpect(status().isConflict());
        mockMvc.perform(delete("/api/users/99")).andExpect(status().isBadRequest());

        verifyNoInteractions(userEmailService);
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
