package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.RunType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cycle link on a job run is written at insert and never updated, so a later {@code save()} of the row cannot
 * rewrite or clear it, and a cycle's children are found by it.
 */
@DataJpaTest
class JobRunPipelineRunIdTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private JobRunRepository jobRuns;

    private JobRunEntity briefing(Long cycleId) {
        return em.persist(JobRunEntity.builder().runType(RunType.BRIEFING).startedAt(LocalDateTime.now())
                .triggeredManually(false).pipelineRunId(cycleId).build());
    }

    @Test
    @DisplayName("the cycle id is stored at insert and an update through the entity does not change it")
    void insertOnly() {
        JobRunEntity run = briefing(330L);
        em.flush();
        em.clear();

        JobRunEntity loaded = jobRuns.findById(run.getId()).orElseThrow();
        assertThat(loaded.getPipelineRunId()).isEqualTo(330L);
        loaded.setPipelineRunId(999L);
        loaded.setSucceeded(7);
        jobRuns.saveAndFlush(loaded);
        em.clear();

        JobRunEntity reloaded = jobRuns.findById(run.getId()).orElseThrow();
        assertThat(reloaded.getSucceeded()).isEqualTo(7);
        assertThat(reloaded.getPipelineRunId()).isEqualTo(330L);
    }

    @Test
    @DisplayName("the children of some cycles are found by their id, and a run without one is not")
    void findByCycle() {
        JobRunEntity first = briefing(1L);
        JobRunEntity second = briefing(2L);
        briefing(null);
        briefing(3L);
        em.flush();
        em.clear();

        List<JobRunEntity> found = jobRuns.findByPipelineRunIdIn(List.of(1L, 2L));

        assertThat(found).extracting(JobRunEntity::getId).containsExactlyInAnyOrder(first.getId(), second.getId());
    }
}
