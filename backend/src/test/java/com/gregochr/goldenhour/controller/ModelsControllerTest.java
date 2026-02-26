package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.service.JwtService;
import com.gregochr.goldenhour.service.ModelSelectionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for {@link ModelsController} — validates model selection endpoints.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ModelsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockBean
    private ModelSelectionService modelSelectionService;

    @Test
    @DisplayName("GET /api/models returns available models and active model")
    void getAvailableModels_returnsHaikuAndSonnetOnly() throws Exception {
        String token = jwtService.generateAccessToken("user", UserRole.LITE_USER);
        when(modelSelectionService.getActiveModel()).thenReturn(EvaluationModel.HAIKU);

        mockMvc.perform(get("/api/models")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("HAIKU")))
                .andExpect(content().string(containsString("SONNET")))
                .andExpect(content().string(containsString("\"active\":\"HAIKU\"")));

        verify(modelSelectionService).getActiveModel();
    }

    @Test
    @DisplayName("GET /api/models does not include WILDLIFE")
    void getAvailableModels_excludesWildlife() throws Exception {
        String token = jwtService.generateAccessToken("user", UserRole.LITE_USER);
        when(modelSelectionService.getActiveModel()).thenReturn(EvaluationModel.HAIKU);

        mockMvc.perform(get("/api/models")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("HAIKU")))
                .andExpect(content().string(containsString("SONNET")));
        // Note: WILDLIFE should not appear in the response at all
    }

    @Test
    @DisplayName("PUT /api/models/active with ADMIN role succeeds")
    void setActiveModel_adminUser_succeeds() throws Exception {
        String adminToken = jwtService.generateAccessToken("admin", UserRole.ADMIN);
        when(modelSelectionService.setActiveModel(EvaluationModel.SONNET))
                .thenReturn(EvaluationModel.SONNET);

        mockMvc.perform(put("/api/models/active")
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content("{\"model\":\"SONNET\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("SONNET")));

        verify(modelSelectionService).setActiveModel(EvaluationModel.SONNET);
    }

    @Test
    @DisplayName("PUT /api/models/active without ADMIN role returns 403")
    void setActiveModel_nonAdminUser_forbidden() throws Exception {
        String userToken = jwtService.generateAccessToken("user", UserRole.LITE_USER);

        mockMvc.perform(put("/api/models/active")
                .header("Authorization", "Bearer " + userToken)
                .contentType("application/json")
                .content("{\"model\":\"SONNET\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PUT /api/models/active without token returns 401")
    void setActiveModel_noToken_unauthorized() throws Exception {
        mockMvc.perform(put("/api/models/active")
                .contentType("application/json")
                .content("{\"model\":\"SONNET\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PUT /api/models/active switches from HAIKU to SONNET")
    void setActiveModel_switchesModels() throws Exception {
        String adminToken = jwtService.generateAccessToken("admin", UserRole.ADMIN);
        when(modelSelectionService.setActiveModel(EvaluationModel.SONNET))
                .thenReturn(EvaluationModel.SONNET);

        mockMvc.perform(put("/api/models/active")
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content("{\"model\":\"SONNET\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"active\":\"SONNET\"")));
    }

    @Test
    @DisplayName("PUT /api/models/active with invalid model returns 400")
    void setActiveModel_invalidModel_badRequest() throws Exception {
        String adminToken = jwtService.generateAccessToken("admin", UserRole.ADMIN);

        mockMvc.perform(put("/api/models/active")
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content("{\"model\":\"INVALID_MODEL\"}"))
                .andExpect(status().isBadRequest());
    }
}
