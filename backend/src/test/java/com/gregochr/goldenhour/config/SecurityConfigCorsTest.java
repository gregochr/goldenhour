package com.gregochr.goldenhour.config;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigUtils;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The origins {@link SecurityConfig#corsConfigurationSource()} lets through: the built-in local and LAN
 * patterns always, plus whatever {@code cors.extra-origins} adds, with blank entries dropped.
 */
class SecurityConfigCorsTest {

    private static final List<String> BUILT_IN = List.of(
            "http://localhost:*", "http://192.168.*.*:*", "http://10.*.*.*:*");

    private static CorsConfiguration apiConfig(CorsConfigurationSource source) {
        return ((UrlBasedCorsConfigurationSource) source).getCorsConfigurations().get("/api/**");
    }

    @Test
    @DisplayName("with no extra origins configured at all, only the local and LAN patterns are allowed")
    void noExtraOrigins_builtInPatternsOnly() {
        SecurityConfig config = new SecurityConfig(mock(JwtAuthenticationFilter.class), mock(RewindFilter.class),
                mock(PasswordChangeRequiredFilter.class));

        CorsConfiguration api = apiConfig(config.corsConfigurationSource());

        assertThat(api.getAllowedOriginPatterns()).containsExactlyElementsOf(BUILT_IN);
        assertThat(api.getAllowedMethods()).containsExactly("GET", "POST", "PUT", "DELETE", "OPTIONS");
        assertThat(api.getAllowedHeaders())
                .containsExactly("Authorization", "Content-Type", "Accept", RewindFilter.HEADER);
    }

    @Test
    @DisplayName("cors.extra-origins adds each non-blank origin after the built-in patterns")
    void extraOrigins_areAppendedAndBlanksDropped() {
        GenericWebApplicationContext ctx = new GenericWebApplicationContext();
        ctx.setServletContext(new MockServletContext());
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
                Map.of("cors.extra-origins", "https://photocast.example, ,https://staging.example")));
        ctx.getBeanFactory().setConversionService(new DefaultConversionService());
        ctx.registerBean(JwtAuthenticationFilter.class, () -> mock(JwtAuthenticationFilter.class));
        ctx.registerBean(RewindFilter.class, () -> mock(RewindFilter.class));
        ctx.registerBean(PasswordChangeRequiredFilter.class, () -> mock(PasswordChangeRequiredFilter.class));
        AnnotationConfigUtils.registerAnnotationConfigProcessors(ctx);
        ctx.registerBean(SecurityConfig.class);
        ctx.refresh();
        try {
            CorsConfiguration api = apiConfig(ctx.getBean(SecurityConfig.class).corsConfigurationSource());

            assertThat(api.getAllowedOriginPatterns()).containsExactly(
                    "http://localhost:*", "http://192.168.*.*:*", "http://10.*.*.*:*",
                    "https://photocast.example", "https://staging.example");
        } finally {
            ctx.close();
        }
    }
}
