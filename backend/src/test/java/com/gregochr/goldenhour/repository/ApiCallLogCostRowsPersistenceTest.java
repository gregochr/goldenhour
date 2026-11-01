package com.gregochr.goldenhour.repository;

import com.gregochr.goldenhour.entity.ApiCallLogEntity;
import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.model.ApiCallCostRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ApiCallLogRepository.findCostRowsSince} and {@code JobRunRepository.findLatestExchangeRates} on H2
 * with the schema generated from the entities: the projection joins the job run and (left) the batch's
 * pipeline cycle, filters to Anthropic rows inside the window, and carries the columns the Run Config cost
 * profile reads. Postgres is proved by {@code ApiCallLogCostProfileIntegrationTest} in CI.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ApiCallLogCostRowsPersistenceTest {

    private static final LocalDateTime SINCE = LocalDateTime.of(2026, 10, 3, 0, 0);

    @Autowired
    private ApiCallLogRepository apiCallLogRepository;
    @Autowired
    private JobRunRepository jobRunRepository;
    @Autowired
    private ForecastBatchRepository forecastBatchRepository;
    @Autowired
    private PipelineRunRepository pipelineRunRepository;

    @AfterEach
    void clean() {
        apiCallLogRepository.deleteAll();
        forecastBatchRepository.deleteAll();
        pipelineRunRepository.deleteAll();
        jobRunRepository.deleteAll();
    }

    private JobRunEntity run(RunType type, LocalDateTime started, Double rate) {
        return jobRunRepository.save(JobRunEntity.builder().runType(type).startedAt(started)
                .triggeredManually(false).exchangeRateGbpPerUsd(rate).build());
    }

    private void call(long jobRunId, ServiceName service, LocalDateTime at, String url,
            String customId, String batchId, boolean batch) {
        apiCallLogRepository.save(ApiCallLogEntity.builder().jobRunId(jobRunId).service(service)
                .calledAt(at).requestUrl(url).customId(customId).batchId(batchId).isBatch(batch).succeeded(true)
                .evaluationModel(EvaluationModel.SONNET_55).targetDate(LocalDate.of(2026, 11, 2))
                .inputTokens(1_000L).outputTokens(200L).cacheCreationInputTokens(5_000L)
                .cacheReadInputTokens(0L).costMicroDollars(2_800L)
                .requestBody("never selected").responseBody("never selected").build());
    }

    @Test
    @DisplayName("the projection carries the run type, run start, cost columns and the batch's pipeline cycle")
    void projectsJoinedColumns() {
        JobRunEntity job = run(RunType.SCHEDULED_BATCH, LocalDateTime.of(2026, 10, 30, 2, 0), null);
        ForecastBatchEntity cycleBatch = new ForecastBatchEntity("msgbatch_cycle",
                ForecastBatchEntity.BatchType.FORECAST, 1, Instant.parse("2026-11-01T00:00:00Z"));
        Instant trigger = Instant.parse("2026-10-30T01:00:00Z");
        Long cycleId = pipelineRunRepository.save(new PipelineRunEntity(CycleType.NIGHTLY, trigger)).getId();
        cycleBatch.setPipelineRunId(cycleId);
        forecastBatchRepository.save(cycleBatch);
        call(job.getId(), ServiceName.ANTHROPIC, SINCE.plusDays(1), null, "fc-1-2026-11-02-SUNRISE",
                "msgbatch_cycle", true);

        List<ApiCallCostRow> rows = apiCallLogRepository.findCostRowsSince(SINCE);

        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.runType()).isEqualTo(RunType.SCHEDULED_BATCH);
            assertThat(r.jobRunStartedAt()).isEqualTo(LocalDateTime.of(2026, 10, 30, 2, 0));
            assertThat(r.customId()).isEqualTo("fc-1-2026-11-02-SUNRISE");
            assertThat(r.isBatch()).isTrue();
            assertThat(r.succeeded()).isTrue();
            assertThat(r.evaluationModel()).isEqualTo(EvaluationModel.SONNET_55);
            assertThat(r.targetDate()).isEqualTo(LocalDate.of(2026, 11, 2));
            assertThat(r.inputTokens()).isEqualTo(1_000L);
            assertThat(r.outputTokens()).isEqualTo(200L);
            assertThat(r.cacheCreationTokens()).isEqualTo(5_000L);
            assertThat(r.cacheReadTokens()).isZero();
            assertThat(r.costMicroDollars()).isEqualTo(2_800L);
            assertThat(r.jobRunId()).isEqualTo(job.getId());
            assertThat(r.pipelineRunId()).isNotNull();
            assertThat(r.cycleTriggerTime()).isEqualTo(trigger);
        });
    }

    @Test
    @DisplayName("a call with no matching batch row has a null pipeline run, and a sync call carries its run type")
    void leftJoinKeepsRowsWithoutABatch() {
        JobRunEntity job = run(RunType.BRIEFING, LocalDateTime.of(2026, 10, 30, 6, 0), null);
        call(job.getId(), ServiceName.ANTHROPIC, SINCE.plusDays(1), "briefing-best-bet", null, null, false);

        List<ApiCallCostRow> rows = apiCallLogRepository.findCostRowsSince(SINCE);

        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.runType()).isEqualTo(RunType.BRIEFING);
            assertThat(r.requestUrl()).isEqualTo("briefing-best-bet");
            assertThat(r.pipelineRunId()).isNull();
        });
    }

    @Test
    @DisplayName("rows before the window and rows for other services are not returned")
    void filtersWindowAndService() {
        JobRunEntity job = run(RunType.BRIEFING, LocalDateTime.of(2026, 10, 30, 6, 0), null);
        call(job.getId(), ServiceName.ANTHROPIC, SINCE.minusSeconds(1), "briefing-best-bet", null, null, false);
        call(job.getId(), ServiceName.WORLD_TIDES, SINCE.plusDays(1), null, null, null, false);
        call(job.getId(), ServiceName.ANTHROPIC, SINCE, "briefing-best-bet", null, null, false);

        assertThat(apiCallLogRepository.findCostRowsSince(SINCE)).hasSize(1);
    }

    @Test
    @DisplayName("the exchange rate query returns the newest non-null rate first")
    void latestExchangeRates() {
        run(RunType.BRIEFING, LocalDateTime.of(2026, 10, 1, 6, 0), 0.70);
        run(RunType.BRIEFING, LocalDateTime.of(2026, 10, 29, 6, 0), 0.78);
        run(RunType.BRIEFING, LocalDateTime.of(2026, 10, 30, 6, 0), null);

        assertThat(jobRunRepository.findLatestExchangeRates(PageRequest.of(0, 1))).containsExactly(0.78);
    }
}
