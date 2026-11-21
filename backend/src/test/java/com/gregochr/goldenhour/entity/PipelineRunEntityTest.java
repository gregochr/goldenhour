package com.gregochr.goldenhour.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * POJO-level coverage for {@link PipelineRunEntity}.
 *
 * <p>The service layer covers the lifecycle methods this entity participates in
 * (start/complete/fail). These tests exercise the remaining accessors and the
 * package-private JPA lifecycle hooks ({@code @PrePersist} / {@code @PreUpdate})
 * so the per-class JaCoCo coverage gate (80%) is met.
 */
class PipelineRunEntityTest {

    private static final Instant T0 = Instant.parse("2026-05-26T01:00:00Z");

    @Test
    @DisplayName("convenience constructor wires cycle type, trigger time, RUNNING status")
    void convenience_constructor_sets_fields() {
        PipelineRunEntity run = new PipelineRunEntity(CycleType.NIGHTLY, T0);

        assertThat(run.getCycleType()).isEqualTo(CycleType.NIGHTLY);
        assertThat(run.getTriggerTime()).isEqualTo(T0);
        assertThat(run.getStatus()).isEqualTo(PipelineRunStatus.RUNNING);
        assertThat(run.getId()).isNull();
        assertThat(run.getCurrentPhase()).isNull();
        assertThat(run.getWaitingOn()).isNull();
        assertThat(run.getCompletedAt()).isNull();
        assertThat(run.getFailureReason()).isNull();
    }

    @Test
    @DisplayName("setters round-trip every mutable field")
    void setters_round_trip() {
        PipelineRunEntity run = new PipelineRunEntity();
        run.setId(42L);
        run.setCycleType(CycleType.INTRADAY);
        run.setStatus(PipelineRunStatus.COMPLETED);
        run.setCurrentPhase(PipelinePhase.BRIEFING);
        run.setWaitingOn("waiting on something");
        run.setTriggerTime(T0);
        run.setCompletedAt(T0.plusSeconds(900));
        run.setFailureReason("safety timeout");

        assertThat(run.getId()).isEqualTo(42L);
        assertThat(run.getCycleType()).isEqualTo(CycleType.INTRADAY);
        assertThat(run.getStatus()).isEqualTo(PipelineRunStatus.COMPLETED);
        assertThat(run.getCurrentPhase()).isEqualTo(PipelinePhase.BRIEFING);
        assertThat(run.getWaitingOn()).isEqualTo("waiting on something");
        assertThat(run.getTriggerTime()).isEqualTo(T0);
        assertThat(run.getCompletedAt()).isEqualTo(T0.plusSeconds(900));
        assertThat(run.getFailureReason()).isEqualTo("safety timeout");
    }

    @Test
    @DisplayName("@PrePersist hook initialises createdAt and updatedAt if null")
    void prePersist_initialises_timestamps() {
        PipelineRunEntity run = new PipelineRunEntity(CycleType.NIGHTLY, T0);
        assertThat(run.getCreatedAt()).isNull();
        assertThat(run.getUpdatedAt()).isNull();

        run.onCreate();

        assertThat(run.getCreatedAt()).isNotNull();
        assertThat(run.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("@PrePersist preserves an existing createdAt — re-attach safe")
    void prePersist_preserves_existing_createdAt() {
        PipelineRunEntity run = new PipelineRunEntity(CycleType.NIGHTLY, T0);
        run.onCreate();
        Instant firstCreated = run.getCreatedAt();
        Instant firstUpdated = run.getUpdatedAt();

        run.onCreate();

        assertThat(run.getCreatedAt()).isEqualTo(firstCreated);
        // updatedAt is also already set, so the no-op branch should leave it alone.
        assertThat(run.getUpdatedAt()).isEqualTo(firstUpdated);
    }

    @Test
    @DisplayName("@PreUpdate hook refreshes updatedAt")
    void preUpdate_refreshes_updatedAt() throws InterruptedException {
        PipelineRunEntity run = new PipelineRunEntity(CycleType.NIGHTLY, T0);
        run.onCreate();
        Instant initialUpdated = run.getUpdatedAt();
        // Sleep 2ms so the new updatedAt is observably different.
        Thread.sleep(2);

        run.onUpdate();

        assertThat(run.getUpdatedAt()).isAfter(initialUpdated);
    }
}
