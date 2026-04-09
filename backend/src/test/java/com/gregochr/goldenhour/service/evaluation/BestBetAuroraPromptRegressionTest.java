package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.TextBlockParam;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AuroraForecastScore;
import com.gregochr.goldenhour.model.BestBet;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Prompt regression tests for the best-bet aurora temporal disambiguation instruction.
 *
 * <p>These tests call the real Claude API (Haiku) to verify that the system prompt
 * produces detail text with explicit temporal qualifiers when mentioning aurora
 * alongside solar event picks.
 *
 * <p>Run on demand with:
 * <pre>
 *   cd backend && ANTHROPIC_API_KEY=sk-ant-... ./mvnw test -Pprompt-regression \
 *       -Dtest=BestBetAuroraPromptRegressionTest
 * </pre>
 */
@Tag("prompt-regression")
class BestBetAuroraPromptRegressionTest {

    private static AnthropicApiClient apiClient;
    private static String systemPrompt;
    private static ObjectMapper objectMapper;

    @BeforeAll
    static void setUp() throws Exception {
        String apiKey = System.getProperty("ANTHROPIC_API_KEY",
                System.getenv("ANTHROPIC_API_KEY"));
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "ANTHROPIC_API_KEY must be set as a system property or environment variable");
        }

        AnthropicClient client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .build();
        apiClient = new AnthropicApiClient(client);
        objectMapper = new ObjectMapper().findAndRegisterModules();

        // Extract the private SYSTEM_PROMPT constant via reflection
        Field promptField = BriefingBestBetAdvisor.class.getDeclaredField("SYSTEM_PROMPT");
        promptField.setAccessible(true);
        systemPrompt = (String) promptField.get(null);
    }

    @Test
    @DisplayName("Aurora tonight + tomorrow sunset pick: detail must say 'tonight' when mentioning aurora")
    void auroraTonightWithTomorrowSunsetPick() throws Exception {
        LocalDate tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        // Strong tomorrow sunset — should be picked
        BriefingSlot goSlot = slot("Bamburgh", Verdict.GO,
                tomorrow.atTime(19, 45));
        BriefingRegion goRegion = new BriefingRegion(
                "Northumberland", Verdict.GO, "All clear, excellent conditions",
                List.of(), List.of(goSlot), 5.0, 3.0, 1.0, 1);
        BriefingDay day = new BriefingDay(tomorrow, List.of(
                new BriefingEventSummary(TargetType.SUNSET,
                        List.of(goRegion), List.of())));

        // Aurora active tonight (MODERATE, Kp 5.5)
        AuroraStateCache cache = mock(AuroraStateCache.class);
        when(cache.isActive()).thenReturn(true);
        when(cache.getCurrentLevel()).thenReturn(AlertLevel.MODERATE);
        when(cache.getLastTriggerKp()).thenReturn(5.5);
        when(cache.getCachedScores()).thenReturn(List.of(
                mock(AuroraForecastScore.class)));

        ModelSelectionService mss = mock(ModelSelectionService.class);
        when(mss.getActiveModel(RunType.BRIEFING_BEST_BET)).thenReturn(EvaluationModel.OPUS);
        BriefingBestBetAdvisor advisor = new BriefingBestBetAdvisor(
                apiClient, objectMapper, mock(JobRunService.class), mss, cache);

        BriefingBestBetAdvisor.RollupResult rollup =
                advisor.buildRollupJson(List.of(day), now);

        String raw = callHaiku(rollup.json());
        List<BestBet> picks = advisor.parseBestBets(raw);

        assertThat(picks).isNotEmpty();

        // Find the sunset pick (non-aurora)
        BestBet sunsetPick = picks.stream()
                .filter(p -> p.event() != null && p.event().contains("sunset"))
                .findFirst()
                .orElse(null);

        if (sunsetPick != null && mentionsAurora(sunsetPick.detail())) {
            // If the sunset pick mentions aurora, it MUST say "tonight"
            assertThat(sunsetPick.detail().toLowerCase())
                    .as("Sunset pick detail mentioning aurora must specify 'tonight'")
                    .containsPattern("tonight.{0,20}aurora|aurora.{0,20}tonight");
        }

        // If there's an aurora pick, it should also say "tonight"
        BestBet auroraPick = picks.stream()
                .filter(p -> "aurora_tonight".equals(p.event()))
                .findFirst()
                .orElse(null);
        if (auroraPick != null) {
            assertThat(auroraPick.detail().toLowerCase())
                    .as("Aurora pick detail must specify 'tonight'")
                    .contains("tonight");
        }
    }

    @Test
    @DisplayName("Aurora tonight + tonight's sunset pick: detail must still say 'tonight' for aurora")
    void auroraTonightWithTonightsSunsetPick() throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDateTime now = today.atTime(14, 0); // mid-afternoon

        // Tonight's sunset — should be picked
        BriefingSlot goSlot = slot("Bamburgh", Verdict.GO,
                today.atTime(19, 30));
        BriefingRegion goRegion = new BriefingRegion(
                "Northumberland", Verdict.GO, "Clear skies, perfect conditions",
                List.of(), List.of(goSlot), 5.0, 3.0, 1.0, 1);
        BriefingDay day = new BriefingDay(today, List.of(
                new BriefingEventSummary(TargetType.SUNSET,
                        List.of(goRegion), List.of())));

        // Aurora active tonight (MODERATE, Kp 5.5)
        AuroraStateCache cache = mock(AuroraStateCache.class);
        when(cache.isActive()).thenReturn(true);
        when(cache.getCurrentLevel()).thenReturn(AlertLevel.MODERATE);
        when(cache.getLastTriggerKp()).thenReturn(5.5);
        when(cache.getCachedScores()).thenReturn(List.of(
                mock(AuroraForecastScore.class)));

        ModelSelectionService mss = mock(ModelSelectionService.class);
        when(mss.getActiveModel(RunType.BRIEFING_BEST_BET)).thenReturn(EvaluationModel.OPUS);
        BriefingBestBetAdvisor advisor = new BriefingBestBetAdvisor(
                apiClient, objectMapper, mock(JobRunService.class), mss, cache);

        BriefingBestBetAdvisor.RollupResult rollup =
                advisor.buildRollupJson(List.of(day), now);

        String raw = callHaiku(rollup.json());
        List<BestBet> picks = advisor.parseBestBets(raw);

        assertThat(picks).isNotEmpty();

        // Any pick mentioning aurora should say "tonight"
        for (BestBet pick : picks) {
            if (mentionsAurora(pick.detail())) {
                assertThat(pick.detail().toLowerCase())
                        .as("Pick #%d detail mentioning aurora must specify 'tonight'",
                                pick.rank())
                        .contains("tonight");
            }
        }
    }

    @Test
    @DisplayName("No aurora active: detail must not mention aurora at all")
    void noAuroraActive() throws Exception {
        LocalDate tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        BriefingSlot goSlot = slot("Bamburgh", Verdict.GO,
                tomorrow.atTime(19, 45));
        BriefingRegion goRegion = new BriefingRegion(
                "Northumberland", Verdict.GO, "All clear, excellent conditions",
                List.of(), List.of(goSlot), 5.0, 3.0, 1.0, 1);
        BriefingDay day = new BriefingDay(tomorrow, List.of(
                new BriefingEventSummary(TargetType.SUNSET,
                        List.of(goRegion), List.of())));

        AuroraStateCache cache = mock(AuroraStateCache.class);
        when(cache.isActive()).thenReturn(false);

        ModelSelectionService mss = mock(ModelSelectionService.class);
        when(mss.getActiveModel(RunType.BRIEFING_BEST_BET)).thenReturn(EvaluationModel.OPUS);
        BriefingBestBetAdvisor advisor = new BriefingBestBetAdvisor(
                apiClient, objectMapper, mock(JobRunService.class), mss, cache);

        BriefingBestBetAdvisor.RollupResult rollup =
                advisor.buildRollupJson(List.of(day), now);

        String raw = callHaiku(rollup.json());
        List<BestBet> picks = advisor.parseBestBets(raw);

        assertThat(picks).isNotEmpty();

        for (BestBet pick : picks) {
            assertThat(mentionsAurora(pick.detail()))
                    .as("Pick #%d detail should not mention aurora when no aurora is active",
                            pick.rank())
                    .isFalse();
            assertThat(mentionsAurora(pick.headline()))
                    .as("Pick #%d headline should not mention aurora when no aurora is active",
                            pick.rank())
                    .isFalse();
        }
    }

    // ── Helpers ──

    private String callHaiku(String rollupJson) {
        Message response = apiClient.createMessage(
                MessageCreateParams.builder()
                        .model(EvaluationModel.HAIKU.getModelId())
                        .maxTokens(1024)
                        .systemOfTextBlockParams(List.of(
                                TextBlockParam.builder().text(systemPrompt).build()))
                        .addUserMessage(rollupJson)
                        .build());

        return response.content().stream()
                .filter(ContentBlock::isText)
                .map(ContentBlock::asText)
                .map(TextBlock::text)
                .findFirst()
                .orElse("");
    }

    private static boolean mentionsAurora(String text) {
        if (text == null) {
            return false;
        }
        String lower = text.toLowerCase();
        return lower.contains("aurora") || lower.contains("northern lights");
    }

    private static BriefingSlot slot(String name, Verdict verdict, LocalDateTime time) {
        return new BriefingSlot(name, time, verdict,
                new BriefingSlot.WeatherConditions(10, BigDecimal.ZERO, 20000, 65,
                        12.0, 10.0, null, new BigDecimal("3.0")),
                BriefingSlot.TideInfo.NONE, List.of(), null);
    }
}
