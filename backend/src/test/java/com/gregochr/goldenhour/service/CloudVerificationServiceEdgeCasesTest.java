package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.CloudVerificationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.CloudVerificationBucket;
import com.gregochr.goldenhour.model.CloudVerificationPair;
import com.gregochr.goldenhour.model.CloudVerificationReport;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.VerificationCandidate;
import com.gregochr.goldenhour.repository.CloudVerificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Edge cases of {@link CloudVerificationService}: archive batches that come back short, null or partly empty, the
 * row-clearing and remaining-count helpers, and report pairs that lack optional readings.
 */
@ExtendWith(MockitoExtension.class)
class CloudVerificationServiceEdgeCasesTest {

    private static final LocalDate FROM = LocalDate.of(2026, 5, 1);
    private static final LocalDate TO = LocalDate.of(2026, 5, 31);
    private static final LocalDate DATE = LocalDate.of(2026, 5, 10);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-31T09:00:00Z"), ZoneOffset.UTC);

    @Mock
    private OpenMeteoArchiveClient archiveClient;
    @Mock
    private CloudVerificationRepository repository;

    private CloudVerificationService service;

    @BeforeEach
    void setUp() {
        service = new CloudVerificationService(archiveClient, repository, CLOCK);
    }

    private static VerificationCandidate candidate() {
        return new VerificationCandidate(42L, 54.7753, -1.5849, 250,
                LocalDateTime.of(2026, 5, 10, 21, 37), TargetType.SUNSET);
    }

    /** An archive whose three hours (20:00, 21:00, 22:00) carry the given low cloud, with fixed mid and high. */
    private static OpenMeteoForecastResponse archive(int lowAtEventHour) {
        OpenMeteoForecastResponse.Hourly hourly = new OpenMeteoForecastResponse.Hourly();
        hourly.setTime(List.of("2026-05-10T20:00", "2026-05-10T21:00", "2026-05-10T22:00"));
        hourly.setCloudCoverLow(List.of(40, lowAtEventHour, 90));
        hourly.setCloudCoverMid(List.of(50, 55, 60));
        hourly.setCloudCoverHigh(List.of(30, 35, 40));
        OpenMeteoForecastResponse response = new OpenMeteoForecastResponse();
        response.setHourly(hourly);
        return response;
    }

    private static OpenMeteoForecastResponse withoutHourly() {
        return new OpenMeteoForecastResponse();
    }

    private static OpenMeteoForecastResponse withoutTimes() {
        OpenMeteoForecastResponse response = new OpenMeteoForecastResponse();
        response.setHourly(new OpenMeteoForecastResponse.Hourly());
        return response;
    }

    private static OpenMeteoForecastResponse withTimesButNoLowCloud() {
        OpenMeteoForecastResponse response = archive(70);
        response.getHourly().setCloudCoverLow(null);
        return response;
    }

    private void stubOneCandidate(List<OpenMeteoForecastResponse> responses) {
        when(repository.findUnverified(LocalDate.of(2026, 5, 25), Limit.of(10))).thenReturn(List.of(candidate()));
        when(archiveClient.fetchArchiveBatch(org.mockito.ArgumentMatchers.argThat(points -> points != null
                && points.size() == 5), eq(DATE), org.mockito.ArgumentMatchers.contains("cloud_cover_low")))
                .thenReturn(responses);
    }

