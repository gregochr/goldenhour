package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.RunType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository slice test (H2, no Flyway, no Docker) proving the job-run list queries order ties on
 * the start second by id descending, so offset paging never reorders rows between requests.
 */
@DataJpaTest
class JobRunRepositoryPagingTest {

    private static final LocalDateTime SAME_SECOND = LocalDateTime.of(2026, 10, 30, 6, 0, 0);

    @Autowired
    private JobRunRepository jobRuns;

    private JobRunEntity run(RunType type, LocalDateTime startedAt) {
        return jobRuns.save(JobRunEntity.builder().runType(type).startedAt(startedAt)
                .triggeredManually(true).locationsProcessed(0).succeeded(0).failed(0).build());
    }

    @Test
    @DisplayName("by-type query returns runs sharing a start second id-descending")
    void byType_tiesOrderedByIdDescending() {
        JobRunEntity first = run(RunType.WEATHER, SAME_SECOND);
        JobRunEntity second = run(RunType.WEATHER, SAME_SECOND);
        JobRunEntity third = run(RunType.WEATHER, SAME_SECOND);

        assertThat(jobRuns.findByRunTypeOrderByStartedAtDescIdDesc(RunType.WEATHER, PageRequest.of(0, 10)))
                .extracting(JobRunEntity::getId)
                .containsExactly(third.getId(), second.getId(), first.getId());
        assertThat(jobRuns.findByRunTypeOrderByStartedAtDescIdDesc(RunType.WEATHER, PageRequest.of(0, 2)))
                .extracting(JobRunEntity::getId)
                .containsExactly(third.getId(), second.getId());
    }

    @Test
    @DisplayName("all-types query returns runs sharing a start second id-descending, newest start first")
    void allTypes_tiesOrderedByIdDescending() {
        JobRunEntity older = run(RunType.TIDE, SAME_SECOND.minusMinutes(1));
        JobRunEntity first = run(RunType.WEATHER, SAME_SECOND);
        JobRunEntity second = run(RunType.BRIEFING, SAME_SECOND);

        assertThat(jobRuns.findByStartedAtAfterOrderByStartedAtDescIdDesc(SAME_SECOND.minusDays(1),
                PageRequest.of(0, 10)))
                .extracting(JobRunEntity::getId)
                .containsExactly(second.getId(), first.getId(), older.getId());
    }
}
