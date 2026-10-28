package com.gregochr.goldenhour.service.ask;

import com.anthropic.core.JsonValue;
import com.anthropic.core.RequestOptions;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolChoiceAny;
import com.anthropic.models.messages.ToolChoiceTool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.exception.ClaudeRefusalException;
import com.gregochr.goldenhour.exception.ClaudeReplyUnreadableException;
import com.gregochr.goldenhour.model.CacheDiagnostics;
import com.gregochr.goldenhour.model.TokenUsage;
import com.gregochr.goldenhour.service.DriveTimeResolver;
import com.gregochr.goldenhour.service.ask.AskAnswerParser.Parsed;
import com.gregochr.goldenhour.service.ask.AskToolArguments.BadArguments;
import com.gregochr.goldenhour.service.evaluation.AnthropicApiClient;
import com.gregochr.goldenhour.service.evaluation.ModelRequestSupport;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The Claude tool loop (plan §2.3, D-1): at most {@code max-turns} model turns, {@code tool_choice}
 * {@code any} (every turn must be a tool call, so a prose reply cannot happen) except the last, which
 * forces {@code submit_answer}, and the reply is the input of the model's {@code submit_answer} call.
 *
 * <p><b>Nothing safety- or cost-relevant depends on the model behaving.</b>
 * <ul>
 *   <li>Anything the model sends is untrusted: its tool arguments are read defensively
 *       ({@link AskToolArguments}, a bad one is an error {@code tool_result} fed back, never an
 *       exception), its reply is read by {@link AskAnswerParser} and then held to what the tools
 *       returned by {@link AskAnswerValidator}, and every card fact is joined by the server.</li>
 *   <li>A turn that ends in a refusal, {@code max_tokens}, a context-window stop, no tool call, or a
 *       last turn without {@code submit_answer} (reachable only if the forced call is malformed or
 *       cut short) is a FAILED outcome. The stop-reason test is
 *       {@link ModelRequestSupport#checkStopReason}, the one every other Claude path uses.</li>
 *   <li>The conversation has a {@code deadline-seconds} deadline. It is checked before every turn,
 *       the time left is that call's timeout, <em>and</em> the engine stops waiting at the deadline
 *       whatever the call is doing: the {@code ask} retry would otherwise hand a second attempt a
 *       fresh timeout of the same size, and the bulkhead can wait two seconds before a call even
 *       starts. The SDK's own retries are off for this door (see {@code AnthropicApiClient}).</li>
 *   <li>Cost fails closed: the job run the spend is booked to is found or created <em>before</em> the
 *       first model turn, so a database that cannot record the money stops the call that would have
 *       spent it. Every turn is logged afterwards, including failed ones. If a write fails the whole
 *       feature latches closed (see {@link AskJobRunService}); the latch is checked in
 *       {@code run()} and before each turn (cheap early refusals) and, binding, by the
 *       {@code CallGate} inside {@code AnthropicApiClient.createAskMessage}: after the bulkhead
 *       permit, before every attempt (a retry's second attempt included), as the last step before the
 *       HTTP request. <b>The window that cannot be closed:</b> a request already issued when another
 *       conversation latches cannot be recalled, so at most the calls in flight at that moment (the
 *       bulkhead's four) can still be paid for unrecorded.</li>
 *   <li>Each typed turn is logged against the daily run resolved <em>for that turn</em>, so a
 *       conversation that crosses UK midnight books its later turns to the new day, which is the day
 *       the spend figure counts them in. The question itself is counted on the day it started.</li>
 * </ul>
 *
 * <p><b>The frame.</b> What is not this loop's own (the options check, the blank-question refusal, the
 * tools, and validating and closing the submitted answer) is {@link AskConversation}, shared with the
 * stub engine. This class is the SDK loop and the cost guards around it.
 *
 * <p><b>Scope.</b> The question carries its region scope, resolved once ({@link AskScope}); that one
 * value is given to {@link AskTools} and to {@link AskAnswerValidator#validate}, so the tools, the
 * validator and the {@code BEST_*} anchor cannot disagree. An id that does not resolve fails the
 * request before the engine runs: scope is a safety boundary, and an empty set would silently widen
 * it to every region.
 *
 * <p>The question reaches Claude only as the user message. It is not in the system prompt, in any
 * tool result, or in {@code api_call_log} (the request body is not stored).
 */
@Service
@Conditional(AskEngineSelection.ClaudeSelected.class)
public class ClaudeAskEngine implements AskEngine {

    private static final Logger LOG = LoggerFactory.getLogger(ClaudeAskEngine.class);

    private static final int HTTP_OK = 200;

    /**
     * Appended to the tool-result message of the last permitted turn, where {@code tool_choice} also
     * forces {@code submit_answer}. Kept out of the system prompt so the fresh and thread prompts
     * are untouched.
     */
    static final String LAST_TURN_LINE = "This is your last turn: call submit_answer now with what you have, "
            + "or with answerable:false and what is missing.";

    private final AnthropicApiClient client;
    private final AskProperties properties;
    private final AskJobRunService jobRuns;
    private final AskConversation.Deps frame;
    private final AskPromptBuilder promptBuilder;
    private final Clock clock;

    /** Runs each model call so the engine can stop waiting at its deadline. One virtual thread a call. */
    private final ExecutorService callExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Creates the engine.
     *
     * @param client           the resilient Anthropic client ({@code createAskMessage})
     * @param properties       the Ask settings
     * @param jobRuns          the per-day run, the turn log and the unrecorded-cost latch
     * @param driveTimes       the asker's own drive times, for {@code maxDriveMinutes}
     * @param validator        holds an answer to what the tools returned
     * @param promptBuilder    builds the system prompt
     * @param mapper           serialises tool results for the model
     * @param clock            the application clock: the deadline and the durations
     * @param locationNames    the names of enabled locations, for a thread's earlier pick that has left
     *                         the forecast
     */
    public ClaudeAskEngine(AnthropicApiClient client, AskProperties properties,
            AskJobRunService jobRuns, DriveTimeResolver driveTimes,
            AskAnswerValidator validator,
            AskPromptBuilder promptBuilder, ObjectMapper mapper, Clock clock,
            AskLocationNames locationNames) {
        this.client = client;
        this.properties = properties;
        this.jobRuns = jobRuns;
        this.frame = new AskConversation.Deps(validator, driveTimes, mapper, locationNames);
        this.promptBuilder = promptBuilder;
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
        return switch (AskConversation.open(question, snapshot, user, options, frame)) {
            case AskConversation.Opening.Refused refused -> refused.run();
            case AskConversation.Opening.Open open -> run(open.conversation(), question, snapshot, user);
        };
    }

    /** The cost guards around one opened conversation: the latch, then the run the spend is booked to. */
    private AskRun run(AskConversation conversation, AskQuestion question, AskSnapshot snapshot,
            AskUserContext user) {
        boolean ready = !user.hasUser();
        if (!accountingOpen()) {
            return conversation.fail(AskRun.ACCOUNTING_UNAVAILABLE, 0, List.of());
        }
        long runId;
        try {
            runId = ready ? conversation.options().readyJobRunId() : jobRuns.dailyRunId();
        } catch (RuntimeException e) {
            // Fail closed: if the spend cannot be booked, nothing is spent.
            LOG.error("[ASK] Could not open the job run; no model call was made: {}", e.getMessage());
            return conversation.fail("the job run could not be opened: " + describe(e), 0, List.of());
        }

        AskRun run;
        try {
            run = converse(conversation, question, snapshot, user, runId, ready);
        } catch (RuntimeException e) {
            LOG.warn("[ASK] Conversation failed unexpectedly: {}", e.toString());
            run = AskRun.failed("unexpected error: " + describe(e), 0, false, List.of());
        }
        if (!ready) {
            jobRuns.recordQuestion(runId, run.outcome().status() != AskOutcome.Status.FAILED);
        }
        return run;
    }

    // -- the loop ---------------------------------------------------------------------------

    /** The windows a Ready conversation's question covers, which its prompt names; none for a typed one. */
    private static List<String> coveredWindows(AskConversation conversation) {
        AskAnswerValidator.ReadyBasis basis = conversation.options().readyBasis();
        // A night question's window is a night, which has no spread to read: it names no covered windows.
        return basis == null || basis.question().nightBound() ? List.of() : basis.offer().coveredWindowIds();
    }

    private AskRun converse(AskConversation conversation, AskQuestion question, AskSnapshot snapshot,
            AskUserContext user, long startRunId, boolean ready) {
        EvaluationModel model = properties.getModel();
        AskTools tools = conversation.tools();
        AskSnapshot.Window contextWindow = question.windowId() == null
                ? null : snapshot.window(question.windowId()).orElse(null);
        AskThreadContext thread = conversation.thread();
        // A night the Map is on (its Ask-local id): only a night the snapshot holds, and an aurora one only for
        // an asker who may see aurora; anything else is no context, as an unresolved solar id is.
        AskSnapshot.Night contextNight = question.windowId() == null ? null
                : snapshot.nights().night(question.windowId())
                        .filter(n -> n.kind() != AskWindowKind.AURORA || user.canSeeAurora()).orElse(null);
        AskPromptBuilder.SystemPrompt system = promptBuilder.systemPromptBlocks(snapshot.today(),
                conversation.scope(), contextWindow, contextNight, snapshot.nights().tonight(), user.hasUser(),
                !thread.isEmpty(), coveredWindows(conversation));
        // Built once: re-joining the thread reads the asker's drive times and, for a place that has left
        // the forecast, one query for names, neither of which should repeat each turn.
        String userMessage = promptBuilder.userMessage(question.sanitised(), thread);
        List<Tool> toolDefinitions = AskToolSchemas.tools(user.hasUser(), !thread.isEmpty(), !ready);
        List<AskTools.ToolCall> trace = new ArrayList<>();
        List<Message> assistantTurns = new ArrayList<>();
        List<List<ContentBlockParam>> toolResults = new ArrayList<>();
        Instant deadline = clock.instant().plusSeconds(properties.getDeadlineSeconds());
        int turns = 0;

        for (int turn = 1; turn <= properties.getMaxTurns(); turn++) {
            // A turn paid for earlier in this very conversation may not have reached the database. Each
            // further turn would be more spend that cannot be recorded, so stop here (the answer a
            // previous turn carried, if any, has already been returned).
            if (turn > 1 && !accountingOpen()) {
                return conversation.fail(AskRun.ACCOUNTING_UNAVAILABLE, turns, trace);
            }
            // The run this turn is billed to: for a typed turn, the daily run as of NOW, so a
            // conversation that crosses UK midnight books its later turns to the new day (the day the
            // spend figure counts them in). Cached, so cheap; if it cannot be resolved nothing is spent.
            long runId;
            try {
                runId = ready ? startRunId : jobRuns.dailyRunId();
            } catch (RuntimeException e) {
                LOG.error("[ASK] Could not resolve the job run before turn {}: {}", turn, e.getMessage());
                return conversation.fail("the job run could not be opened: " + describe(e), turns, trace);
            }
            Duration remaining = Duration.between(clock.instant(), deadline);
            if (remaining.isZero() || remaining.isNegative()) {
                return conversation.fail("the deadline passed before turn " + turn, turns, trace);
            }
            Duration callTimeout = min(Duration.ofSeconds(properties.getCallTimeoutSeconds()), remaining);
            MessageCreateParams params = buildParams(model, system, toolDefinitions, userMessage,
                    assistantTurns, toolResults, turn == properties.getMaxTurns());
            turns = turn;

            Instant started = clock.instant();
            Message response;
            try {
                response = callModel(params, callTimeout, remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logTurn(runId, ready, model, started, null, false, "interrupted", null, null);
                return conversation.fail("interrupted", turns, trace);
            } catch (AnthropicApiClient.CallRefusedException e) {
                // The gate refused the attempt, so no request was made for this turn (a retry's
                // refused second attempt follows a first that returned no usage): nothing to log,
                // and this turn did not happen.
                return conversation.fail(AskRun.ACCOUNTING_UNAVAILABLE, turn - 1, trace);
            } catch (Exception e) {
                Integer status = e instanceof AnthropicServiceException s ? s.statusCode() : null;
                logTurn(runId, ready, model, started, status, false, describe(e), null, null);
                return conversation.fail("the model call failed: " + describe(e), turns, trace);
            }

            TokenUsage usage = response.usage() == null ? null : TokenUsage.from(response.usage());
            String stopProblem = stopProblem(response);
            if (stopProblem != null) {
                logTurn(runId, ready, model, started, HTTP_OK, false, stopProblem, usage, response);
                return conversation.fail(stopProblem, turns, trace);
            }
            List<ToolUseBlock> toolUses = response.content().stream()
                    .filter(ContentBlock::isToolUse).map(ContentBlock::asToolUse).toList();
            if (toolUses.isEmpty()) {
                String reason = "the turn asked for a tool but carried no tool call";
                logTurn(runId, ready, model, started, HTTP_OK, false, reason, usage, response);
                return conversation.fail(reason, turns, trace);
            }
            logTurn(runId, ready, model, started, HTTP_OK, true, null, usage, response);

            List<ContentBlockParam> results = new ArrayList<>();
            for (ToolUseBlock block : toolUses) {
                if (AskToolSchemas.SUBMIT_ANSWER.equals(block.name())) {
                    return submit(block, conversation, turns, trace);
                }
                AskToolResult<?> result = dispatch(block, tools, trace);
                results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                        .toolUseId(block.id()).content(result.content()).isError(result.error())
                        .build()));
            }
            assistantTurns.add(response);
            toolResults.add(results);
        }
        return conversation.fail("no submit_answer within " + properties.getMaxTurns() + " turns", turns,
                trace);
    }

    /**
     * The params of one turn: the rules, the tools, the question, then every earlier turn. Every turn
     * but the last is {@code tool_choice any}. The last permitted turn forces {@code submit_answer}
     * ({@code tool_choice} {@code tool}) and its tool-result message gains {@link #LAST_TURN_LINE}, so
     * a model that spent every earlier turn exploring still ends the conversation with an answer
     * (production, 2026-10-11: four tool turns and no {@code submit_answer} was a 502). With a single
     * permitted turn the first turn is the last: no tool result exists to carry the line, and the
     * forced call alone is the whole request.
     */
    private MessageCreateParams buildParams(EvaluationModel model, AskPromptBuilder.SystemPrompt system,
            List<Tool> tools, String userMessage, List<Message> assistantTurns,
            List<List<ContentBlockParam>> results, boolean lastTurn) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(model.getModelId())
                .maxTokens(ModelRequestSupport.maxTokens(model, properties.getMaxTokens()))
                .systemOfTextBlockParams(List.of(
                        // Tools render before the system prompt, so this breakpoint caches both. The tail
                        // after it changes per question and carries none.
                        TextBlockParam.builder().text(system.staticPart())
                                .cacheControl(CacheControlEphemeral.builder().build()).build(),
                        TextBlockParam.builder().text(system.dynamicPart()).build()))
                .addUserMessage(userMessage);
        if (lastTurn) {
            builder.toolChoice(ToolChoiceTool.builder().name(AskToolSchemas.SUBMIT_ANSWER).build());
        } else {
            builder.toolChoice(ToolChoiceAny.builder().build());
        }
        tools.forEach(builder::addTool);
        ModelRequestSupport.tune(builder, model);
        // The two lists are appended together, one entry per completed turn; the bound is the shorter
        // of them so a mismatch could never read past either.
        int completedTurns = Math.min(assistantTurns.size(), results.size());
        for (int i = 0; i < completedTurns; i++) {
            builder.addMessage(assistantTurns.get(i));
            List<ContentBlockParam> blocks = results.get(i);
            if (lastTurn && i == completedTurns - 1) {
                // After the tool results (a tool_result block must lead its message), in the same turn.
                blocks = new ArrayList<>(blocks);
                blocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text(LAST_TURN_LINE).build()));
            }
            if (i == completedTurns - 1) {
                // The rolling breakpoint: only the latest tool-result message carries one (every request is
                // rebuilt from scratch, so an earlier turn's mark is never carried forward), which with the
                // static system block makes two breakpoints, under the limit of four.
                blocks = withBreakpointOnLast(blocks);
            }
            builder.addUserMessageOfBlockParams(blocks);
        }
        return builder.build();
    }

    /** The blocks with a cache breakpoint on the last one (a tool result or the last-turn text line). */
    private static List<ContentBlockParam> withBreakpointOnLast(List<ContentBlockParam> blocks) {
        List<ContentBlockParam> marked = new ArrayList<>(blocks);
        int last = marked.size() - 1;
        ContentBlockParam block = marked.get(last);
        CacheControlEphemeral control = CacheControlEphemeral.builder().build();
        if (block.isToolResult()) {
            marked.set(last, ContentBlockParam.ofToolResult(
                    block.asToolResult().toBuilder().cacheControl(control).build()));
        } else if (block.isText()) {
            marked.set(last, ContentBlockParam.ofText(block.asText().toBuilder().cacheControl(control).build()));
        }
        return marked;
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
        // The gate is the binding accounting check: made inside the call, after the bulkhead permit and
        // before each attempt (see AnthropicApiClient#createAskMessage).
        Future<Message> call = callExecutor.submit(
                () -> client.createAskMessage(params, requestOptions, jobRuns::accountingAvailable));
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
     * is a turn that did not call a tool. With {@code tool_choice} {@code any} a text-only turn is
     * not reachable except through those stops; the test stays as the defence behind the request
     * (2026-10-10: under {@code auto}, Haiku once answered a drive question with a question of its
     * own and the whole conversation failed).
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
    private AskToolResult<?> dispatch(ToolUseBlock block, AskTools tools, List<AskTools.ToolCall> trace) {
        String name = block.name();
        int before = tools.trace().size();
        AskToolResult<?> result;
        try {
            JsonNode input = toNode(block._input());
            result = switch (name) {
                case AskToolSchemas.LIST_WINDOWS -> tools.listWindows();
                case AskToolSchemas.RANK_SPOTS -> tools.rankSpots(AskToolArguments.rankSpots(input));
                case AskToolSchemas.LOOK_UP_PLACE -> tools.lookUpPlace(AskToolArguments.lookUpPlace(input));
                case AskToolSchemas.RANK_NIGHT_SPOTS ->
                        tools.rankNightSpots(AskToolArguments.rankNightSpots(input));
                case AskToolSchemas.GET_HOT_TOPICS -> tools.getHotTopics(AskToolArguments.hotTopics(input));
                case AskToolSchemas.GET_COMING_UP -> tools.getComingUp(AskToolArguments.comingUp(input));
                default -> AskToolResult.error("Unknown tool '" + clip(name) + "'. The tools are "
                        + AskToolSchemas.LIST_WINDOWS + ", " + AskToolSchemas.RANK_SPOTS + ", "
                        + AskToolSchemas.LOOK_UP_PLACE + ", " + AskToolSchemas.RANK_NIGHT_SPOTS + ", "
                        + AskToolSchemas.GET_HOT_TOPICS + ", "
                        + AskToolSchemas.GET_COMING_UP + " and " + AskToolSchemas.SUBMIT_ANSWER + ".");
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
    private AskRun submit(ToolUseBlock block, AskConversation conversation, int turns,
            List<AskTools.ToolCall> trace) {
        Parsed parsed = AskAnswerParser.parse(toNode(block._input()));
        if (!parsed.ok()) {
            return conversation.rejectSubmission(parsed.error(), turns, trace);
        }
        return conversation.submit(parsed.raw(), turns, trace);
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

    /**
     * Hands one model turn, failed or not, to {@link AskJobRunService#recordTurn}, which never throws:
     * if the row cannot be written it holds the turn and latches the whole Ask feature closed (see
     * {@link #accountingOpen}). The request and response bodies are not stored: the request is the
     * reader's question.
     */
    private void logTurn(long runId, boolean ready, EvaluationModel model, Instant started,
            Integer status, boolean succeeded, String error, TokenUsage usage, Message response) {
        long durationMs = Math.max(0L, Duration.between(started, clock.instant()).toMillis());
        jobRuns.recordTurn(new AskJobRunService.Turn(runId, ready, model, durationMs, status, succeeded, error,
                usage, CacheDiagnostics.from(response)));
    }

    /**
     * Whether every paid model call is on record. Asked before each model turn: while one is not, the
     * engine makes no call at all, for any conversation (see {@link AskJobRunService}).
     */
    private boolean accountingOpen() {
        return jobRuns.accountingAvailable();
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