    private CloudVerificationEntity backfillOne() {
        assertThat(service.backfill(10).rowsWritten()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CloudVerificationEntity>> saved = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(saved.capture());
        return saved.getValue().getFirst();
    }

    // ── helpers on the repository ────────────────────────────────────────────────

    @Test
    @DisplayName("clearIncompleteVerifications returns how many rows the repository removed, none or some")
    void clearIncompleteVerifications_returnsTheRemovedCount() {
        when(repository.deleteIncompleteVerifications()).thenReturn(0, 4);

        assertThat(service.clearIncompleteVerifications()).isZero();
        assertThat(service.clearIncompleteVerifications()).isEqualTo(4);
    }

    @Test
    @DisplayName("countRemaining counts evaluations older than the archive lag from the clock's date")
    void countRemaining_usesTheArchiveLagCutoff() {
        when(repository.countUnverified(LocalDate.of(2026, 5, 25))).thenReturn(7L);

        assertThat(service.countRemaining()).isEqualTo(7L);
    }

    // ── short, null and partly empty archive batches ─────────────────────────────

    @Test
    @DisplayName("a short batch leaves the missing points null: the cone survives, observer and far do not")
    void shortBatch_leavesTheMissingPointsNull() {
        stubOneCandidate(List.of(archive(60), archive(80), archive(100)));

        CloudVerificationEntity row = backfillOne();

        assertThat(row.getHorizonLowCloud()).isEqualTo(80);
        assertThat(row.getHorizonLowMin()).isEqualTo(60);
        assertThat(row.getHorizonLowMax()).isEqualTo(100);
        assertThat(row.getObserverLowCloud()).isNull();
        assertThat(row.getObserverMidCloud()).isNull();
        assertThat(row.getFarLowCloud()).isNull();
    }

    @Test
    @DisplayName("a null batch records a row with no observations at all")
    void nullBatch_recordsAnEmptyRow() {
        stubOneCandidate(null);

        CloudVerificationEntity row = backfillOne();

        assertThat(row.getHorizonLowCloud()).isNull();
        assertThat(row.getObserverLowCloud()).isNull();
        assertThat(row.getFarLowCloud()).isNull();
    }

    @Test
    @DisplayName("cone points with no hourly block, no times, empty times or no low cloud are skipped, not averaged in")
    void conePointsWithGaps_areSkipped() {
        stubOneCandidate(Arrays.asList(
                archive(60), withoutHourly(), withoutTimes(), withTimesButNoLowCloud(), null));

        CloudVerificationEntity row = backfillOne();

        assertThat(row.getHorizonLowCloud()).as("only the one usable point is averaged").isEqualTo(60);
        assertThat(row.getHorizonLowMin()).isEqualTo(60);
        assertThat(row.getHorizonLowMax()).isEqualTo(60);
    }

    @Test
    @DisplayName("an observer or far point with no hourly block, or none of the times, records nothing for that point")
    void observerAndFarWithGaps_recordNothing() {
        stubOneCandidate(Arrays.asList(
                archive(60), archive(80), archive(100), withoutHourly(), withoutTimes()));

        CloudVerificationEntity row = backfillOne();

        assertThat(row.getHorizonLowCloud()).isEqualTo(80);
        assertThat(row.getObserverLowCloud()).isNull();
        assertThat(row.getObserverMidCloud()).isNull();
        assertThat(row.getObserverHighCloud()).isNull();
        assertThat(row.getFarLowCloud()).isNull();
        assertThat(row.getFarMidCloud()).isNull();
    }

    @Test
    @DisplayName("an observer point with times but no low-cloud series reads null layers")
    void observerWithoutLowCloudSeries_readsNullLayer() {
        stubOneCandidate(Arrays.asList(
                archive(60), archive(80), archive(100), withTimesButNoLowCloud(), archive(20)));

        CloudVerificationEntity row = backfillOne();

        assertThat(row.getObserverLowCloud()).isNull();
        assertThat(row.getObserverMidCloud()).isEqualTo(55);
        assertThat(row.getFarLowCloud()).isEqualTo(20);
    }

    // ── report pairs that lack optional readings ─────────────────────────────────

    private static CloudVerificationPair pair(Integer forecastGapLow, Integer observedGapLow, Integer canvasMid,
            Integer canvasHigh, Boolean building, Integer upwindLow, Integer windDirection, Integer forecastFarLow,
            Integer observedFarLow) {
        return new CloudVerificationPair("Durham UK", DATE, TargetType.SUNSET, 0, 2,
                forecastGapLow, observedGapLow, canvasMid, canvasHigh, 55, 40,
                building, upwindLow, 120, windDirection, 250,
                forecastFarLow, null, null, observedFarLow, null, null);
    }

    private static CloudVerificationBucket bucket(List<CloudVerificationBucket> buckets, String key) {
        return buckets.stream().filter(b -> key.equals(b.key())).findFirst()
                .orElseThrow(() -> new AssertionError("no bucket " + key + " in "
                        + buckets.stream().map(CloudVerificationBucket::key).toList()));
    }

    @Test
    @DisplayName("with no veto-fired pairs the veto and cap separations are null, not zero")
    void report_noVetoFiredPairs_separationsAreNull() {
        when(repository.findVerifiedPairs(FROM, TO)).thenReturn(List.of(
                pair(30, 50, 60, 40, false, 20, 240, 25, 30)));
        when(repository.countVerifiedInWindow(FROM, TO)).thenReturn(1L);

        CloudVerificationReport report = service.report(FROM, TO);

        assertThat(report.vetoFired().sampleCount()).isZero();
        assertThat(report.vetoSeparation()).isNull();
        assertThat(report.capSeparation()).isNull();
    }

    @Test
    @DisplayName("a veto-fired pair with no wind direction is left out of the wind-sun angle bands")
    void report_vetoFiredPairWithoutWindDirection_isNotBanded() {
        when(repository.findVerifiedPairs(FROM, TO)).thenReturn(List.of(
                pair(30, 80, 60, 40, true, 70, null, 25, 30)));
        when(repository.countVerifiedInWindow(FROM, TO)).thenReturn(1L);

        CloudVerificationReport report = service.report(FROM, TO);

        assertThat(report.vetoFired().sampleCount()).isEqualTo(1);
        assertThat(report.byWindSunAngle()).allSatisfy(b -> assertThat(b.sampleCount()).isZero());
    }

    @Test
    @DisplayName("pairs missing a forecast gap, far, canvas or observed far reading never reach the gated buckets")
    void report_pairsMissingReadings_areExcludedFromGatedBuckets() {
        // forecast far 70 and observed far 30 make it "far clearer"; every gating reading below is absent.
        when(repository.findVerifiedPairs(FROM, TO)).thenReturn(List.of(
                pair(null, 50, 60, 40, false, 20, 240, 70, 30),
                pair(70, 50, null, null, false, 20, 240, 70, null),
                pair(10, 50, 60, null, false, 20, 240, 25, 5),
                pair(10, 50, 30, 60, false, 20, 240, 25, 5)));
        when(repository.countVerifiedInWindow(FROM, TO)).thenReturn(4L);

        CloudVerificationReport report = service.report(FROM, TO);

        assertThat(report.overall().sampleCount()).isEqualTo(4);
        assertThat(bucket(report.byCorridor(), "farCloudier&fcstIdeal").sampleCount()).isZero();
        assertThat(bucket(report.byCorridor(), "fcstBlanket").sampleCount()).isZero();
    }

    @Test
    @DisplayName("the veto separation is the fired bucket's mean observed gap minus the not-fired one's")
    void report_vetoSeparation_isTheDifferenceOfMeanObservedGaps() {
        List<CloudVerificationPair> pairs = new ArrayList<>(List.of(
                pair(30, 80, 60, 40, true, 70, 240, 25, 30),
                pair(30, 50, 60, 40, false, 20, 240, 25, 30)));
        when(repository.findVerifiedPairs(FROM, TO)).thenReturn(pairs);
        when(repository.countVerifiedInWindow(FROM, TO)).thenReturn(2L);

        CloudVerificationReport report = service.report(FROM, TO);

        assertThat(report.vetoSeparation()).isEqualTo(30.0);
    }
}
