package com.gregochr.goldenhour.service.ask;

import java.util.Optional;

/**
 * The can't-answer pre-filter (plan §2.5 step 3): whole-word phrases (car park, crowds, opening
 * times…) the forecast can never answer, refused before anything is spent. <b>B5 implements it</b>;
 * until then {@link NoOpAskPreFilter} (a {@code @Fallback} bean) is wired, which never refuses, so
 * every question goes on to the later steps. A real {@code @Component} implementing this interface
 * wins over it.
 */
public interface AskPreFilter {

    /**
     * Decides whether a question is one the forecast can never answer.
     *
     * @param question the sanitised question
     * @return the {@code cant} answer to give (answerable false, {@code missing} set, no picks), or
     *         empty to carry on
     */
    Optional<AskAnswer> refuse(AskQuestion question);
}
