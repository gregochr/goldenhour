package com.gregochr.goldenhour.service.runs;

import com.gregochr.goldenhour.entity.ApiCallLogEntity;
import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchStatus;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunStatus;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.entity.SkyRatingEvalRunEntity;
import com.gregochr.goldenhour.entity.SkyRatingEvalStatus;
import com.gregochr.goldenhour.entity.SkyRatingEvalTrigger;
import com.gregochr.goldenhour.model.runs.RunCallsResponse;
import com.gregochr.goldenhour.model.runs.RunCallsResponse.CallService;
import com.gregochr.goldenhour.model.runs.RunCallsResponse.CallStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Service tests for a run's external calls over real repositories (H2, no Docker): grouped by SQL, batch calls
 * served as batches and requests, and the three call statuses.
 */
@DataJpaTest
@Import({RunsCallsService.class, CycleFactsLoader.class, RunFxLoader.class})
class RunsCallsServiceTest {

    private static final LocalDateTime START = LocalDateTime.parse("2026-11-01T01:00:00");

    @Autowired
    private RunsCallsService service;

    @Autowired
    private TestEntityManager em;

    private JobRunEntity job(RunType type, Double rate) {
        return em.persist(JobRunEntity.builder().runType(type).startedAt(START).completedAt(START.plusMinutes(5))
                .succeeded(1).failed(0).locationsProcessed(1).triggeredManually(false)
                .exchangeRateGbpPerUsd(rate).totalCostMicroDollars(0L).build());
    }

    private void call(JobRunEntity run, ServiceName service, boolean batch, boolean succeeded, long durationMs,
            Long cost, String error) {
        em.persist(ApiCallLogEntity.builder().jobRunId(run.getId()).service(service).calledAt(START)
                .durationMs(durationMs).succeeded(succeeded).isBatch(batch).costMicroDollars(cost)
                .errorMessage(error).evaluationModel(batch ? EvaluationModel.SONNET : null).build());
    }

