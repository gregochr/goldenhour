package com.gregochr.goldenhour.service.ask;

import org.springframework.context.annotation.Fallback;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Optional;

/**
 * B4's stand-in for the Ready intent match: matches nothing. A {@link Fallback} bean, so B5's real
 * {@code @Component} implementing {@link AskIntentMatcher} is chosen over it with nothing to delete
 * here.
 */
@Component
@Fallback
public class NoOpAskIntentMatcher implements AskIntentMatcher {

    /**
     * Matches nothing.
     *
     * @param question   the sanitised question (unused)
     * @param snapshot   the live snapshot (unused)
     * @param scopeKey   the scope key (unused)
     * @param scopeNames the scope's region names (unused)
     * @return always empty
     */
    @Override
    public Optional<AskReadyResponse.Question> match(AskQuestion question, AskSnapshot snapshot,
            String scopeKey, Collection<String> scopeNames) {
        return Optional.empty();
    }
}
