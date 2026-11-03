package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.CycleType;
import com.gregochr.goldenhour.entity.ScheduleType;
import com.gregochr.goldenhour.entity.SchedulerFireLogEntity;
import com.gregochr.goldenhour.entity.SchedulerFireStatus;
import com.gregochr.goldenhour.entity.SchedulerJobConfigEntity;
import com.gregochr.goldenhour.entity.SchedulerJobStatus;
import com.gregochr.goldenhour.repository.SchedulerFireLogRepository;
import com.gregochr.goldenhour.repository.SchedulerJobConfigRepository;
import com.gregochr.goldenhour.service.runs.SchedulerJobCostService;
import com.gregochr.goldenhour.service.runs.SchedulerJobCostService.ClaudeCost;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pins {@link SchedulerJobDetailService}: which source gives a job its typical duration, the three-sample floor,
 * and when the Claude cost is served.
 */
@ExtendWith(MockitoExtension.class)
class SchedulerJobDetailServiceTest {

    private static final Instant T0 = Instant.parse("2027-03-05T03:00:00Z");
    private static final Set<SchedulerFireStatus> OK =
            Set.of(SchedulerFireStatus.SUCCEEDED, SchedulerFireStatus.PARTIAL);

    @Mock
    private SchedulerJobConfigRepository configs;

    @Mock
    private SchedulerFireLogRepository fireLog;

    @Mock
    private SolarSchedulePreviewService preview;

    @Mock
    private SchedulerJobCostService costService;

    private SchedulerJobDetailService service() {
        return new SchedulerJobDetailService(configs, fireLog, preview, costService);
    }

    private void jobExists(String key) {
        when(configs.findByJobKey(key)).thenReturn(Optional.of(SchedulerJobConfigEntity.builder().id(1L)
                .jobKey(key).displayName(key).scheduleType(ScheduleType.CRON).cronExpression("0 0 3 * * *")
                .status(SchedulerJobStatus.ACTIVE).build()));
    }

    private static SchedulerFireLogEntity fire(long seconds) {
        SchedulerFireLogEntity row = new SchedulerFireLogEntity();
        row.setFiredAt(T0);
        row.setCompletedAt(T0.plusSeconds(seconds));
        row.setStatus(SchedulerFireStatus.SUCCEEDED);
        return row;
    }

    private static ClaudeCost cost(String gbp) {
        return new ClaudeCost(new BigDecimal(gbp), true, 4, List.of("batches"), true, new BigDecimal("1.00"), null);
    }

    @Test
    @DisplayName("an unknown job has no detail")
    void unknownJob_isEmpty() {
        when(configs.findByJobKey("nope")).thenReturn(Optional.empty());

        assertThat(service().detail("nope")).isEmpty();
        verifyNoInteractions(fireLog, preview, costService);
    }

    @Test
    @DisplayName("a cycle job takes the run-based median and its Claude cost")
    void cycleJob_usesRunMedianAndCost() {
        jobExists("near_term_batch_evaluation");
        when(preview.medianDurationSeconds(CycleType.NIGHTLY)).thenReturn(1260L);
        when(costService.claudeCost30d("near_term_batch_evaluation")).thenReturn(cost("41.20"));

        SchedulerJobDetailService.Detail detail = service().detail("near_term_batch_evaluation").orElseThrow();

        assertThat(detail).isEqualTo(new SchedulerJobDetailService.Detail("near_term_batch_evaluation", 1260L,
                "runs", cost("41.20")));
        verifyNoInteractions(fireLog);
    }

    @Test
    @DisplayName("the evening cycle reads its own cycle type")
    void eveningJob_usesEveningCycle() {
        jobExists("evening_sunrise_refresh");
        when(preview.medianDurationSeconds(CycleType.EVENING)).thenReturn(760L);
        when(costService.claudeCost30d("evening_sunrise_refresh")).thenReturn(cost("17.40"));

        SchedulerJobDetailService.Detail detail = service().detail("evening_sunrise_refresh").orElseThrow();

        assertThat(detail.typicalDurationSeconds()).isEqualTo(760L);
        assertThat(detail.typicalDurationBasis()).isEqualTo("runs");
    }

    @Test
    @DisplayName("a cycle with no completed run has no duration and no basis, but still has its cost")
    void cycleJob_noRuns() {
        jobExists("intraday_forecast_refresh");
        when(preview.medianDurationSeconds(CycleType.INTRADAY)).thenReturn(null);
        when(costService.claudeCost30d("intraday_forecast_refresh")).thenReturn(cost("0.00"));

        SchedulerJobDetailService.Detail detail = service().detail("intraday_forecast_refresh").orElseThrow();

        assertThat(detail.typicalDurationSeconds()).isNull();
        assertThat(detail.typicalDurationBasis()).isNull();
        assertThat(detail.claudeCost30d()).isEqualTo(cost("0.00"));
    }

