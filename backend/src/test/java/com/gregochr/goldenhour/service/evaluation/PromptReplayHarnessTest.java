package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.TextBlockParam;
import com.gregochr.goldenhour.config.AppConfig;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

/**
 * Replays stored production sky prompts against several models, N times each, and tabulates the
 * ratings. It is the P0 step of {@code docs/engineering/far-cloud-lid-plan.md}: it asks whether a
 * forecast's rating came from its inputs, from run-to-run sampling noise, or from the model.
 *
 * <p><b>An experiment, not a test.</b> It asserts nothing about Claude's answers (the
 * prompt-regression assertions are the owner's to write) and is tagged {@code prompt-regression},
 * so CI never runs it. It needs a real {@code ANTHROPIC_API_KEY} and spends real money: roughly
 * prompts × models × N calls, at standard (not batch) prices with the system prompt cached.
 *
 * <p><b>What it sends is what production sends</b>, minus the batch wrapper: the same system prompt
 * (inland {@link PromptBuilder} or {@link CoastalPromptBuilder}), the same output schema, the same
 * per-model thinking and effort settings ({@link ModelRequestSupport}) and the same
 * {@link EvaluationModel#getMaxTokens()}, built exactly as {@code ClaudeEvaluationStrategy}
 * builds its request, with the stored user message verbatim. The reply goes through the
 * production {@link SunsetEvaluationParser}, and the stop-reason check is the strategy's own
 * {@code checkStopReason}, so a refusal or truncation is recorded as a failure, never parsed.
 *
 * <p><b>Inputs</b> (environment variables or system properties):
 * <ul>
 *   <li>{@code REPLAY_PROMPTS_DIR} (required): a directory of {@code <label>.inland.txt} and
 *       {@code <label>.coastal.txt} files, each one stored user message
 *       ({@code forecast_evaluation_prompt.user_message}); the suffix picks the system prompt</li>
 *   <li>{@code REPLAY_MODELS}: comma-separated {@link EvaluationModel} names, default
 *       {@code SONNET_55,SONNET,HAIKU}</li>
 *   <li>{@code REPLAY_N}: runs per prompt and model, default 20</li>
 *   <li>{@code REPLAY_THREADS}: concurrent calls, default 4</li>
 *   <li>{@code REPLAY_SYSTEM_PROMPT_FILE} (optional): a text file whose contents REPLACE the system
 *       prompt of every request (Part D's R1 variant, {@code docs/engineering/far-cloud-p1-plan.md}).
 *       Unset, the production prompt is sent byte-for-byte as before. The file must be the inland
 *       prompt (one already ending with the coastal suffix is rejected); a coastal request gets
 *       {@link CoastalPromptBuilder}'s suffix appended, so coastal and inland differ exactly as in
 *       production ({@link #resolveSystemPrompt}, {@link #systemPromptFor}). The prompt used is recorded in
 *       the {@code system_prompt} column of {@code runs.csv} and in {@code summary.md}.</li>
 *   <li>{@code REPLAY_DUMP_SYSTEM_PROMPTS_DIR} (optional, used by {@link #dumpSystemPrompts}, no API
 *       key needed): writes the production {@code system-inland.txt} and {@code system-coastal.txt}
 *       so a variant file can be built from them</li>
 * </ul>
 *
 * <p>Prompt files may be named with an evaluation id ({@code 101110.inland.txt}): the label is
 * everything before the {@code .inland.txt} / {@code .coastal.txt} suffix, so it is just a string.
 *
 * <p><b>Output</b>: {@code target/p0-replay/runs.csv} (one row per call) and
 * {@code target/p0-replay/summary.md} (rating distribution, mean scores and failures per prompt and
 * model). Run it with
 * {@code ./mvnw test -Pprompt-regression -Dtest=PromptReplayHarnessTest}.
 */
@Tag("prompt-regression")
class PromptReplayHarnessTest {

    private static final Path OUT_DIR = Path.of("target", "p0-replay");

