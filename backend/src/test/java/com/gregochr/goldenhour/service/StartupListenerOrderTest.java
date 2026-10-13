package com.gregochr.goldenhour.service;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import com.gregochr.goldenhour.service.pipeline.PipelineOrchestrator;
import com.gregochr.goldenhour.service.pipeline.PipelineOrchestratorStartup;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * The pipeline's startup recovery scan must finish before the scheduler starts: a solar job's
 * catch-up can launch a run straight away, and a scan that came after it would take that live run
 * for one the previous process left behind.
 */
class StartupListenerOrderTest {

    @Test
    @DisplayName("recovery runs before the scheduler on ApplicationReadyEvent, whatever the bean order")
    void recoveryRunsBeforeTheScheduler() {
        PipelineOrchestrator orchestrator = mock(PipelineOrchestrator.class);
        DynamicSchedulerService scheduler = mock(DynamicSchedulerService.class);

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            // Registered scheduler-first, so only the listeners' @Order can put recovery first.
            context.registerBean(DynamicSchedulerBootstrap.class,
                    () -> new DynamicSchedulerBootstrap(scheduler));
            context.registerBean(PipelineOrchestratorStartup.class,
                    () -> new PipelineOrchestratorStartup(orchestrator));
            context.refresh();

            context.publishEvent(new ApplicationReadyEvent(mock(SpringApplication.class), new String[0],
                    context, Duration.ZERO));
        }

        InOrder order = inOrder(orchestrator, scheduler);
        order.verify(orchestrator).resumeRunningCyclesOnStartup();
        order.verify(scheduler).initSchedules();
    }
}