    @Test
    @DisplayName("another job takes the median of its completed fires, with no Claude cost when it does not use Claude")
    void otherJob_usesFireMedian() {
        jobExists("tide_refresh");
        when(fireLog.findTop200ByJobKeyAndStatusInAndCompletedAtIsNotNullOrderByFiredAtDesc("tide_refresh", OK))
                .thenReturn(List.of(fire(50), fire(10), fire(30), fire(20), fire(40)));

        SchedulerJobDetailService.Detail detail = service().detail("tide_refresh").orElseThrow();

        assertThat(detail.typicalDurationSeconds()).isEqualTo(30L);
        assertThat(detail.typicalDurationBasis()).isEqualTo("fires");
        assertThat(detail.claudeCost30d()).isNull();
        verifyNoInteractions(preview, costService);
    }

    @Test
    @DisplayName("an even number of fires averages the middle two")
    void otherJob_evenSampleAverages() {
        jobExists("tide_refresh");
        when(fireLog.findTop200ByJobKeyAndStatusInAndCompletedAtIsNotNullOrderByFiredAtDesc("tide_refresh", OK))
                .thenReturn(List.of(fire(10), fire(20), fire(30), fire(100)));

        assertThat(service().detail("tide_refresh").orElseThrow().typicalDurationSeconds()).isEqualTo(25L);
    }

    @Test
    @DisplayName("exactly three fires is enough, two is not")
    void otherJob_threeSampleFloor() {
        jobExists("tide_refresh");
        when(fireLog.findTop200ByJobKeyAndStatusInAndCompletedAtIsNotNullOrderByFiredAtDesc("tide_refresh", OK))
                .thenReturn(List.of(fire(1), fire(2), fire(3)));
        assertThat(service().detail("tide_refresh").orElseThrow().typicalDurationSeconds()).isEqualTo(2L);

        jobExists("weekly_job");
        when(fireLog.findTop200ByJobKeyAndStatusInAndCompletedAtIsNotNullOrderByFiredAtDesc("weekly_job", OK))
                .thenReturn(List.of(fire(1), fire(2)));
        SchedulerJobDetailService.Detail weekly = service().detail("weekly_job").orElseThrow();
        assertThat(weekly.typicalDurationSeconds()).isNull();
        assertThat(weekly.typicalDurationBasis()).isNull();
    }

    @Test
    @DisplayName("a fire with no completion time is left out of the sample")
    void otherJob_incompleteFireIgnored() {
        jobExists("tide_refresh");
        SchedulerFireLogEntity running = fire(0);
        running.setCompletedAt(null);
        when(fireLog.findTop200ByJobKeyAndStatusInAndCompletedAtIsNotNullOrderByFiredAtDesc("tide_refresh", OK))
                .thenReturn(List.of(fire(5), fire(6), running));

        assertThat(service().detail("tide_refresh").orElseThrow().typicalDurationSeconds()).isNull();
    }

    @Test
    @DisplayName("a Claude job that is not a cycle reads its fires and gets its cost")
    void claudeJobWithoutCycle_getsCostAndFireMedian() {
        jobExists("aurora_batch_evaluation");
        when(fireLog.findTop200ByJobKeyAndStatusInAndCompletedAtIsNotNullOrderByFiredAtDesc(
                "aurora_batch_evaluation", OK)).thenReturn(List.of(fire(4), fire(5), fire(6)));
        when(costService.claudeCost30d("aurora_batch_evaluation")).thenReturn(cost("4.60"));

        SchedulerJobDetailService.Detail detail = service().detail("aurora_batch_evaluation").orElseThrow();

        assertThat(detail.typicalDurationSeconds()).isEqualTo(5L);
        assertThat(detail.claudeCost30d()).isEqualTo(cost("4.60"));
    }

    @Test
    @DisplayName("a job the catalogue does not know has no Claude cost")
    void uncataloguedJob_hasNoCost() {
        jobExists("hand_added_job");
        when(fireLog.findTop200ByJobKeyAndStatusInAndCompletedAtIsNotNullOrderByFiredAtDesc("hand_added_job", OK))
                .thenReturn(List.of());

        SchedulerJobDetailService.Detail detail = service().detail("hand_added_job").orElseThrow();

        assertThat(detail.claudeCost30d()).isNull();
        assertThat(detail.typicalDurationSeconds()).isNull();
        verifyNoInteractions(costService);
    }
}
