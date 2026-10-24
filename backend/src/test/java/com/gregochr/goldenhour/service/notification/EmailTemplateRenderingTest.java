package com.gregochr.goldenhour.service.notification;

import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders the five transactional email templates through a real Thymeleaf engine (the
 * {@code UserEmailServiceTest} mocks the engine, so nothing else renders them) and checks they wear
 * the current brand through the shared {@code fragments/email-brand} fragments.
 */
class EmailTemplateRenderingTest {

    private static final String[] RETIRED = {
            "#E5A00D", "#181A1E", "#252830", "#3A3D45", "#A0A0A0", "#6B6B6B", "#EBEBEB", "Fiery Skies"
    };

    private static SpringTemplateEngine engine;

    @BeforeAll
    static void buildEngine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML");
        resolver.setCharacterEncoding("UTF-8");
        engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
    }

    static Stream<Arguments> templates() {
        return Stream.of(
                Arguments.of("verification-email", "Verify your email address",
                        Map.of("username", "alice", "verificationUrl", "https://app.photocast.online/verify?token=v1"),
                        "https://app.photocast.online/verify?token=v1"),
                Arguments.of("welcome-email", "Your account is ready",
                        Map.of("username", "alice", "temporaryPassword", "Tmp-Pass-123"),
                        "Tmp-Pass-123"),
                Arguments.of("password-reset-email", "Password reset",
                        Map.of("username", "alice", "temporaryPassword", "Tmp-Pass-456"),
                        "Tmp-Pass-456"),
                Arguments.of("password-reset-link-email", "Reset your password",
                        Map.of("username", "alice", "resetUrl", "https://app.photocast.online/reset?token=r1"),
                        "https://app.photocast.online/reset?token=r1"),
                Arguments.of("account-deleted-email", "Account notification",
                        Map.of("username", "alice"),
                        "alice"));
    }

    private static String render(String template, Map<String, Object> variables) {
        Context context = new Context();
        variables.forEach(context::setVariable);
        return engine.process(template, context);
    }

    @ParameterizedTest(name = "{0} wears the brand fragments and its variables")
    @MethodSource("templates")
    @DisplayName("each template renders with the shared lockup, footer, heading and its variables")
    void rendersBrandedWithVariables(String template, String heading, Map<String, Object> variables,
                                     String distinctValue) {
        String html = render(template, variables);

        assertThat(html).contains("Field guide to light");
        assertThat(html).contains("Golden hour, forecast and ranked by AI");
        assertThat(html).contains("<h1").contains(">PhotoCast</h1>");
        assertThat(html).contains("<h2").contains(heading);
        assertThat(html).contains("alice");
        assertThat(html).contains(distinctValue);
        assertThat(html).contains("background-color:#181210").contains("background-color:#221A15");
        assertThat(html).contains("name=\"color-scheme\"");
        assertThat(html).as("only the template's own head, not the fragment file's").containsOnlyOnce("<head>")
                .doesNotContain("email brand fragments");
    }

    @ParameterizedTest(name = "{0} carries none of the retired brand")
    @MethodSource("templates")
    @DisplayName("no retired colour or tagline survives in any rendered template")
    void noRetiredBrandRemains(String template, String heading, Map<String, Object> variables,
                               String distinctValue) {
        String html = render(template, variables);

        for (String retired : RETIRED) {
            assertThat(html).as("%s must not contain %s", template, retired).doesNotContainIgnoringCase(retired);
        }
    }

    @ParameterizedTest(name = "{0} keeps the lockup flush left")
    @MethodSource("templates")
    @DisplayName("the wordmark is never centred (a centred spine reads as a stray rule)")
    void lockupIsLeftAligned(String template, String heading, Map<String, Object> variables,
                             String distinctValue) {
        String html = render(template, variables);
        String header = html.substring(html.indexOf("<h1") - 600, html.indexOf("Field guide to light"));

        assertThat(html).doesNotContain("text-align:center");
        assertThat(header).contains("text-align:left");
    }
}