    @Test
    void replayStoredPrompts() throws Exception {
        String apiKey = setting("ANTHROPIC_API_KEY", null);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("ANTHROPIC_API_KEY must be set");
        }
        String dir = setting("REPLAY_PROMPTS_DIR", null);
        if (dir == null || dir.isBlank()) {
            throw new IllegalStateException("REPLAY_PROMPTS_DIR must name a directory of prompts");
        }
        List<EvaluationModel> models = Stream.of(setting("REPLAY_MODELS", "SONNET_55,SONNET,HAIKU")
                        .split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> EvaluationModel.valueOf(s.toUpperCase(Locale.ROOT)))
                .toList();
        int n = Integer.parseInt(setting("REPLAY_N", "20"));
        int threads = Integer.parseInt(setting("REPLAY_THREADS", "4"));
        String overrideSetting = setting("REPLAY_SYSTEM_PROMPT_FILE", null);
        Path overrideFile = overrideSetting == null || overrideSetting.isBlank()
                ? null : Path.of(overrideSetting);
        String systemPromptId = describeSystemPrompt(overrideFile);

        List<StoredPrompt> prompts = loadPrompts(Path.of(dir));
        if (prompts.isEmpty()) {
            throw new IllegalStateException("No *.inland.txt or *.coastal.txt files in " + dir);
        }

        AnthropicClient client = AppConfig.anthropicClientBuilder(apiKey).build();
        ObjectMapper mapper = new ObjectMapper();
        SunsetEvaluationParser parser = new SunsetEvaluationParser();
        PromptBuilder inland = new PromptBuilder();
        PromptBuilder coastal = new CoastalPromptBuilder();

