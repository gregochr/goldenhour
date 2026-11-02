package com.gregochr.goldenhour.service.pipeline;

import com.gregochr.goldenhour.config.AuroraProperties;
import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.ScheduleType;
import com.gregochr.goldenhour.entity.SchedulerFireLogEntity;
import com.gregochr.goldenhour.entity.SchedulerFireStatus;
import com.gregochr.goldenhour.entity.SchedulerFireTrigger;
import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import com.gregochr.goldenhour.entity.SchedulerJobStatus;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.repository.SchedulerFireLogRepository;
import com.gregochr.goldenhour.repository.SchedulerJobConfigRepository;
import com.gregochr.goldenhour.service.BriefingService;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import com.gregochr.goldenhour.service.LocationFailureService;
import com.gregochr.goldenhour.service.SolarCatchUpPolicy;
import com.gregochr.goldenhour.service.SolarTriggerFactory;
import com.gregochr.goldenhour.service.batch.BatchRetryService;
import com.gregochr.goldenhour.service.batch.BatchSubmissionSummary;
import com.gregochr.goldenhour.service.batch.ForecastBatchSubmissionOutcome;
import com.gregochr.goldenhour.service.batch.NightlyCandidateCollectionStrategy;
import com.gregochr.goldenhour.service.batch.NightlyEligibilityPolicy;
import com.gregochr.goldenhour.service.batch.ScheduledBatchEvaluationService;
import com.gregochr.goldenhour.service.notification.AdminAlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The submit-then-async threading shape, end to end (scheduler-redesign-plan.md S2): a forecast cycle fired
 * through the real {@code DynamicSchedulerService.wrapTarget}. The submit step runs on the scheduler thread and
 * reports and links its pipeline run; the wait-and-brief tail is handed to a queueing executor that is never
 * drained, so nothing it does can reach the fire. The assertion is the exact {@code complete(...)} call.
 */
@ExtendWith(MockitoExtension.class)
class PipelineOrchestratorFireLogTest {

    private static final Instant NOW = Instant.parse("2027-03-05T01:00:00Z");
    private static final long LOG_ID = 21L;
    private static final long RUN_ID = 77L;
    private static final String KEY = "near_term_batch_evaluation";

    @Mock
    private SchedulerJobConfigRepository configRepository;
    @Mock
    private SchedulerFireLogRepository fireLog;
    @Mock
    private TaskScheduler taskScheduler;
    @Mock
    private AuroraProperties auroraProperties;
    @Mock
    private SolarTriggerFactory solarTriggers;
    @Mock
    private SolarCatchUpPolicy solarCatchUp;
    @Mock
    private PipelineRunService pipelineRunService;
    @Mock
    private ScheduledBatchEvaluationService scheduledBatchEvaluationService;
    @Mock
    private BriefingService briefingService;
    @Mock
    private ForecastBatchRepository forecastBatchRepository;
    @Mock
    private PipelineRunPickService pipelineRunPickService;
    @Mock
    private BatchRetryService batchRetryService;
    @Mock
    private AdminAlertService adminAlertService;
    @Mock
    private LocationFailureService locationFailureService;

