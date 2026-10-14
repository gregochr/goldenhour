package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.client.AnthropicClient;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.integration.WireMockAnthropicClientTestConfiguration;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.service.evaluation.PromptReplayBatch.Plan;
import com.gregochr.goldenhour.service.evaluation.PromptReplayBatch.Request;
import com.gregochr.goldenhour.service.evaluation.PromptReplayBatch.Runner;
import com.gregochr.goldenhour.service.evaluation.PromptReplayBatch.Settings;
import com.gregochr.goldenhour.service.evaluation.PromptReplayBatch.State;
import com.gregochr.goldenhour.service.evaluation.PromptReplayBatch.SystemPrompts;
import com.gregochr.goldenhour.service.evaluation.PromptReplayHarnessTest.Run;
import com.gregochr.goldenhour.service.evaluation.PromptReplayHarnessTest.StoredPrompt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the Batch API mode of {@link PromptReplayHarnessTest} against a WireMock Anthropic: what is
 * submitted, in what order, what is refused, what is resumed, and how results become rows. It makes
 * no real call and needs no API key, so it runs in the ordinary suite.
 */
@Timeout(60)
class PromptReplayBatchTest {

    private static final String BATCHES = "/v1/messages/batches";
    private static final EvaluationModel MODEL = EvaluationModel.SONNET_55;
    private static final String REPLY =
            "{\"rating\": 4, \"fiery_sky\": 70, \"golden_hour\": 75, \"summary\": \"Promising.\"}";
    private static final ObjectMapper JSON = new ObjectMapper();

    @RegisterExtension
    static final WireMockExtension WIRE_MOCK = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @TempDir
    Path outDir;

    private AnthropicClient client;
    private final PromptBuilder inland = new PromptBuilder();
    private final PromptBuilder coastal = new CoastalPromptBuilder();
    private final List<StoredPrompt> prompts = List.of(
            new StoredPrompt("101110", false, "INLAND USER MESSAGE\nline two"),
            new StoredPrompt("low.hauxley 1004", true, "COASTAL USER MESSAGE"));
    private ByteArrayOutputStream console;
    private PrintStream out;

