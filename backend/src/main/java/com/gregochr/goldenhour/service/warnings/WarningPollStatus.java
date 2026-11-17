package com.gregochr.goldenhour.service.warnings;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory record of the warnings poll for Operations and for {@code stale}: when it last tried, when it last
 * succeeded, what the last attempt did and why it last failed. It resets on a restart, so until the first poll
 * after startup the feed reads as never checked, which is the honest answer. Unknown weather types are counted
 * cumulatively since startup.
 */
@Component
public class WarningPollStatus {

    /**
     * What an attempt did.
     *
     * @param at               when it started
     * @param succeeded        whether it finished without a failure reason
     * @param failureReason    NOT_CONFIGURED, UNAUTHORISED, RATE_LIMITED, UNREACHABLE, MALFORMED_FEED or HTTP_n
     * @param feedEntries      entries seen in the feed
     * @param stored           new versions stored
     * @param alreadyStored    entries already held
     * @param goneSkipped      details that answered 404 or 410
     * @param malformedSkipped entries or details that could not be read
     */
    public record Attempt(Instant at, boolean succeeded, String failureReason, int feedEntries, int stored,
            int alreadyStored, int goneSkipped, int malformedSkipped) {
    }

    private final AtomicReference<Attempt> lastAttempt = new AtomicReference<>();
    private final AtomicReference<Instant> lastSuccess = new AtomicReference<>();
    private final Map<String, Integer> unknownTypes = new ConcurrentHashMap<>();

    /**
     * Records a finished attempt.
     *
     * @param attempt what it did
     */
    public void record(Attempt attempt) {
        lastAttempt.set(attempt);
        if (attempt.succeeded()) {
            lastSuccess.set(attempt.at());
        }
    }

    /**
     * Counts one occurrence of a weather type the app has no glyph for.
     *
     * @param weatherType the type as received
     */
    public void countUnknownType(String weatherType) {
        unknownTypes.merge(weatherType, 1, Integer::sum);
    }

    /**
     * The last attempt.
     *
     * @return the attempt, or null before the first poll since startup
     */
    public Attempt lastAttempt() {
        return lastAttempt.get();
    }

    /**
     * The last successful poll.
     *
     * @return when it finished, or null if none since startup
     */
    public Instant lastSuccessAt() {
        return lastSuccess.get();
    }

    /**
     * Unknown types seen since startup, with counts, in name order.
     *
     * @return a copy of the counts
     */
    public Map<String, Integer> unknownTypeCounts() {
        return new TreeMap<>(unknownTypes);
    }
}
