package com.gregochr.goldenhour.service.ask;

import java.util.Collection;
import java.util.Optional;

/**
 * The Ready intent match (plan §2.5 step 5): a deterministic keyword classifier from a typed question
 * to an <em>available, fresh</em> Ready question of the scope that answers exactly that question
 * (the subject matches and there is no qualifier the Ready answer ignores). A match is answered
 * free as {@code kind: ready}. <b>B5 implements it</b>; until then {@link #NEVER_MATCHES} is wired.
 */
public interface AskIntentMatcher {

    /** The B4 default: matches nothing. */
    AskIntentMatcher NEVER_MATCHES = (question, snapshot, scopeKey, scopeNames) -> Optional.empty();

    /**
     * Looks for a Ready question that answers this one.
     *
     * @param question   the sanitised question
     * @param snapshot   the live snapshot freshness is judged against
     * @param scopeKey   {@code ALL} or the single region's id as text
     * @param scopeNames the scope's region names; empty for every region
     * @return the fresh Ready question to serve, or empty
     */
    Optional<AskReadyResponse.Question> match(AskQuestion question, AskSnapshot snapshot,
            String scopeKey, Collection<String> scopeNames);
}
