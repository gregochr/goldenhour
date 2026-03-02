package com.gregochr.goldenhour.config;

import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.service.ForecastService;
import com.gregochr.goldenhour.service.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for {@link JwtAuthenticationFilter} — validates the filter's JWT extraction and
 * authentication logic using real requests through the Spring Security filter chain.
 */
@SpringBootTest
@AutoConfigureMockMvc
class JwtAuthenticationFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private ForecastEvaluationRepository forecastEvaluationRepository;

    @MockitoBean
    private ForecastService forecastService;

    @Test
    @DisplayName("Request with valid JWT token is authenticated and reaches the endpoint")
    void request_validJwt_isAuthenticated() throws Exception {
        String token = jwtService.generateAccessToken("alice", UserRole.LITE_USER);
        when(forecastEvaluationRepository
                .findByLocationNameAndTargetDateBetweenOrderByTargetDateAscTargetTypeAsc(
                        any(), any(), any()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/forecast")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Request with malformed JWT token returns 401")
    void request_malformedJwt_returns401() throws Exception {
        mockMvc.perform(get("/api/forecast")
                        .header("Authorization", "Bearer this.is.not.a.valid.token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Request with no Authorization header returns 401")
    void request_noAuthHeader_returns401() throws Exception {
        mockMvc.perform(get("/api/forecast"))
                .andExpect(status().isUnauthorized());
    }
}
