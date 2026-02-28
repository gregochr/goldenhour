package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.RunType;
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

import java.util.EnumMap;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for {@link ModelsController} — validates per-run-type model selection endpoints.
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

    private Map<RunType, EvaluationModel> buildDefaultConfigs() {
        Map<RunType, EvaluationModel> configs = new EnumMap<>(RunType.class);
        configs.put(RunType.VERY_SHORT_TERM, EvaluationModel.HAIKU);
        configs.put(RunType.SHORT_TERM, EvaluationModel.HAIKU);
        configs.put(RunType.LONG_TERM, EvaluationModel.HAIKU);
        return configs;
    }

    @Test
    @DisplayName("GET /api/models returns available models and per-run-type configs")
    void getAvailableModels_returnsConfigsMap() throws Exception {
        String token = jwtService.generateAccessToken("user", UserRole.LITE_USER);
        when(modelSelectionService.getAllConfigs()).thenReturn(buildDefaultConfigs());

        mockMvc.perform(get("/api/models")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").isArray())
                .andExpect(content().string(containsString("HAIKU")))
                .andExpect(content().string(containsString("SONNET")))
                .andExpect(content().string(containsString("OPUS")))
                .andExpect(jsonPath("$.configs.VERY_SHORT_TERM").value("HAIKU"))
                .andExpect(jsonPath("$.configs.SHORT_TERM").value("HAIKU"))
                .andExpect(jsonPath("$.configs.LONG_TERM").value("HAIKU"));

        verify(modelSelectionService).getAllConfigs();
    }

    @Test
    @DisplayName("GET /api/models does not include WILDLIFE in available models")
    void getAvailableModels_excludesWildlife() throws Exception {
        String token = jwtService.generateAccessToken("user", UserRole.LITE_USER);
        when(modelSelectionService.getAllConfigs()).thenReturn(buildDefaultConfigs());

        String body = mockMvc.perform(get("/api/models")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("WILDLIFE");
    }

    @Test
    @DisplayName("PUT /api/models/active with ADMIN role and runType succeeds")
    void setActiveModel_adminUser_withRunType_succeeds() throws Exception {
        String adminToken = jwtService.generateAccessToken("admin", UserRole.ADMIN);
        when(modelSelectionService.setActiveModel(RunType.VERY_SHORT_TERM, EvaluationModel.OPUS))
                .thenReturn(EvaluationModel.OPUS);

        mockMvc.perform(put("/api/models/active")
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content("{\"runType\":\"VERY_SHORT_TERM\",\"model\":\"OPUS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runType").value("VERY_SHORT_TERM"))
                .andExpect(jsonPath("$.active").value("OPUS"));

        verify(modelSelectionService).setActiveModel(RunType.VERY_SHORT_TERM, EvaluationModel.OPUS);
    }

    @Test
    @DisplayName("PUT /api/models/active switches SHORT_TERM to SONNET")
    void setActiveModel_switchesShortTermToSonnet() throws Exception {
        String adminToken = jwtService.generateAccessToken("admin", UserRole.ADMIN);
        when(modelSelectionService.setActiveModel(RunType.SHORT_TERM, EvaluationModel.SONNET))
                .thenReturn(EvaluationModel.SONNET);

        mockMvc.perform(put("/api/models/active")
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content("{\"runType\":\"SHORT_TERM\",\"model\":\"SONNET\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runType").value("SHORT_TERM"))
                .andExpect(jsonPath("$.active").value("SONNET"));
    }

    @Test
    @DisplayName("PUT /api/models/active without ADMIN role returns 403")
    void setActiveModel_nonAdminUser_forbidden() throws Exception {
        String userToken = jwtService.generateAccessToken("user", UserRole.LITE_USER);

        mockMvc.perform(put("/api/models/active")
                .header("Authorization", "Bearer " + userToken)
                .contentType("application/json")
                .content("{\"runType\":\"SHORT_TERM\",\"model\":\"SONNET\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PUT /api/models/active without token returns 401")
    void setActiveModel_noToken_unauthorized() throws Exception {
        mockMvc.perform(put("/api/models/active")
                .contentType("application/json")
                .content("{\"runType\":\"SHORT_TERM\",\"model\":\"SONNET\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PUT /api/models/active with invalid model returns 400")
    void setActiveModel_invalidModel_badRequest() throws Exception {
        String adminToken = jwtService.generateAccessToken("admin", UserRole.ADMIN);

        mockMvc.perform(put("/api/models/active")
                .header("Authorization", "Bearer " + adminToken)
                .contentType("application/json")
                .content("{\"runType\":\"SHORT_TERM\",\"model\":\"INVALID_MODEL\"}"))
                .andExpect(status().isBadRequest());
    }
}