    private final List<Runnable> queuedTail = new ArrayList<>();
    private DynamicSchedulerService scheduler;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        scheduler = new DynamicSchedulerService(configRepository, fireLog, taskScheduler, auroraProperties,
                solarTriggers, solarCatchUp, clock);
        PipelineOrchestrator orchestrator = new PipelineOrchestrator(pipelineRunService,
                scheduledBatchEvaluationService, briefingService, forecastBatchRepository, clock,
                queuedTail::add, Duration.ofMillis(1), Duration.ofHours(1), scheduler, pipelineRunPickService,
                batchRetryService, adminAlertService, locationFailureService);
        orchestrator.registerJobTarget();
    }

    private PipelineRunEntity run() {
        PipelineRunEntity run = new PipelineRunEntity(CycleType.NIGHTLY, NOW);
        run.setId(RUN_ID);
        return run;
    }

    private void stubSubmit(ForecastBatchSubmissionOutcome outcome) {
        when(pipelineRunService.startRun(CycleType.NIGHTLY)).thenReturn(run());
        when(scheduledBatchEvaluationService.submitForecastBatchForPipelineRun(
                eq(RUN_ID),
                eq(NightlyCandidateCollectionStrategy.INSTANCE),
                eq(NightlyEligibilityPolicy.INSTANCE),
                eq(false),
                notNull()))
                .thenReturn(outcome);
    }

    @Test
    @DisplayName("a cycle that submits its batches is stored SUCCEEDED with the batch count and its pipeline run, "
            + "and the wait/brief tail is only queued")
    void submittedCycle_storesSummaryAndPipelineRun() {
        stubSubmit(ForecastBatchSubmissionOutcome.ran(new BatchSubmissionSummary(3, 3, List.of())));

        scheduledRunnable().run();

        verify(fireLog).complete(LOG_ID, NOW, SchedulerFireStatus.SUCCEEDED, "3 of 3 batches submitted", RUN_ID);
        assertThat(queuedTail).hasSize(1);
    }

    @Test
    @DisplayName("a cycle dropped by the submission guard is stored SKIPPED, still linked to its failed run, "
            + "and no tail is queued")
    void droppedCycle_storesSkippedAndPipelineRun() {
        stubSubmit(ForecastBatchSubmissionOutcome.dropped());

        scheduledRunnable().run();

        verify(fireLog).complete(LOG_ID, NOW, SchedulerFireStatus.SKIPPED,
                "Dropped — another forecast submission was already running", RUN_ID);
        assertThat(queuedTail).isEmpty();
    }

    @Test
    @DisplayName("a cycle whose submit step throws is swallowed by the cycle but stored FAILED with the reason")
    void failedSubmit_storesFailed() {
        when(pipelineRunService.startRun(CycleType.NIGHTLY)).thenReturn(run());
        when(scheduledBatchEvaluationService.submitForecastBatchForPipelineRun(
                eq(RUN_ID),
                eq(NightlyCandidateCollectionStrategy.INSTANCE),
                eq(NightlyEligibilityPolicy.INSTANCE),
                eq(false),
                notNull()))
                .thenThrow(new IllegalStateException("anthropic down"));

        scheduledRunnable().run();

        verify(fireLog).complete(LOG_ID, NOW, SchedulerFireStatus.FAILED,
                "Submit phase failed: anthropic down", RUN_ID);
        assertThat(queuedTail).isEmpty();
    }

    /** Stubs the RUNNING insert for exactly this fire: job key, RUNNING, trigger kind and the fixed fire instant. */
    private void stubInsert(String jobKey, SchedulerFireTrigger kind) {
        when(fireLog.save(argThat((SchedulerFireLogEntity row) -> row != null
                && jobKey.equals(row.getJobKey())
                && row.getStatus() == SchedulerFireStatus.RUNNING
                && row.getTriggerKind() == kind
                && NOW.equals(row.getFiredAt())))).thenAnswer(this::withLogId);
    }

    private SchedulerFireLogEntity withLogId(InvocationOnMock invocation) {
        SchedulerFireLogEntity row = invocation.getArgument(0);
        row.setId(LOG_ID);
        return row;
    }

    /** Fires the nightly job through the scheduler (Run now) and returns the wrapped runnable it was given. */
    private Runnable scheduledRunnable() {
        when(configRepository.findByJobKey(KEY)).thenReturn(Optional.of(SchedulerJobConfigEntity.builder()
                .id(1L)
                .jobKey(KEY)
                .displayName(KEY)
                .scheduleType(ScheduleType.CRON)
                .cronExpression("0 0 2 * * *")
                .status(SchedulerJobStatus.ACTIVE)
                .build()));
        stubInsert(KEY, SchedulerFireTrigger.MANUAL);
        Instant before = Instant.now();
        scheduler.triggerNow(KEY);
        Instant after = Instant.now();
        ArgumentCaptor<Runnable> run = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(run.capture(),
                argThat((Instant at) -> at != null && !at.isBefore(before) && !at.isAfter(after)));
        return run.getValue();
    }
}
