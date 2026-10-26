package com.gregochr.goldenhour.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Registers a second Thymeleaf template resolver for the plain-text twins of the account emails.
 *
 * <p>Spring Boot's Thymeleaf auto-configuration builds the one {@code SpringTemplateEngine} from every
 * {@code ITemplateResolver} bean, so declaring this resolver is enough: no second engine is created. It claims
 * only names under {@code text/} (e.g. {@code text/welcome-email} loads {@code templates/text/welcome-email.txt}
 * in TEXT mode), so Boot's default HTML resolver keeps resolving every other name exactly as before.
 */
@Configuration
public class EmailTextTemplateConfig {

    /** Template-name prefix reserved for plain-text email templates. */
    public static final String TEXT_TEMPLATE_PREFIX = "text/";

    /** Runs before Boot's default HTML resolver (which has no explicit order). */
    private static final int RESOLVER_ORDER = 0;

    /**
     * Builds the TEXT-mode resolver for {@code text/*} template names.
     *
     * @return a resolver loading {@code classpath:/templates/text/*.txt} as UTF-8 text
     */
    @Bean
    public ClassLoaderTemplateResolver emailTextTemplateResolver() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".txt");
        resolver.setTemplateMode(TemplateMode.TEXT);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resolver.setResolvablePatterns(Set.of(TEXT_TEMPLATE_PREFIX + "*"));
        resolver.setCheckExistence(true);
        resolver.setOrder(RESOLVER_ORDER);
        return resolver;
    }
}
