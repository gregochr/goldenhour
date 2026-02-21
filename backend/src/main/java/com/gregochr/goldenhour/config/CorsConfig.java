package com.gregochr.goldenhour.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS configuration allowing the React frontend to call the REST API.
 *
 * <p>Permits requests from the Vite dev server ({@code http://localhost:5173}).
 * In production, replace or extend the allowed origins to match the deployed frontend URL.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /** Vite dev server origin. */
    private static final String DEV_FRONTEND_ORIGIN = "http://localhost:5173";

    /**
     * {@inheritDoc}
     *
     * <p>Allows all HTTP methods from the configured frontend origin on all {@code /api/**} paths.
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(DEV_FRONTEND_ORIGIN)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS");
    }
}
