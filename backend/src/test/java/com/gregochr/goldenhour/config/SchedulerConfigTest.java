package com.gregochr.goldenhour.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

/**
 * Pins the two halves of the dynamic scheduler's shutdown contract: a pending cron firing must not
 * hold the JVM open, while a job already running still gets its grace period.
 *
 * <p>The first half is what every CI fork, and every production restart, paid 30 s for until
 * 2026-10-06 — see {@code SchedulerConfig.AWAIT_TERMINATION_SECONDS}'s javadoc.
 */
class SchedulerConfigTest {

    /** Far below the 30 s await: a shutdown that waited for the pending cron would blow through it. */
    private static final Duration PROMPT_SHUTDOWN = Duration.ofSeconds(5);

    private ThreadPoolTaskScheduler scheduler;

    @BeforeEach
    void buildBeanAsSpringWould() {
        scheduler = new SchedulerConfig().dynamicTaskScheduler();
        scheduler.afterPropertiesSet();
    }

    @AfterEach
    void stop() {
        scheduler.shutdown();
    }

    @Test
    @DisplayName("the executor drops pending delayed tasks at shutdown")
    void pendingDelayedTasksAreNotExecutedAfterShutdown() {
        assertThat(scheduler.getScheduledThreadPoolExecutor()
                .getExecuteExistingDelayedTasksAfterShutdownPolicy())
                .as("Spring's default keeps the JDK policy (true), which is the 30 s hang")
                .isFalse();
    }

    @Test
    @DisplayName("a cron job pending hours away does not delay shutdown")
    void shutdownReturnsPromptlyWithACronJobPending() {
        AtomicBoolean fired = new AtomicBoolean(false);
        scheduler.schedule(() -> fired.set(true), new CronTrigger("0 0 4 * * *"));

        Instant before = Instant.now();
        scheduler.shutdown();
        Duration took = Duration.between(before, Instant.now());

        assertThat(took).isLessThan(PROMPT_SHUTDOWN);
        assertThat(fired).as("the pending firing is dropped, not run early").isFalse();
        assertThat(scheduler.getScheduledThreadPoolExecutor().isTerminated()).isTrue();
    }

    @Test
    @DisplayName("a job that is already running is still waited for")
    void shutdownWaitsForARunningJob() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean(false);
        scheduler.schedule(() -> {
            started.countDown();
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            finished.set(true);
        }, Instant.now());
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        scheduler.shutdown();

        assertThat(finished).as("waitForTasksToCompleteOnShutdown still holds for a running job")
                .isTrue();
        assertThat(scheduler.getScheduledThreadPoolExecutor().isTerminated()).isTrue();
    }
}
