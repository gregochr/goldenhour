package com.gregochr.goldenhour.service;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Triggers {@link DynamicSchedulerService#initSchedules()} on application startup
 * outside the {@code integration-test} profile.
 *
 * <p>Splitting the bootstrap from the service lets integration tests opt out of cron
 * activation by activating the {@code integration-test} profile, while leaving
 * {@link DynamicSchedulerService#registerJobTarget(String, java.lang.Runnable)} available
 * to all profiles. Without this split, suppressing the listener would have required
 * either a runtime profile check inside the listener (less idiomatic) or removing the
 * scheduler bean entirely (which would break every service that registers a job target).
 */
@Component
@Profile("!integration-test")
public class DynamicSchedulerBootstrap {

    private final DynamicSchedulerService schedulerService;

    /**
     * Constructs the bootstrap.
     *
     * @param schedulerService the dynamic scheduler service whose {@code initSchedules()}
     *                         method this bootstrap invokes on {@link ApplicationReadyEvent}
     */
    public DynamicSchedulerBootstrap(DynamicSchedulerService schedulerService) {
        this.schedulerService = schedulerService;
    }

    /**
     * Initialises all schedules once the application context is ready.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        schedulerService.initSchedules();
    }
}
