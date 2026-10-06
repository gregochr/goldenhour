package com.gregochr.goldenhour.service.ask;

import org.springframework.context.annotation.Fallback;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * B4's stand-in for the can't-answer pre-filter: never refuses. A {@link Fallback} bean, so B5's real
 * {@code @Component} implementing {@link AskPreFilter} is chosen over it with nothing to delete here.
 */
@Component
@Fallback
public class NoOpAskPreFilter implements AskPreFilter {

    /**
     * Refuses nothing.
     *
     * @param question the sanitised question (unused)
     * @return always empty
     */
    @Override
    public Optional<AskAnswer> refuse(AskQuestion question) {
        return Optional.empty();
    }
}