    @BeforeEach
    void setUp() {
        WIRE_MOCK.resetAll();
        client = new WireMockAnthropicClientTestConfiguration()
                .wireMockAnthropicClient("http://localhost:" + WIRE_MOCK.getPort());
        console = new ByteArrayOutputStream();
        out = new PrintStream(console, true, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------------------------------
    // Plan and gate
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("custom ids encode prompt, model and run, are unique, and obey Anthropic's charset and length")
    void customIds_areValid() {
        List<StoredPrompt> awkward = List.of(
                new StoredPrompt("a.b c/d", false, "x"),
                new StoredPrompt("a.b c/d", true, "y"),
                new StoredPrompt("x".repeat(200), false, "z"));

        List<Request> requests = PromptReplayBatch.plan(awkward, List.of(MODEL, EvaluationModel.SONNET), 2);

        assertThat(requests).hasSize(12);
        assertThat(requests).extracting(Request::customId).doesNotHaveDuplicates()
                .allSatisfy(id -> assertThat(id).matches("[A-Za-z0-9_-]{1,64}"));
        assertThat(requests.get(0).customId()).isEqualTo("p0-SONNET_55-r1-a_b_c_d");
        assertThat(requests.get(1).customId()).isEqualTo("p0-SONNET_55-r2-a_b_c_d");
        assertThat(requests.get(2).customId()).startsWith("p0-SONNET-r1-");
    }

    @Test
    @DisplayName("nothing is submitted, and no key or network is needed, unless the cost is confirmed")
    void preflight_refusesWithoutTheRightConfirmation() {
        List<Request> requests = PromptReplayBatch.plan(prompts, List.of(MODEL), 2);

        assertThatThrownBy(() -> preflight(settings(true, false, null), requests))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REPLAY_CONFIRM_COST=6");
        assertThatThrownBy(() -> preflight(settings(true, false, "4"), requests))
                .hasMessageContaining("REPLAY_CONFIRM_COST=6");

        Plan plan = preflight(settings(true, false, "6"), requests);

        assertThat(plan.requests()).hasSize(4);
        assertThat(console.toString(StandardCharsets.UTF_8))
                .contains("TOTAL REQUESTS TO SUBMIT: 6 (primers included)")
                .contains("ESTIMATED COST at batch prices")
                .contains("SONNET_55");
        WIRE_MOCK.verify(0, postRequestedFor(urlPathEqualTo(BATCHES)));
    }

    @Test
    @DisplayName("with the primer skipped the confirmation is the bare request count")
    void preflight_countsNoPrimersWhenSkipped() {
        List<Request> requests = PromptReplayBatch.plan(prompts, List.of(MODEL), 2);

        Plan plan = preflight(settings(false, false, "4"), requests);

        assertThat(plan.requests()).hasSize(4);
    }

    // ------------------------------------------------------------------------------------------
    // Submission
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("primes each prefix, then submits one batch whose bodies match the direct path, and writes rows")
    void run_primesSubmitsAndCollects() throws Exception {
        List<Request> requests = PromptReplayBatch.plan(prompts, List.of(MODEL), 2);
        stubCreates("msgbatch_primer_inland", "msgbatch_primer_coastal", "msgbatch_real");
        stubBatch("msgbatch_primer_inland", "ended", 1, 0);
        stubBatch("msgbatch_primer_coastal", "ended", 1, 0);
        stubBatch("msgbatch_real", "ended", 2, 2);
        stubResults("msgbatch_real",
                succeeded(requests.get(0), REPLY, "end_turn", usage(100, 40, 3000, 0, 0)),
                succeeded(requests.get(1), REPLY, "refusal", usage(100, 5, 0, 0, 0)),
                errored(requests.get(2)),
                succeeded(requests.get(3), "not json at all", "end_turn", usage(100, 10, 0, 3000, 0)));
        Plan plan = preflight(settings(true, false, "6"), requests);

        List<Run> rows = runner(settings(true, false, "6")).run(plan);

        // order: primer inland, primer coastal, then the real batch
        List<LoggedRequest> creates = WIRE_MOCK.findAll(postRequestedFor(urlPathEqualTo(BATCHES)));
        assertThat(creates).hasSize(3);
        JsonNode primerInland = JSON.readTree(creates.get(0).getBodyAsString()).path("requests");
        JsonNode primerCoastal = JSON.readTree(creates.get(1).getBodyAsString()).path("requests");
        JsonNode real = JSON.readTree(creates.get(2).getBodyAsString()).path("requests");
        assertThat(primerInland).hasSize(1);
        assertThat(primerCoastal).hasSize(1);
        assertThat(real).hasSize(4);
        assertThat(primerInland.get(0).path("custom_id").asString()).startsWith("pw-");
        assertThat(primerInland.get(0).path("params").path("system").get(0).path("text").asString())
                .isEqualTo(inland.getSystemPrompt());
        assertThat(primerCoastal.get(0).path("params").path("system").get(0).path("text").asString())
                .isEqualTo(coastal.getSystemPrompt());
        for (JsonNode request : real) {
            JsonNode system = request.path("params").path("system").get(0);
            assertThat(system.path("cache_control").path("ttl").asString()).isEqualTo("1h");
            assertThat(request.path("params").path("model").asString()).isEqualTo(MODEL.getModelId());
            assertThat(request.path("params").path("max_tokens").asInt()).isEqualTo(MODEL.getMaxTokens());
        }
        assertThat(real.get(0).path("custom_id").asString()).isEqualTo(requests.get(0).customId());
        assertThat(real.get(0).path("params").path("messages").get(0).path("content").asString())
                .isEqualTo("INLAND USER MESSAGE\nline two");
        assertThat(real.get(2).path("params").path("system").get(0).path("text").asString())
                .isEqualTo(coastal.getSystemPrompt());

        // the rows: a parsed success, a refusal that is never parsed, an errored request, unparseable text
        assertThat(rows).hasSize(4);
        assertThat(rows.get(0).rating()).isEqualTo(4);
        assertThat(rows.get(0).fiery()).isEqualTo(70);
        assertThat(rows.get(0).mode()).isEqualTo("batch");
        assertThat(rows.get(0).batchId()).isEqualTo("msgbatch_real");
        assertThat(rows.get(0).usage().cacheCreationInputTokens()).isEqualTo(3000);
        assertThat(rows.get(1).rating()).isNull();
        assertThat(rows.get(1).failure()).contains("refus");
        assertThat(rows.get(2).failure()).startsWith("BatchErrored");
        assertThat(rows.get(3).rating()).isNull();
        assertThat(rows.get(3).failure()).isNotBlank();
        assertThat(rows.get(3).usage().cacheReadInputTokens()).isEqualTo(3000);

        State state = PromptReplayBatch.readState(outDir, JSON);
        assertThat(state.collected()).isTrue();
        assertThat(state.models()).singleElement().satisfies(ms -> {
            assertThat(ms.batchId()).isEqualTo("msgbatch_real");
            assertThat(ms.primers()).containsOnlyKeys("inland", "coastal");
            assertThat(ms.warmed()).containsExactlyInAnyOrder("inland", "coastal");
        });
    }

    @Test
    @DisplayName("the batch request carries the same model, limits, thinking, output config, system and message "
            + "as the direct one (SONNET_55: adaptive thinking)")
    void batchBody_matchesTheDirectBody_sonnet55() throws Exception {
        assertBodyMatchesDirect(EvaluationModel.SONNET_55);
    }

    @Test
    @DisplayName("... and for HAIKU_55, where thinking is sent disabled and compared, not merely absent on both sides")
    void batchBody_matchesTheDirectBody_haiku55() throws Exception {
        assertBodyMatchesDirect(EvaluationModel.HAIKU_55);
    }

    private void assertBodyMatchesDirect(EvaluationModel model) throws Exception {
        List<Request> requests = PromptReplayBatch.plan(prompts.subList(0, 1), List.of(model), 1);
        stubCreates("msgbatch_real");
        stubBatch("msgbatch_real", "ended", 1, 0);
        stubResults("msgbatch_real", succeeded(requests.get(0), REPLY, "end_turn", usage(1, 1, 0, 0, 0)));
        Settings noPrimer = settings(false, false, "1");

        new Runner(client, systemPrompts(), noPrimer, outDir, JSON, new SunsetEvaluationParser(),
                "production", Clock.systemUTC(), d -> { }, out)
                .run(PromptReplayBatch.preflight(noPrimer, outDir, requests, List.of(model),
                        systemPrompts(), "production", JSON, out));

        JsonNode sent = JSON.readTree(WIRE_MOCK.findAll(postRequestedFor(urlPathEqualTo(BATCHES)))
                .get(0).getBodyAsString()).path("requests").get(0).path("params");
        // exactly what PromptReplayHarnessTest#call builds for the same stored prompt
        var direct = ModelRequestSupport.withThinkingSetting(
                        com.anthropic.models.messages.MessageCreateParams.builder()
                                .model(model.getModelId())
                                .maxTokens(model.getMaxTokens())
                                .systemOfTextBlockParams(List.of(
                                        com.anthropic.models.messages.TextBlockParam.builder()
                                                .text(inland.getSystemPrompt())
                                                .cacheControl(com.anthropic.models.messages
                                                        .CacheControlEphemeral.builder().build())
                                                .build()))
                                .outputConfig(ModelRequestSupport.withEffort(
                                        inland.buildOutputConfig(), model))
                                .addUserMessage(prompts.get(0).userMessage()), model)
                .build();
        JsonNode expected = JSON.readTree(com.anthropic.core.ObjectMappers.jsonMapper()
                .writeValueAsString(direct._body()));
        for (String field : List.of("model", "max_tokens", "system", "messages", "output_config",
                "thinking")) {
            assertThat(sent.has(field)).as("has " + field).isEqualTo(expected.has(field));
            assertThat(sent.path(field)).as(field).isEqualTo(expected.path(field));
        }
        if (model == EvaluationModel.HAIKU_55) {
            assertThat(sent.path("thinking").path("type").asString()).isEqualTo("disabled");
        }
        // no primer: the system block is the direct path's own 5-minute cache control, no ttl
        assertThat(sent.path("system").get(0).path("cache_control").has("ttl")).isFalse();
    }

    @Test
    @DisplayName("a primer that fails to submit never stops the run; its requests go without the 1-hour ttl")
    void run_failsOpenOnPrimerFailure() throws Exception {
        List<Request> requests = PromptReplayBatch.plan(prompts.subList(0, 1), List.of(MODEL), 1);
        WIRE_MOCK.stubFor(post(urlPathEqualTo(BATCHES)).inScenario("create")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(500).withBody("{\"type\":\"error\",\"error\":"
                        + "{\"type\":\"api_error\",\"message\":\"boom\"}}"))
                .willSetStateTo("second"));
        WIRE_MOCK.stubFor(post(urlPathEqualTo(BATCHES)).inScenario("create")
                .whenScenarioStateIs("second")
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody(batchJson("msgbatch_real", "in_progress", 0, 0))));
        stubBatch("msgbatch_real", "ended", 1, 0);
        stubResults("msgbatch_real", succeeded(requests.get(0), REPLY, "end_turn", usage(1, 1, 0, 0, 0)));
        Settings settings = settings(true, false, "2");

        List<Run> rows = runner(settings).run(preflight(settings, requests));

        assertThat(rows).singleElement().satisfies(r -> assertThat(r.rating()).isEqualTo(4));
        List<LoggedRequest> creates = WIRE_MOCK.findAll(postRequestedFor(urlPathEqualTo(BATCHES)));
        assertThat(creates).hasSize(2);
        JsonNode system = JSON.readTree(creates.get(1).getBodyAsString()).path("requests").get(0)
                .path("params").path("system").get(0);
        assertThat(system.path("cache_control").has("ttl")).isFalse();
        assertThat(console.toString(StandardCharsets.UTF_8)).contains("could not be submitted");
    }

