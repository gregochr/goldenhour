package com.gregochr.goldenhour.service.pipeline;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.EventListener;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Pins that the startup resume is skipped under the {@code integration-test} profile.
 *
 * <p>{@code @Profile} on an {@code @EventListener} method is a silent no-op, so these tests
 * publish a real {@link ApplicationReadyEvent} into a context and observe whether the
 * orchestrator was reached, rather than reading annotations.
 */
class PipelineOrchestratorStartupTest {

    @Test
    void applicationReady_underIntegrationTestProfile_doesNotResumeAnything() {
        PipelineOrchestrator orchestrator = mock(PipelineOrchestrator.class);

        try (AnnotationConfigApplicationContext context = contextWith("integration-test", orchestrator)) {
            publishReady(context);
        }

        verify(orchestrator, never()).resumeRunningCyclesOnStartup();
    }

    @Test
    void applicationReady_underAnyOtherProfile_resumesExactlyOnce() {
        PipelineOrchestrator orchestrator = mock(PipelineOrchestrator.class);

        try (AnnotationConfigApplicationContext context = contextWith("prod", orchestrator)) {
            publishReady(context);
        }

        verify(orchestrator, times(1)).resumeRunningCyclesOnStartup();
    }

    @Test
    void orchestrator_declaresNoEventListener_soNoProfileGuardCanBeBypassed() {
        boolean anyListener = Arrays.stream(PipelineOrchestrator.class.getDeclaredMethods())
                .map(Method::getAnnotations)
                .flatMap(Arrays::stream)
                .anyMatch(a -> a.annotationType() == EventListener.class);

        assertThat(anyListener)
                .as("an @EventListener on PipelineOrchestrator runs under every profile, "
                        + "because @Profile is ignored on a method")
                .isFalse();
    }

    private static AnnotationConfigApplicationContext contextWith(String profile,
            PipelineOrchestrator orchestrator) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles(profile);
        context.registerBean(PipelineOrchestrator.class, () -> orchestrator);
        context.register(PipelineOrchestratorStartup.class);
        context.refresh();
        return context;
    }

    private static void publishReady(AnnotationConfigApplicationContext context) {
        context.publishEvent(new ApplicationReadyEvent(
                new SpringApplication(), new String[0], context, Duration.ZERO));
    }
}
