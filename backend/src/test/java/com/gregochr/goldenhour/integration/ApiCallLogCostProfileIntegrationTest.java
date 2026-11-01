package com.gregochr.goldenhour.integration;

import com.gregochr.goldenhour.entity.ApiCallLogEntity;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.model.ApiCallCostRow;
import com.gregochr.goldenhour.repository.ApiCallLogRepository;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.repository.JobRunRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the Run Config cost projection ({@code ApiCallLogRepository.findCostRowsSince}) and the latest
 * exchange-rate query against a real Postgres (Flyway runs every migration in {@link IntegrationTestBase}):
 * the JPQL joins to {@code job_run} and {@code forecast_batch} compile to valid Postgres SQL, the batch's
 * pipeline cycle comes through, and the window and service filters hold.
 *
 * <p>CI-only: it needs the Postgres Testcontainer, and there is no Docker on the development machine
 * (see CLAUDE.md). The same queries are proved on H2 by {@code ApiCallLogCostRowsPersistenceTest}.
 */
class ApiCallLogCostProfileIntegrationTest extends IntegrationTestBase {

    private static final LocalDateTime SINCE = LocalDateTime.of(2026, 10, 3, 0, 0);

    @Autowired
    private ApiCallLogRepository apiCallLogRepository;
    @Autowired
    private JobRunRepository jobRunRepository;
    @Autowired
    private ForecastBatchRepository forecastBatchRepository;

    private JobRunEntity run(RunType type, LocalDateTime started, Double rate) {
        return jobRunRepository.save(JobRunEntity.builder().runType(type).startedAt(started)
                .triggeredManually(false).exchangeRateGbpPerUsd(rate).build());
    }

    private void call(long jobRunId, ServiceName service, LocalDateTime at, String url, String customId,
            String batchId, boolean batch) {
        apiCallLogRepository.save(ApiCallLogEntity.builder().jobRunId(jobRunId).service(service).calledAt(at)
                .requestUrl(url).customId(customId).batchId(batchId).isBatch(batch).succeeded(true)
                .evaluationModel(EvaluationModel.SONNET_55).targetDate(LocalDate.of(2026, 11, 2))
                .inputTokens(1_000L).outputTokens(200L).cacheCreationInputTokens(5_000L)
                .cacheReadInputTokens(0L).costMicroDollars(2_800L).build());
    }

    @Test
    @DisplayName("the cost projection runs on Postgres: batch rows carry their pipeline cycle, "
            + "sync rows carry none, and rows outside the window or service are dropped")
    void costRowsOnPostgres() {
        JobRunEntity batchJob = run(RunType.SCHEDULED_BATCH, LocalDateTime.of(2026, 10, 30, 2, 0), null);
        JobRunEntity briefing = run(RunType.BRIEFING, LocalDateTime.of(2026, 10, 30, 6, 0), null);
        ForecastBatchEntity cycleBatch = new ForecastBatchEntity("msgbatch_cost_profile",
                ForecastBatchEntity.BatchType.FORECAST, 1, Instant.parse("2026-11-01T00:00:00Z"));
        cycleBatch.setPipelineRunId(4242L);
        forecastBatchRepository.save(cycleBatch);

        call(batchJob.getId(), ServiceName.ANTHROPIC, SINCE.plusDays(1), null, "fc-1-2026-11-02-SUNRISE",
                "msgbatch_cost_profile", true);
        call(briefing.getId(), ServiceName.ANTHROPIC, SINCE.plusDays(1), "briefing-best-bet", null, null, false);
        call(briefing.getId(), ServiceName.ANTHROPIC, SINCE.minusDays(1), "briefing-best-bet", null, null, false);
        call(briefing.getId(), ServiceName.WORLD_TIDES, SINCE.plusDays(1), null, null, null, false);

        List<ApiCallCostRow> rows = apiCallLogRepository.findCostRowsSince(SINCE);

        assertThat(rows).hasSize(2);
        ApiCallCostRow batchRow = rows.stream().filter(r -> r.customId() != null).findFirst().orElseThrow();
        assertThat(batchRow.runType()).isEqualTo(RunType.SCHEDULED_BATCH);
        assertThat(batchRow.pipelineRunId()).isEqualTo(4242L);
        assertThat(batchRow.costMicroDollars()).isEqualTo(2_800L);
        assertThat(batchRow.cacheCreationTokens()).isEqualTo(5_000L);
        ApiCallCostRow syncRow = rows.stream().filter(r -> r.customId() == null).findFirst().orElseThrow();
        assertThat(syncRow.runType()).isEqualTo(RunType.BRIEFING);
        assertThat(syncRow.requestUrl()).isEqualTo("briefing-best-bet");
        assertThat(syncRow.pipelineRunId()).isNull();
    }

    @Test
    @DisplayName("the latest exchange rate query returns the newest non-null rate on Postgres")
    void latestExchangeRateOnPostgres() {
        run(RunType.BRIEFING, LocalDateTime.of(2026, 10, 1, 6, 0), 0.70);
        run(RunType.BRIEFING, LocalDateTime.of(2026, 10, 29, 6, 0), 0.78);
        run(RunType.BRIEFING, LocalDateTime.of(2026, 10, 30, 6, 0), null);

        assertThat(jobRunRepository.findLatestExchangeRates(PageRequest.of(0, 1))).containsExactly(0.78);
    }
}
