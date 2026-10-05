package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.ApiCallLogEntity;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.ServiceName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository slice test (H2, schema from the entities, no Flyway, no Docker) for the three queries
 * Ask's cost recording stands on: the daily-run lookup, the scoped increments and the spend sum.
 * It also proves {@link RunType#ASK} and {@link RunType#ASK_READY} persist.
 */
@DataJpaTest
class AskJobRunRepositoryTest {

    private static final LocalDateTime UK_MIDNIGHT = LocalDateTime.of(2026, 10, 4, 23, 0);

    @Autowired
    private JobRunRepository jobRuns;

    @Autowired
    private ApiCallLogRepository apiCalls;

    @Autowired
    private TestEntityManager entityManager;

    private JobRunEntity run(RunType type, LocalDateTime startedAt) {
        return jobRuns.save(JobRunEntity.builder().runType(type).startedAt(startedAt)
                .triggeredManually(true).locationsProcessed(0).succeeded(0).failed(0).build());
    }

    private void call(JobRunEntity run, Long cost) {
        apiCalls.save(ApiCallLogEntity.builder().jobRunId(run.getId()).service(ServiceName.ANTHROPIC)
                .calledAt(UK_MIDNIGHT).succeeded(true).costMicroDollars(cost).build());
    }

    @Test
    @DisplayName("both Ask run types are storable and read back")
    void askRunTypesPersist() {
        JobRunEntity ask = run(RunType.ASK, UK_MIDNIGHT);
        JobRunEntity ready = run(RunType.ASK_READY, UK_MIDNIGHT);
        entityManager.flush();
        entityManager.clear();

        assertThat(jobRuns.findById(ask.getId()).orElseThrow().getRunType()).isEqualTo(RunType.ASK);
        assertThat(jobRuns.findById(ready.getId()).orElseThrow().getRunType()).isEqualTo(RunType.ASK_READY);
    }

    @Test
    @DisplayName("the daily-run lookup includes a run started exactly at UK midnight and excludes one a "
            + "moment before it")
    void dailyRunLookupBoundary() {
        run(RunType.ASK, UK_MIDNIGHT.minusNanos(1_000));
        JobRunEntity atMidnight = run(RunType.ASK, UK_MIDNIGHT);

        assertThat(jobRuns.findFirstByRunTypeAndStartedAtGreaterThanEqualOrderByStartedAtDesc(RunType.ASK,
                UK_MIDNIGHT)).map(JobRunEntity::getId).contains(atMidnight.getId());
        assertThat(jobRuns.findFirstByRunTypeAndStartedAtGreaterThanEqualOrderByStartedAtDesc(RunType.ASK,
                UK_MIDNIGHT.plusNanos(1_000))).isEmpty();
    }

    @Test
    @DisplayName("the daily-run lookup returns the newest ASK run and ignores other run types")
    void dailyRunLookupNewestOfTheType() {
        run(RunType.ASK, UK_MIDNIGHT.plusHours(1));
        JobRunEntity newest = run(RunType.ASK, UK_MIDNIGHT.plusHours(5));
        run(RunType.ASK_READY, UK_MIDNIGHT.plusHours(9));
        run(RunType.BRIEFING, UK_MIDNIGHT.plusHours(10));

        assertThat(jobRuns.findFirstByRunTypeAndStartedAtGreaterThanEqualOrderByStartedAtDesc(RunType.ASK,
                UK_MIDNIGHT)).map(JobRunEntity::getId).contains(newest.getId());
    }

    @Test
    @DisplayName("the cost increment adds to the run's total, from zero or null, and touches nothing else")
    void addCostIncrementsOnlyTheTotal() {
        JobRunEntity started = run(RunType.ASK, UK_MIDNIGHT);
        JobRunEntity untouched = run(RunType.ASK, UK_MIDNIGHT);
        entityManager.flush();

        assertThat(jobRuns.addCostMicroDollars(started.getId(), 1_500)).isEqualTo(1);
        assertThat(jobRuns.addCostMicroDollars(started.getId(), 2_500)).isEqualTo(1);
        entityManager.clear();

        JobRunEntity read = jobRuns.findById(started.getId()).orElseThrow();
        assertThat(read.getTotalCostMicroDollars()).as("null total started from zero").isEqualTo(4_000L);
        assertThat(read.getLocationsProcessed()).isZero();
        assertThat(read.getSucceeded()).isZero();
        assertThat(jobRuns.findById(untouched.getId()).orElseThrow().getTotalCostMicroDollars()).isNull();
        assertThat(jobRuns.addCostMicroDollars(-1L, 10)).as("no such run").isZero();
    }

    @Test
    @DisplayName("a question outcome bumps processed and exactly one of succeeded or failed")
    void addQuestionOutcome() {
        JobRunEntity started = run(RunType.ASK, UK_MIDNIGHT);
        entityManager.flush();

        jobRuns.addQuestionOutcome(started.getId(), 1, 0);
        jobRuns.addQuestionOutcome(started.getId(), 1, 0);
        jobRuns.addQuestionOutcome(started.getId(), 0, 1);
        entityManager.clear();

        JobRunEntity read = jobRuns.findById(started.getId()).orElseThrow();
        assertThat(read.getLocationsProcessed()).isEqualTo(3);
        assertThat(read.getSucceeded()).isEqualTo(2);
        assertThat(read.getFailed()).isEqualTo(1);
        assertThat(read.getTotalCostMicroDollars()).isNull();
    }

    @Test
    @DisplayName("the spend sum counts ASK runs started since the moment, ignores ASK_READY, other types and "
            + "earlier runs, and treats a null cost as nothing")
    void spendSumCountsAskOnly() {
        JobRunEntity today = run(RunType.ASK, UK_MIDNIGHT);
        JobRunEntity todayAtLimit = run(RunType.ASK, UK_MIDNIGHT.plusHours(2));
        JobRunEntity yesterday = run(RunType.ASK, UK_MIDNIGHT.minusHours(1));
        JobRunEntity ready = run(RunType.ASK_READY, UK_MIDNIGHT.plusHours(1));
        JobRunEntity briefing = run(RunType.BRIEFING_BEST_BET, UK_MIDNIGHT.plusHours(1));
        call(today, 100_000L);
        call(today, 50_000L);
        call(today, null);
        call(todayAtLimit, 25_000L);
        call(yesterday, 700_000L);
        call(ready, 600_000L);
        call(briefing, 500_000L);

        assertThat(apiCalls.sumCostMicroDollarsByRunTypeStartedSince(RunType.ASK, UK_MIDNIGHT))
                .isEqualTo(175_000L);
        assertThat(apiCalls.sumCostMicroDollarsByRunTypeStartedSince(RunType.ASK_READY, UK_MIDNIGHT))
                .isEqualTo(600_000L);
    }

    @Test
    @DisplayName("with nothing logged the sum is zero, not null")
    void spendSumIsZeroWhenEmpty() {
        run(RunType.ASK, UK_MIDNIGHT);

        assertThat(apiCalls.sumCostMicroDollarsByRunTypeStartedSince(RunType.ASK, UK_MIDNIGHT)).isZero();
    }
}