    @Test
    @DisplayName("a primer that never ends within the wait is skipped, and the clock bounds the wait")
    void run_skipsAPrimerThatDoesNotEnd() throws Exception {
        List<Request> requests = PromptReplayBatch.plan(prompts.subList(0, 1), List.of(MODEL), 1);
        stubCreates("msgbatch_primer", "msgbatch_real");
        stubBatch("msgbatch_primer", "in_progress", 0, 0);
        stubBatch("msgbatch_real", "ended", 1, 0);
        stubResults("msgbatch_real", succeeded(requests.get(0), REPLY, "end_turn", usage(1, 1, 0, 0, 0)));
        Settings settings = settings(true, false, "2");
        TickingClock clock = new TickingClock();

        List<Run> rows = new Runner(client, systemPrompts(), settings, outDir, JSON,
                new SunsetEvaluationParser(), "production", clock, clock::advance, out)
                .run(preflight(settings, requests));

        assertThat(rows.get(0).rating()).isEqualTo(4);
        assertThat(clock.slept).isNotEmpty();
        JsonNode real = JSON.readTree(WIRE_MOCK.findAll(postRequestedFor(urlPathEqualTo(BATCHES)))
                .get(1).getBodyAsString()).path("requests").get(0);
        assertThat(real.path("params").path("system").get(0).path("cache_control").has("ttl")).isFalse();
        assertThat(PromptReplayBatch.readState(outDir, JSON).models().get(0).warmed()).isEmpty();
    }

