package com.gregochr.goldenhour.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Provides a dedicated {@link ThreadPoolTaskScheduler} for dynamically managed jobs.
 *
 * <p>Named {@code dynamicTaskScheduler} to avoid overriding Spring's default
 * {@code taskScheduler} bean used by {@code @Scheduled} annotations.
 */
@Configuration
public class SchedulerConfig {

    /** Pool size — one thread per scheduled job is sufficient. */
    private static final int POOL_SIZE = 5;

    /**
     * Maximum seconds to wait, on shutdown, for a job that is RUNNING to finish.
     *
     * <p>Only a running job may use this budget. Until 2026-10-06 the wait also covered every
     * cron job merely <em>pending</em> on the pool: Spring's {@link ThreadPoolTaskScheduler} keeps
     * the JDK default of executing already-scheduled delayed tasks after {@code shutdown()}, and a
     * cron trigger is a delayed one-shot task (its next firing), so with
     * {@code waitForTasksToCompleteOnShutdown} the await had nothing to do but run out the full
     * 30 s for a job due hours later. Every production shutdown paid that wait (and Docker's 10 s
     * stop budget killed the JVM mid-shutdown first), and every CI fork whose test context had
     * scheduled a cron hung 30 s at exit until Surefire killed it. The bean below drops pending
     * delayed tasks at shutdown so this budget means what its name says.
     */
    private static final int AWAIT_TERMINATION_SECONDS = 30;

    /**
     * Creates the thread pool task scheduler used by {@code DynamicSchedulerService}.
     *
     * @return a configured task scheduler
     */
    @Bean
    public ThreadPoolTaskScheduler dynamicTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(POOL_SIZE);
        scheduler.setThreadNamePrefix("photocast-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(AWAIT_TERMINATION_SECONDS);
        // A pending cron firing is a delayed task; it must not hold the JVM open at shutdown. A job
        // already running still gets AWAIT_TERMINATION_SECONDS to finish (see that constant's javadoc).
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return scheduler;
    }
}
