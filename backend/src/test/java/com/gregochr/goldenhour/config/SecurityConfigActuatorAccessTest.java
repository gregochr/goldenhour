package com.gregochr.goldenhour.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigUtils;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.GenericWebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * Proves, through the real {@link SecurityConfig} filter chain, that the actuator health endpoints a
 * container probe curls are reachable without a token while {@code /api/**} still is not.
 */
class SecurityConfigActuatorAccessTest {

    private MockMvc mvc;

    private static <T extends Filter> T passThrough(Class<T> type) {
        try {
            T filter = Mockito.mock(type);
            Mockito.doAnswer(inv -> {
                inv.<FilterChain>getArgument(2).doFilter(inv.getArgument(0), inv.getArgument(1));
                return null;
            }).when(filter).doFilter(Mockito.any(ServletRequest.class), Mockito.any(ServletResponse.class),
                    Mockito.any(FilterChain.class));
            return filter;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void setUp() {
        GenericWebApplicationContext ctx = new GenericWebApplicationContext();
        ctx.setServletContext(new MockServletContext());
        ctx.registerBean(JwtAuthenticationFilter.class, () -> passThrough(JwtAuthenticationFilter.class));
        ctx.registerBean(RewindFilter.class, () -> passThrough(RewindFilter.class));
        ctx.registerBean(PasswordChangeRequiredFilter.class,
                () -> passThrough(PasswordChangeRequiredFilter.class));
        AnnotationConfigUtils.registerAnnotationConfigProcessors(ctx);
        ctx.registerBean(SecurityConfig.class);
        ctx.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(ctx).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("/actuator/health/container is not refused to an anonymous caller")
    void containerGroup_isAnonymous() throws Exception {
        int status = mvc.perform(MockMvcRequestBuilders.get("/actuator/health/container"))
                .andReturn().getResponse().getStatus();
        assertThat(status).isNotIn(401, 403);
    }

    @Test
    @DisplayName("/actuator/health stays anonymous")
    void aggregate_isAnonymous() throws Exception {
        int status = mvc.perform(MockMvcRequestBuilders.get("/actuator/health"))
                .andReturn().getResponse().getStatus();
        assertThat(status).isNotIn(401, 403);
    }

    @Test
    @DisplayName("control: an /api/** path without a token is still 401")
    void api_requiresAuthentication() throws Exception {
        int status = mvc.perform(MockMvcRequestBuilders.get("/api/briefing"))
                .andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(401);
    }

    @Test
    @DisplayName("/v3/api-docs stays anonymous (the ZAP pen-test stack reads it)")
    void apiDocs_isAnonymous() throws Exception {
        int status = mvc.perform(MockMvcRequestBuilders.get("/v3/api-docs"))
                .andReturn().getResponse().getStatus();
        assertThat(status).isNotIn(401, 403);
    }

    @Test
    @DisplayName("Swagger UI paths are denied to an anonymous caller")
    void swaggerUi_isDeniedAnonymously() throws Exception {
        for (String path : new String[] {"/swagger-ui/index.html", "/swagger-ui.html"}) {
            int status = mvc.perform(MockMvcRequestBuilders.get(path))
                    .andReturn().getResponse().getStatus();
            assertThat(status).as(path).isIn(401, 403);
        }
    }

    @Test
    @DisplayName("Swagger UI paths are denied even to an authenticated ADMIN")
    void swaggerUi_isDeniedToAdmin() throws Exception {
        for (String path : new String[] {"/swagger-ui/index.html", "/swagger-ui.html"}) {
            int status = mvc.perform(MockMvcRequestBuilders.get(path)
                            .with(SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN")))
                    .andReturn().getResponse().getStatus();
            assertThat(status).as(path).isEqualTo(403);
        }
    }
}
