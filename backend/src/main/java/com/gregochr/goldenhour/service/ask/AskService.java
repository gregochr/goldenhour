package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.AppUserEntity;
import com.gregochr.goldenhour.repository.AppUserRepository;
import com.gregochr.goldenhour.repository.RegionRepository;
import com.gregochr.goldenhour.service.DriveTimeResolver;
import com.gregochr.goldenhour.util.ForecastHorizon;
import com.gregochr.goldenhour.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * {@code POST /api/ask}: a reader's typed question (plan §2.5), run through the guards <b>in the
 * plan's order, cheapest first</b>, so nothing expensive happens before a cheaper guard has had its
 * say:
 * <ol>
 *   <li><b>Rate limit</b> ({@link AskRateLimiter}, 5 a minute per user, applied by {@link #admit}):
 *       before the request body is converted, so a body that cannot be read is counted too, and
 *       before the snapshot is built. 429 {@code RATE_LIMITED}.</li>
 *   <li><b>Sanitise and validate</b> ({@link AskQuestionSanitiser#sanitiseTyped}, the view, the
 *       regions). 400 {@code INVALID}.</li>
 *   <li>Pre-filter ({@link AskPreFilter}): a can't-answer phrase. Free; runs before the Ready match,
 *       so "is the car park busy this weekend" is a can't-answer, never a Ready answer.</li>
 *   <li><b>Snapshot</b> ({@link AskSnapshotBuilder#current()}, memoised): <em>first built here</em>.
 *       None built yet is 503 {@code TYPED_UNAVAILABLE}, which the client shows as "Ready questions
 *       only today" — honest, since with no briefing nothing can be answered (a 409, as the admin
 *       dry-run answers, has no row in the plan's error table). A {@code windowId} outside the window
 *       set is ignored.</li>
 *   <li>Ready intent match ({@link AskIntentMatcher}). Free.</li>
 *   <li>Typed cache ({@link AskAnswerCache}). Free.</li>
 *   <li><b>Spend cap and accounting latch</b> ({@link AskSpendGuard}). 503 {@code TYPED_UNAVAILABLE},
 *       before anything is reserved.</li>
 *   <li><b>Daily ceilings</b> ({@link AskUsageStore#reserve}): one atomic reservation of the allowance
 *       and the never-refunded engine-call ceiling. 429 {@code ALLOWANCE_EXHAUSTED} or
 *       {@code DAILY_LIMIT}.</li>
 *   <li><b>Engine.</b> An answer is charged; an honest "not in the forecast" and a failure are
 *       refunded ({@code used} only, on the date reserved, never below zero) and
 *       {@code engine_calls} never is. A failure is 502 {@code ENGINE_FAILED}, or 503
 *       {@code TYPED_UNAVAILABLE} when the engine stopped because accounting is unavailable.</li>
 *   <li><b>Respond</b> ({@link AskResponse}), offer the cache the answer and log it.</li>
 * </ol>
 *
 * <p><b>A follow-up</b> (a request carrying a {@code thread}, plan {@code ask-thread-plan.md}) runs the same
 * guards with three differences. The thread is validated with step 2 ({@link AskThreadValidation}). Once
 * the snapshot is built it is reconciled with the live forecast: a typed exchange answered against another
 * briefing run drops the whole thread and the answer is fresh, marked {@code threadReset}. And a
 * follow-up that still has a thread <b>skips steps 5 and 6</b>: a question that leans on earlier answers
 * is never a catalogue question, and its cache key would have to include the whole thread, so it is
 * neither matched to a Ready answer nor read from or written to the typed cache. It is a typed question
 * for allowance, spend and engine-call purposes.
 *
 * <p>Steps 5 and 6 sit <em>before</em> the spend cap and the reservation on purpose: a Ready match
 * or a cache hit costs nothing, so it is served even when typed questions are switched off for the
 * day. Every refusal is an {@link AskRefusal}, which the web layer renders as
 * {@code {"error","code"}}.
 *
 * <p><b>The cap and the reservation are separate steps</b>, so the cap can be crossed between them.
 * The cap is therefore asked again after the reservation and just before the engine runs (a refusal
 * there refunds the allowance), and what is left is documented on {@link AskSpendGuard}: a soft
 * ceiling, overshoot bounded by the rate limit, the allowance, the bulkhead and the engine's own
 * per-call accounting latch.
 *
 * <p><b>A crash between reserving and refunding loses the question</b>: the safe direction.
 */
@Service
public class AskService {

    private static final Logger LOG = LoggerFactory.getLogger(AskService.class);

    /** The question after step 2: sanitised, scoped and carrying its validated thread. */
    private record Validated(AskQuestion question, AskThread thread) {
    }

    /**
     * What a request's thread did to its answer: how many earlier exchanges the engine saw (after any
     * reset) and whether the thread was dropped because the forecast had moved.
     */
    private record Carry(int threadSize, boolean reset) {

        AskResponse apply(AskResponse response) {
            return reset ? response.withThreadReset() : response;
        }
    }

    /** How many other Ready questions a can't-answer suggests. */
    static final int TRY_COUNT = 2;

    private final AskProperties properties;
    private final AskRateLimiter rateLimiter;
    private final AppUserRepository userRepository;
    private final RegionRepository regionRepository;
    private final AskSnapshotBuilder snapshotBuilder;
    private final AskEngine engine;
    private final AskUsageStore usageStore;
    private final AskSpendGuard spendGuard;
    private final AskReadyServing readyServing;
    private final DriveTimeResolver driveTimeResolver;
    private final AskPreFilter preFilter;
    private final AskIntentMatcher intentMatcher;
    private final AskAnswerCache cache;
    private final AskLog askLog;
    private final AskDenialCounter denials;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param properties        the Ask settings (limits, ceiling)
     * @param rateLimiter       the per-user sliding-window limit
     * @param userRepository    resolves the asker from the authentication
     * @param regionRepository  validates and resolves the question's regions
     * @param snapshotBuilder   builds the snapshot the engine reads
     * @param engine            the one active engine: the stub or Claude
     * @param usageStore        the per-day counters
     * @param spendGuard        the spend cap and the accounting latch
     * @param readyServing      chooses the {@code try} suggestions
     * @param driveTimeResolver whether the asker has stored drive times
     * @param preFilter         the can't-answer pre-filter (step 3)
     * @param intentMatcher     the Ready intent match (step 5)
     * @param cache             the typed-answer cache (step 6)
     * @param askLog            the question log (step 10)
     * @param denials           counts denied requests, for the hourly INFO line
     * @param clock             the application clock (the UK civil day); a POST sees the real clock
     */
    public AskService(AskProperties properties, AskRateLimiter rateLimiter,
            AppUserRepository userRepository, RegionRepository regionRepository,
            AskSnapshotBuilder snapshotBuilder, AskEngine engine, AskUsageStore usageStore,
            AskSpendGuard spendGuard, AskReadyServing readyServing,
            DriveTimeResolver driveTimeResolver, AskPreFilter preFilter,
            AskIntentMatcher intentMatcher, AskAnswerCache cache, AskLog askLog,
            AskDenialCounter denials, Clock clock) {
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.userRepository = userRepository;
        this.regionRepository = regionRepository;
        this.snapshotBuilder = snapshotBuilder;
        this.engine = engine;
        this.usageStore = usageStore;
        this.spendGuard = spendGuard;
        this.readyServing = readyServing;
        this.driveTimeResolver = driveTimeResolver;
        this.preFilter = preFilter;
        this.intentMatcher = intentMatcher;
        this.cache = cache;
        this.askLog = askLog;
        this.denials = denials;
        this.clock = clock;
    }

    /**
     * Step 1, the rate limit, and the only place a request is counted against it: resolves the asker
     * (one indexed read) and takes one slot of their sliding window.
     *
     * <p>Called by {@code AskAdmissionInterceptor} <b>before the request body is converted</b>, so a
     * body that cannot be read, is mistyped or is oversized is counted too and cannot be used to get
     * past the limit. The interceptor hands the admitted user to the controller on a request
     * attribute, and the controller calls this itself only when that attribute is absent (the
     * interceptor did not run), so a request is counted exactly once either way.
     * {@link #ask(AppUserEntity, AskRequest)} deliberately does <em>not</em> count: it takes the
     * admitted user, so there is no way to count twice.
     *
     * @param auth the asker
     * @return the asker, now counted against the limit
     * @throws AskRefusal {@code UNAUTHENTICATED} when the token names a user that no longer exists
     *                    (nothing is counted); {@code RATE_LIMITED} when the window is full
     */
    public AppUserEntity admit(Authentication auth) {
        AppUserEntity user = resolveUser(auth);
        if (!rateLimiter.tryAcquire(user.getId())) {
            LOG.debug("[ASK] User {} is over the rate limit", user.getId());
            throw denied(user.getId(), new AskRefusal(AskErrorCode.RATE_LIMITED));
        }
        return user;
    }

    /**
     * Answers a typed question for an asker already admitted by {@link #admit}.
     *
     * @param user    the admitted asker
     * @param request the request body
     * @return the answer
     * @throws AskRefusal for every way a question is turned away or fails (see the class
     *                    documentation); never for a missing flag, which the controller answers first
     */
    public AskResponse ask(AppUserEntity user, AskRequest request) {
        long startedAt = System.nanoTime();
        long userId = user.getId();

        // 1. The rate limit was applied by admit(), before the body was converted.
        // 2. Sanitise and validate.
        Validated validated;
        try {
            validated = validate(request);
        } catch (AskRefusal refusal) {
            throw denied(userId, refusal);
        }
        AskQuestion question = validated.question();

        // 3. Can't-answer pre-filter.
        Optional<AskAnswer> refused = preFilter.refuse(question);
        if (refused.isPresent()) {
            // Before the snapshot exists the thread cannot yet be reconciled: the size is what was sent.
            return cant(userId, user, refused.get(), question, startedAt, AskLog.Outcome.PREFILTER_CANT,
                    snapshotBuilder.current().orElse(null), new Carry(validated.thread().size(), false));
        }

        // 4. Snapshot — the first place it is built.
        AskSnapshot snapshot = snapshotBuilder.current()
                .orElseThrow(() -> denied(userId, new AskRefusal(AskErrorCode.TYPED_UNAVAILABLE)));
        question = withWindow(question, snapshot);
        // The reset rule (plan §2.2): a typed exchange answered against another run ends the thread.
        boolean reset = validated.thread().staleAgainst(snapshot.generatedAt());
        AskThread thread = reset ? AskThread.EMPTY : validated.thread();
        Carry carry = new Carry(thread.size(), reset);
        AskUserContext context = new AskUserContext(userId, user.getRole(),
                driveTimeResolver.hasDriveTimes(userId));
        LocalDate day = ForecastHorizon.today(clock);
        int limit = properties.limitFor(user.getRole());

        // 5. Ready intent match: free. Skipped for a follow-up, which is never a catalogue question.
        Optional<AskReadyResponse.Question> ready = thread.isEmpty()
                ? intentMatcher.match(question, snapshot) : Optional.empty();
        if (ready.isPresent()) {
            AskReadyResponse.Answer answer = ready.get().answer();
            AskResponse response = new AskResponse(true, AskResponse.KIND_READY, answer.summary(),
                    answer.picks(), answer.events(), null, answer.tryThese(), left(userId, day, limit),
                    limit, false, ready.get().generatedAt(), ready.get().runLabel());
            log(userId, question, AskLog.Outcome.READY_MATCH, null, startedAt, carry);
            return carry.apply(response);
        }

        // 6. Typed cache: free. Neither read nor written for a follow-up (its key would need the thread).
        Optional<AskAnswer> hit = thread.isEmpty() ? cache.lookup(question, snapshot, context)
                : Optional.empty();
        if (hit.isPresent()) {
            AskResponse response = own(hit.get(), snapshot, left(userId, day, limit), limit, false);
            log(userId, question, AskLog.Outcome.CACHE_HIT, null, startedAt, carry);
            return carry.apply(response);
        }

        // 7. Spend cap and the accounting latch — before anything is reserved.
        if (spendGuard.refuse()) {
            throw denied(userId, new AskRefusal(AskErrorCode.TYPED_UNAVAILABLE));
        }

        // 8. Daily ceilings: one atomic reservation of the allowance and the engine-call ceiling.
        reserve(userId, day, limit, properties.engineCeilingFor(user.getRole()));

        // The cap and the reservation are separate steps: ask the cap again at the last moment.
        if (spendGuard.refuse()) {
            usageStore.refund(userId, day);
            throw denied(userId, new AskRefusal(AskErrorCode.TYPED_UNAVAILABLE));
        }

        // 9. Engine.
        AskRun run = runEngine(question, snapshot, context, thread);
        AskOutcome outcome = run.outcome();
        if (outcome.status() == AskOutcome.Status.FAILED || outcome.answer() == null) {
            usageStore.refund(userId, day);
            log(userId, question, AskLog.Outcome.CLAUDE_FAILED, null, startedAt, carry);
            throw new AskRefusal(run.accountingUnavailable() ? AskErrorCode.TYPED_UNAVAILABLE
                    : AskErrorCode.ENGINE_FAILED);
        }
        if (outcome.status() == AskOutcome.Status.CANT) {
            usageStore.refund(userId, day);
            return cant(userId, user, outcome.answer(), question, startedAt, AskLog.Outcome.CLAUDE_CANT,
                    snapshot, carry);
        }

        // 10. Respond, cache (a fresh question only), log.
        if (thread.isEmpty()) {
            offerToCache(question, snapshot, context, outcome);
        }
        AskResponse response = own(outcome.answer(), snapshot, left(userId, day, limit), limit, true);
        log(userId, question, AskLog.Outcome.CLAUDE_OK, outcome.answer().missing(), startedAt, carry);
        return carry.apply(response);
    }

    /**
     * The asker's allowance today, for {@code GET /api/user/settings/ask}. Always answers (an
     * unresolvable user is the only refusal): with Ask off it is {@code enabled: false} and zeros.
     *
     * @param auth the asker
     * @return the settings
     * @throws AskRefusal {@code UNAUTHENTICATED} when the token names a user that no longer exists
     */
    public AskSettingsResponse settings(Authentication auth) {
        if (!properties.isEnabled()) {
            return AskSettingsResponse.off();
        }
        AppUserEntity user = resolveUser(auth);
        int limit = properties.limitFor(user.getRole());
        int used = usageStore.read(user.getId(), ForecastHorizon.today(clock)).used();
        return new AskSettingsResponse(true, used, limit, Math.max(0, limit - used),
                spendGuard.typedAvailable());
    }

    // -- steps --------------------------------------------------------------------------------

    private AppUserEntity resolveUser(Authentication auth) {
        if (auth == null || auth.getName() == null) {
            throw new AskRefusal(AskErrorCode.UNAUTHENTICATED);
        }
        return userRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new AskRefusal(AskErrorCode.UNAUTHENTICATED));
    }

    /**
     * Step 2: the sanitised question, the view, the thread and the regions, or an {@code INVALID}
     * refusal. The only place the region ids are resolved: the question carries the scope from here on.
     */
    private Validated validate(AskRequest request) {
        if (request == null) {
            throw invalid("A request body is required.");
        }
        if (request.view() == null || !AskRequest.VIEWS.contains(request.view())) {
            throw invalid("The view must be one of map, plan or coming-up.");
        }
        AskQuestionSanitiser.Result cleaned = AskQuestionSanitiser.sanitiseTyped(request.question());
        if (!cleaned.ok()) {
            throw invalid(cleaned.error());
        }
        AskThread thread = AskThreadValidation.validate(request.thread(),
                properties.getThread().getMaxExchanges());
        AskScope scope = AskScopes.resolve(regionRepository, request.regionIds())
                .orElseThrow(() -> invalid(AskScopes.INVALID_REGIONS));
        return new Validated(AskQuestion.of(cleaned, blankToNull(request.windowId()), scope, request.view()),
                thread);
    }

    /**
     * Counts a refusal as a denied request (no {@code ask_log} row, an hourly INFO line instead) and
     * returns it to be thrown. The counter never throws into the request.
     */
    private AskRefusal denied(long userId, AskRefusal refusal) {
        try {
            denials.record(userId, refusal.code());
        } catch (RuntimeException e) {
            LOG.warn("[ASK] A denied request could not be counted: {}", e.toString());
        }
        return refusal;
    }

    private static AskRefusal invalid(String message) {
        return new AskRefusal(AskErrorCode.INVALID, message);
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    /** A window that is not in the window set is ignored: the question is asked without one. */
    private static AskQuestion withWindow(AskQuestion question, AskSnapshot snapshot) {
        String windowId = question.windowId() != null && snapshot.window(question.windowId()).isPresent()
                ? question.windowId() : null;
        return new AskQuestion(question.sanitised(), question.normalised(), windowId,
                question.scope(), question.view());
    }

    private void reserve(long userId, LocalDate day, int limit, int ceiling) {
        AskUsageStore.Reservation reservation;
        try {
            reservation = usageStore.reserve(userId, day, limit, ceiling);
        } catch (RuntimeException e) {
            // Nothing was reserved, so nothing is spent: fail closed.
            LOG.error("[ASK] Could not reserve a question for user {}: {}", userId, e.toString());
            throw denied(userId, new AskRefusal(AskErrorCode.TYPED_UNAVAILABLE));
        }
        switch (reservation) {
            case RESERVED -> { }
            case ALLOWANCE_EXHAUSTED -> throw denied(userId,
                    new AskRefusal(AskErrorCode.ALLOWANCE_EXHAUSTED));
            case DAILY_LIMIT -> {
                LOG.warn("[ASK] User {} reached the daily engine-call ceiling {}", userId, ceiling);
                throw denied(userId, new AskRefusal(AskErrorCode.DAILY_LIMIT));
            }
            default -> throw new IllegalStateException("Unhandled reservation " + reservation);
        }
    }

    /**
     * Runs the engine. It never throws for a model failure; anything it does throw is a FAILED run.
     * A FAILED run's reason is logged here, once, at INFO: it reaches no reader (the response is the
     * fixed {@code ENGINE_FAILED}/{@code TYPED_UNAVAILABLE} sentence), so this line is the only trace
     * of why a typed question failed. The reason is the engine's own text (a fixed sentence, a window
     * id, a count or a library exception's message), never the question, and is passed through
     * {@link LogSanitizer} regardless.
     */
    private AskRun runEngine(AskQuestion question, AskSnapshot snapshot, AskUserContext context,
            AskThread thread) {
        AskRun run;
        try {
            run = engine.run(question, snapshot, context, AskRunOptions.typed(thread));
        } catch (RuntimeException e) {
            LOG.error("[ASK] The engine threw for user {}: {}", context.userId(), e.toString());
            run = AskRun.failed("the engine threw: " + e.getClass().getSimpleName(), 0, false, List.of());
        }
        if (run.outcome().status() == AskOutcome.Status.FAILED) {
            LOG.info("[ASK] A typed question for user {} failed after {} turn(s) thread={}: {}",
                    context.userId(), run.outcome().turns(), thread.size(),
                    LogSanitizer.sanitize(run.reason()));
        }
        return run;
    }

    private void offerToCache(AskQuestion question, AskSnapshot snapshot, AskUserContext context,
            AskOutcome outcome) {
        try {
            cache.store(question, snapshot, context, outcome);
        } catch (RuntimeException e) {
            LOG.warn("[ASK] The answer could not be cached: {}", e.toString());
        }
    }

    // -- responses ----------------------------------------------------------------------------

    private AskResponse own(AskAnswer answer, AskSnapshot snapshot, Integer left, int limit,
            boolean charged) {
        return new AskResponse(true, AskResponse.KIND_OWN, answer.summary(),
                answer.picks().stream().map(AskReadyResponse.Pick::of).toList(), answer.events(),
                null, List.of(), left, limit, charged, snapshot.generatedAt(), snapshot.runLabel());
    }

    /**
     * A can't-answer: {@code answerable: false}, no picks or events, {@code missing} set, up to two
     * other fresh Ready questions to try, never charged. The allowance shown is the asker's after
     * any refund.
     */
    private AskResponse cant(long userId, AppUserEntity user, AskAnswer answer, AskQuestion question,
            long startedAt, AskLog.Outcome outcome, AskSnapshot snapshot, Carry carry) {
        LocalDate day = ForecastHorizon.today(clock);
        int limit = properties.limitFor(user.getRole());
        List<AskReadyResponse.Suggestion> suggestions = snapshot == null ? List.of()
                : readyServing.suggestions(question.scope().readyScope(), snapshot, TRY_COUNT);
        LocalDateTime generatedAt = snapshot == null ? null : snapshot.generatedAt();
        String runLabel = snapshot == null ? null : snapshot.runLabel();
        AskResponse response = new AskResponse(false, AskResponse.KIND_CANT, answer.summary(), List.of(),
                List.of(), answer.missing(), suggestions, left(userId, day, limit), limit, false,
                generatedAt, runLabel);
        log(userId, question, outcome, answer.missing(), startedAt, carry);
        return carry.apply(response);
    }

    /**
     * {@code limit - used} for today; never throws and never negative, and {@code null} when the read
     * fails. A null is NOT a count of zero: the client applies a served figure as the server's word and
     * does not re-read after it, but treats a missing one as "unknown" and re-reads the settings, so a
     * transient database failure cannot turn off typed questions.
     */
    private Integer left(long userId, LocalDate day, int limit) {
        try {
            return Math.max(0, limit - usageStore.read(userId, day).used());
        } catch (RuntimeException e) {
            // The answer is already paid for; do not lose it over a count. The response carries no
            // figure and the client re-reads the settings, which show the true one.
            LOG.warn("[ASK] Could not read user {}'s usage for the response: {}", userId, e.toString());
            return null;
        }
    }

    private void log(long userId, AskQuestion question, AskLog.Outcome outcome, String missing,
            long startedAt, Carry carry) {
        if (carry.threadSize() > 0 || carry.reset()) {
            // A follow-up only: a fresh question logs nothing new. Ints and an enum, never the thread's
            // text, so nothing a reader typed reaches this line.
            LOG.info("[ASK] User {} answered a follow-up: outcome={} thread={} reset={}", userId, outcome,
                    carry.threadSize(), carry.reset());
        }
        try {
            // The Ready scope's key: a single region's id, otherwise ALL, as the log has always held.
            askLog.record(new AskLog.Entry(userId, question.scope().readyScope().key(), question.view(),
                    outcome,
                    question.normalised(), missing, (System.nanoTime() - startedAt) / 1_000_000L));
        } catch (RuntimeException e) {
            LOG.warn("[ASK] The question could not be logged: {}", e.toString());
        }
    }
}
