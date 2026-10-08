package com.gregochr.goldenhour.controller;

import com.gregochr.goldenhour.repository.RegionRepository;
import com.gregochr.goldenhour.service.ask.AskEngine;
import com.gregochr.goldenhour.service.ask.AskMetricsService;
import com.gregochr.goldenhour.service.ask.AskProperties;
import com.gregochr.goldenhour.service.ask.AskReadyService;
import com.gregochr.goldenhour.service.ask.AskSettingsResponse;
import com.gregochr.goldenhour.service.ask.AskSnapshotBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Ask is switched off" is one interceptor ({@code AskFlagInterceptor}), and this proves its
 * precedence through the real security chain on every Ask route: <b>401 (anonymous), then 403 (an
 * admin route without the admin role), then 404 (the flag)</b>. The admin routes are guarded by
 * method security, which runs inside the controller after any interceptor, so the interceptor must
 * stand down for a non-admin; the public routes are guarded by the filter chain alone.
 * {@code GET /api/user/settings/ask} is outside the interceptor and answers whatever the flag says.
 */
class AskFlagOffPrecedenceTest extends AbstractControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AskProperties properties;

    // The same set AskAdminControllerTest declares, so the two share one application context.
    @MockitoBean
    private AskEngine engine;
    @MockitoBean
    private AskSnapshotBuilder snapshotBuilder;
    @MockitoBean
    private RegionRepository regionRepository;
    @MockitoBean
    private AskReadyService readyService;
    @MockitoBean
    private AskMetricsService metricsService;

    @BeforeEach
    void setUp() {
        properties.setEnabled(false);
    }

    @AfterEach
    void tearDown() {
        properties.setEnabled(false);
    }

    private static RequestPostProcessor as(String role) {
        return SecurityMockMvcRequestPostProcessors.user("someone").roles(role);
    }

    private int statusOf(String method, String path, RequestPostProcessor caller) throws Exception {
        var builder = request(HttpMethod.valueOf(method), path)
                .contentType(MediaType.APPLICATION_JSON).content("{}");
        if (caller != null) {
            builder = builder.with(caller);
        }
        return mockMvc.perform(builder).andReturn().getResponse().getStatus();
    }

    private static final String ROUTES = """
            POST, /api/ask
            GET, /api/ask/ready
            POST, /api/admin/ask/dry-run
            POST, /api/admin/ask/ready/precompute
            GET, /api/admin/ask/metrics
            """;

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(textBlock = ROUTES)
    @DisplayName("401 first: an anonymous caller is 401 on every Ask route with the flag off")
    void anonymousIs401(String method, String path) throws Exception {
        assertThat(statusOf(method, path, null)).isEqualTo(401);
        verifyNoInteractions(engine, snapshotBuilder, readyService, metricsService, askService);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(textBlock = """
            POST, /api/ask, 404
            GET, /api/ask/ready, 404
            POST, /api/admin/ask/dry-run, 403
            POST, /api/admin/ask/ready/precompute, 403
            GET, /api/admin/ask/metrics, 403
            """)
    @DisplayName("then 403: a LITE caller with the flag off is 404 on the public routes and still 403 on the "
            + "admin routes, which the interceptor leaves to the role check")
    void liteCallerSeesTheRoleCheckBeforeTheFlag(String method, String path, int expected) throws Exception {
        assertThat(statusOf(method, path, as("LITE_USER"))).isEqualTo(expected);
        verifyNoInteractions(engine, snapshotBuilder, readyService, metricsService);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(textBlock = ROUTES)
    @DisplayName("then 404: an ADMIN caller with the flag off is 404 on every Ask route and nothing runs")
    void adminSeesTheFlag(String method, String path) throws Exception {
        assertThat(statusOf(method, path, as("ADMIN"))).isEqualTo(404);
        verifyNoInteractions(engine, snapshotBuilder, readyService, metricsService, askService);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(textBlock = """
            POST, /api/admin/ask/dry-run
            POST, /api/admin/ask/ready/precompute
            GET, /api/admin/ask/metrics
            """)
    @DisplayName("control: with the flag on the same LITE caller is 403 on the admin routes, so the 403 above "
            + "is the role check and not the flag")
    void liteIs403WithTheFlagOn(String method, String path) throws Exception {
        properties.setEnabled(true);

        assertThat(statusOf(method, path, as("LITE_USER"))).isEqualTo(403);
    }

    @Test
    @DisplayName("GET /api/user/settings/ask is outside the interceptor: 200 for a LITE caller with the flag off")
    void settingsReadIsAlwaysAnswered() throws Exception {
        when(askService.settings(any())).thenReturn(AskSettingsResponse.off());

        mockMvc.perform(get("/api/user/settings/ask").with(as("LITE_USER")))
                .andExpect(status().isOk());
    }
}
