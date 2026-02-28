package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link UserController}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserService userService;

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
        when(userService.resetPassword(1L)).thenReturn("Abc1!xyz9Qr2");

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
