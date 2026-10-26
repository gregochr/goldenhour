package com.gregochr.goldenhour.service.notification;

import com.gregochr.goldenhour.config.EmailTextTemplateConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Renders the six plain-text email twins through a real Thymeleaf engine wired like production: the
 * {@link EmailTextTemplateConfig} resolver beside an HTML resolver mirroring Spring Boot's default one.
 */
class EmailTextTemplateRenderingTest {

    private static final String SIGN_OFF = "PhotoCast — Golden hour, forecast and ranked by AI";
    private static final String HOSTILE = "a&b<c>";

    private static SpringTemplateEngine engine;

    @BeforeAll
    static void buildEngine() {
        engine = new SpringTemplateEngine();
        engine.addTemplateResolver(htmlResolver());
        engine.addTemplateResolver(new EmailTextTemplateConfig().emailTextTemplateResolver());
    }

    /** Mirrors Boot's {@code defaultTemplateResolver}: {@code classpath:/templates/*.html}, no explicit order. */
    private static ClassLoaderTemplateResolver htmlResolver() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML");
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCheckExistence(true);
        return resolver;
    }

    static Stream<Arguments> templates() {
        return Stream.of(
                Arguments.of("verification-email",
                        Map.of("username", "alice", "verificationUrl", "https://app.photocast.online/verify#token=v1",
                                "holdDays", 7),
                        "https://app.photocast.online/verify#token=v1"),
                Arguments.of("verification-reminder-email",
                        Map.of("username", "alice", "verificationUrl", "https://app.photocast.online/verify#token=f1",
                                "finalReminder", false, "daysLeft", 3, "lapsesOn", "Friday 30 October"),
                        "https://app.photocast.online/verify#token=f1"),
                Arguments.of("verification-reminder-email",
                        Map.of("username", "alice", "verificationUrl", "https://app.photocast.online/verify#token=f2",
                                "finalReminder", true, "daysLeft", 3, "lapsesOn", "Friday 30 October"),
                        "https://app.photocast.online/verify#token=f2"),
                Arguments.of("welcome-email",
                        Map.of("username", "alice", "temporaryPassword", "Tmp-Pass-123"),
                        "Tmp-Pass-123"),
                Arguments.of("password-reset-email",
                        Map.of("username", "alice", "temporaryPassword", "Tmp-Pass-456"),
                        "Tmp-Pass-456"),
                Arguments.of("password-reset-link-email",
                        Map.of("username", "alice", "resetUrl", "https://app.photocast.online/reset#token=r1"),
                        "https://app.photocast.online/reset#token=r1"),
                Arguments.of("account-deleted-email",
                        Map.of("username", "alice"),
                        "alice"));
    }

    private static String renderText(String template, Map<String, Object> variables) {
        Context context = new Context();
        variables.forEach(context::setVariable);
        return engine.process("text/" + template, context);
    }

    @ParameterizedTest(name = "text/{0} renders its key value and a clean body")
    @MethodSource("templates")
    @DisplayName("each text template shows its key value on its own line, the sign-off and no markup")
    void rendersKeyValueAndNoMarkup(String template, Map<String, Object> variables, String keyValue) {
        String text = renderText(template, variables);

        if (!"alice".equals(keyValue)) {
            assertThat(text.lines()).as("the key value sits on its own line").contains(keyValue);
        }
        assertThat(text).contains("alice").contains(keyValue).endsWith(SIGN_OFF + "\n");
        assertThat(text).doesNotContain("${").doesNotContain("th:").doesNotContain("[(").doesNotContain("[#")
                .doesNotContain("<").doesNotContain(">");
        assertThat(text.lines().filter(line -> !line.contains("http")))
                .as("prose is wrapped at a sensible width").allSatisfy(line -> assertThat(line).hasSizeLessThan(81));
    }

    @ParameterizedTest(name = "text/{0} prints a hostile username literally")
    @MethodSource("templates")
    @DisplayName("a username containing & < > is not HTML-escaped in the text part")
    void usernameIsUnescaped(String template, Map<String, Object> variables, String keyValue) {
        Map<String, Object> hostile = new java.util.HashMap<>(variables);
        hostile.put("username", HOSTILE);

        String text = renderText(template, hostile);

        assertThat(text).contains(HOSTILE).doesNotContain("&amp;").doesNotContain("&lt;");
    }

    @Test
    @DisplayName("the reminder text takes both finalReminder branches")
    void reminderBranches() {
        Map<String, Object> first = Map.of("username", "alice", "verificationUrl", "u", "finalReminder", false,
                "daysLeft", 3, "lapsesOn", "Friday 30 October");
        Map<String, Object> last = Map.of("username", "alice", "verificationUrl", "u", "finalReminder", true,
                "daysLeft", 3, "lapsesOn", "Friday 30 October");

        String firstText = renderText("verification-reminder-email", first);
        String lastText = renderText("verification-reminder-email", last);

        assertThat(firstText).startsWith("Your place is still held\n")
                .contains("held until Friday 30 October (3 more days).")
                .doesNotContain("released tomorrow").doesNotContain("Last day");
        assertThat(lastText).startsWith("Last day to claim your place\n")
                .contains("released tomorrow (Friday 30 October).")
                .doesNotContain("held until").doesNotContain("Your place is still held");
        assertThat(firstText).contains("the place lapses on its own");
        assertThat(lastText).contains("the place lapses on its own");
    }

    @Test
    @DisplayName("the verification text states the hold only when holdDays is supplied")
    void verificationHoldLine() {
        String with = renderText("verification-email",
                Map.of("username", "alice", "verificationUrl", "u", "holdDays", 7));
        Map<String, Object> noHold = new java.util.HashMap<>();
        noHold.put("username", "alice");
        noHold.put("verificationUrl", "u");
        noHold.put("holdDays", null);
        String without = renderText("verification-email", noHold);

        assertThat(with).contains("We'll hold your place for 7 days.");
        assertThat(without).doesNotContain("hold your place").contains("copy and paste it into your browser.");
    }

    @Test
    @DisplayName("the resolvers do not steal each other's names: html names stay HTML, text/ names go to the .txt")
    void resolversDoNotStealEachOthersNames() {
        Map<String, Object> vars = Map.of("username", "alice", "verificationUrl", "https://x/verify#token=t",
                "holdDays", 7);
        Context context = new Context();
        vars.forEach(context::setVariable);

        String html = engine.process("verification-email", context);
        String text = engine.process("text/verification-email", context);

        assertThat(html).contains("<html").contains("<h2");
        assertThat(text).doesNotContain("<html").startsWith("Verify your email address\n");

        var textResolver = new EmailTextTemplateConfig().emailTextTemplateResolver();
        assertThat(textResolver.getResolvablePatternSpec().matches("verification-email")).isFalse();
        assertThat(textResolver.getResolvablePatternSpec().matches("text/verification-email")).isTrue();
    }
}
