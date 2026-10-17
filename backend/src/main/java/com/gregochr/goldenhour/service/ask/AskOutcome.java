package com.gregochr.goldenhour.service.ask;

import java.time.LocalDate;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/**
 * The result of one engine run.
 *
 * @param status      how it ended
 * @param answer      the validated answer; null unless {@code status} is OK or CANT
 * @param personal    true when the conversation used the asker's own drive times, so the answer must
 *                    not be shared
 * @param turns       how many model turns it took
 * @param uncacheable true when the conversation called {@code rank_night_spots}, whichever branch ran:
 *                    a night answer depends on the asker's role (aurora figures) and on facts that move
 *                    under the cache's TTL (the aurora alert state, the astro refresh), so it is never
 *                    stored in the typed cache
 * @param nights      the dates of the nights the conversation consulted (the tool resolved them), which
 *                    version the answer ({@link AskNightVersion}); empty for a solar answer
 */
public record AskOutcome(Status status, AskAnswer answer, boolean personal, int turns,
        boolean uncacheable, Set<LocalDate> nights) {

    /** Canonical constructor: an immutable, date-ordered copy of the nights; null reads as none. */
    public AskOutcome {
        nights = nights == null ? Set.of() : Collections.unmodifiableSet(new TreeSet<>(nights));
    }

    /**
     * An outcome of a conversation that consulted no night.
     *
     * @param status   how it ended
     * @param answer   the validated answer; null unless {@code status} is OK or CANT
     * @param personal true when the conversation used the asker's own drive times
     * @param turns    how many model turns it took
     */
    public AskOutcome(Status status, AskAnswer answer, boolean personal, int turns) {
        this(status, answer, personal, turns, false, Set.of());
    }

    /** How an engine run ended. */
    public enum Status {
        /** A validated, answerable reply. */
        OK,
        /** The forecast cannot answer the question. */
        CANT,
        /** The run failed (timeout, refusal, no submitted answer, a discarded answer). */
        FAILED
    }
}
