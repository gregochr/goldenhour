package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.model.PrefetchCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WeatherPrefetchReport} and the way the task records carry it.
 */
class WeatherPrefetchReportTest {

    private static final PrefetchCall CALL = new PrefetchCall(ServiceName.OPEN_METEO_FORECAST,
            "batch-prefetch forecast chunk 1/1", 5, 5, LocalDateTime.of(2026, 10, 31, 21, 5),
            400L, 200, true, null, 1);

    @Test
    @DisplayName("none() has no calls, asked for nothing and did not abort")
    void none_isEmptyAndNotAttempted() {
        WeatherPrefetchReport none = WeatherPrefetchReport.none();

        assertThat(none.calls()).isEmpty();
        assertThat(none.locationsRequested()).isZero();
        assertThat(none.locationsReturned()).isZero();
        assertThat(none.abortReason()).isNull();
        assertThat(none.attempted()).isFalse();
        assertThat(none.aborted()).isFalse();
    }

    @Test
    @DisplayName("a report with calls, or with locations requested, is attempted")
    void attempted_whenCallsOrLocationsRequested() {
        assertThat(new WeatherPrefetchReport(List.of(CALL), 0, 0, null).attempted()).isTrue();
        assertThat(new WeatherPrefetchReport(List.of(), 3, 0, null).attempted()).isTrue();
    }

    @Test
    @DisplayName("aborted() follows the abort reason")
    void aborted_followsAbortReason() {
        assertThat(new WeatherPrefetchReport(List.of(), 3, 0, "0/3 locations returned").aborted()).isTrue();
        assertThat(new WeatherPrefetchReport(List.of(CALL), 3, 3, null).aborted()).isFalse();
    }

    @Test
    @DisplayName("the call list is copied, so a later change to the collector's list cannot alter the report")
    void calls_areDefensivelyCopied() {
        List<PrefetchCall> live = new ArrayList<>(List.of(CALL));
        WeatherPrefetchReport report = new WeatherPrefetchReport(live, 1, 1, null);

        live.clear();

        assertThat(report.calls()).containsExactly(CALL);
    }

    @Test
    @DisplayName("a null call list becomes empty")
    void nullCalls_becomeEmpty() {
        assertThat(new WeatherPrefetchReport(null, 0, 0, null).calls()).isEmpty();
    }

    @Test
    @DisplayName("ScheduledBatchTasks defaults to none() with the old constructor and when given null")
    void scheduledBatchTasks_defaultsToNone() {
        ScheduledBatchTasks oldShape = new ScheduledBatchTasks(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        ScheduledBatchTasks withNull = new ScheduledBatchTasks(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
        WeatherPrefetchReport report = new WeatherPrefetchReport(List.of(CALL), 5, 5, null);
        ScheduledBatchTasks withReport = new ScheduledBatchTasks(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), report);

        assertThat(oldShape.weatherPrefetch()).isEqualTo(WeatherPrefetchReport.none());
        assertThat(withNull.weatherPrefetch()).isEqualTo(WeatherPrefetchReport.none());
        assertThat(withReport.weatherPrefetch()).isSameAs(report);
        assertThat(ScheduledBatchTasks.empty().weatherPrefetch()).isEqualTo(WeatherPrefetchReport.none());
    }

    @Test
    @DisplayName("RegionFilteredBatchTasks.aborted() is empty and carries the report; empty() carries none")
    void regionFilteredBatchTasks_abortedCarriesReport() {
        WeatherPrefetchReport report = new WeatherPrefetchReport(List.of(CALL), 5, 0, "0/5 locations returned");

        RegionFilteredBatchTasks aborted = RegionFilteredBatchTasks.aborted(report);

        assertThat(aborted.isEmpty()).isTrue();
        assertThat(aborted.weatherPrefetch()).isSameAs(report);
        assertThat(RegionFilteredBatchTasks.empty().weatherPrefetch()).isEqualTo(WeatherPrefetchReport.none());
        assertThat(new RegionFilteredBatchTasks(List.of(), List.of(), null).weatherPrefetch())
                .isEqualTo(WeatherPrefetchReport.none());
    }
}
