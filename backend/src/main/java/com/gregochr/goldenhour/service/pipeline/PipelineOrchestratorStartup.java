package com.gregochr.goldenhour.service.pipeline;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
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
 */
@Component
@Profile("!integration-test")
public class PipelineOrchestratorStartup {

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
    public void onApplicationReady() {
        pipelineOrchestrator.resumeRunningCyclesOnStartup();
    }
}
