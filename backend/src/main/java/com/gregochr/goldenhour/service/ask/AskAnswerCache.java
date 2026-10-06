package com.gregochr.goldenhour.service.ask;

import java.util.Optional;

/**
 * The typed-answer cache (plan §2.5 step 6, D-6): a shared answer to a question already paid for,
 * served free and re-checked for freshness against live data on every hit. The key (scope, UK date,
 * briefing build, normalised question, window, and the user only for a personal answer) is B5's to
 * define. <b>B5 implements it</b>; until then {@link #DISABLED} is wired: always a miss, stores nothing.
 */
public interface AskAnswerCache {

    /** The B4 default: never hits and never stores. */
    AskAnswerCache DISABLED = new AskAnswerCache() {
        @Override
        public Optional<AskAnswer> lookup(AskQuestion question, AskSnapshot snapshot,
                AskUserContext user) {
            return Optional.empty();
        }

        @Override
        public void store(AskQuestion question, AskSnapshot snapshot, AskUserContext user,
                AskOutcome outcome) {
            // Deliberately nothing: no cache until B5.
        }
    };

    /**
     * Looks for a cached answer that is still true.
     *
     * @param question the sanitised question
     * @param snapshot the live snapshot freshness is judged against
     * @param user     the asker (a personal answer is cached per user)
     * @return the answer, re-decorated and fresh, or empty for a miss
     */
    Optional<AskAnswer> lookup(AskQuestion question, AskSnapshot snapshot, AskUserContext user);

    /**
     * Offers a freshly paid-for answer to the cache. Called for an {@code OK} outcome only.
     *
     * @param question the sanitised question
     * @param snapshot the snapshot the answer was built from
     * @param user     the asker
     * @param outcome  the engine's outcome; {@code personal} says whether it may be shared
     */
    void store(AskQuestion question, AskSnapshot snapshot, AskUserContext user, AskOutcome outcome);
}
