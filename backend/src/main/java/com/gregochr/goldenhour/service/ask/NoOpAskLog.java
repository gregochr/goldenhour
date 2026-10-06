package com.gregochr.goldenhour.service.ask;

import org.springframework.context.annotation.Fallback;
import org.springframework.stereotype.Component;

/**
 * B4's stand-in for the question log: records nothing. A {@link Fallback} bean, so B5's real
 * {@code @Component} implementing {@link AskLog} is chosen over it with nothing to delete here.
 */
@Component
@Fallback
public class NoOpAskLog implements AskLog {

    /**
     * Records nothing.
     *
     * @param entry the row (unused)
     */
    @Override
    public void record(Entry entry) {
        // Deliberately nothing: no log until B5.
    }
}
