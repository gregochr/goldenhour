package com.gregochr.goldenhour.service.batch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CycleDispositionJobRuns}: the in-memory cycle-to-anchor-run link.
 */
class CycleDispositionJobRunsTest {

    @Test
    @DisplayName("a remembered cycle returns its job run, and an unknown cycle returns empty")
    void remembersAndReturns() {
        CycleDispositionJobRuns links = new CycleDispositionJobRuns();

        links.remember(99L, 555L);

        assertThat(links.jobRunFor(99L)).contains(555L);
        assertThat(links.jobRunFor(100L)).isEmpty();
    }

    @Test
    @DisplayName("remembering a cycle again replaces its job run")
    void rememberingAgainReplaces() {
        CycleDispositionJobRuns links = new CycleDispositionJobRuns();
        links.remember(99L, 555L);

        links.remember(99L, 556L);

        assertThat(links.jobRunFor(99L)).contains(556L);
    }

    @Test
    @DisplayName("only the last 200 cycles are remembered: the 201st evicts the oldest")
    void boundedToTheLast200Cycles() {
        CycleDispositionJobRuns links = new CycleDispositionJobRuns();
        for (long pipelineRunId = 1; pipelineRunId <= 201; pipelineRunId++) {
            links.remember(pipelineRunId, 1000 + pipelineRunId);
        }

        assertThat(links.jobRunFor(1L)).isEmpty();
        assertThat(links.jobRunFor(2L)).contains(1002L);
        assertThat(links.jobRunFor(201L)).contains(1201L);
    }

    @Test
    @DisplayName("a new instance, which is what a restart gives, remembers nothing")
    void newInstanceRemembersNothing() {
        new CycleDispositionJobRuns().remember(99L, 555L);

        assertThat(new CycleDispositionJobRuns().jobRunFor(99L)).isEmpty();
    }
}
