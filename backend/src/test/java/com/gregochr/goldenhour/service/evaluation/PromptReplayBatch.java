package com.gregochr.goldenhour.service.evaluation;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.RequestOptions;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.batches.BatchCreateParams;
import com.anthropic.models.messages.batches.MessageBatch;
import com.anthropic.models.messages.batches.MessageBatchIndividualResponse;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.service.evaluation.PromptReplayHarnessTest.Run;
import com.gregochr.goldenhour.service.evaluation.PromptReplayHarnessTest.StoredPrompt;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The Message Batches mode of {@link PromptReplayHarnessTest}: the same per-request bodies the
 * direct path sends, submitted as one batch per model at half the standard price.
 *
 * <p><b>What is identical to the direct path:</b> the system prompt (production or the
 * {@code REPLAY_SYSTEM_PROMPT_FILE} override, inland or coastal), the {@link ModelRequestSupport}
 * thinking and effort settings, {@link EvaluationModel#getMaxTokens()}, the stored user message
 * verbatim, and the reply handling ({@code checkStopReason}, then {@link SunsetEvaluationParser}).
 *
 * <p><b>What is batch-specific</b>, each borrowed from production's batch pipeline
 * ({@code BatchCachePrimer}, {@code BatchRequestFactory}):
 * <ul>
 *   <li><b>Priming.</b> A batch does not reliably reuse a cache a standard call wrote, so before a
 *       model's batch one one-request primer batch per system prefix (inland, coastal) is submitted
 *       and waited for, at most {@code REPLAY_PRIMER_WAIT_SECONDS}. Only a prefix whose primer ended
 *       with a succeeded request marks its real requests' system block with the 1-hour cache
 *       {@code ttl}; otherwise the request is byte-identical to the direct path's (5-minute
 *       default). A primer failure never stops the run.</li>
 *   <li><b>Resumability.</b> Every batch id is written to {@code batches.json} the moment it is
 *       created. A run with {@code REPLAY_BATCH_RESUME=true} re-polls those ids and submits nothing
 *       that already exists; a plan that changed since (prompts, models, N, system prompt) is
 *       refused, because the stored ids would be attributed to the wrong requests.</li>
 *   <li><b>Cost gate.</b> The plan, request counts and a cost estimate are printed before anything
 *       is submitted, and nothing is submitted unless {@code REPLAY_CONFIRM_COST} equals the
 *       printed request total (primers included: they are billed).</li>
 * </ul>
 *
 * <p>Batch creation is made with the SDK's own retries OFF: a create whose response is lost may
 * still have made the batch, and a silent retry would bill it twice. A create that fails stops the
 * run with the ids so far on disk; check the Anthropic console for an orphan before resuming.
 */
final class PromptReplayBatch {

    /** The resumable state file inside the output directory. */
    static final String STATE_FILE = "batches.json";

    static final int MAX_CUSTOM_ID_LENGTH = 64;

    /** Consecutive failed reads of one batch before the run stops waiting on it (state stays resumable). */
    static final int MAX_CONSECUTIVE_READ_FAILURES = 10;

