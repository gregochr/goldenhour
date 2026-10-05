package com.gregochr.goldenhour.service.ask;

import com.anthropic.core.JsonValue;
import com.anthropic.core.RequestOptions;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolChoiceAuto;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.ApiCallLogEntity;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.exception.ClaudeRefusalException;
import com.gregochr.goldenhour.exception.ClaudeReplyUnreadableException;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.repository.RegionRepository;
import com.gregochr.goldenhour.service.DriveTimeResolver;
import com.gregochr.goldenhour.service.JobRunService;
import com.gregochr.goldenhour.service.ask.AskAnswerParser.Parsed;
import com.gregochr.goldenhour.service.ask.AskAnswerValidator.Result;
import com.gregochr.goldenhour.service.ask.AskToolArguments.BadArguments;
import com.gregochr.goldenhour.service.evaluation.AnthropicApiClient;
import com.gregochr.goldenhour.service.evaluation.ModelRequestSupport;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The Claude tool loop (plan §2.3, D-1): at most {@code max-turns} model turns, {@code tool_choice}
 * auto, and the reply is the input of the model's {@code submit_answer} call.
 *
 * <p><b>Nothing safety- or cost-relevant depends on the model behaving.</b>
 * <ul>
 *   <li>Anything the model sends is untrusted: its tool arguments are read defensively
 *       ({@link AskToolArguments}, a bad one is an error {@code tool_result} fed back, never an
 *       exception), its reply is read by {@link AskAnswerParser} and then held to what the tools
 *       returned by {@link AskAnswerValidator}, and every card fact is joined by the server.</li>
 *   <li>A turn that ends in a refusal, {@code max_tokens}, a context-window stop, no tool call, or a
 *       fourth turn without {@code submit_answer} is a FAILED outcome. The stop-reason test is
 *       {@link ModelRequestSupport#checkStopReason}, the one every other Claude path uses.</li>
 *   <li>The conversation has a {@code deadline-seconds} deadline. It is checked before every turn,
 *       the time left is that call's timeout, <em>and</em> the engine stops waiting at the deadline
 *       whatever the call is doing: the {@code ask} retry would otherwise hand a second attempt a
 *       fresh timeout of the same size, and the bulkhead can wait two seconds before a call even
 *       starts. The SDK's own retries are off for this door (see {@code AnthropicApiClient}).</li>
 *   <li>Cost fails closed: the job run the spend is booked to is found or created <em>before</em> the
 *       first model turn, so a database that cannot record the money stops the call that would have
 *       spent it. Every turn is logged afterwards, including failed ones.</li>
 * </ul>
 *
 * <p><b>Scope.</b> The question's region ids are resolved to names once; that one set is given to
 * {@link AskTools} and to {@link AskAnswerValidator#validate}, so the tools, the validator and the
 * {@code BEST_*} anchor cannot disagree. An id that does not resolve fails the run: scope is a
 * safety boundary, and an empty set would silently widen it to every region.
 *
 * <p>The question reaches Claude only as the user message. It is not in the system prompt, in any
 * tool result, or in {@code api_call_log} (the request body is not stored).
 */
@Service
@ConditionalOnProperty(name = "photocast.ask.stub", havingValue = "false", matchIfMissing = true)
public class ClaudeAskEngine implements AskEngine {

    private static final Logger LOG = LoggerFactory.getLogger(ClaudeAskEngine.class);

    /** The {@code api_call_log.request_url} tag of a typed question's calls. */
    static final String URL_TYPED = "ask";

    /** The {@code api_call_log.request_url} tag of a Ready precompute conversation's calls. */
    static final String URL_READY = "ask-ready";

    private static final int HTTP_OK = 200;

    private final AnthropicApiClient client;
    private final AskProperties properties;
    private final AskJobRunService jobRuns;
    private final JobRunService jobRunService;
    private final DriveTimeResolver driveTimes;
    private final RegionRepository regionRepository;
    private final AskAnswerValidator validator;
    private final AskPromptBuilder promptBuilder;
    private final ObjectMapper mapper;
    private final Clock clock;

    /** Runs each model call so the engine can stop waiting at its deadline. One virtual thread a call. */
    private final ExecutorService callExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Creates the engine.
     *
     * @param client           the resilient Anthropic client ({@code createAskMessage})
     * @param properties       the Ask settings
     * @param jobRuns          the per-day run and the cost increments
     * @param jobRunService    writes {@code api_call_log} rows
     * @param driveTimes       the asker's own drive times, for {@code maxDriveMinutes}
     * @param regionRepository resolves the question's region ids to names
     * @param validator        holds an answer to what the tools returned
     * @param promptBuilder    builds the system prompt
     * @param mapper           serialises tool results for the model
     * @param clock            the application clock: the deadline and the durations
     */
    public ClaudeAskEngine(AnthropicApiClient client, AskProperties properties,
            AskJobRunService jobRuns, JobRunService jobRunService, DriveTimeResolver driveTimes,
            RegionRepository regionRepository, AskAnswerValidator validator,
            AskPromptBuilder promptBuilder, ObjectMapper mapper, Clock clock) {
        this.client = client;
        this.properties = properties;
        this.jobRuns = jobRuns;
        this.jobRunService = jobRunService;
        this.driveTimes = driveTimes;
        this.regionRepository = regionRepository;
        this.validator = validator;
        this.promptBuilder = promptBuilder;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Stops the call threads at shutdown. */
    @PreDestroy
    void shutdown() {
        callExecutor.shutdownNow();
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException when the options do not match the conversation: a user-less
     *         (Ready) conversation needs a Ready job run, and a typed one must not carry one. That is
     *         a caller bug, so it is loud rather than a quiet FAILED
     */
    @Override
    public AskRun run(AskQuestion question, AskSnapshot snapshot, AskUserContext user,
            AskRunOptions options) {
        AskRunOptions opts = options == null ? AskRunOptions.none() : options;
        boolean ready = !user.hasUser();
        if (ready && opts.readyJobRunId() == null) {
            throw new IllegalArgumentException(
                    "a user-less (Ready) conversation needs the ASK_READY job run to bill");
        }
        if (!ready && opts.readyJobRunId() != null) {
            throw new IllegalArgumentException("a typed conversation is billed to the daily ASK run, "
                    + "not to a Ready run");
        }
        if (question.sanitised() == null || question.sanitised().isBlank()) {
            return failed("the question is empty", 0, false, List.of());
        }
        Optional<Set<String>> scope = resolveScope(question);
        if (scope.isEmpty()) {
            return failed("a region id in the question's scope does not exist", 0, false, List.of());
        }
        long runId;
        try {
            runId = ready ? opts.readyJobRunId() : jobRuns.dailyRunId();
        } catch (RuntimeException e) {
            // Fail closed: if the spend cannot be booked, nothing is spent.
            LOG.error("[ASK] Could not open the job run; no model call was made: {}", e.getMessage());
            return failed("the job run could not be opened: " + describe(e), 0, false, List.of());
        }

        AskRun run;
        try {
            run = converse(question, snapshot, user, opts, scope.get(), runId, ready);
        } catch (RuntimeException e) {
            LOG.warn("[ASK] Conversation failed unexpectedly: {}", e.toString());
            run = failed("unexpected error: " + describe(e), 0, false, List.of());
        }
        if (!ready) {
            jobRuns.recordQuestion(runId, run.outcome().status() != AskOutcome.Status.FAILED);
        }
        return run;
    }

    // -- the loop ---------------------------------------------------------------------------

    private AskRun converse(AskQuestion question, AskSnapshot snapshot, AskUserContext user,
            AskRunOptions opts, Set<String> scope, long runId, boolean ready) {
        EvaluationModel model = properties.getModel();
        AskTools tools = new AskTools(snapshot, user, scope, driveTimes, mapper);
        Optional<AskSnapshot.Window> contextWindow = question.windowId() == null
                ? Optional.empty() : snapshot.window(question.windowId());
        String system = promptBuilder.systemPrompt(snapshot.today(), scope, contextWindow,
                user.hasUser());
        List<Tool> toolDefinitions = AskToolSchemas.tools(user.hasUser());
        List<AskTools.ToolCall> trace = new ArrayList<>();
        List<Message> assistantTurns = new ArrayList<>();
        List<List<ContentBlockParam>> toolResults = new ArrayList<>();
        Instant deadline = clock.instant().plusSeconds(properties.getDeadlineSeconds());
        int turns = 0;

        for (int turn = 1; turn <= properties.getMaxTurns(); turn++) {
            Duration remaining = Duration.between(clock.instant(), deadline);
            if (remaining.isZero() || remaining.isNegative()) {
                return failed("the deadline passed before turn " + turn, turns, tools.personal(), trace);
            }
            Duration callTimeout = min(Duration.ofSeconds(properties.getCallTimeoutSeconds()), remaining);
            MessageCreateParams params = buildParams(model, system, toolDefinitions, question,
                    assistantTurns, toolResults);
            turns = turn;

            Instant started = clock.instant();
            Message response;
            try {
                response = callModel(params, callTimeout, remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logTurn(runId, ready, model, started, null, false, "interrupted", null);
                return failed("interrupted", turns, tools.personal(), trace);
            } catch (Exception e) {
                Integer status = e instanceof AnthropicServiceException s ? s.statusCode() : null;
                logTurn(runId, ready, model, started, status, false, describe(e), null);
                return failed("the model call failed: " + describe(e), turns, tools.personal(), trace);
            }

            TokenUsage usage = response.usage() == null ? null : TokenUsage.from(response.usage());
            String stopProblem = stopProblem(response);
            if (stopProblem != null) {
                logTurn(runId, ready, model, started, HTTP_OK, false, stopProblem, usage);
                return failed(stopProblem, turns, tools.personal(), trace);
            }
            List<ToolUseBlock> toolUses = response.content().stream()
                    .filter(ContentBlock::isToolUse).map(ContentBlock::asToolUse).toList();
            if (toolUses.isEmpty()) {
                String reason = "the turn asked for a tool but carried no tool call";
                logTurn(runId, ready, model, started, HTTP_OK, false, reason, usage);
                return failed(reason, turns, tools.personal(), trace);
            }
            logTurn(runId, ready, model, started, HTTP_OK, true, null, usage);

            List<ContentBlockParam> results = new ArrayList<>();
            for (ToolUseBlock block : toolUses) {
                if (AskToolSchemas.SUBMIT_ANSWER.equals(block.name())) {
                    return submit(block, snapshot, tools, scope, opts, turns, trace);
                }
                AskToolResult result = dispatch(block, tools, trace);
                results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                        .toolUseId(block.id()).content(result.content()).isError(result.error())
                        .build()));
            }
            assistantTurns.add(response);
            toolResults.add(results);
        }
        return failed("no submit_answer within " + properties.getMaxTurns() + " turns", turns,
                tools.personal(), trace);
    }

    /** The params of one turn: the rules, the tools, the question, then every earlier turn. */
    private MessageCreateParams buildParams(EvaluationModel model, String system, List<Tool> tools,
            AskQuestion question, List<Message> assistantTurns, List<List<ContentBlockParam>> results) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(model.getModelId())
                .maxTokens(ModelRequestSupport.maxTokens(model, properties.getMaxTokens()))
                .systemOfTextBlockParams(List.of(TextBlockParam.builder().text(system).build()))
                .toolChoice(ToolChoiceAuto.builder().build())
                .addUserMessage(question.sanitised());
        tools.forEach(builder::addTool);
        ModelRequestSupport.tune(builder, model);
        // The two lists are appended together, one entry per completed turn; the bound is the shorter
        // of them so a mismatch could never read past either.
        int completedTurns = Math.min(assistantTurns.size(), results.size());
        for (int i = 0; i < completedTurns; i++) {
            builder.addMessage(assistantTurns.get(i));
            builder.addUserMessageOfBlockParams(results.get(i));
        }
        return builder.build();
    }

    /**
     * Makes one model call and stops waiting at the deadline. The SDK call carries its own timeout
     * (the shorter of the per-call timeout and the time left); this guard is for everything the SDK
     * timeout does not cover: the retry's second attempt, the retry's backoff and the bulkhead's
     * wait. A call abandoned here is interrupted, and finishes on its own thread within the SDK
     * timeout; its usage, if it ever arrives, is not logged (the turn is logged as failed, with no
     * usage, which is the cost recorded for any call that never returned).
     */
    private Message callModel(MessageCreateParams params, Duration callTimeout, Duration remaining)
            throws Exception {
        RequestOptions requestOptions = RequestOptions.builder().timeout(callTimeout).build();
        Future<Message> call = callExecutor.submit(() -> client.createAskMessage(params, requestOptions));
        try {
            return call.get(Math.max(1L, remaining.toMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            call.cancel(true);
            throw new DeadlineExceededException("the conversation deadline passed during the call");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw e;
        } catch (InterruptedException e) {
            call.cancel(true);
            throw e;
        }
    }

    /** The engine gave up waiting at its deadline. */
    private static final class DeadlineExceededException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        DeadlineExceededException(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * Why a response cannot be used, or null when it is a tool-use turn. Refusal, {@code max_tokens}
     * and the context-window stop are the shared {@link ModelRequestSupport#checkStopReason} test;
     * anything else that is not {@code tool_use} (an {@code end_turn} with no tool call, a pause)
     * is a turn that did not call a tool.
     */
    private static String stopProblem(Message response) {
        try {
            ModelRequestSupport.checkStopReason(response);
        } catch (ClaudeRefusalException | ClaudeReplyUnreadableException e) {
            return e.getMessage();
        }
        StopReason stop = response.stopReason().orElse(null);
        if (!StopReason.TOOL_USE.equals(stop)) {
            return "the turn ended without a tool call (stop_reason=" + (stop == null ? "none" : stop.asString())
                    + ")";
        }
        return null;
    }

    // -- tools ------------------------------------------------------------------------------

    /** Runs one non-terminal tool call; an unknown tool or unreadable arguments is an error result. */
    private AskToolResult dispatch(ToolUseBlock block, AskTools tools, List<AskTools.ToolCall> trace) {
        String name = block.name();
        int before = tools.trace().size();
        AskToolResult result;
        try {
            JsonNode input = toNode(block._input());
            result = switch (name) {
                case AskToolSchemas.LIST_WINDOWS -> tools.listWindows();
                case AskToolSchemas.RANK_SPOTS -> tools.rankSpots(AskToolArguments.rankSpots(input));
                case AskToolSchemas.GET_HOT_TOPICS -> tools.getHotTopics(AskToolArguments.hotTopics(input));
                case AskToolSchemas.GET_COMING_UP -> tools.getComingUp(AskToolArguments.comingUp(input));
                default -> AskToolResult.error("Unknown tool '" + clip(name) + "'. The tools are "
                        + AskToolSchemas.LIST_WINDOWS + ", " + AskToolSchemas.RANK_SPOTS + ", "
                        + AskToolSchemas.GET_HOT_TOPICS + ", " + AskToolSchemas.GET_COMING_UP + " and "
                        + AskToolSchemas.SUBMIT_ANSWER + ".");
            };
        } catch (BadArguments e) {
            result = AskToolResult.error(e.getMessage());
        } catch (RuntimeException e) {
            LOG.warn("[ASK] Tool {} threw: {}", clip(name), e.toString());
            result = AskToolResult.error("The tool could not be run.");
        }
        List<AskTools.ToolCall> recorded = tools.trace();
        if (recorded.size() > before) {
            trace.addAll(recorded.subList(before, recorded.size()));
        } else {
            trace.add(new AskTools.ToolCall(clip(name), true, 0));
        }
        return result;
    }

    /** Reads, parses and validates the model's {@code submit_answer}; ends the conversation. */
    private AskRun submit(ToolUseBlock block, AskSnapshot snapshot, AskTools tools, Set<String> scope,
            AskRunOptions opts, int turns, List<AskTools.ToolCall> trace) {
        Parsed parsed = AskAnswerParser.parse(toNode(block._input()));
        trace.add(new AskTools.ToolCall(AskToolSchemas.SUBMIT_ANSWER, !parsed.ok(), 0));
        if (!parsed.ok()) {
            return failed("submit_answer was malformed: " + parsed.error(), turns, tools.personal(),
                    trace);
        }
        Result result = validator.validate(parsed.raw(), snapshot, tools.evidence(), scope,
                opts.anchor());
        if (!result.accepted()) {
            return failed("the answer was discarded: " + result.reason(), turns, tools.personal(),
                    trace);
        }
        AskOutcome.Status status = result.answer().answerable() ? AskOutcome.Status.OK
                : AskOutcome.Status.CANT;
        return new AskRun(new AskOutcome(status, result.answer(), tools.personal(), turns), trace, null);
    }

    /** A tool input as a JSON tree; null when it cannot be read as JSON, which no tool accepts. */
    private JsonNode toNode(JsonValue input) {
        if (input == null) {
            return null;
        }
        try {
            return input.convert(JsonNode.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // -- scope and cost ---------------------------------------------------------------------

    /** The question's region names, or empty when an id does not resolve. */
    private Optional<Set<String>> resolveScope(AskQuestion question) {
        Set<Long> ids = new LinkedHashSet<>(question.regionIds());
        if (ids.isEmpty()) {
            return Optional.of(Set.of());
        }
        List<RegionEntity> found = regionRepository.findAllById(ids);
        Set<String> names = new TreeSet<>();
        Set<Long> foundIds = new LinkedHashSet<>();
        for (RegionEntity region : found) {
            foundIds.add(region.getId());
            names.add(region.getName());
        }
        return foundIds.containsAll(ids) ? Optional.of(names) : Optional.empty();
    }

    /**
     * Writes one {@code api_call_log} row for a model turn, failed or not, and adds its cost to the
     * day's run. Logging is best effort once the call has been made: a database failure here is
     * logged at ERROR and the answer is not thrown away over it. The request and response bodies are
     * not stored: the request is the reader's question.
     */
    private void logTurn(long runId, boolean ready, EvaluationModel model, Instant started,
            Integer status, boolean succeeded, String error, TokenUsage usage) {
        try {
            long durationMs = Math.max(0L, Duration.between(started, clock.instant()).toMillis());
            ApiCallLogEntity row = jobRunService.logApiCall(runId, ServiceName.ANTHROPIC, "POST",
                    ready ? URL_READY : URL_TYPED, null, durationMs, status, null, succeeded, error,
                    model, usage);
            if (!ready && row != null && row.getCostMicroDollars() != null && row.getCostMicroDollars() > 0) {
                jobRuns.recordCost(runId, row.getCostMicroDollars());
            }
        } catch (RuntimeException e) {
            LOG.error("[ASK] Could not log a model turn on job run {}: {}", runId, e.getMessage());
        }
    }

    private static AskRun failed(String reason, int turns, boolean personal,
            List<AskTools.ToolCall> trace) {
        return new AskRun(new AskOutcome(AskOutcome.Status.FAILED, null, personal, turns), trace, reason);
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    /** A model-supplied name, bounded so it cannot flood a log line or an error result. */
    private static String clip(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= 40) {
            return text;
        }
        // Never end on half of a surrogate pair.
        int end = Character.isHighSurrogate(text.charAt(39)) ? 39 : 40;
        return text.substring(0, end) + "…";
    }
}
