package com.gregochr.goldenhour.config;

import com.anthropic.errors.AnthropicServiceException;

import java.util.function.Predicate;

/**
 * Tells the {@code anthropic} circuit breaker which failures are NOT evidence that Claude is down, so
 * they count neither as a failure nor as a success.
 *
 * <p>Today that is one thing: a rejected API key (HTTP 401 or 403). A rejected key is a configuration
 * fault, not an outage. Every further call fails the same way until someone replaces the key, so a
 * breaker that counted the rejections would open after the first wave of calls and then answer the
 * next minute's calls with "paused after repeated failures" — hiding the real reason (the key) and
 * making a run started after the key was fixed wait for the pause to lapse. The run itself already
 * stops on a rejected key (see {@code EvaluationFailure#stopsRun}).
 *
 * <p><b>The trade-off.</b> A rejected key no longer opens the breaker, so anything derived from the
 * breaker's state no longer reflects it: the status page's overall DEGRADED/DOWN comes from the actuator's
 * circuit-breaker health, and its Claude entry is the {@code claudeApi} probe, which deliberately counts
 * ANY HTTP answer (401 and 403 included) as reachable, so a revoked key shows Claude as UP there. A rejected key is
 * visible where it is actually reported: a hand-started run stops with "Claude rejected the API key."
 * (and logs an ERROR), and a failed call's {@code api_call_log} row carries the status and an
 * {@code anthropic_401} / {@code anthropic_403} error type, which the Job Runs detail shows.
 * Another consequence: callers with no stop-on-rejected-key rule of their own (the briefing's gloss and
 * best-bet calls) used to be cut off by the open breaker after about ten calls and now attempt every call.
 *
 * <p>Wired as the breaker's {@code ignoreException} predicate by
 * {@link ResilienceConfig#anthropicCircuitBreakerCustomizer()}. Every other exception keeps counting
 * as a failure, exactly as before: 400, 404, 429, 5xx, 529 and the SDK's I/O failures.
 */
public class ClaudeBreakerIgnorePredicate implements Predicate<Throwable> {

    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_FORBIDDEN = 403;

    @Override
    public boolean test(Throwable throwable) {
        return throwable instanceof AnthropicServiceException ex && isKeyRejection(ex.statusCode());
    }

    /**
     * Whether an HTTP status from Anthropic means the API key was rejected. Shared with the failure
     * classifier so the breaker and the run's "stop on a rejected key" rule can never disagree about
     * what a rejected key is.
     *
     * @param status the HTTP status of an Anthropic error
     * @return {@code true} for 401 (invalid or revoked key) and 403 (key not permitted)
     */
    public static boolean isKeyRejection(int status) {
        return status == HTTP_UNAUTHORIZED || status == HTTP_FORBIDDEN;
    }
}
