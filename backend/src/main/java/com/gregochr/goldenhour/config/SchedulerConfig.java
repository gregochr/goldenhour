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

    /** Maximum seconds to wait for running tasks on shutdown. */
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
        return scheduler;
    }
}
