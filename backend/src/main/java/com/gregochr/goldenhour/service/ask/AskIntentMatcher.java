package com.gregochr.goldenhour.service.ask;

import java.util.Optional;

/**
 * The Ready intent match (plan §2.5 step 5): a deterministic keyword classifier from a typed question
 * to an <em>available, fresh</em> Ready question of the scope that answers exactly that question
 * (the subject matches and there is no qualifier the Ready answer ignores). A match is answered
 * free as {@code kind: ready}. <b>B5 implements it</b>; until then {@link NoOpAskIntentMatcher} (a
 * {@code @Fallback} bean) is wired, which matches nothing. A real {@code @Component} wins over it.
 */
public interface AskIntentMatcher {

    /**
     * Looks for a Ready question that answers this one.
     *
     * @param question the sanitised question, carrying its scope
     * @param snapshot the live snapshot freshness is judged against
     * @return the fresh Ready question to serve, or empty
     */
    Optional<AskReadyResponse.Question> match(AskQuestion question, AskSnapshot snapshot);
}
