package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository slice test for {@link PipelineRunRepository#findTriggerTimesAfter} — the round-14
 * primitive {@code SupersedingDispositionService} builds on. Runs on H2 (schema generated from
 * entity annotations via {@code ddl-auto: create-drop}, no Flyway, no Docker) per this project's
 * {@code @DataJpaTest} pattern (see {@code ForecastScoreRepositoryTest}).
 */
@DataJpaTest
class PipelineRunRepositoryTest {

    @Autowired
    private PipelineRunRepository repository;

    private static PipelineRunEntity run(CycleType type, Instant triggerTime) {
        return new PipelineRunEntity(type, triggerTime);
    }

    @Test
    @DisplayName("returns every trigger time strictly after the threshold, ascending")
    void returnsLaterTriggerTimesAscending() {
        Instant threshold = Instant.parse("2026-09-29T01:05:00Z");
        Instant intraday = Instant.parse("2026-09-29T14:00:00Z");
        Instant nextNightly = Instant.parse("2026-09-30T01:05:00Z");
        repository.save(run(CycleType.NIGHTLY, threshold));
        repository.save(run(CycleType.INTRADAY, intraday));
        repository.save(run(CycleType.NIGHTLY, nextNightly));

        List<Instant> result = repository.findTriggerTimesAfter(threshold);

        assertThat(result).containsExactly(intraday, nextNightly);
    }

    @Test
    @DisplayName("the threshold instant itself is excluded — strictly after, not at or after")
    void excludesTheThresholdItself() {
        Instant threshold = Instant.parse("2026-09-29T14:00:00Z");
        repository.save(run(CycleType.INTRADAY, threshold));

        List<Instant> result = repository.findTriggerTimesAfter(threshold);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("no pipeline run has been triggered since the threshold: empty list — the common, "
            + "cheap-path case a delayed result almost never lands in")
    void noLaterRun_returnsEmpty() {
        Instant threshold = Instant.parse("2026-09-29T14:00:00Z");
        repository.save(run(CycleType.NIGHTLY, Instant.parse("2026-09-29T01:05:00Z")));

        List<Instant> result = repository.findTriggerTimesAfter(threshold);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("a run's own disposition-only anchor path does not change this: the row in "
            + "pipeline_run exists regardless of whether any forecast_batch row was ever created "
            + "for it — this query never touches forecast_batch at all")
    void answersFromPipelineRunAlone_noBatchRowNeeded() {
        // Simulates pipeline run 249 (2026-09-29, all three Anthropic batch submissions failed):
        // the PipelineRunEntity row exists exactly as it would for any other cycle.
        Instant threshold = Instant.parse("2026-09-29T01:05:00Z");
        Instant anchorRunTrigger = Instant.parse("2026-09-29T14:00:00Z");
        repository.save(run(CycleType.INTRADAY, anchorRunTrigger));

        List<Instant> result = repository.findTriggerTimesAfter(threshold);

        assertThat(result).containsExactly(anchorRunTrigger);
    }
}