    private static CallService named(RunCallsResponse response, String label) {
        return response.services().stream().filter(s -> s.label().equals(label)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("a job's sync calls are summed per service with their status, cost and commonest error")
    void jobAggregatesSyncCalls() {
        JobRunEntity run = job(RunType.ASK_READY, 0.8);
        for (int i = 0; i < 5; i++) {
            call(run, ServiceName.ANTHROPIC, false, true, 1000, 200_000L, null);
        }
        call(run, ServiceName.ANTHROPIC, false, false, 500, 0L, "529 overloaded after retries");
        call(run, ServiceName.ANTHROPIC, false, false, 500, 0L, "529 overloaded after retries");
        call(run, ServiceName.ANTHROPIC, false, false, 500, 0L, "timeout");
        call(run, ServiceName.OPEN_METEO_FORECAST, false, true, 120, 0L, null);
        call(run, ServiceName.LIGHT_POLLUTION, false, false, 30, null, "401 · API key not configured");
        em.flush();
        em.clear();

        RunCallsResponse response = service.calls("job-" + run.getId()).orElseThrow();

        assertThat(response.logged()).isTrue();
        assertThat(response.runId()).isEqualTo("job-" + run.getId());
        assertThat(response.services()).extracting(CallService::label).containsExactly("Anthropic Messages",
                "Light pollution API", "Open-Meteo Forecast");
        CallService anthropic = named(response, "Anthropic Messages");
        assertThat(anthropic.calls()).isEqualTo(8);
        assertThat(anthropic.errors()).isEqualTo(3);
        assertThat(anthropic.status()).isEqualTo(CallStatus.ERRORS);
        assertThat(anthropic.errorSummary()).isEqualTo("529 overloaded after retries");
        assertThat(anthropic.purpose()).isEqualTo("Messages · ready answers");
        assertThat(anthropic.durationMs()).isEqualTo(6500L);
        assertThat(anthropic.costMicroDollars()).isEqualTo(1_000_000L);
        assertThat(anthropic.costGbp()).isEqualByComparingTo("0.80");
        assertThat(anthropic.costUsd()).isEqualByComparingTo("1.00");
        assertThat(anthropic.batches()).isNull();
        CallService meteo = named(response, "Open-Meteo Forecast");
        assertThat(meteo.status()).isEqualTo(CallStatus.OK);
        assertThat(meteo.errorSummary()).isNull();
        assertThat(meteo.costMicroDollars()).isZero();
        CallService light = named(response, "Light pollution API");
        assertThat(light.status()).isEqualTo(CallStatus.FAILED);
        assertThat(light.errorSummary()).isEqualTo("401 · API key not configured");
    }

    @Test
    @DisplayName("an error message is cut to 120 characters in SQL")
    void longErrorMessageTruncated() {
        JobRunEntity run = job(RunType.TIDE, 0.8);
        call(run, ServiceName.WORLD_TIDES, false, false, 10, 0L, "x".repeat(400));
        em.flush();
        em.clear();

        CallService tides = service.calls("job-" + run.getId()).orElseThrow().services().get(0);

        assertThat(tides.label()).isEqualTo("WorldTides");
        assertThat(tides.errorSummary()).hasSize(120);
    }

    @Test
    @DisplayName("a cycle's calls are those of its batch runs; batch rows are batches and requests with wall time")
    void cycleAggregatesBatchAndSync() {
        PipelineRunEntity cycle = new PipelineRunEntity(CycleType.INTRADAY, Instant.parse("2026-11-01T01:00:00Z"));
        cycle.setStatus(PipelineRunStatus.COMPLETED);
        em.persist(cycle);
        JobRunEntity batchRun = job(RunType.SCHEDULED_BATCH, 0.8);
        ForecastBatchEntity first = new ForecastBatchEntity("msgbatch_1", BatchType.FORECAST, 2,
                Instant.parse("2026-11-02T02:00:00Z"));
        first.setSubmittedAt(Instant.parse("2026-11-01T02:00:00Z"));
        first.setPipelineRunId(cycle.getId());
        first.setJobRunId(batchRun.getId());
        first.setStatus(BatchStatus.COMPLETED);
        first.setEndedAt(Instant.parse("2026-11-01T02:30:00Z"));
        em.persist(first);
        ForecastBatchEntity second = new ForecastBatchEntity("msgbatch_2", BatchType.FORECAST, 1,
                Instant.parse("2026-11-02T02:20:00Z"));
        second.setSubmittedAt(Instant.parse("2026-11-01T02:20:00Z"));
        second.setPipelineRunId(cycle.getId());
        second.setStatus(BatchStatus.COMPLETED);
        second.setEndedAt(Instant.parse("2026-11-01T03:00:00Z"));
        em.persist(second);
        call(batchRun, ServiceName.ANTHROPIC, true, true, 0, 400_000L, null);
        call(batchRun, ServiceName.ANTHROPIC, true, true, 0, 400_000L, null);
        call(batchRun, ServiceName.ANTHROPIC, true, false, 0, 0L, "invalid_request");
        call(batchRun, ServiceName.OPEN_METEO_FORECAST, false, true, 100, 0L, null);
        call(batchRun, ServiceName.OPEN_METEO_AIR_QUALITY, false, true, 80, 0L, null);
        em.flush();
        em.clear();

        RunCallsResponse response = service.calls("cycle-" + cycle.getId()).orElseThrow();

        CallService batches = named(response, "Anthropic Message Batches");
        assertThat(batches.calls()).isEqualTo(3);
        assertThat(batches.requests()).isEqualTo(3L);
        assertThat(batches.batches()).isEqualTo(2);
        assertThat(batches.durationMs()).isEqualTo(3_600_000L);
        assertThat(batches.purpose()).isEqualTo("Message Batches · sky scoring");
        assertThat(batches.errors()).isEqualTo(1);
        assertThat(batches.errorSummary()).isEqualTo("invalid_request");
        assertThat(named(response, "Open-Meteo Forecast").purpose()).isEqualTo("Forecast · cloud, visibility, wind");
        assertThat(named(response, "Open-Meteo Air quality").purpose()).isEqualTo("Air quality · aerosols");
    }

    @Test
    @DisplayName("a batch still open has no wall time yet")
    void openBatchHasNoWallTime() {
        PipelineRunEntity cycle = new PipelineRunEntity(CycleType.NIGHTLY, Instant.parse("2026-11-01T01:00:00Z"));
        em.persist(cycle);
        JobRunEntity batchRun = job(RunType.SCHEDULED_BATCH, null);
        ForecastBatchEntity open = new ForecastBatchEntity("msgbatch_open", BatchType.FORECAST, 2,
                Instant.parse("2026-11-01T02:00:00Z"));
        open.setPipelineRunId(cycle.getId());
        open.setJobRunId(batchRun.getId());
        em.persist(open);
        call(batchRun, ServiceName.ANTHROPIC, true, true, 0, 100_000L, null);
        em.flush();
        em.clear();

        CallService batches = service.calls("cycle-" + cycle.getId()).orElseThrow().services().get(0);

        assertThat(batches.durationMs()).isNull();
        assertThat(batches.batches()).isEqualTo(1);
        // No run rate and no stored day: the last-resort 0.79.
        assertThat(batches.costGbp()).isEqualByComparingTo("0.08");
    }

    @Test
    @DisplayName("every logged service has a label and a purpose for any kind of run")
    void labelsAndPurposes() {
        for (RunType type : new RunType[] {RunType.BRIEFING, RunType.AURORA_EVALUATION, RunType.WEATHER}) {
            JobRunEntity run = job(type, 0.8);
            for (ServiceName name : ServiceName.values()) {
                call(run, name, false, true, 1, 1L, null);
            }
            call(run, ServiceName.ANTHROPIC, true, true, 1, 1L, null);
            em.flush();
            em.clear();

            RunCallsResponse response = service.calls("job-" + run.getId()).orElseThrow();

            assertThat(response.services()).hasSize(ServiceName.values().length + 1)
                    .allSatisfy(s -> {
                        assertThat(s.label()).isNotBlank();
                        assertThat(s.purpose()).isNotBlank();
                    });
        }
        JobRunEntity briefing = job(RunType.BRIEFING, 0.8);
        call(briefing, ServiceName.ANTHROPIC, false, true, 1, 1L, null);
        JobRunEntity aurora = job(RunType.AURORA_EVALUATION, 0.8);
        call(aurora, ServiceName.ANTHROPIC, false, true, 1, 1L, null);
        em.flush();
        em.clear();
        assertThat(service.calls("job-" + briefing.getId()).orElseThrow().services().get(0).purpose())
                .isEqualTo("Messages · briefing summary and picks");
        assertThat(service.calls("job-" + aurora.getId()).orElseThrow().services().get(0).purpose())
                .isEqualTo("Messages · aurora scoring");
    }

    @Test
    @DisplayName("a run with no logged calls, or no member runs, has an empty list")
    void noCalls() {
        JobRunEntity quiet = job(RunType.TIDE, 0.8);
        PipelineRunEntity bare = new PipelineRunEntity(CycleType.EVENING, Instant.parse("2026-11-01T01:00:00Z"));
        em.persist(bare);
        em.flush();
        em.clear();

        assertThat(service.calls("job-" + quiet.getId()).orElseThrow().services()).isEmpty();
        RunCallsResponse cycle = service.calls("cycle-" + bare.getId()).orElseThrow();
        assertThat(cycle.logged()).isTrue();
        assertThat(cycle.services()).isEmpty();
    }

    @Test
    @DisplayName("a sky eval logs no calls, an unknown run is empty, and a malformed id is refused")
    void evalUnknownAndMalformed() {
        SkyRatingEvalRunEntity eval = em.persist(SkyRatingEvalRunEntity.builder()
                .runTimestamp(START).startedAt(START).model(EvaluationModel.SONNET).runsPerFixture(1)
                .triggerSource(SkyRatingEvalTrigger.MANUAL).status(SkyRatingEvalStatus.COMPLETED).build());
        em.flush();
        em.clear();

        RunCallsResponse response = service.calls("eval-" + eval.getId()).orElseThrow();
        assertThat(response.logged()).isFalse();
        assertThat(response.services()).isEmpty();
        assertThat(service.calls("eval-99999")).isEqualTo(Optional.empty());
        assertThat(service.calls("job-99999")).isEmpty();
        assertThat(service.calls("cycle-99999")).isEmpty();
        for (String bad : new String[] {"", "job", "job-", "job-x", "sched-tide-1", "cycle--1", "JOB-1", "job-1 ",
                "job-99999999999999999999", "cycle-9223372036854775808"}) {
            assertThatThrownBy(() -> service.calls(bad)).as(bad).isInstanceOf(RunsRequestException.class);
        }
        assertThatThrownBy(() -> service.calls(null)).isInstanceOf(RunsRequestException.class);
    }
}
