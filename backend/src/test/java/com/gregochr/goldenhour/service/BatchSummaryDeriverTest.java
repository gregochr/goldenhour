package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.ApiCallLogEntity;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BatchSummary;
import com.gregochr.goldenhour.repository.ApiCallLogRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BatchSummaryDeriver}.
 */
@ExtendWith(MockitoExtension.class)
class BatchSummaryDeriverTest {

    private static final LocalDateTime RUN_START =
            LocalDateTime.of(2026, 5, 18, 2, 5, 30);
    private static final LocalDate RUN_DATE = RUN_START.toLocalDate();

    @Mock
    private ApiCallLogRepository apiCallLogRepository;

    @Mock
    private LocationRepository locationRepository;

    private BatchSummaryDeriver deriver;

    @BeforeEach
    void setUp() {
        deriver = new BatchSummaryDeriver(apiCallLogRepository, locationRepository);
    }

    @Test
    @DisplayName("Non-batch run types return empty without DB lookup")
    void nonBatch_returnsEmpty() {
        JobRunEntity run = batchRun(RunType.TIDE);

        Optional<BatchSummary> result = deriver.derive(run);

        assertThat(result).isEmpty();
        verifyNoInteractions(apiCallLogRepository, locationRepository);
    }

    @Test
    @DisplayName("Batch with no api_call_log rows returns empty")
    void emptyCalls_returnsEmpty() {
        JobRunEntity run = batchRun(RunType.SCHEDULED_BATCH);
        when(apiCallLogRepository.findByJobRunIdOrderByCalledAtAsc(1L)).thenReturn(List.of());

        Optional<BatchSummary> result = deriver.derive(run);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Single-day single-event single-region batch yields T+1 / SUNRISE summary")
    void singleDay_singleEvent_singleRegion() {
        JobRunEntity run = batchRun(RunType.SCHEDULED_BATCH);
        when(apiCallLogRepository.findByJobRunIdOrderByCalledAtAsc(1L)).thenReturn(List.of(
                call("fc-10-2026-05-19-SUNRISE", RUN_DATE.plusDays(1),
                        TargetType.SUNRISE, EvaluationModel.HAIKU),
                call("fc-11-2026-05-19-SUNRISE", RUN_DATE.plusDays(1),
                        TargetType.SUNRISE, EvaluationModel.HAIKU)));
        when(locationRepository.findAllById(ArgumentMatchers.anyIterable()))
                .thenReturn(List.of(location(10L, region(1L)), location(11L, region(1L))));

        BatchSummary summary = deriver.derive(run).orElseThrow();

        assertThat(summary.horizonRange()).isEqualTo("T+1");
        assertThat(summary.eventTypes()).containsExactly("SUNRISE");
        assertThat(summary.evaluationModel()).isEqualTo("HAIKU");
        assertThat(summary.locationCount()).isEqualTo(2);
        assertThat(summary.regionCount()).isEqualTo(1);
        assertThat(summary.extendedThinking()).isFalse();
    }

    @Test
    @DisplayName("Multi-day contiguous batch yields 'T to T+2' range")
    void multiDayRange_rendersRange() {
        JobRunEntity run = batchRun(RunType.SCHEDULED_BATCH);
        when(apiCallLogRepository.findByJobRunIdOrderByCalledAtAsc(1L)).thenReturn(List.of(
                call("fc-10-2026-05-18-SUNSET", RUN_DATE,
                        TargetType.SUNSET, EvaluationModel.SONNET),
                call("fc-10-2026-05-19-SUNSET", RUN_DATE.plusDays(1),
                        TargetType.SUNSET, EvaluationModel.SONNET),
                call("fc-10-2026-05-20-SUNSET", RUN_DATE.plusDays(2),
                        TargetType.SUNSET, EvaluationModel.SONNET)));
        when(locationRepository.findAllById(any())).thenReturn(List.of(location(10L, region(1L))));

        BatchSummary summary = deriver.derive(run).orElseThrow();

        assertThat(summary.horizonRange()).isEqualTo("T to T+2");
    }

    @Test
    @DisplayName("Multiple event types are returned sorted and de-duplicated")
    void multiEvent_returnsSortedDistinct() {
        JobRunEntity run = batchRun(RunType.SCHEDULED_BATCH);
        when(apiCallLogRepository.findByJobRunIdOrderByCalledAtAsc(1L)).thenReturn(List.of(
                call("fc-10-2026-05-19-SUNRISE", RUN_DATE.plusDays(1),
                        TargetType.SUNRISE, EvaluationModel.HAIKU),
                call("fc-10-2026-05-19-SUNSET", RUN_DATE.plusDays(1),
                        TargetType.SUNSET, EvaluationModel.HAIKU)));
        when(locationRepository.findAllById(any())).thenReturn(List.of(location(10L, region(1L))));

        BatchSummary summary = deriver.derive(run).orElseThrow();

        assertThat(summary.eventTypes()).containsExactly("SUNRISE", "SUNSET");
    }

    @Test
    @DisplayName("Extended-thinking model variants set extendedThinking=true")
    void extendedThinkingModel_setsFlag() {
        JobRunEntity run = batchRun(RunType.BATCH_NEAR_TERM);
        when(apiCallLogRepository.findByJobRunIdOrderByCalledAtAsc(1L)).thenReturn(List.of(
                call("fc-10-2026-05-19-SUNRISE", RUN_DATE.plusDays(1),
                        TargetType.SUNRISE, EvaluationModel.SONNET_ET)));
        when(locationRepository.findAllById(any())).thenReturn(List.of(location(10L, region(1L))));

        BatchSummary summary = deriver.derive(run).orElseThrow();

        assertThat(summary.evaluationModel()).isEqualTo("SONNET_ET");
        assertThat(summary.extendedThinking()).isTrue();
    }

    @Test
    @DisplayName("Locations across multiple regions yield correct distinct region count")
    void multipleRegions_distinctCounted() {
        JobRunEntity run = batchRun(RunType.SCHEDULED_BATCH);
        when(apiCallLogRepository.findByJobRunIdOrderByCalledAtAsc(1L)).thenReturn(List.of(
                call("fc-10-2026-05-19-SUNRISE", RUN_DATE.plusDays(1),
                        TargetType.SUNRISE, EvaluationModel.HAIKU),
                call("fc-20-2026-05-19-SUNRISE", RUN_DATE.plusDays(1),
                        TargetType.SUNRISE, EvaluationModel.HAIKU),
                call("fc-30-2026-05-19-SUNRISE", RUN_DATE.plusDays(1),
                        TargetType.SUNRISE, EvaluationModel.HAIKU)));
        when(locationRepository.findAllById(any())).thenReturn(List.of(
                location(10L, region(1L)),
                location(20L, region(2L)),
                location(30L, region(2L))));

        BatchSummary summary = deriver.derive(run).orElseThrow();

        assertThat(summary.locationCount()).isEqualTo(3);
        assertThat(summary.regionCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Locations without a region are excluded from regionCount but counted as locations")
    void locationWithoutRegion_excludedFromRegionCount() {
        JobRunEntity run = batchRun(RunType.SCHEDULED_BATCH);
        when(apiCallLogRepository.findByJobRunIdOrderByCalledAtAsc(1L)).thenReturn(List.of(
                call("fc-10-2026-05-19-SUNRISE", RUN_DATE.plusDays(1),
                        TargetType.SUNRISE, EvaluationModel.HAIKU),
                call("fc-20-2026-05-19-SUNRISE", RUN_DATE.plusDays(1),
                        TargetType.SUNRISE, EvaluationModel.HAIKU)));
        when(locationRepository.findAllById(any())).thenReturn(List.of(
                location(10L, region(1L)),
                location(20L, null)));

        BatchSummary summary = deriver.derive(run).orElseThrow();

        assertThat(summary.locationCount()).isEqualTo(2);
        assertThat(summary.regionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Malformed customId is skipped, doesn't throw, doesn't count toward regions")
    void malformedCustomId_skippedDefensively() {
        JobRunEntity run = batchRun(RunType.SCHEDULED_BATCH);
        ApiCallLogEntity good = call("fc-10-2026-05-19-SUNRISE", RUN_DATE.plusDays(1),
                TargetType.SUNRISE, EvaluationModel.HAIKU);
        ApiCallLogEntity broken = call("not-a-real-customId", RUN_DATE.plusDays(1),
                TargetType.SUNRISE, EvaluationModel.HAIKU);
        when(apiCallLogRepository.findByJobRunIdOrderByCalledAtAsc(1L))
                .thenReturn(List.of(good, broken));
        when(locationRepository.findAllById(any())).thenReturn(List.of(location(10L, region(1L))));

        BatchSummary summary = deriver.derive(run).orElseThrow();

        assertThat(summary.locationCount()).isEqualTo(2);
        assertThat(summary.regionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Non-Anthropic service calls are ignored")
    void nonAnthropicCalls_ignored() {
        JobRunEntity run = batchRun(RunType.SCHEDULED_BATCH);
        ApiCallLogEntity openMeteo = ApiCallLogEntity.builder()
                .jobRunId(1L)
                .service(ServiceName.OPEN_METEO_FORECAST)
                .targetDate(RUN_DATE.plusDays(1))
                .targetType(TargetType.SUNRISE)
                .build();
        when(apiCallLogRepository.findByJobRunIdOrderByCalledAtAsc(1L)).thenReturn(List.of(openMeteo));

        Optional<BatchSummary> result = deriver.derive(run);

        assertThat(result).isEmpty();
    }

    private static JobRunEntity batchRun(RunType runType) {
        return JobRunEntity.builder()
                .id(1L)
                .runType(runType)
                .startedAt(RUN_START)
                .build();
    }

    private static ApiCallLogEntity call(String customId, LocalDate targetDate,
            TargetType targetType, EvaluationModel model) {
        return ApiCallLogEntity.builder()
                .jobRunId(1L)
                .service(ServiceName.ANTHROPIC)
                .isBatch(true)
                .customId(customId)
                .targetDate(targetDate)
                .targetType(targetType)
                .evaluationModel(model)
                .build();
    }

    private static LocationEntity location(Long id, RegionEntity region) {
        LocationEntity loc = new LocationEntity();
        loc.setId(id);
        loc.setRegion(region);
        return loc;
    }

    private static RegionEntity region(Long id) {
        RegionEntity r = new RegionEntity();
        r.setId(id);
        return r;
    }
}
