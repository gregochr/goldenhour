package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.repository.AskUsageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * The per-user, per-UK-day counters behind the typed endpoint's two ceilings (plan §2.5 step 8, §1
 * #20): {@code used} (questions charged, refundable) and {@code engine_calls} (engine runs, never
 * refunded).
 *
 * <p><b>Reserve is one statement.</b> {@link #reserve} makes sure the day's row exists (a find-or-insert
 * that survives losing a race to another request's insert), then runs ONE conditional update that
 * takes both counters together — {@code used < limit AND engine_calls < ceiling} — so the check and
 * the increment cannot be separated however many requests are in flight. When that update takes
 * nothing, the row is read again to say <em>which</em> ceiling stopped it.
 *
 * <p><b>Refund targets the date it reserved on</b> (the caller passes it back), is guarded by
 * {@code used > 0} so it can never go negative, and never touches {@code engine_calls}.
 */
@Service
public class AskUsageStore {

    private static final Logger LOG = LoggerFactory.getLogger(AskUsageStore.class);

    /** How often a reservation is re-asked when a refund moves the counters under it. */
    private static final int MAX_ATTEMPTS = 3;

    private final AskUsageRepository repository;

    /**
     * Creates the store.
     *
     * @param repository the {@code ask_usage} table
     */
    public AskUsageStore(AskUsageRepository repository) {
        this.repository = repository;
    }

    /** What stopped a reservation, or that none did. */
    public enum Reservation {
        /** Both counters were taken. */
        RESERVED,
        /** The day's allowance of charged questions is used up. */
        ALLOWANCE_EXHAUSTED,
        /** The day's ceiling on engine runs is reached (the allowance is not: refunds made it free). */
        DAILY_LIMIT
    }

    /**
     * A user's counters on one day.
     *
     * @param used        typed questions charged
     * @param engineCalls engine runs
     */
    public record Usage(int used, int engineCalls) {

        /** No usage: the user has not asked that day. */
        public static final Usage NONE = new Usage(0, 0);
    }

    /**
     * Reserves one typed question for a user on a UK day.
     *
     * @param userId  the user
     * @param date    the UK civil date being reserved on; hand the same date back to {@link #refund}
     * @param limit   the user's allowance of charged questions a day
     * @param ceiling the user's ceiling on engine runs a day
     * @return {@code RESERVED}, or the ceiling that stopped it: the allowance first when both are
     *         reached, since that is the one the reader can see
     * @throws RuntimeException when the database cannot be read or written, or the row will not settle
     *         (nothing is reserved)
     */
    public Reservation reserve(long userId, LocalDate date, int limit, int ceiling) {
        ensureRow(userId, date);
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            if (repository.reserve(userId, date, limit, ceiling) == 1) {
                return Reservation.RESERVED;
            }
            Usage now = read(userId, date);
            if (now.used() >= limit) {
                return Reservation.ALLOWANCE_EXHAUSTED;
            }
            if (now.engineCalls() >= ceiling) {
                return Reservation.DAILY_LIMIT;
            }
            // The update refused, yet neither counter is at its limit now: a refund landed between
            // the two statements. The state has moved, so ask again rather than name the wrong ceiling.
        }
        throw new IllegalStateException("Could not settle a reservation for user " + userId + " on " + date);
    }

    /**
     * Gives back one charged question on the date it was reserved on. {@code engine_calls} is never
     * touched. Never throws: a refund that fails is logged at ERROR and the question stays charged
     * (the safe direction), because the caller is already answering a reader.
     *
     * @param userId the user
     * @param date   the date {@link #reserve} reserved on, not today's date
     * @return true when a question was given back; false when the counter was already zero or the
     *         refund failed
     */
    public boolean refund(long userId, LocalDate date) {
        try {
            return repository.refund(userId, date) == 1;
        } catch (RuntimeException e) {
            LOG.error("[ASK] Could not refund a question for user {} on {}: {}", userId, date,
                    e.toString());
            return false;
        }
    }

    /**
     * A user's counters on a day.
     *
     * @param userId the user
     * @param date   the UK civil date
     * @return the counters; {@link Usage#NONE} when the user has not asked that day
     */
    public Usage read(long userId, LocalDate date) {
        return repository.findCountsByUserIdAndUsageDate(userId, date)
                .map(c -> new Usage(c.getUsed(), c.getEngineCalls()))
                .orElse(Usage.NONE);
    }

    /**
     * Makes sure the day's row exists. A plain insert when it does not: the loser of two concurrent
     * first questions gets a unique violation, which is fine — the row exists now. A violation after
     * which the row still does not exist is something else (the user is gone, the database is down)
     * and is rethrown.
     */
    private void ensureRow(long userId, LocalDate date) {
        if (repository.existsByUserIdAndUsageDate(userId, date)) {
            return;
        }
        try {
            repository.insertRow(userId, date);
        } catch (DataIntegrityViolationException e) {
            if (!repository.existsByUserIdAndUsageDate(userId, date)) {
                throw e;
            }
            LOG.debug("[ASK] Lost the race to create user {}'s usage row for {}; using theirs", userId,
                    date);
        }
    }
}
