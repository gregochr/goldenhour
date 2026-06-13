package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.PipelineRunPickEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository slice tests for {@link PipelineRunPickRepository#findFreshFallbackCandidates},
 * the freshness-bounded query behind the fail-safe best-bet fallback. Proves the event-passed
 * and age-ceiling exclusions are enforced by the query itself (not just the caller).
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
}
