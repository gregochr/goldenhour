package com.gregochr.goldenhour.service.ask;

import org.springframework.context.annotation.Fallback;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * B4's stand-in for the typed-answer cache: always a miss, stores nothing. A {@link Fallback} bean, so
 * B5's real {@code @Component} implementing {@link AskAnswerCache} is chosen over it with nothing to
 * delete here.
 */
@Component
@Fallback
public class NoOpAskAnswerCache implements AskAnswerCache {

    /**
     * Never hits.
     *
     * @param question the sanitised question (unused)
     * @param snapshot the live snapshot (unused)
     * @param user     the asker (unused)
     * @return always empty
     */
    @Override
    public Optional<AskAnswer> lookup(AskQuestion question, AskSnapshot snapshot, AskUserContext user) {
        return Optional.empty();
    }

    /**
     * Stores nothing.
     *
     * @param question the sanitised question (unused)
     * @param snapshot the snapshot (unused)
     * @param user     the asker (unused)
     * @param outcome  the outcome (unused)
     */
    @Override
    public void store(AskQuestion question, AskSnapshot snapshot, AskUserContext user,
            AskOutcome outcome) {
        // Deliberately nothing: no cache until B5.
    }
}
