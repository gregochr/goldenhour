package com.gregochr.goldenhour.model;

import org.springframework.context.ApplicationEvent;

/**
 * Published by {@link com.gregochr.goldenhour.service.BriefingService} after a successful
 * briefing refresh. Listeners (e.g. {@code BriefingEvaluationService}) use this to
 * invalidate stale caches without introducing a circular dependency.
 */
public class BriefingRefreshedEvent extends ApplicationEvent {

    /**
     * Constructs a new briefing-refreshed event.
     *
     * @param source the object that published the event
     */
    public BriefingRefreshedEvent(Object source) {
        super(source);
    }
}