        List<Future<Run>> futures = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (StoredPrompt prompt : prompts) {
                PromptBuilder builder = prompt.coastal() ? coastal : inland;
                for (EvaluationModel model : models) {
                    for (int i = 1; i <= n; i++) {
                        int attempt = i;
                        futures.add(pool.submit(() -> call(client, builder, model, prompt,
                                attempt, parser, mapper, inland.getSystemPrompt(),
                                coastal.getSystemPrompt(), overrideFile, systemPromptId)));
                    }
                }
            }
            List<Run> runs = new ArrayList<>();
            for (Future<Run> f : futures) {
                runs.add(f.get());
            }
            write(runs, prompts, models, n, systemPromptId);
        } finally {
            pool.shutdownNow();
        }
    }

    /** One stored user message and which system prompt it was sent with. */
    record StoredPrompt(String label, boolean coastal, String userMessage) {
    }

    /** One call's outcome: a parsed evaluation, or the reason there is none. */
    record Run(String label, EvaluationModel model, int attempt, Integer rating, Integer fiery,
            Integer golden, String failure, String summary, String systemPrompt) {
    }

    private static List<StoredPrompt> loadPrompts(Path dir) throws IOException {
        List<StoredPrompt> prompts = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                boolean coastal = name.endsWith(".coastal.txt");
                if (!coastal && !name.endsWith(".inland.txt")) {
                    continue;
                }
                String label = name.substring(0, name.length()
                        - (coastal ? ".coastal.txt" : ".inland.txt").length());
                prompts.add(new StoredPrompt(label, coastal,
                        Files.readString(file, StandardCharsets.UTF_8)));
            }
        }
        return prompts;
    }

    /** Sends one request, built exactly as {@code ClaudeEvaluationStrategy#invokeClaude} builds it. */
    private static Run call(AnthropicClient client, PromptBuilder builder, EvaluationModel model,
            StoredPrompt prompt, int attempt, SunsetEvaluationParser parser, ObjectMapper mapper,
            String inlandPrompt, String coastalPrompt, Path overrideFile, String systemPromptId) {
        try {
            String systemPrompt = systemPromptFor(prompt, inlandPrompt, coastalPrompt, overrideFile);
            Message response = client.messages().create(
                    ModelRequestSupport.withThinkingSetting(MessageCreateParams.builder()
                            .model(model.getModelId())
                            .maxTokens(model.getMaxTokens())
                            .systemOfTextBlockParams(List.of(
                                    TextBlockParam.builder()
                                            .text(systemPrompt)
                                            .cacheControl(CacheControlEphemeral.builder().build())
                                            .build()))
                            .outputConfig(ModelRequestSupport.withEffort(
                                    builder.buildOutputConfig(), model))
                            .addUserMessage(prompt.userMessage()), model)
                            .build());
            ClaudeEvaluationStrategy.checkStopReason(response);
            String text = response.content().stream()
                    .filter(ContentBlock::isText)
                    .map(ContentBlock::asText)
                    .map(TextBlock::text)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("no text content"));
            SunsetEvaluation e = parser.parseEvaluation(text, mapper);
            return new Run(prompt.label(), model, attempt, e.rating(), e.fierySkyPotential(),
                    e.goldenHourPotential(), null, e.summary(), systemPromptId);
        } catch (RuntimeException ex) {
            return new Run(prompt.label(), model, attempt, null, null, null,
                    ex.getClass().getSimpleName() + ": " + ex.getMessage(), null, systemPromptId);
        }
    }

    /**
     * The system prompt one stored prompt's request is sent with: the production inland or coastal
     * prompt by the stored prompt's suffix, or the override file's contents (plus the coastal suffix
     * for a coastal prompt) when an override is set.
     *
     * @param prompt        the stored prompt (its suffix says coastal or inland)
     * @param inlandPrompt  {@code PromptBuilder#getSystemPrompt()}
     * @param coastalPrompt {@code CoastalPromptBuilder#getSystemPrompt()}
     * @param overrideFile  the override file (the INLAND prompt), or null
     * @return the system prompt text
     */
    static String systemPromptFor(StoredPrompt prompt, String inlandPrompt, String coastalPrompt,
            Path overrideFile) {
        return prompt.coastal()
                ? resolveSystemPrompt(coastalPrompt, CoastalPromptBuilder.COASTAL_SYSTEM_PROMPT_SUFFIX,
                        overrideFile)
                : resolveSystemPrompt(inlandPrompt, null, overrideFile);
    }

    /**
     * The system prompt a request is sent with: {@code productionPrompt} untouched when
     * {@code overrideFile} is null, otherwise the file's contents (UTF-8, verbatim). For a coastal
     * request ({@code coastalSuffix} non-null) the suffix is appended to the file's contents. The file
     * must be the INLAND prompt: one that already ends with the coastal suffix is rejected, because
     * it would silently send the coastal guidance to inland prompts too.
     *
     * @param productionPrompt what the prompt builder returns
     * @param coastalSuffix    the coastal suffix, or null for an inland request
     * @param overrideFile     the override file, or null for none
     * @return the system prompt text
     */
    static String resolveSystemPrompt(String productionPrompt, String coastalSuffix, Path overrideFile) {
        if (overrideFile == null) {
            return productionPrompt;
        }
        try {
            String content = Files.readString(overrideFile, StandardCharsets.UTF_8);
            if (content.isEmpty()) {
                throw new IllegalStateException("REPLAY_SYSTEM_PROMPT_FILE is empty: " + overrideFile);
            }
            if (content.endsWith(CoastalPromptBuilder.COASTAL_SYSTEM_PROMPT_SUFFIX)) {
                throw new IllegalStateException("REPLAY_SYSTEM_PROMPT_FILE " + overrideFile
                        + " already ends with the coastal suffix; it must be the INLAND prompt"
                        + " (system-inland.txt, patched)");
            }
            return coastalSuffix == null ? content : content + coastalSuffix;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read REPLAY_SYSTEM_PROMPT_FILE " + overrideFile, e);
        }
    }

    /**
     * Names the system prompt for the outputs: {@code production}, or the file name plus the first
     * 12 hex characters of the SHA-256 of its contents.
     *
     * @param overrideFile the override file, or null
     * @return the label recorded in {@code runs.csv} and {@code summary.md}
     */
    static String describeSystemPrompt(Path overrideFile) {
        if (overrideFile == null) {
            return "production";
        }
        try {
            byte[] bytes = Files.readAllBytes(overrideFile);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return overrideFile.getFileName() + "@sha256:" + HexFormat.of().formatHex(digest, 0, 6);
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot read REPLAY_SYSTEM_PROMPT_FILE " + overrideFile, e);
        }
    }

    /** Writes the production inland and coastal system prompts, to build an override from. */
    @Test
    void dumpSystemPrompts() throws IOException {
        String dir = setting("REPLAY_DUMP_SYSTEM_PROMPTS_DIR", null);
        if (dir == null || dir.isBlank()) {
            System.out.println("dumpSystemPrompts: REPLAY_DUMP_SYSTEM_PROMPTS_DIR is not set; WROTE NOTHING");
            return;
        }
        Files.createDirectories(Path.of(dir));
        Files.writeString(Path.of(dir, "system-inland.txt"), new PromptBuilder().getSystemPrompt(),
                StandardCharsets.UTF_8);
        Files.writeString(Path.of(dir, "system-coastal.txt"),
                new CoastalPromptBuilder().getSystemPrompt(), StandardCharsets.UTF_8);
        System.out.println("dumpSystemPrompts: wrote system-inland.txt and system-coastal.txt to " + dir);
    }

    private static void write(List<Run> runs, List<StoredPrompt> prompts,
            List<EvaluationModel> models, int n, String systemPromptId) throws IOException {
        Files.createDirectories(OUT_DIR);
        StringBuilder csv = new StringBuilder(
                "label,model,attempt,rating,fiery,golden,failure,summary,system_prompt\n");
        for (Run r : runs) {
            csv.append(String.join(",", r.label(), r.model().name(), String.valueOf(r.attempt()),
                    str(r.rating()), str(r.fiery()), str(r.golden()), quote(r.failure()),
                    quote(r.summary()), quote(r.systemPrompt()))).append('\n');
        }
        Files.writeString(OUT_DIR.resolve("runs.csv"), csv, StandardCharsets.UTF_8);

        StringBuilder md = new StringBuilder("# P0 prompt replay\n\n");
        md.append("N = ").append(n).append(" per prompt and model.\n\n");
        md.append("System prompt: ").append(systemPromptId).append("\n\n");
        md.append("| Prompt | Model | 1★ | 2★ | 3★ | 4★ | 5★ | Failed | Mean ★ | Mean fiery | Mean golden |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (StoredPrompt p : prompts) {
            for (EvaluationModel m : models) {
                List<Run> cell = runs.stream()
                        .filter(r -> r.label().equals(p.label()) && r.model() == m).toList();
                Map<Integer, Integer> stars = new TreeMap<>();
                int failed = 0;
                double sumRating = 0;
                double sumFiery = 0;
                double sumGolden = 0;
                int ok = 0;
                for (Run r : cell) {
                    if (r.rating() == null) {
                        failed++;
                        continue;
                    }
                    ok++;
                    stars.merge(r.rating(), 1, Integer::sum);
                    sumRating += r.rating();
                    sumFiery += r.fiery() == null ? 0 : r.fiery();
                    sumGolden += r.golden() == null ? 0 : r.golden();
                }
                md.append("| ").append(p.label()).append(" | ").append(m.name());
                for (int s = 1; s <= 5; s++) {
                    md.append(" | ").append(stars.getOrDefault(s, 0));
                }
                md.append(" | ").append(failed)
                        .append(" | ").append(mean(sumRating, ok))
                        .append(" | ").append(mean(sumFiery, ok))
                        .append(" | ").append(mean(sumGolden, ok)).append(" |\n");
            }
        }
        Files.writeString(OUT_DIR.resolve("summary.md"), md, StandardCharsets.UTF_8);
        System.out.println(md);
    }

    private static String setting(String key, String fallback) {
        String value = System.getProperty(key, System.getenv(key));
        return value == null ? fallback : value;
    }

    private static String str(Integer value) {
        return value == null ? "" : value.toString();
    }

    private static String mean(double sum, int count) {
        return count == 0 ? "—" : String.format(Locale.ROOT, "%.2f", sum / count);
    }

    private static String quote(String value) {
        return value == null ? "" : '"' + value.replace("\"", "\"\"").replace('\n', ' ') + '"';
    }
}
