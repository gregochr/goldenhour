package com.gregochr.goldenhour.config;

import com.gregochr.goldenhour.service.ask.AskProperties;
import com.gregochr.goldenhour.service.ask.AskService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web wiring for Ask: the flag-off interceptor ({@link AskFlagInterceptor}) over every Ask route, the
 * admission interceptor (the rate limit, applied before the body is converted) and the body-size
 * filter. The last two are mapped to exactly {@value #PATH}, so nothing else in the application is
 * touched; both act on POST only. The flag interceptor is registered first, so with Ask off nothing
 * downstream of it runs.
 */
@Configuration
public class AskWebConfig implements WebMvcConfigurer {

    /** The one path both are mapped to. */
    static final String PATH = "/api/ask";

    /** The admin routes' prefix. */
    static final String ADMIN_PATH = "/api/admin/ask";

    /** After Spring Security's chain (order -100), so an anonymous request is 401 before it. */
    private static final int AFTER_SECURITY = 0;

    private final AskProperties properties;
    private final AskService askService;

    /**
     * Creates the configuration.
     *
     * @param properties the Ask settings (the flag interceptor reads them)
     * @param askService the service the interceptor admits through
     */
    public AskWebConfig(AskProperties properties, @Lazy AskService askService) {
        this.properties = properties;
        this.askService = askService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AskFlagInterceptor(properties, false))
                .addPathPatterns(PATH, PATH + "/**");
        registry.addInterceptor(new AskFlagInterceptor(properties, true))
                .addPathPatterns(ADMIN_PATH, ADMIN_PATH + "/**");
        registry.addInterceptor(new AskAdmissionInterceptor(askService)).addPathPatterns(PATH);
    }

    /**
     * Registers the body-size filter for {@value #PATH}.
     *
     * @return the registration
     */
    @Bean
    public FilterRegistrationBean<AskBodyLimitFilter> askBodyLimitFilter() {
        FilterRegistrationBean<AskBodyLimitFilter> registration =
                new FilterRegistrationBean<>(new AskBodyLimitFilter());
        registration.addUrlPatterns(PATH);
        registration.setName("askBodyLimitFilter");
        registration.setOrder(AFTER_SECURITY);
        return registration;
    }
}