    @Test
    @DisplayName("expired and canceled results are failure rows, and an unknown custom_id is ignored")
    void results_expiredCanceledAndUnknown() throws Exception {
        List<Request> requests = PromptReplayBatch.plan(prompts, List.of(MODEL), 2);
        stubCreates("msgbatch_real");
        stubBatch("msgbatch_real", "ended", 1, 0);
        Request stranger = new Request("not-in-the-plan", "x", MODEL, 1, false, "x");
        stubResults("msgbatch_real",
                succeeded(requests.get(0), REPLY, "end_turn", usage(1, 1, 0, 0, 0)),
                plain(requests.get(1), "expired"),
                plain(requests.get(2), "canceled"),
                succeeded(stranger, REPLY, "end_turn", usage(1, 1, 0, 0, 0)));
        Settings settings = settings(false, false, "4");

        List<Run> rows = runner(settings).run(preflight(settings, requests));

        assertThat(rows).hasSize(4);
        assertThat(rows.get(0).rating()).isEqualTo(4);
        assertThat(rows.get(1).failure()).isEqualTo("BatchExpired");
        assertThat(rows.get(2).failure()).isEqualTo("BatchCanceled");
        assertThat(rows.get(3).failure()).startsWith("NoResult");
        assertThat(console.toString(StandardCharsets.UTF_8)).contains("unknown custom_id not-in-the-plan");
    }

