package com.gregochr.goldenhour.util;

import java.time.Duration;
import java.time.Instant;

/**
 * The per-request "rewind" instant: an admin's request to have the app render as it would have at
 * an earlier moment, so a sunrise that has already passed reads as still ahead.
 *
 * <p>The instant lives in a {@link ThreadLocal} that {@code RewindFilter} sets for the span of one
 * admin GET and clears in a {@code finally}. {@code RewindAwareClock} — the {@link java.time.Clock}
 * bean every serve-time "now" is read from — answers with it while it is set. Nothing else reads
 * it directly, so the rewind reaches every serve-path clock read through the one seam and no
 * scheduled job, batch poller or pipeline thread can ever see one: those threads never pass
 * through the filter.
 *
 * <p>This is deliberately a holder, not request-scoped state on the clock bean itself: the bean is
 * a singleton shared by request and background threads alike, and a field on it would rewind the
 * pipeline.
 */
public final class Rewind {

    /**
     * The furthest back a rewind may go: the forecast serve window plus a day. {@code RewindFilter}
     * refuses an older instant, and {@code RewindEventService} serves it beside the events so the
     * admin screen's own bound on a hand-typed moment is this one, never a copy of it.
     */
    public static final Duration MAX_AGE = Duration.ofDays(ForecastHorizon.SERVE_PAST_DAYS + 1L);

    private static final ThreadLocal<Instant> CURRENT = new ThreadLocal<>();

    private Rewind() {
    }

    /**
     * Sets the rewind instant for the current thread. Callers must {@link #clear()} in a
     * {@code finally} block — a thread that keeps a rewind past its request would serve it to
     * the next request that thread handles.
     *
     * @param instant the moment the app should render as of; null clears
     */
    public static void set(Instant instant) {
        if (instant == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(instant);
        }
    }

    /**
     * The rewind instant for the current thread, or null when none is set.
     *
     * @return the rewind instant, or null
     */
    public static Instant current() {
        return CURRENT.get();
    }

    /**
     * Whether a rewind is set on the current thread.
     *
     * @return true when {@link #current()} is non-null
     */
    public static boolean isActive() {
        return CURRENT.get() != null;
    }

    /**
     * Clears the rewind instant for the current thread.
     */
    public static void clear() {
        CURRENT.remove();
    }
}
