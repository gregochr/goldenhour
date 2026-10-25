package com.gregochr.goldenhour.service.notification;

import java.util.Map;
import java.util.regex.Pattern;
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
 * Renders the six transactional email templates through a real Thymeleaf engine (the
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
                        Map.of("username", "alice", "verificationUrl", "https://app.photocast.online/verify#token=v1",
                                "holdDays", 7),
                        "https://app.photocast.online/verify#token=v1"),
                Arguments.of("verification-reminder-email", "Your place is still held",
                        Map.of("username", "alice", "verificationUrl", "https://app.photocast.online/verify#token=f1",
                                "finalReminder", false, "daysLeft", 3, "lapsesOn", "Friday 30 October"),
                        "https://app.photocast.online/verify#token=f1"),
                Arguments.of("verification-reminder-email", "Last day to claim your place",
                        Map.of("username", "alice", "verificationUrl", "https://app.photocast.online/verify#token=f2",
                                "finalReminder", true, "daysLeft", 3, "lapsesOn", "Friday 30 October"),
                        "https://app.photocast.online/verify#token=f2"),
                Arguments.of("welcome-email", "Your account is ready",
                        Map.of("username", "alice", "temporaryPassword", "Tmp-Pass-123"),
                        "Tmp-Pass-123"),
                Arguments.of("password-reset-email", "Password reset",
                        Map.of("username", "alice", "temporaryPassword", "Tmp-Pass-456"),
                        "Tmp-Pass-456"),
                Arguments.of("password-reset-link-email", "Reset your password",
                        Map.of("username", "alice", "resetUrl", "https://app.photocast.online/reset#token=r1"),
                        "https://app.photocast.online/reset#token=r1"),
                Arguments.of("account-deleted-email", "Account notification",
                        Map.of("username", "alice"),
                        "alice"));
    }

    static Stream<Arguments> linkTemplates() {
        return templates().filter(a -> ((String) a.get()[3]).contains("#token="));
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

    @ParameterizedTest(name = "{0} mails the one-time token in the fragment")
    @MethodSource("linkTemplates")
    @DisplayName("a one-time link renders with #token= in both the button href and the visible URL, never ?token=")
    void oneTimeLinksUseTheFragment(String template, String heading, Map<String, Object> variables,
                                    String distinctValue) {
        String html = render(template, variables);

        assertThat(html).contains("href=\"" + distinctValue + "\"");
        assertThat(html).doesNotContain("?token=");
        assertThat(html.split(Pattern.quote(distinctValue), -1))
                .as("the URL appears as the href and as the visible copy-paste text").hasSize(3);
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

    @org.junit.jupiter.api.Test
    @DisplayName("the verification email says how long the place is held; the reminders end with the do-nothing line")
    void holdAndDoNothingLines() {
        assertThat(render("verification-email", Map.of("username", "alice", "verificationUrl", "u",
                "holdDays", 7))).contains("We'll hold your place for").contains(">7<").contains("days.");
        assertThat(render("verification-reminder-email", Map.of("username", "alice", "verificationUrl", "u",
                "finalReminder", true, "daysLeft", 3, "lapsesOn", "Friday 30 October")))
                .contains("released tomorrow").contains("the place lapses on its own");
        assertThat(render("verification-reminder-email", Map.of("username", "alice", "verificationUrl", "u",
                "finalReminder", false, "daysLeft", 3, "lapsesOn", "Friday 30 October")))
                .contains("held until").contains("Friday 30 October");
    }
}
