package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.repository.ApiCallLogRepository;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.service.DynamicSchedulerService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EvaluationAbandonmentService} — R7 of the prompted-row persistence plan.
 */
@ExtendWith(MockitoExtension.class)
class EvaluationAbandonmentServiceTest {

    private static final Instant NOW = Instant.parse("2026-04-16T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private ForecastEvaluationRepository forecastEvaluationRepository;
    @Mock
    private ApiCallLogRepository apiCallLogRepository;
    @Mock
    private DynamicSchedulerService dynamicSchedulerService;

    private EvaluationAbandonmentService service() {
        return new EvaluationAbandonmentService(
                forecastEvaluationRepository, apiCallLogRepository, dynamicSchedulerService, CLOCK);
    }

    // ── registerJob ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("registerJob registers the backstop sweep under the seeded job key")
    void registerJob_registersBackstopTarget() {
        service().registerJob();

        verify(dynamicSchedulerService).registerJobTarget(
                eq("evaluation_abandonment_sweep"), any());
    }

    // ── R7(a): abandonPendingForBatch ───────────────────────────────────────

    @Test
    @DisplayName("R7(a): extracts evalRowId from every fc- custom_id logged against the batch and "
            + "bulk-abandons them")
    void abandonPendingForBatch_extractsRowIdsFromFcCustomIds() {
        when(apiCallLogRepository.findCustomIdsByBatchId("msgbatch_x")).thenReturn(List.of(
                "fc-1-2026-04-16-SUNRISE-r101",
                "fc-2-2026-04-16-SUNSET-r102"));
        when(forecastEvaluationRepository.abandonPending(anyCollection())).thenReturn(2);

        service().abandonPendingForBatch("msgbatch_x");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(forecastEvaluationRepository).abandonPending(captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder(101L, 102L);
    }

    @Test
    @DisplayName("R7(a): skips a non-fc custom_id (bb-/wd-/jfdi-/force-/au-) — R8 scope is the "
            + "sky lane only")
    void abandonPendingForBatch_skipsNonForecastCustomIds() {
        when(apiCallLogRepository.findCustomIdsByBatchId("msgbatch_x")).thenReturn(List.of(
                "bb-1-2026-04-16-SUNRISE",
                "fc-2-2026-04-16-SUNSET-r202"));
        when(forecastEvaluationRepository.abandonPending(anyCollection())).thenReturn(1);

        service().abandonPendingForBatch("msgbatch_x");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(forecastEvaluationRepository).abandonPending(captor.capture());
        assertThat(captor.getValue()).containsExactly(202L);
    }

    @Test
    @DisplayName("R7(a): skips an fc- custom_id with no embedded row id (pre-deploy format) — "
            + "there is nothing to act on")
    void abandonPendingForBatch_skipsOldFormatForecastCustomId() {
        when(apiCallLogRepository.findCustomIdsByBatchId("msgbatch_x")).thenReturn(List.of(
                "fc-1-2026-04-16-SUNRISE",
                "fc-2-2026-04-16-SUNSET-r202"));
        when(forecastEvaluationRepository.abandonPending(anyCollection())).thenReturn(1);

        service().abandonPendingForBatch("msgbatch_x");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(forecastEvaluationRepository).abandonPending(captor.capture());
        assertThat(captor.getValue()).containsExactly(202L);
    }

    @Test
    @DisplayName("R7(a): skips a malformed custom_id rather than throwing")
    void abandonPendingForBatch_skipsMalformedCustomId() {
        when(apiCallLogRepository.findCustomIdsByBatchId("msgbatch_x")).thenReturn(List.of(
                "fc-not-a-number-2026-04-16-SUNRISE",
                "fc-2-2026-04-16-SUNSET-r202"));
        when(forecastEvaluationRepository.abandonPending(anyCollection())).thenReturn(1);

        service().abandonPendingForBatch("msgbatch_x");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(forecastEvaluationRepository).abandonPending(captor.capture());
        assertThat(captor.getValue()).containsExactly(202L);
    }

    @Test
    @DisplayName("R7(a): no fc- rows in the batch at all — the bulk update is never called")
    void abandonPendingForBatch_noForecastRows_neverCallsBulkUpdate() {
        when(apiCallLogRepository.findCustomIdsByBatchId("msgbatch_x")).thenReturn(List.of(
                "bb-1-2026-04-16-SUNRISE", "au-MODERATE-2026-04-16"));

        service().abandonPendingForBatch("msgbatch_x");

        verify(forecastEvaluationRepository, never()).abandonPending(any());
    }

    // ── R7(b): sweepBackstop ────────────────────────────────────────────────

    @Test
    @DisplayName("R7(b): the backstop cutoff is exactly 48h before now")
    void sweepBackstop_cutoffIs48HoursBeforeNow() {
        when(forecastEvaluationRepository.abandonPendingOlderThan(any())).thenReturn(3);

        service().sweepBackstop();

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(forecastEvaluationRepository).abandonPendingOlderThan(captor.capture());
        assertThat(captor.getValue())
                .isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).minusHours(48));
    }

    @Test
    @DisplayName("R7(b): the bulk update runs every tick regardless of whether it finds anything "
            + "to abandon")
    void sweepBackstop_zeroAbandoned_stillCallsRepository() {
        when(forecastEvaluationRepository.abandonPendingOlderThan(any())).thenReturn(0);

        service().sweepBackstop();

        verify(forecastEvaluationRepository, times(1)).abandonPendingOlderThan(any());
    }
}