    private static final Pattern UNSAFE_CUSTOM_ID_CHARS = Pattern.compile("[^A-Za-z0-9_-]");
    private static final double CHARS_PER_TOKEN = 3.5;
    private static final int EXPECTED_OUTPUT_TOKENS = 400;
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);
    private static final double ONE_HOUR_WRITE_MULTIPLE = 2.0;
    private static final double FIVE_MINUTE_WRITE_MULTIPLE = 1.25;
    private static final double READ_MULTIPLE = 0.1;
    private static final double BATCH_MULTIPLE = 0.5;
    private static final double TOKENS_PER_MTOK = 1_000_000.0;

    private PromptReplayBatch() {
    }

    /** Batch-mode settings, read by the harness from environment variables or system properties. */
    record Settings(boolean primerEnabled, Duration primerWait, Duration pollInterval, Duration deadline,
            boolean resume, String confirmCost) {
    }

    /** The two system prompts (inland, coastal), after any override, and their output builders. */
    record SystemPrompts(String inland, String coastal, PromptBuilder inlandBuilder,
            PromptBuilder coastalBuilder) {

        String of(boolean isCoastal) {
            return isCoastal ? coastal : inland;
        }

        PromptBuilder builderOf(boolean isCoastal) {
            return isCoastal ? coastalBuilder : inlandBuilder;
        }
    }

    /** One request of the replay: a stored prompt, a model and a run index. */
    record Request(String customId, String label, EvaluationModel model, int attempt, boolean coastal,
            String userMessage) {

        String prefix() {
            return coastal ? "coastal" : "inland";
        }
    }

    /** A model's progress, persisted. {@code warmed} is null until the primers have been resolved. */
    record ModelState(String model, int requests, Map<String, String> primers, List<String> warmed,
            String batchId) {

        ModelState withPrimer(String prefix, String id) {
            Map<String, String> copy = new LinkedHashMap<>(primers == null ? Map.of() : primers);
            copy.put(prefix, id);
            return new ModelState(model, requests, copy, warmed, batchId);
        }

        ModelState withWarmed(List<String> prefixes) {
            return new ModelState(model, requests, primers, prefixes, batchId);
        }

        ModelState withBatch(String id) {
            return new ModelState(model, requests, primers, warmed, id);
        }
    }

    /** The persisted run: what was planned and what exists at Anthropic. */
    record State(String fingerprint, String systemPrompt, boolean collected, List<ModelState> models) {
    }

    /** The validated plan a run will execute, with the state it starts from. */
    record Plan(List<Request> requests, List<EvaluationModel> models, String fingerprint,
            String systemPromptId, State state) {

        List<Request> requestsOf(EvaluationModel model) {
            return requests.stream().filter(r -> r.model() == model).toList();
        }
    }

    // ------------------------------------------------------------------------------------------
    // Plan
    // ------------------------------------------------------------------------------------------

    /**
     * The requests of a replay, in the order the direct path runs them (prompt, then model, then run).
     * A custom id is {@code p<prompt index>-<MODEL>-r<run>-<label>} with the label reduced to the
     * characters Anthropic allows and cut to the 64-character limit; the prompt index keeps ids
     * unique whatever the labels are, and the plan, not the id, is the source of truth on read-back.
     *
     * @param prompts the stored prompts, in file-name order
     * @param models  the models to replay on
     * @param n       runs per prompt and model
     * @return the requests
     */
    static List<Request> plan(List<StoredPrompt> prompts, List<EvaluationModel> models, int n) {
        List<Request> requests = new ArrayList<>();
        for (int p = 0; p < prompts.size(); p++) {
            StoredPrompt prompt = prompts.get(p);
            for (EvaluationModel model : models) {
                for (int run = 1; run <= n; run++) {
                    requests.add(new Request(customId(p, model, run, prompt.label()), prompt.label(),
                            model, run, prompt.coastal(), prompt.userMessage()));
                }
            }
        }
        return requests;
    }

    static String customId(int promptIndex, EvaluationModel model, int run, String label) {
        String head = "p" + promptIndex + "-" + model.name() + "-r" + run + "-";
        String safe = UNSAFE_CUSTOM_ID_CHARS.matcher(label).replaceAll("_");
        int room = Math.max(0, MAX_CUSTOM_ID_LENGTH - head.length());
        return head + safe.substring(0, Math.min(safe.length(), room));
    }

    /**
     * Validates settings and state, prints the plan and the cost, and refuses to go on unless the
     * cost is confirmed. Nothing here needs an API key or the network.
     *
     * @param settings      the batch settings
     * @param outDir        where {@code batches.json} lives
     * @param requests      the planned requests
     * @param models        the models
     * @param systemPrompts the system prompts the requests will carry
     * @param systemId      the system prompt's label (see {@code describeSystemPrompt})
     * @param mapper        JSON mapper for the state file
     * @param out           where the plan is printed
     * @return the plan to execute
     */
    static Plan preflight(Settings settings, Path outDir, List<Request> requests,
            List<EvaluationModel> models, SystemPrompts systemPrompts, String systemId,
            ObjectMapper mapper, PrintStream out) {
        String fingerprint = fingerprint(requests, systemId, systemPrompts);
        State state = readState(outDir, mapper);
        if (settings.resume()) {
            if (state == null) {
                throw new IllegalStateException("REPLAY_BATCH_RESUME is set but " + outDir.resolve(STATE_FILE)
                        + " does not exist");
            }
            if (!fingerprint.equals(state.fingerprint())) {
                throw new IllegalStateException("REPLAY_BATCH_RESUME: the plan differs from the one in "
                        + outDir.resolve(STATE_FILE) + " (prompts, models, REPLAY_N or the system prompt"
                        + " changed). Rerun with the original settings, or delete the file to abandon it"
                        + " (its batches still bill).");
            }
        } else if (state != null && !state.collected()) {
            throw new IllegalStateException(outDir.resolve(STATE_FILE) + " holds batches that were never"
                    + " collected. Set REPLAY_BATCH_RESUME=true to continue them, or delete the file to"
                    + " abandon them (they still bill). Starting afresh would overwrite their ids.");
        }
        Map<String, ModelState> known = new LinkedHashMap<>();
        if (settings.resume()) {
            state.models().forEach(m -> known.put(m.model(), m));
        }

        int toSubmit = 0;
        double expected = 0;
        double ceiling = 0;
        out.println("== Batch replay plan (Message Batches API, 50% of standard prices) ==");
        out.println("System prompt: " + systemId);
        for (EvaluationModel model : models) {
            List<Request> own = requests.stream().filter(r -> r.model() == model).toList();
            ModelState ms = known.get(model.name());
            Set<String> prefixes = new LinkedHashSet<>();
            own.forEach(r -> prefixes.add(r.prefix()));
            int primers = 0;
            String status;
            if (ms != null && ms.batchId() != null) {
                status = "already submitted as " + ms.batchId() + " (resume: nothing to submit)";
            } else {
                if (settings.primerEnabled() && (ms == null || ms.warmed() == null)) {
                    for (String prefix : prefixes) {
                        if (ms == null || ms.primers() == null || !ms.primers().containsKey(prefix)) {
                            primers++;
                        }
                    }
                }
                toSubmit += own.size() + primers;
                status = "to submit: 1 batch of " + own.size() + " + " + primers + " primer request(s)";
            }
            double[] cost = estimate(model, own, primers, systemPrompts);
            if (ms == null || ms.batchId() == null) {
                expected += cost[0];
                ceiling += cost[1];
            }
            out.printf(Locale.ROOT, "  %-10s %5d request(s); %s; est. $%.2f expected, $%.2f ceiling%n",
                    model.name(), own.size(), status, cost[0], cost[1]);
        }
        out.printf(Locale.ROOT, "TOTAL REQUESTS TO SUBMIT: %d (primers included)%n", toSubmit);
        out.printf(Locale.ROOT, "ESTIMATED COST at batch prices: $%.2f expected (%d output tokens each, system"
                + " prompt read from cache), $%.2f ceiling (every reply at max_tokens, thinking included)."
                + " Thinking tokens bill as output; the figures are estimates, not a quote.%n",
                expected, EXPECTED_OUTPUT_TOKENS, ceiling);
        if (toSubmit > 0 && !String.valueOf(toSubmit).equals(settings.confirmCost() == null ? null
                : settings.confirmCost().trim())) {
            throw new IllegalStateException("Refusing to submit " + toSubmit + " request(s): set"
                    + " REPLAY_CONFIRM_COST=" + toSubmit + " to confirm the plan printed above.");
        }
        return new Plan(requests, models, fingerprint, systemId, state == null || !settings.resume() ? null : state);
    }

    // ------------------------------------------------------------------------------------------
    // Execution
    // ------------------------------------------------------------------------------------------

    /** Pause between polls; replaceable so a test need not wait. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /** Submits, waits for and collects the batches of a {@link Plan}. */
    static final class Runner {

        private final AnthropicClient client;
        private final SystemPrompts systemPrompts;
        private final Settings settings;
        private final Path outDir;
        private final ObjectMapper mapper;
        private final SunsetEvaluationParser parser;
        private final Clock clock;
        private final Sleeper sleeper;
        private final PrintStream out;
        private final String systemId;

        Runner(AnthropicClient client, SystemPrompts systemPrompts, Settings settings, Path outDir,
                ObjectMapper mapper, SunsetEvaluationParser parser, String systemId, Clock clock,
                Sleeper sleeper, PrintStream out) {
            this.client = client;
            this.systemPrompts = systemPrompts;
            this.settings = settings;
            this.outDir = outDir;
            this.mapper = mapper;
            this.parser = parser;
            this.systemId = systemId;
            this.clock = clock;
            this.sleeper = sleeper;
            this.out = out;
        }

        /**
         * Runs the plan to the end (or the deadline) and returns one row per planned request, in plan
         * order. Rows for a batch that had not ended by the deadline are failures and the state file
         * is left uncollected, so a resume continues them.
         *
         * @param plan the plan from {@link #preflight}
         * @return the rows
         * @throws InterruptedException if interrupted while waiting
         */
        List<Run> run(Plan plan) throws InterruptedException {
            Map<String, ModelState> states = new LinkedHashMap<>();
            if (plan.state() != null) {
                plan.state().models().forEach(m -> states.put(m.model(), m));
            }
            for (EvaluationModel model : plan.models()) {
                List<Request> own = plan.requestsOf(model);
                ModelState ms = states.getOrDefault(model.name(),
                        new ModelState(model.name(), own.size(), new LinkedHashMap<>(), null, null));
                if (ms.batchId() == null) {
                    ms = prime(model, own, ms, plan, states);
                    ms = submit(model, own, ms);
                    states.put(model.name(), ms);
                    save(plan, states, false);
                }
            }
            return collect(plan, states);
        }

        // -- priming -------------------------------------------------------------------------

        private ModelState prime(EvaluationModel model, List<Request> own, ModelState start, Plan plan,
                Map<String, ModelState> states) {
            if (!settings.primerEnabled() || settings.primerWait().isZero()) {
                return start.withWarmed(List.of());
            }
            if (start.warmed() != null) {
                return start;
            }
            ModelState ms = start;
            try {
                Map<String, Request> representatives = new LinkedHashMap<>();
                own.forEach(r -> representatives.putIfAbsent(r.prefix(), r));
                int ordinal = 0;
                for (Map.Entry<String, Request> entry : representatives.entrySet()) {
                    String prefix = entry.getKey();
                    if (ms.primers() != null && ms.primers().containsKey(prefix)) {
                        continue;
                    }
                    try {
                        BatchCreateParams params = BatchCreateParams.builder()
                                .addRequest(buildRequest("pw-" + model.name() + "-" + ordinal++,
                                        entry.getValue(), true))
                                .build();
                        String id = create(params).id();
                        ms = ms.withPrimer(prefix, id);
                        states.put(model.name(), ms);
                        save(plan, states, false);
                    } catch (RuntimeException e) {
                        out.println("[primer] " + model.name() + "/" + prefix + " could not be submitted"
                                + " - its requests go without the 1-hour cache: " + e);
                    }
                }
                List<String> warmed = awaitPrimers(model, ms);
                return ms.withWarmed(warmed);
            } catch (RuntimeException e) {
                out.println("[primer] " + model.name() + " priming failed - continuing without it: " + e);
                return ms.withWarmed(List.of());
            }
        }

        private List<String> awaitPrimers(EvaluationModel model, ModelState ms) {
            Map<String, String> pending = new LinkedHashMap<>(ms.primers() == null ? Map.of() : ms.primers());
            List<String> warmed = new ArrayList<>();
            Instant deadline = clock.instant().plus(settings.primerWait());
            while (!pending.isEmpty()) {
                for (Map.Entry<String, String> entry : new ArrayList<>(pending.entrySet())) {
                    try {
                        MessageBatch batch = client.messages().batches().retrieve(entry.getValue(),
                                RequestOptions.builder().timeout(CALL_TIMEOUT).build());
                        if (MessageBatch.ProcessingStatus.ENDED.equals(batch.processingStatus())) {
                            pending.remove(entry.getKey());
                            if (batch.requestCounts().succeeded() > 0) {
                                warmed.add(entry.getKey());
                            } else {
                                out.println("[primer] " + model.name() + "/" + entry.getKey()
                                        + " ended without a succeeded request - the cache is not warm");
                            }
                        }
                    } catch (RuntimeException e) {
                        out.println("[primer] could not read primer batch " + entry.getValue()
                                + " (treated as pending): " + e);
                    }
                }
                if (pending.isEmpty()) {
                    break;
                }
                Duration remaining = Duration.between(clock.instant(), deadline);
                if (remaining.isNegative() || remaining.isZero()) {
                    out.println("[primer] " + model.name() + " primer(s) " + pending.keySet()
                            + " had not ended within " + settings.primerWait().toSeconds()
                            + "s - continuing without them");
                    break;
                }
                try {
                    sleeper.sleep(remaining.compareTo(settings.pollInterval()) < 0
                            ? remaining : settings.pollInterval());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            out.println("[primer] " + model.name() + " warmed prefixes: " + warmed);
            return warmed;
        }

        // -- submit --------------------------------------------------------------------------

        private ModelState submit(EvaluationModel model, List<Request> own, ModelState ms) {
            Set<String> warmed = new LinkedHashSet<>(ms.warmed() == null ? List.of() : ms.warmed());
            BatchCreateParams.Builder params = BatchCreateParams.builder();
            for (Request r : own) {
                params.addRequest(buildRequest(r.customId(), r, warmed.contains(r.prefix())));
            }
            String id = create(params.build()).id();
            out.println("Created " + model.name() + " batch " + id + " (" + own.size()
                    + " requests); writing it to " + STATE_FILE);
            return ms.withBatch(id);
        }

        private MessageBatch create(BatchCreateParams params) {
            return client.withOptions(o -> o.maxRetries(0)).messages().batches().create(params);
        }

        /**
         * One batch request, built as {@code ClaudeEvaluationStrategy#invokeClaude} builds the direct
         * one, in the batch SDK's parameter type (as {@code BatchRequestFactory} does).
         */
        BatchCreateParams.Request buildRequest(String customId, Request r, boolean longLivedCache) {
            CacheControlEphemeral.Builder cache = CacheControlEphemeral.builder();
            if (longLivedCache) {
                cache.ttl(CacheControlEphemeral.Ttl.TTL_1H);
            }
            return BatchCreateParams.Request.builder()
                    .customId(customId)
                    .params(ModelRequestSupport.withThinkingSetting(
                            BatchCreateParams.Request.Params.builder()
                                    .model(r.model().getModelId())
                                    .maxTokens(r.model().getMaxTokens())
                                    .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                                            .text(systemPrompts.of(r.coastal()))
                                            .cacheControl(cache.build())
                                            .build()))
                                    .outputConfig(ModelRequestSupport.withEffort(
                                            systemPrompts.builderOf(r.coastal()).buildOutputConfig(),
                                            r.model()))
                                    .addUserMessage(r.userMessage()), r.model())
                            .build())
                    .build();
        }

        // -- poll and collect ----------------------------------------------------------------

        private List<Run> collect(Plan plan, Map<String, ModelState> states) throws InterruptedException {
            Map<String, Request> byId = new LinkedHashMap<>();
            plan.requests().forEach(r -> byId.put(r.customId(), r));
            Map<String, Run> rows = new LinkedHashMap<>();
            Map<String, ModelState> pending = new LinkedHashMap<>(states);
            Map<String, Integer> failedReads = new LinkedHashMap<>();
            Set<String> abandoned = new LinkedHashSet<>();
            Instant deadline = clock.instant().plus(settings.deadline());
            while (!pending.isEmpty()) {
                for (Map.Entry<String, ModelState> entry : new ArrayList<>(pending.entrySet())) {
                    ModelState ms = entry.getValue();
                    try {
                        MessageBatch batch = client.messages().batches().retrieve(ms.batchId(),
                                RequestOptions.builder().timeout(CALL_TIMEOUT).build());
                        if (!MessageBatch.ProcessingStatus.ENDED.equals(batch.processingStatus())) {
                            continue;
                        }
                        out.println("Batch " + ms.batchId() + " (" + ms.model() + ") ended: "
                                + batch.requestCounts().succeeded() + " succeeded, "
                                + batch.requestCounts().errored() + " errored, "
                                + batch.requestCounts().expired() + " expired, "
                                + batch.requestCounts().canceled() + " canceled");
                        readResults(ms, byId, rows);
                        failedReads.remove(entry.getKey());
                        pending.remove(entry.getKey());
                    } catch (RuntimeException e) {
                        int failures = failedReads.merge(entry.getKey(), 1, Integer::sum);
                        if (failures >= MAX_CONSECUTIVE_READ_FAILURES) {
                            out.println("Giving up on batch " + ms.batchId() + " after " + failures
                                    + " failed reads in a row (rerun with REPLAY_BATCH_RESUME=true once"
                                    + " it is readable): " + e);
                            abandoned.add(entry.getKey());
                            pending.remove(entry.getKey());
                        } else {
                            out.println("Could not read batch " + ms.batchId() + " (will retry, "
                                    + failures + "/" + MAX_CONSECUTIVE_READ_FAILURES + "): " + e);
                        }
                    }
                }
                if (pending.isEmpty()) {
                    break;
                }
                Duration remaining = Duration.between(clock.instant(), deadline);
                if (remaining.isNegative() || remaining.isZero()) {
                    out.println("Deadline reached with " + pending.size() + " batch(es) unfinished: "
                            + pending.values().stream().map(ModelState::batchId).toList()
                            + ". Rerun with REPLAY_BATCH_RESUME=true to continue.");
                    break;
                }
                sleeper.sleep(remaining.compareTo(settings.pollInterval()) < 0
                        ? remaining : settings.pollInterval());
            }
            boolean complete = pending.isEmpty() && abandoned.isEmpty();
            save(plan, states, complete);
            List<Run> ordered = new ArrayList<>();
            for (Request r : plan.requests()) {
                Run row = rows.get(r.customId());
                if (row == null) {
                    ModelState ms = states.get(r.model().name());
                    String reason = pending.containsKey(r.model().name())
                            || abandoned.contains(r.model().name())
                            ? "BatchNotCollected: batch " + ms.batchId() + " had not ended"
                            : "NoResult: batch " + ms.batchId() + " returned nothing for " + r.customId();
                    row = failure(r, reason, ms == null ? null : ms.batchId(), TokenUsage.EMPTY);
                }
                ordered.add(row);
            }
            return ordered;
        }

        private void readResults(ModelState ms, Map<String, Request> byId, Map<String, Run> rows) {
            Map<String, Run> local = new LinkedHashMap<>();
            try (StreamResponse<MessageBatchIndividualResponse> results = client.messages().batches()
                    .resultsStreaming(ms.batchId())) {
                results.stream().forEach(response -> {
                    Request r = byId.get(response.customId());
                    if (r == null) {
                        out.println("Batch " + ms.batchId() + " returned unknown custom_id "
                                + response.customId() + " (ignored)");
                        return;
                    }
                    local.put(r.customId(), toRun(r, response, ms.batchId()));
                });
            }
            rows.putAll(local);
        }

        private Run toRun(Request r, MessageBatchIndividualResponse response, String batchId) {
            if (response.result().isSucceeded()) {
                Message message = response.result().asSucceeded().message();
                TokenUsage usage = TokenUsage.from(message.usage());
                try {
                    return PromptReplayHarnessTest.parseReply(message, r.label(), r.model(), r.attempt(),
                            parser, mapper, systemId, "batch", batchId, usage);
                } catch (RuntimeException e) {
                    return failure(r, e.getClass().getSimpleName() + ": " + e.getMessage(), batchId, usage);
                }
            }
            if (response.result().isErrored()) {
                String error = String.valueOf(response.result().asErrored().error());
                return failure(r, "BatchErrored: " + error, batchId, TokenUsage.EMPTY);
            }
            return failure(r, response.result().isExpired() ? "BatchExpired" : "BatchCanceled", batchId,
                    TokenUsage.EMPTY);
        }

        private Run failure(Request r, String reason, String batchId, TokenUsage usage) {
            return new Run(r.label(), r.model(), r.attempt(), null, null, null, reason, null, systemId,
                    "batch", batchId, usage);
        }

        private void save(Plan plan, Map<String, ModelState> states, boolean collected) {
            try {
                Files.createDirectories(outDir);
                State state = new State(plan.fingerprint(), plan.systemPromptId(), collected,
                        new ArrayList<>(states.values()));
                Files.writeString(outDir.resolve(STATE_FILE),
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(state),
                        StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot write " + outDir.resolve(STATE_FILE)
                        + " - stopping before more batches are created that could not be resumed", e);
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // State, fingerprint, pricing
    // ------------------------------------------------------------------------------------------

    static State readState(Path outDir, ObjectMapper mapper) {
        Path file = outDir.resolve(STATE_FILE);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return mapper.readValue(Files.readString(file, StandardCharsets.UTF_8), State.class);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Cannot read " + file + " (delete it only if you accept that"
                    + " its batches are abandoned and still bill): " + e.getMessage(), e);
        }
    }

    static String fingerprint(List<Request> requests, String systemId, SystemPrompts systemPrompts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(systemId.getBytes(StandardCharsets.UTF_8));
            digest.update(systemPrompts.inland().getBytes(StandardCharsets.UTF_8));
            digest.update(systemPrompts.coastal().getBytes(StandardCharsets.UTF_8));
            for (Request r : requests) {
                digest.update(r.customId().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update((r.model().getModelId() + "/" + r.model().getMaxTokens())
                        .getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(r.userMessage().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest(), 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** USD per million input tokens and per million output tokens, at standard prices. */
    record Price(double input, double output) {
    }

    /**
     * Standard prices per model, from {@code cost.*} in {@code application-example.yml} (the cache
     * rates there are fixed multiples of the input rate, applied in {@link #cost}).
     *
     * @param model the model
     * @return its standard input and output prices
     */
    static Price price(EvaluationModel model) {
        return switch (model) {
            case HAIKU_55 -> new Price(0.10, 0.50);
            case HAIKU -> new Price(1.00, 5.00);
            case SONNET_55 -> new Price(2.00, 10.00);
            case SONNET, SONNET_ET -> new Price(3.00, 15.00);
            case OPUS, OPUS_ET -> new Price(5.00, 25.00);
            default -> throw new IllegalArgumentException("No price known for " + model);
        };
    }

    /**
     * The cost of one call's usage in USD.
     *
     * @param model the model
     * @param usage the usage the API reported
     * @param batch true for Batch API prices (half of standard)
     * @return dollars
     */
    static double cost(EvaluationModel model, TokenUsage usage, boolean batch) {
        Price p = price(model);
        long oneHour = usage.cacheCreationOneHourTokens();
        long fiveMinute = usage.cacheCreationInputTokens() - oneHour;
        double dollars = (usage.inputTokens() * p.input()
                + oneHour * p.input() * ONE_HOUR_WRITE_MULTIPLE
                + fiveMinute * p.input() * FIVE_MINUTE_WRITE_MULTIPLE
                + usage.cacheReadInputTokens() * p.input() * READ_MULTIPLE
                + usage.outputTokens() * p.output()) / TOKENS_PER_MTOK;
        return batch ? dollars * BATCH_MULTIPLE : dollars;
    }

    /** {expected, ceiling} dollars for one model's requests and primers at batch prices. */
    private static double[] estimate(EvaluationModel model, List<Request> own, int primers,
            SystemPrompts systemPrompts) {
        double expected = 0;
        double ceiling = 0;
        for (Request r : own) {
            long system = Math.round(systemPrompts.of(r.coastal()).length() / CHARS_PER_TOKEN);
            long user = Math.round(r.userMessage().length() / CHARS_PER_TOKEN);
            TokenUsage common = new TokenUsage(user, EXPECTED_OUTPUT_TOKENS, 0, system);
            expected += cost(model, common, true);
            ceiling += cost(model, new TokenUsage(user, model.getMaxTokens(), 0, system), true);
        }
        if (primers > 0 && !own.isEmpty()) {
            Request r = own.get(0);
            long system = Math.round(systemPrompts.of(r.coastal()).length() / CHARS_PER_TOKEN);
            long user = Math.round(r.userMessage().length() / CHARS_PER_TOKEN);
            double one = cost(model, new TokenUsage(user, EXPECTED_OUTPUT_TOKENS, system, 0, system), true);
            double max = cost(model, new TokenUsage(user, model.getMaxTokens(), system, 0, system), true);
            expected += one * primers;
            ceiling += max * primers;
        }
        return new double[] {expected, ceiling};
    }
}
