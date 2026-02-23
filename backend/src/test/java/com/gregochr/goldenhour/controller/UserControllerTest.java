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
import static org.mockito.ArgumentMatchers.anyString;
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
        when(userService.createUser(anyString(), anyString(), any(UserRole.class)))
                .thenReturn(created);

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bob\",\"password\":\"pass123\",\"role\":\"LITE_USER\"}"))
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
