package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.CalibrationBucket;
import com.gregochr.goldenhour.model.CalibrationPair;
import com.gregochr.goldenhour.model.CalibrationReport;
import com.gregochr.goldenhour.repository.ActualOutcomeRepository;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ForecastCalibrationService}.
 */
@ExtendWith(MockitoExtension.class)
class ForecastCalibrationServiceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 5, 1);
    private static final LocalDate TO = LocalDate.of(2026, 5, 31);
    private static final LocalDate DATE = LocalDate.of(2026, 5, 10);

    @Mock
    private ForecastEvaluationRepository forecastEvaluationRepository;

    @Mock
    private ActualOutcomeRepository actualOutcomeRepository;

    private ForecastCalibrationService service;

    @BeforeEach
    void setUp() {
        service = new ForecastCalibrationService(
                forecastEvaluationRepository, actualOutcomeRepository);
    }

    @Test
    @DisplayName("report() keeps only the newest run for a slot at the same horizon")
    void report_sameSlotAndHorizon_keepsNewestRun() {
        // The nightly batch and the intraday refresh both wrote T+0 for this slot. Counting both
        // would weight one sunset twice; the later run is the one that was actually on screen.
        CalibrationPair stale = pair(0, LocalDateTime.of(2026, 5, 10, 1, 0), 1, 5);
        CalibrationPair fresh = pair(0, LocalDateTime.of(2026, 5, 10, 14, 0), 5, 5);
        when(forecastEvaluationRepository.findCalibrationPairs(FROM, TO))
                .thenReturn(List.of(stale, fresh));
        when(actualOutcomeRepository.findAllByOutcomeDateBetween(FROM, TO)).thenReturn(List.of());

        CalibrationReport report = service.report(FROM, TO);

        assertThat(report.scoredPairs()).isEqualTo(1);
        assertThat(report.overall().meanRatingError()).isZero();
        assertThat(report.overall().missedOpportunities()).isZero();
    }

    @Test
    @DisplayName("report() prefers a timestamped run over one with no run time")
    void report_missingRunTime_losesToTimestampedRun() {
        // forecast_run_at is non-nullable in the entity, so this is defensive — but the tie-break
        // must not let an untimestamped row displace a real one in either arrival order.
        CalibrationPair noTimestamp = pair(0, null, 1, 5);
        CalibrationPair timestamped = pair(0, LocalDateTime.of(2026, 5, 10, 14, 0), 5, 5);
        when(actualOutcomeRepository.findAllByOutcomeDateBetween(FROM, TO)).thenReturn(List.of());

        when(forecastEvaluationRepository.findCalibrationPairs(FROM, TO))
                .thenReturn(List.of(noTimestamp, timestamped));
        assertThat(service.report(FROM, TO).overall().missedOpportunities()).isZero();

        when(forecastEvaluationRepository.findCalibrationPairs(FROM, TO))
                .thenReturn(List.of(timestamped, noTimestamp));
        assertThat(service.report(FROM, TO).overall().missedOpportunities()).isZero();
    }

    @Test
    @DisplayName("report() keeps both horizons for the same slot")
    void report_differentHorizons_keepsBoth() {
        when(forecastEvaluationRepository.findCalibrationPairs(FROM, TO)).thenReturn(List.of(
                pair(0, LocalDateTime.of(2026, 5, 10, 1, 0), 4, 4),
                pair(3, LocalDateTime.of(2026, 5, 7, 1, 0), 1, 4)));
        when(actualOutcomeRepository.findAllByOutcomeDateBetween(FROM, TO)).thenReturn(List.of());

        CalibrationReport report = service.report(FROM, TO);

        assertThat(report.scoredPairs()).isEqualTo(2);
        assertThat(report.byDaysAhead()).extracting(CalibrationBucket::key)
                .containsExactly("T+0", "T+3");
        // The far-horizon forecast is the one that told the photographer to stay home.
        assertThat(bucket(report, "T+0").missedOpportunities()).isZero();
        assertThat(bucket(report, "T+3").missedOpportunities()).isEqualTo(1);
    }

    @Test
    @DisplayName("report() orders horizon buckets T+0 upward, not lexicographically")
    void report_ordersHorizonsNumerically() {
        when(forecastEvaluationRepository.findCalibrationPairs(FROM, TO)).thenReturn(List.of(
                pairAtDate(DATE.plusDays(1), 10, 3, 3),
                pairAtDate(DATE.plusDays(2), 2, 3, 3),
                pairAtDate(DATE.plusDays(3), 0, 3, 3)));
        when(actualOutcomeRepository.findAllByOutcomeDateBetween(FROM, TO)).thenReturn(List.of());

        CalibrationReport report = service.report(FROM, TO);

        assertThat(report.byDaysAhead()).extracting(CalibrationBucket::key)
                .containsExactly("T+0", "T+2", "T+10");
    }

    @Test
    @DisplayName("report() groups by model and labels a missing model rather than dropping it")
    void report_groupsByModel() {
        CalibrationPair haiku = pair(0, LocalDateTime.of(2026, 5, 10, 1, 0), 3, 3);
        CalibrationPair noModel = new CalibrationPair("Edinburgh", DATE, TargetType.SUNSET, 0,
                LocalDateTime.of(2026, 5, 10, 1, 0), null, 3, null, null, 3, null, null, true);
        when(forecastEvaluationRepository.findCalibrationPairs(FROM, TO))
                .thenReturn(List.of(haiku, noModel));
        when(actualOutcomeRepository.findAllByOutcomeDateBetween(FROM, TO)).thenReturn(List.of());

        CalibrationReport report = service.report(FROM, TO);

        assertThat(report.byModel()).extracting(CalibrationBucket::key)
                .containsExactly("HAIKU", "UNKNOWN");
        assertThat(report.scoredPairs()).isEqualTo(2);
    }

    @Test
    @DisplayName("report() returns an empty report rather than failing when nothing is recorded")
    void report_noPairs_returnsEmptyReport() {
        when(forecastEvaluationRepository.findCalibrationPairs(FROM, TO)).thenReturn(List.of());
        when(actualOutcomeRepository.findAllByOutcomeDateBetween(FROM, TO)).thenReturn(List.of());

        CalibrationReport report = service.report(FROM, TO);

        assertThat(report.scoredPairs()).isZero();
        assertThat(report.overall().sampleCount()).isZero();
        assertThat(report.overall().meanAbsoluteRatingError()).isNull();
        assertThat(report.byDaysAhead()).isEmpty();
        assertThat(report.byModel()).isEmpty();
    }

    @Test
    @DisplayName("report() rejects a missing or inverted window")
    void report_invalidWindow_throws() {
        assertThatThrownBy(() -> service.report(null, TO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.report(FROM, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.report(TO, FROM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be before");
    }

    private CalibrationBucket bucket(CalibrationReport report, String key) {
        return report.byDaysAhead().stream()
                .filter(b -> b.key().equals(key))
                .findFirst()
                .orElseThrow();
    }

    private CalibrationPair pair(int daysAhead, LocalDateTime runAt, int predicted, int actual) {
        return pairAtDate(DATE, daysAhead, predicted, actual, runAt);
    }

    private CalibrationPair pairAtDate(LocalDate date, int daysAhead, int predicted, int actual) {
        return pairAtDate(date, daysAhead, predicted, actual,
                LocalDateTime.of(2026, 5, 9, 1, 0));
    }

    private CalibrationPair pairAtDate(LocalDate date, int daysAhead, int predicted, int actual,
            LocalDateTime runAt) {
        return new CalibrationPair("Durham", date, TargetType.SUNSET, daysAhead, runAt,
                EvaluationModel.HAIKU, predicted, null, null, actual, null, null, true);
    }
}
