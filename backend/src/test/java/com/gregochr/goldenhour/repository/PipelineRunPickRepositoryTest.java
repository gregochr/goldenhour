package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.PipelineRunPickEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Repository slice tests for {@link PipelineRunPickRepository#findFreshFallbackCandidates},
 * the freshness-bounded query behind the fail-safe best-bet fallback, and for
 * {@link PipelineRunPickRepository#findByPipelineRunIdAndPickRank} / the V148
 * {@code (pipeline_run_id, pick_rank)} unique constraint that backs
 * {@code PipelineRunPickService}'s upsert. Runs against H2's entity-annotation-generated
 * schema, so the constraint asserted here is the one declared on
 * {@code PipelineRunPickEntity}'s {@code @Table} — kept in sync with V148 by the
 * integration-test Flyway schema in CI, same pattern as {@code ForecastScoreRepositoryTest}.
 */
@DataJpaTest
class PipelineRunPickRepositoryTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 13);
    private static final Instant NOW = Instant.parse("2026-06-13T09:00:00Z");

    @Autowired
    private PipelineRunPickRepository repository;

    private PipelineRunPickEntity pick(long runId, int rank, LocalDate eventDate, Instant recordedAt) {
        PipelineRunPickEntity e = new PipelineRunPickEntity();
        e.setPipelineRunId(runId);
        e.setPickRank(rank);
        e.setHeadline("h" + runId + "-" + rank);
        e.setEventId(eventDate + "_sunset");
        e.setEventDate(eventDate);
        e.setEventType("sunset");
        e.setRegion("Northumberland");
        e.setConfidence("HIGH");
        e.setRecordedAt(recordedAt);
        return e;
    }

    @Test
    @DisplayName("Excludes picks whose event date is before today (already passed)")
    void excludesPassedEvents() {
        Instant recent = NOW.minusSeconds(3600);
        repository.save(pick(1L, 1, TODAY.minusDays(1), recent));   // yesterday — passed
        repository.save(pick(2L, 1, TODAY, recent));                // today — kept
        repository.save(pick(3L, 1, TODAY.plusDays(2), recent));    // future — kept

        List<PipelineRunPickEntity> result = repository.findFreshFallbackCandidates(
                TODAY, NOW.minusSeconds(30 * 3600));

        assertThat(result).extracting(PipelineRunPickEntity::getEventDate)
                .containsExactlyInAnyOrder(TODAY, TODAY.plusDays(2));
    }

    @Test
    @DisplayName("Excludes picks recorded before the age-ceiling cutoff")
    void excludesTooOld() {
        Instant cutoff = NOW.minusSeconds(30 * 3600);
        repository.save(pick(1L, 1, TODAY.plusDays(1), cutoff.minusSeconds(60)));  // too old
        repository.save(pick(2L, 1, TODAY.plusDays(1), cutoff.plusSeconds(60)));   // fresh

        List<PipelineRunPickEntity> result = repository.findFreshFallbackCandidates(TODAY, cutoff);

        assertThat(result).extracting(PipelineRunPickEntity::getPipelineRunId)
                .containsExactly(2L);
    }

    @Test
    @DisplayName("Orders newest-recorded first then by rank, so the latest run leads")
    void ordersNewestFirstThenRank() {
        Instant older = NOW.minusSeconds(7200);
        Instant newer = NOW.minusSeconds(1800);
        repository.save(pick(1L, 1, TODAY.plusDays(1), older));
        repository.save(pick(2L, 2, TODAY.plusDays(1), newer));
        repository.save(pick(2L, 1, TODAY.plusDays(1), newer));

        List<PipelineRunPickEntity> result = repository.findFreshFallbackCandidates(
                TODAY, NOW.minusSeconds(30 * 3600));

        // Run 2 (newer) first, rank 1 before rank 2; run 1 (older) last.
        assertThat(result).extracting(PipelineRunPickEntity::getPipelineRunId)
                .containsExactly(2L, 2L, 1L);
        assertThat(result.get(0).getPickRank()).isEqualTo(1);
        assertThat(result.get(1).getPickRank()).isEqualTo(2);
    }

    @Test
    @DisplayName("findByPipelineRunIdAndPickRank finds the existing row for that exact "
            + "(run, rank) pair and nothing else")
    void findByPipelineRunIdAndPickRank_findsExactPair() {
        Instant recorded = NOW.minusSeconds(60);
        repository.save(pick(1L, 1, TODAY, recorded));
        repository.save(pick(1L, 2, TODAY, recorded));
        repository.save(pick(2L, 1, TODAY, recorded));

        Optional<PipelineRunPickEntity> found = repository.findByPipelineRunIdAndPickRank(1L, 1);

        assertThat(found).isPresent();
        assertThat(found.get().getPipelineRunId()).isEqualTo(1L);
        assertThat(found.get().getPickRank()).isEqualTo(1);
    }

    @Test
    @DisplayName("findByPipelineRunIdAndPickRank returns empty when no row exists for that pair")
    void findByPipelineRunIdAndPickRank_absentPair_returnsEmpty() {
        repository.save(pick(1L, 1, TODAY, NOW));

        assertThat(repository.findByPipelineRunIdAndPickRank(1L, 2)).isEmpty();
        assertThat(repository.findByPipelineRunIdAndPickRank(99L, 1)).isEmpty();
    }

    @Test
    @DisplayName("V148: a second row for the same (pipeline_run_id, pick_rank) violates the "
            + "unique constraint — this is what makes PipelineRunPickService's find-before-save "
            + "upsert necessary instead of a blind insert")
    void duplicateRunIdAndRank_violatesUniqueConstraint() {
        repository.saveAndFlush(pick(1L, 1, TODAY, NOW));

        assertThatThrownBy(() -> repository.saveAndFlush(pick(1L, 1, TODAY, NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("rank is scoped per run — the same rank on a different pipeline_run_id is a "
            + "distinct, allowed row")
    void sameRankDifferentRun_isAllowed() {
        repository.saveAndFlush(pick(1L, 1, TODAY, NOW));
        repository.saveAndFlush(pick(2L, 1, TODAY, NOW));

        assertThat(repository.count()).isEqualTo(2);
    }
}
