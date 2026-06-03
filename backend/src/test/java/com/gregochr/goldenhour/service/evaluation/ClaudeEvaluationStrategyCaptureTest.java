package com.gregochr.goldenhour.service.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gregochr.goldenhour.entity.EvaluationModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Tests the Bug B capture instrumentation in {@link ClaudeEvaluationStrategy#parseEvaluation}.
 *
 * <p>The instrumentation is a v2.13.2 <em>precursor-to-the-precursor</em>: it exists only to
 * recover a real malformed Claude response from production logs. The over-capture bug
 * (greedy regex fallback swallowing {@code headline} into {@code summary}) is a <em>silent
 * success</em> — the raw input is neither logged nor persisted anywhere today — so before the
 * fallback can be fixed against a real fixture, every fallback invocation must emit its raw
 * input. These tests prove that capture fires on the fallback path and stays silent on the
 * strict-parse happy path, and that parsing behaviour is unchanged.
 *
 * <p>The inputs reused here (unescaped-quote malformed JSON; a well-formed response) are the
 * same ones the existing {@code ClaudeEvaluationStrategyTest} uses to exercise the fallback —
 * not a reverse-engineered Bug B fixture (which is deliberately deferred until a real sample
 * is captured in production).
 */
class ClaudeEvaluationStrategyCaptureTest {

    private static final String CAPTURE_MARKER = "Bug B capture";

    /** Malformed: unescaped inner quotes break strict JSON → regex fallback is used. */
    private static final String MALFORMED_JSON =
            "{\"rating\":4,\"fiery_sky\":72,\"golden_hour\":65,"
            + "\"summary\":\"Beautiful \"orange\" sky at sunset.\"}";

    /** Well-formed: strict parse succeeds → no fallback, no capture. */
    private static final String WELL_FORMED_JSON =
            "{\"rating\":2,\"fiery_sky\":25,\"golden_hour\":30,"
            + "\"summary\":\"Cloudy with little colour.\"}";

    private ClaudeEvaluationStrategy strategy;
    private Logger captureLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        strategy = new ClaudeEvaluationStrategy(
                mock(AnthropicApiClient.class), new PromptBuilder(), new CoastalPromptBuilder(),
                new ObjectMapper(), EvaluationModel.SONNET);

        captureLogger = (Logger) LoggerFactory.getLogger(ClaudeEvaluationStrategy.class);
        appender = new ListAppender<>();
        appender.start();
        captureLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        captureLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("fallback path: emits a WARN 'Bug B capture' carrying the raw response")
    void fallbackPath_logsCaptureWithRawText() {
        strategy.parseEvaluation(MALFORMED_JSON, new ObjectMapper());

        ILoggingEvent event = appender.list.stream()
                .filter(e -> e.getFormattedMessage().contains(CAPTURE_MARKER))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No 'Bug B capture' log emitted"));

        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        // The raw response must be present so a real fixture is recoverable from the log.
        assertThat(event.getFormattedMessage()).contains(MALFORMED_JSON);
    }

    @Test
    @DisplayName("happy path: well-formed JSON does NOT emit a capture log")
    void happyPath_noCaptureLog() {
        strategy.parseEvaluation(WELL_FORMED_JSON, new ObjectMapper());

        boolean captured = appender.list.stream()
                .anyMatch(e -> e.getFormattedMessage().contains(CAPTURE_MARKER));
        assertThat(captured).isFalse();
    }

    @Test
    @DisplayName("capture instrumentation does not change fallback parse results")
    void fallbackPath_parseResultUnchanged() {
        var result = strategy.parseEvaluation(MALFORMED_JSON, new ObjectMapper());

        // Behaviour is identical to pre-instrumentation: rating/scores still extracted by the
        // independent patterns; only a diagnostic log was added.
        assertThat(result.rating()).isEqualTo(4);
        assertThat(result.fierySkyPotential()).isEqualTo(72);
        assertThat(result.goldenHourPotential()).isEqualTo(65);
    }

    @Test
    @DisplayName("truncateForCapture: null and short text pass through; over-long text is bounded")
    void truncateForCapture_boundaries() {
        assertThat(ClaudeEvaluationStrategy.truncateForCapture(null)).isEqualTo("null");

        String shortText = "{\"rating\":3}";
        assertThat(ClaudeEvaluationStrategy.truncateForCapture(shortText)).isEqualTo(shortText);

        String overLong = "a".repeat(4001);
        String truncated = ClaudeEvaluationStrategy.truncateForCapture(overLong);
        assertThat(truncated).startsWith("a".repeat(4000));
        assertThat(truncated).contains("[truncated 4001 chars]");
        assertThat(truncated).doesNotMatch("a{4001}.*");
    }
}
