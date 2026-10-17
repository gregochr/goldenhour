package com.gregochr.goldenhour.service.pipeline;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Resumes interrupted pipeline cycles, and sweeps unsettled location failures, on application
 * startup outside the {@code integration-test} profile.
 *
 * <p>The profile guard lives on this class because Spring evaluates {@code @Profile} (a
 * {@code @Conditional}) only when registering bean definitions. Placed on an
 * {@code @EventListener} method it is silently ignored, which is how
 * {@link PipelineOrchestrator#resumeRunningCyclesOnStartup()} came to run under every profile.
 * This is the same split {@code DynamicSchedulerBootstrap} makes for the scheduler.
 *
 * <p><b>It must run before the scheduler bootstrap.</b> The recovery scan treats every RUNNING
 * run it finds as left behind by the previous process. Once the scheduler is up it can start a
 * run of its own at once (a solar job's catch-up run), and a scan that came after would mistake
 * that live run for a dead one: failing it in a pre-submission phase, or starting a second
 * wait/brief tail beside the one it already has. Both listeners take {@link ApplicationReadyEvent}
 * and Spring calls them in {@link Order} order on the publishing thread, so {@link #ORDER} being
 * lower than {@code DynamicSchedulerBootstrap.ORDER} finishes the scan (it is synchronous; only the
 * resumed tails are handed to the background executor) before any schedule exists.
 */
@Component
@Profile("!integration-test")
public class PipelineOrchestratorStartup {

    /** Listener order: lower than {@code DynamicSchedulerBootstrap.ORDER}, so recovery runs first. */
    public static final int ORDER = 0;

    private final PipelineOrchestrator pipelineOrchestrator;

    /**
     * Constructs the startup hook.
     *
     * @param pipelineOrchestrator the orchestrator whose interrupted cycles are resumed
     */
    public PipelineOrchestratorStartup(PipelineOrchestrator pipelineOrchestrator) {
        this.pipelineOrchestrator = pipelineOrchestrator;
    }

    /**
     * Resumes any RUNNING pipeline cycles and sweeps unsettled location failures once the
     * application context is ready.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(ORDER)
    public void onApplicationReady() {
        pipelineOrchestrator.resumeRunningCyclesOnStartup();
    }
}
