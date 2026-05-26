package com.gregochr.goldenhour.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * POJO-level coverage for {@link PipelineRunPhaseEntity}. The service layer
 * exercises the persistence flow; these tests cover the accessor surface that
 * the service does not touch directly, so the per-class JaCoCo gate (80%)
 * is met.
 */
class PipelineRunPhaseEntityTest {

    private static final Instant T0 = Instant.parse("2026-05-26T01:00:00Z");

    @Test
    @DisplayName("convenience constructor wires every supplied field + defaults status to RUNNING")
    void convenience_constructor_sets_fields() {
        PipelineRunPhaseEntity phase = new PipelineRunPhaseEntity(
                42L, PipelinePhase.FORECAST_BATCH_WAIT, 2, T0);

        assertThat(phase.getPipelineRunId()).isEqualTo(42L);
        assertThat(phase.getPhase()).isEqualTo(PipelinePhase.FORECAST_BATCH_WAIT);
        assertThat(phase.getSequenceOrder()).isEqualTo(2);
        assertThat(phase.getStartedAt()).isEqualTo(T0);
        assertThat(phase.getStatus()).isEqualTo(PipelinePhaseStatus.RUNNING);
        assertThat(phase.getCompletedAt()).isNull();
        assertThat(phase.getDetail()).isNull();
    }

    @Test
    @DisplayName("setters round-trip every mutable field")
    void setters_round_trip() {
        PipelineRunPhaseEntity phase = new PipelineRunPhaseEntity();
        phase.setId(7L);
        phase.setPipelineRunId(42L);
        phase.setPhase(PipelinePhase.BRIEFING);
        phase.setSequenceOrder(3);
        phase.setStatus(PipelinePhaseStatus.FAILED);
        phase.setStartedAt(T0);
        phase.setCompletedAt(T0.plusSeconds(120));
        phase.setDetail("briefing crashed");

        assertThat(phase.getId()).isEqualTo(7L);
        assertThat(phase.getPipelineRunId()).isEqualTo(42L);
        assertThat(phase.getPhase()).isEqualTo(PipelinePhase.BRIEFING);
        assertThat(phase.getSequenceOrder()).isEqualTo(3);
        assertThat(phase.getStatus()).isEqualTo(PipelinePhaseStatus.FAILED);
        assertThat(phase.getStartedAt()).isEqualTo(T0);
        assertThat(phase.getCompletedAt()).isEqualTo(T0.plusSeconds(120));
        assertThat(phase.getDetail()).isEqualTo("briefing crashed");
    }
}
