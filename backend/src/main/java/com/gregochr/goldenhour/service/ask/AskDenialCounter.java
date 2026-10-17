package com.gregochr.goldenhour.service.ask;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Counts the typed requests that were <b>denied</b> (plan §2.5): rate limited, allowance used up,
 * daily engine ceiling reached, typed questions switched off, or a question that was not acceptable.
 * Denied requests write no {@code ask_log} row — a looping client must not be able to write millions
 * — so this is how the owner sees them: one INFO line per user per hour, naming the user and the
 * counts by reason, never the question.
 *
 * <p><b>In memory and bounded.</b> A user holds at most one open hour's counts. A user's line is
 * written when their next denial arrives in a later hour, or when a sweep (every
 * {@value #SWEEP_EVERY} denials, by anyone) finds their hour over, and their entry is removed at that
 * point, so the map holds only users denied in the current hour plus whatever has not been swept
 * since. Restart forgets the open hour (D-8: single instance), and a user denied once and never
 * again is reported at the next sweep, which needs no further request from them. Not counted:
 * {@code UNAUTHENTICATED} (not a reader's denial) and {@code ENGINE_FAILED} (a failure, which has its
 * own {@code CLAUDE_FAILED} log row).
 */
@Component
public class AskDenialCounter {

    private static final Logger LOG = LoggerFactory.getLogger(AskDenialCounter.class);

    /** The window each line covers, in seconds. */
    static final long HOUR_SECONDS = 3_600L;

    /** The map is swept for finished hours once every this many denials. */
    static final int SWEEP_EVERY = 64;

    /**
     * One user's denials in one clock hour.
     *
     * @param userId    the user
     * @param hourStart the start of the hour the counts cover
     * @param counts    the number of denials by reason
     */
    record Report(long userId, Instant hourStart, Map<AskErrorCode, Integer> counts) {

        /**
         * The total denials in the report.
         *
         * @return the sum of the counts
         */
        int total() {
            return counts.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    /** The open hour of one user. Only touched inside {@code ConcurrentHashMap.compute}. */
    private static final class Bucket {
        private final long hour;
        private final Map<AskErrorCode, Integer> counts = new EnumMap<>(AskErrorCode.class);

        Bucket(long hour) {
            this.hour = hour;
        }

        Report report(long userId) {
            return new Report(userId, Instant.ofEpochSecond(hour * HOUR_SECONDS),
                    Collections.unmodifiableMap(new EnumMap<>(counts)));
        }
    }

    private final Clock clock;
    private final Consumer<Report> sink;
    private final ConcurrentHashMap<Long, Bucket> open = new ConcurrentHashMap<>();
    private final AtomicInteger denials = new AtomicInteger();

    /**
     * Creates the counter, reporting through the INFO log.
     *
     * @param clock the application clock
     */
    @Autowired
    public AskDenialCounter(Clock clock) {
        this(clock, AskDenialCounter::log);
    }

    /**
     * Creates the counter with its own report sink (tests).
     *
     * @param clock the clock
     * @param sink  receives each finished hour
     */
    AskDenialCounter(Clock clock, Consumer<Report> sink) {
        this.clock = clock;
        this.sink = sink;
    }

    /**
     * Records one denial.
     *
     * @param userId the user denied
     * @param code   why
     */
    public void record(long userId, AskErrorCode code) {
        if (code == AskErrorCode.UNAUTHENTICATED || code == AskErrorCode.ENGINE_FAILED) {
            return;
        }
        long hour = Math.floorDiv(clock.instant().getEpochSecond(), HOUR_SECONDS);
        AtomicReference<Report> finished = new AtomicReference<>();
        open.compute(userId, (id, bucket) -> {
            Bucket current = bucket;
            if (current == null || current.hour != hour) {
                if (current != null) {
                    finished.set(current.report(id));
                }
                current = new Bucket(hour);
            }
            current.counts.merge(code, 1, Integer::sum);
            return current;
        });
        emit(finished.get());
        if (denials.incrementAndGet() % SWEEP_EVERY == 0) {
            sweep(hour);
        }
    }

    /**
     * How many users have an open hour held.
     *
     * @return the number of tracked users
     */
    int trackedUsers() {
        return open.size();
    }

    /** Reports and removes every user whose hour is over. */
    private void sweep(long hour) {
        for (Long userId : open.keySet()) {
            AtomicReference<Report> finished = new AtomicReference<>();
            open.computeIfPresent(userId, (id, bucket) -> {
                if (bucket.hour < hour) {
                    finished.set(bucket.report(id));
                    return null;
                }
                return bucket;
            });
            emit(finished.get());
        }
    }

    private void emit(Report report) {
        if (report != null) {
            sink.accept(report);
        }
    }

    private static void log(Report report) {
        LOG.info("[ASK] User {} had {} request(s) denied in the hour from {}: {}", report.userId(),
                report.total(), report.hourStart(), report.counts());
    }
}