    @Test
    @DisplayName("a batch whose results can never be read is given up on, not waited for until the deadline")
    void unreadableResults_areAbandonedAfterTenReads() throws Exception {
        List<Request> requests = PromptReplayBatch.plan(prompts.subList(0, 1), List.of(MODEL), 1);
        stubCreates("msgbatch_real");
        stubBatch("msgbatch_real", "ended", 1, 0);
        WIRE_MOCK.stubFor(get(urlPathEqualTo(BATCHES + "/msgbatch_real/results"))
                .willReturn(aResponse().withStatus(404)));
        Settings settings = settings(false, false, "1");

        List<Run> rows = runner(settings).run(preflight(settings, requests));

        assertThat(rows).singleElement().satisfies(r -> assertThat(r.failure())
                .startsWith("BatchNotCollected"));
        assertThat(PromptReplayBatch.readState(outDir, JSON).collected()).isFalse();
        assertThat(console.toString(StandardCharsets.UTF_8)).contains("Giving up on batch msgbatch_real");
    }

    // ------------------------------------------------------------------------------------------
    // Resume, deadline, state guard
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a deadline leaves failure rows and an uncollected state; resume re-polls and submits nothing")
    void deadline_thenResume() throws Exception {
        List<Request> requests = PromptReplayBatch.plan(prompts.subList(0, 1), List.of(MODEL), 1);
        stubCreates("msgbatch_real");
        stubBatch("msgbatch_real", "in_progress", 0, 0);
        Settings first = settings(false, false, "1");
        TickingClock clock = new TickingClock();

        List<Run> rows = new Runner(client, systemPrompts(), first, outDir, JSON,
                new SunsetEvaluationParser(), "production", clock, clock::advance, out)
                .run(preflight(first, requests));

        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.rating()).isNull();
            assertThat(r.failure()).startsWith("BatchNotCollected");
        });
        assertThat(PromptReplayBatch.readState(outDir, JSON).collected()).isFalse();
        WIRE_MOCK.verify(1, postRequestedFor(urlPathEqualTo(BATCHES)));

        // a fresh (non-resume) start now would overwrite uncollected ids: refused
        assertThatThrownBy(() -> preflight(settings(false, false, "1"), requests))
                .hasMessageContaining("never collected")
                .hasMessageContaining("REPLAY_BATCH_RESUME=true");

        // resume: no confirmation needed (nothing to submit), no POST, the batch has now ended
        WIRE_MOCK.resetAll();
        stubBatch("msgbatch_real", "ended", 1, 0);
        stubResults("msgbatch_real", succeeded(requests.get(0), REPLY, "end_turn", usage(1, 1, 0, 0, 0)));
        Settings resume = settings(false, true, null);

        List<Run> resumed = runner(resume).run(preflight(resume, requests));

        assertThat(resumed).singleElement().satisfies(r -> assertThat(r.rating()).isEqualTo(4));
        WIRE_MOCK.verify(0, postRequestedFor(urlPathEqualTo(BATCHES)));
        assertThat(PromptReplayBatch.readState(outDir, JSON).collected()).isTrue();
    }

    @Test
    @DisplayName("resume refuses a plan that differs from the stored one, and a missing state file")
    void resume_refusesAChangedPlan() throws Exception {
        List<Request> requests = PromptReplayBatch.plan(prompts.subList(0, 1), List.of(MODEL), 1);
        assertThatThrownBy(() -> preflight(settings(false, true, null), requests))
                .hasMessageContaining("does not exist");
        stubCreates("msgbatch_real");
        stubBatch("msgbatch_real", "ended", 1, 0);
        stubResults("msgbatch_real", succeeded(requests.get(0), REPLY, "end_turn", usage(1, 1, 0, 0, 0)));
        Settings first = settings(false, false, "1");
        runner(first).run(preflight(first, requests));

        List<Request> changed = PromptReplayBatch.plan(prompts.subList(0, 1), List.of(MODEL), 2);

        assertThatThrownBy(() -> preflight(settings(false, true, null), changed))
                .hasMessageContaining("differs");
    }

    // ------------------------------------------------------------------------------------------
    // Outputs
    // ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("runs.csv keeps the direct path's leading columns and adds mode, batch_id and tokens")
    void csv_keepsTheLeadingColumns() throws IOException {
        List<Run> rows = List.of(
                new Run("101110", MODEL, 1, 4, 70, 75, null, "Promising.", "production", "batch",
                        "msgbatch_x", new TokenUsage(10, 20, 30, 40, 30)),
                new Run("101110", MODEL, 2, null, null, null, "BatchErrored: boom", null, "production",
                        "direct", null, TokenUsage.EMPTY));

        PromptReplayHarnessTest.write(outDir, rows, prompts.subList(0, 1), List.of(MODEL), 2, "production");

        List<String> lines = Files.readAllLines(outDir.resolve("runs.csv"));
        assertThat(lines.get(0)).isEqualTo("label,model,attempt,rating,fiery,golden,failure,summary,"
                + "system_prompt,mode,batch_id,input_tokens,output_tokens,cache_read_tokens,"
                + "cache_write_tokens,cache_write_1h_tokens");
        assertThat(lines.get(1)).isEqualTo("101110,SONNET_55,1,4,70,75,,\"Promising.\",\"production\","
                + "batch,msgbatch_x,10,20,40,30,30");
        assertThat(lines.get(2)).contains("direct,,0,0,0,0,0");
        String summary = Files.readString(outDir.resolve("summary.md"));
        assertThat(summary).contains("Mode: batch (ids: [msgbatch_x])").contains("Tokens:");
    }

    @Test
    @DisplayName("batch prices are half of standard; cache reads a tenth, 1-hour writes 2x, 5-minute writes 1.25x")
    void cost_isHalfOfStandard() {
        TokenUsage usage = new TokenUsage(1_000_000, 1_000_000, 2_000_000, 1_000_000, 1_000_000);

        double standard = PromptReplayBatch.cost(MODEL, usage, false);
        double batch = PromptReplayBatch.cost(MODEL, usage, true);

        // sonnet55: 2.00 in, 10.00 out; 1h write 4.00 (1M), 5m write 2.50 (the other 1M), read 0.20
        assertThat(standard).isEqualTo(2.00 + 10.00 + 4.00 + 2.50 + 0.20, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(batch).isEqualTo(standard / 2, org.assertj.core.data.Offset.offset(1e-9));
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private SystemPrompts systemPrompts() {
        return new SystemPrompts(inland.getSystemPrompt(), coastal.getSystemPrompt(), inland, coastal);
    }

    private static Settings settings(boolean primer, boolean resume, String confirm) {
        return new Settings(primer, Duration.ofSeconds(primer ? 180 : 0), Duration.ofSeconds(30),
                Duration.ofMinutes(60), resume, confirm);
    }

    private Plan preflight(Settings settings, List<Request> requests) {
        return PromptReplayBatch.preflight(settings, outDir, requests, List.of(MODEL), systemPrompts(),
                "production", JSON, out);
    }

    private Runner runner(Settings settings) {
        return new Runner(client, systemPrompts(), settings, outDir, JSON, new SunsetEvaluationParser(),
                "production", Clock.systemUTC(), d -> { }, out);
    }

    private void stubCreates(String... ids) {
        String scenario = "create-" + ids.length;
        String state = STARTED;
        for (int i = 0; i < ids.length; i++) {
            String next = "created-" + (i + 1);
            WIRE_MOCK.stubFor(post(urlPathEqualTo(BATCHES)).inScenario(scenario)
                    .whenScenarioStateIs(state)
                    .willReturn(aResponse().withHeader("Content-Type", "application/json")
                            .withBody(batchJson(ids[i], "in_progress", 0, 0)))
                    .willSetStateTo(next));
            state = next;
        }
    }

    private void stubBatch(String id, String status, int succeeded, int errored) {
        WIRE_MOCK.stubFor(get(urlPathEqualTo(BATCHES + "/" + id))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody(batchJson(id, status, succeeded, errored))));
    }

    private void stubResults(String id, String... lines) {
        WIRE_MOCK.stubFor(get(urlPathEqualTo(BATCHES + "/" + id + "/results"))
                .willReturn(aResponse().withHeader("Content-Type", "application/x-jsonl")
                        .withBody(String.join("\n", lines) + "\n")));
    }

    private static String batchJson(String id, String status, int succeeded, int errored) {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("processing", "in_progress".equals(status) ? 1 : 0);
        counts.put("succeeded", succeeded);
        counts.put("errored", errored);
        counts.put("canceled", 0);
        counts.put("expired", 0);
        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("id", id);
        batch.put("type", "message_batch");
        batch.put("processing_status", status);
        batch.put("request_counts", counts);
        batch.put("created_at", "2026-10-14T10:00:00Z");
        batch.put("expires_at", "2026-10-15T10:00:00Z");
        batch.put("ended_at", "ended".equals(status) ? "2026-10-14T10:30:00Z" : null);
        batch.put("archived_at", null);
        batch.put("cancel_initiated_at", null);
        batch.put("results_url", "ended".equals(status) ? "http://localhost/results" : null);
        return JSON.writeValueAsString(batch);
    }

    private static Map<String, Object> usage(int in, int out, int write, int read, int write1h) {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("input_tokens", in);
        usage.put("output_tokens", out);
        usage.put("cache_creation_input_tokens", write);
        usage.put("cache_read_input_tokens", read);
        Map<String, Object> creation = new LinkedHashMap<>();
        creation.put("ephemeral_1h_input_tokens", write1h);
        creation.put("ephemeral_5m_input_tokens", write - write1h);
        usage.put("cache_creation", creation);
        return usage;
    }

    private static String succeeded(Request r, String text, String stopReason, Map<String, Object> usage) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", "msg_" + r.customId());
        message.put("type", "message");
        message.put("role", "assistant");
        message.put("model", r.model().getModelId());
        message.put("content", List.of(block));
        message.put("stop_reason", stopReason);
        message.put("stop_sequence", null);
        message.put("usage", usage);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "succeeded");
        result.put("message", message);
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("custom_id", r.customId());
        line.put("result", result);
        return JSON.writeValueAsString(line);
    }

    private static String plain(Request r, String type) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type);
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("custom_id", r.customId());
        line.put("result", result);
        return JSON.writeValueAsString(line);
    }

    private static String errored(Request r) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("type", "api_error");
        error.put("message", "boom");
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("type", "error");
        inner.put("error", error);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "errored");
        result.put("error", inner);
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("custom_id", r.customId());
        line.put("result", result);
        return JSON.writeValueAsString(line);
    }

    /** A clock the {@link PromptReplayBatch.Sleeper} advances, so a deadline test does not wait. */
    private static final class TickingClock extends Clock {

        private Instant now = Instant.parse("2026-10-14T10:00:00Z");
        private final List<Duration> slept = new ArrayList<>();

        void advance(Duration duration) {
            slept.add(duration);
            now = now.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
