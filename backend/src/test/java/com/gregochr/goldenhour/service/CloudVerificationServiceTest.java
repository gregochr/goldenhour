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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CloudVerificationService}.
 */
@ExtendWith(MockitoExtension.class)
class CloudVerificationServiceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 5, 1);
    private static final LocalDate TO = LocalDate.of(2026, 5, 31);
    private static final LocalDate DATE = LocalDate.of(2026, 5, 10);
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-05-31T09:00:00Z"), ZoneOffset.UTC);

    @Mock
    private OpenMeteoArchiveClient archiveClient;

    @Mock
    private CloudVerificationRepository repository;

    private CloudVerificationService service;

    @BeforeEach
    void setUp() {
        service = new CloudVerificationService(archiveClient, repository, CLOCK);
    }

    @Test
    @DisplayName("backfill samples the cone, the observer and the far-solar point, at the event hour")
    void backfill_samplesAllPoints() {
        when(repository.findUnverified(any(), any())).thenReturn(List.of(candidate()));
        // Cone bearings read 60/85/95, the observer the default, the far-solar point 20 — so the
        // mean, the extremes and the far reading are all distinguishable in the saved row.
        when(archiveClient.fetchArchiveBatch(any(), any(), anyString()))
                .thenReturn(List.of(archive(60), archive(85), archive(95), archive(), archive(20)));

        assertThat(service.backfill(10).rowsWritten()).isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CloudVerificationEntity>> saved =
                ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(saved.capture());
        CloudVerificationEntity row = saved.getValue().getFirst();

        // Horizon point is offset from the observer along the solar azimuth, not the observer itself.
        assertThat(row.getHorizonSampleLat()).isNotEqualTo(54.7753);
        // 21:37 sunset resolves to the 21:00 slot — the hour the forecast itself scored.
        assertThat(row.getObservedAt()).isEqualTo(LocalDateTime.of(2026, 5, 10, 21, 0));
        assertThat(row.getHorizonLowCloud()).isEqualTo(80);
        // The extremes carry the structure the mean throws away.
        assertThat(row.getHorizonLowMin()).isEqualTo(60);
        assertThat(row.getHorizonLowMax()).isEqualTo(95);
        assertThat(row.getFarLowCloud()).isEqualTo(20);
        assertThat(row.getObserverMidCloud()).isEqualTo(55);
    }

    @Test
    @DisplayName("backfill records a row even when the archive has no data, so it is not retried")
    void backfill_archiveFailure_stillRecordsRow() {
        when(repository.findUnverified(any(), any())).thenReturn(List.of(candidate()));
        when(archiveClient.fetchArchiveBatch(any(), any(), anyString()))
                .thenReturn(Arrays.asList(null, null, null, null, null));

        assertThat(service.backfill(10).rowsWritten()).isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CloudVerificationEntity>> saved =
                ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(saved.capture());
        CloudVerificationEntity row = saved.getValue().getFirst();
        assertThat(row.getHorizonLowCloud()).isNull();
        assertThat(row.getFarLowCloud()).isNull();
        assertThat(row.getObservedAt()).isNull();
        assertThat(row.getForecastEvaluationId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("backfill batches one request per date, five points per candidate, in order")
    void backfill_batchesByDateWithConeObserverThenFar() {
        VerificationCandidate first = candidate();
        VerificationCandidate second = new VerificationCandidate(43L, 55.0, -2.0, 250,
                LocalDateTime.of(2026, 5, 10, 21, 40), TargetType.SUNSET);
        when(repository.findUnverified(any(), any())).thenReturn(List.of(first, second));
        when(archiveClient.fetchArchiveBatch(any(), any(), anyString()))
                .thenReturn(List.of(archive(), archive(), archive(), archive(), archive(),
                        archive(), archive(), archive(), archive(), archive()));

        assertThat(service.backfill(10).rowsWritten()).isEqualTo(2);

        // Same date → exactly one batched request, not one call per point.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<double[]>> points = ArgumentCaptor.forClass(List.class);
        verify(archiveClient).fetchArchiveBatch(
                points.capture(), eq(LocalDate.of(2026, 5, 10)), anyString());

        // Five points per candidate: the three solar-cone bearings, the observer, then the
        // far-solar corridor point. The cone matters because the forecast's own solar reading is
        // a 3-point average; the far point reads the canvas-underlighting corridor (centre of the
        // mid-canvas corridor, near edge of the high-canvas one).
        assertThat(points.getValue()).hasSize(10);
        assertThat(points.getValue().get(3)).containsExactly(54.7753, -1.5849);
        assertThat(points.getValue().get(8)).containsExactly(55.0, -2.0);
        // Cone bearings and the far point are offset along the azimuth, not at their observer.
        assertThat(points.getValue().get(0)[0]).isNotEqualTo(54.7753);
        assertThat(points.getValue().get(4)[0]).isNotEqualTo(54.7753);
        assertThat(points.getValue().get(5)[0]).isNotEqualTo(55.0);
    }

    @Test
    @DisplayName("backfill issues one request per distinct date")
    void backfill_separateRequestPerDate() {
        VerificationCandidate may10 = candidate();
        VerificationCandidate may11 = new VerificationCandidate(43L, 55.0, -2.0, 250,
                LocalDateTime.of(2026, 5, 11, 21, 40), TargetType.SUNSET);
        when(repository.findUnverified(any(), any())).thenReturn(List.of(may10, may11));
        when(archiveClient.fetchArchiveBatch(any(), any(), anyString()))
                .thenReturn(List.of(archive(), archive(), archive(), archive(),
                        archive(), archive(), archive(), archive()));

        assertThat(service.backfill(10).rowsWritten()).isEqualTo(2);

        verify(archiveClient).fetchArchiveBatch(any(), eq(LocalDate.of(2026, 5, 10)), anyString());
        verify(archiveClient).fetchArchiveBatch(any(), eq(LocalDate.of(2026, 5, 11)), anyString());
    }

    @Test
    @DisplayName("backfill excludes dates the archive has not caught up with yet")
    void backfill_appliesArchiveLagCutoff() {
        when(repository.findUnverified(any(), any())).thenReturn(List.of());

        assertThat(service.backfill(10).rowsWritten()).isZero();

        ArgumentCaptor<LocalDate> cutoff = ArgumentCaptor.forClass(LocalDate.class);
        verify(repository).findUnverified(cutoff.capture(), any());
        assertThat(cutoff.getValue())
                .isEqualTo(LocalDate.of(2026, 5, 31).minusDays(
                        CloudVerificationService.ARCHIVE_LAG_DAYS));
        verify(repository, never()).saveAll(any());
    }

    @Test
    @DisplayName("report splits veto firings by the 200 km cap — the D7 question")
    void report_splitsVetoByCap() {
        // Same veto conditions, different upwind distance: one a real advection nowcast (120 km),
        // one clamped at the cap. The uncapped sample called a genuinely blocked horizon (90%
        // observed); the clamped one vetoed a horizon that was actually clear (20%). That is the
        // shape that would confirm D7 — the veto tracking reality only where the trajectory
        // identity holds.
        CloudVerificationPair uncapped = pair(true, 70, 120, 240, 90);
        CloudVerificationPair capped = pair(true, 70, 200, 240, 20);
        when(repository.findVerifiedPairs(FROM, TO)).thenReturn(List.of(uncapped, capped));
        when(repository.countVerifiedInWindow(FROM, TO)).thenReturn(2L);

        CloudVerificationReport report = service.report(FROM, TO);

        assertThat(report.vetoFired().sampleCount()).isEqualTo(2);
        assertThat(report.vetoUncapped().sampleCount()).isEqualTo(1);
        assertThat(report.vetoCapped().sampleCount()).isEqualTo(1);
        // Offset-immune: the uncapped sample saw a genuinely cloudy horizon (90%), the clamped one
        // vetoed a clear one (20%). The separation is what survives the reanalysis baseline's
        // systematic offset — an absolute threshold on either bucket alone would not.
        assertThat(report.vetoUncapped().meanObservedGapLow()).isEqualTo(90.0);
        assertThat(report.vetoCapped().meanObservedGapLow()).isEqualTo(20.0);
        assertThat(report.capSeparation()).isEqualTo(70.0);
    }

    @Test
    @DisplayName("report separates veto firings from non-firings")
    void report_separatesVetoFirings() {
        CloudVerificationPair fired = pair(true, 70, 120, 240, 90);
        CloudVerificationPair notFired = pair(false, 20, 120, 240, 90);
        when(repository.findVerifiedPairs(FROM, TO)).thenReturn(List.of(fired, notFired));
        when(repository.countVerifiedInWindow(FROM, TO)).thenReturn(2L);

        CloudVerificationReport report = service.report(FROM, TO);

        assertThat(report.overall().sampleCount()).isEqualTo(2);
        assertThat(report.vetoFired().sampleCount()).isEqualTo(1);
        assertThat(report.vetoNotFired().sampleCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("report buckets veto firings by wind-to-sun alignment")
    void report_bucketsByWindSunAngle() {
        // Solar azimuth 250 throughout; wind bearings pick the band.
        CloudVerificationPair aligned = pair(true, 70, 120, 240, 90);
        CloudVerificationPair oblique = pair(true, 70, 120, 160, 90);
        CloudVerificationPair opposed = pair(true, 70, 120, 60, 90);
        when(repository.findVerifiedPairs(FROM, TO))
                .thenReturn(List.of(aligned, oblique, opposed));
        when(repository.countVerifiedInWindow(FROM, TO)).thenReturn(3L);

        CloudVerificationReport report = service.report(FROM, TO);

        assertThat(report.byWindSunAngle()).extracting(CloudVerificationBucket::key)
                .containsExactly("aligned(<45)", "oblique(45-135)", "opposed(>135)");
        assertThat(report.byWindSunAngle()).extracting(CloudVerificationBucket::sampleCount)
                .containsExactly(1, 1, 1);
    }

    @Test
    @DisplayName("report buckets cone spread, so gapped horizons stop hiding behind the mean")
    void report_bucketsConeStructure() {
        // Same mean-ish horizon, radically different structure: a uniform deck, a broken one,
        // and a wall with a clear bearing that a 3-point mean renders identically to the deck.
        CloudVerificationPair uniform = conePair(60, 70);
        CloudVerificationPair mixed = conePair(50, 75);
        CloudVerificationPair gapped = conePair(0, 90);
        when(repository.findVerifiedPairs(FROM, TO))
                .thenReturn(List.of(uniform, mixed, gapped));
        when(repository.countVerifiedInWindow(FROM, TO)).thenReturn(3L);

        CloudVerificationReport report = service.report(FROM, TO);

        assertThat(report.byConeStructure()).extracting(CloudVerificationBucket::key)
                .containsExactly("uniform(spread<20)", "mixed(20-39)", "gapped(>=40)");
        assertThat(report.byConeStructure()).extracting(CloudVerificationBucket::sampleCount)
                .containsExactly(1, 1, 1);
        // Spreads 10, 25 and 90 average to 41.67 across the window.
        assertThat(report.overall().meanConeSpread()).isEqualTo(41.67);
    }

    @Test
    @DisplayName("report buckets corridor divergence and cuts it by both canvas heights")
    void report_bucketsCorridor() {
        // Near and far agree; the corridor is one deck.
        CloudVerificationPair similar = corridorPair(50, 40, 55, 40);
        // Near strip over a clear far corridor, under a high-dominant canvas — the over-pessimism
        // candidate: the 113 km gate reads blocked while the cirrus corridor is open. Being
        // high-dominant it is proxy evidence only, and must stay out of the &midCanvas cut.
        CloudVerificationPair clearerHigh = corridorPair(80, 20, 10, 90);
        // Clear near point, blanketed far corridor, mid-dominant canvas — the false-optimism case
        // measured directly, since 226 km centres a 4 km canvas's corridor. It belongs in
        // &midCanvas and must not reach &highCanvas.
        CloudVerificationPair cloudierMid = corridorPair(20, 70, 60, 40);
        // Same divergence as clearerHigh but under a mid-dominant canvas, so the two clearer
        // sub-buckets split one parent between them. Without this the &midCanvas cut would only
        // ever be asserted as zero — a bucket wired to return nothing would pass.
        CloudVerificationPair clearerMid = corridorPair(80, 20, 70, 30);
        when(repository.findVerifiedPairs(FROM, TO))
                .thenReturn(List.of(similar, clearerHigh, cloudierMid, clearerMid));
        when(repository.countVerifiedInWindow(FROM, TO)).thenReturn(4L);

        CloudVerificationReport report = service.report(FROM, TO);

        assertThat(report.byCorridor()).extracting(CloudVerificationBucket::key)
                .containsExactly("farSimilar(|drop|<30)", "farClearer(drop>=30)",
                        "farClearer&highCanvas", "farClearer&midCanvas",
                        "farCloudier(drop<=-30)", "farCloudier&highCanvas",
                        "farCloudier&midCanvas");
        // Each canvas cut both holds a sample and excludes the other's: the two clearer pairs
        // split 1/1 by dominance, and the sole cloudier pair is mid-dominant so &highCanvas is 0.
        assertThat(report.byCorridor()).extracting(CloudVerificationBucket::sampleCount)
                .containsExactly(1, 2, 1, 1, 1, 0, 1);
    }

    @Test
    @DisplayName("report rejects a missing or inverted window")
    void report_invalidWindow_throws() {
        assertThatThrownBy(() -> service.report(null, TO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.report(TO, FROM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be before");
    }

    private VerificationCandidate candidate() {
        return new VerificationCandidate(42L, 54.7753, -1.5849, 250,
                LocalDateTime.of(2026, 5, 10, 21, 37), TargetType.SUNSET);
    }

    private OpenMeteoForecastResponse archive() {
        return archive(85);
    }

    private OpenMeteoForecastResponse archive(int lowAtEventHour) {
        OpenMeteoForecastResponse.Hourly hourly = new OpenMeteoForecastResponse.Hourly();
        hourly.setTime(List.of("2026-05-10T20:00", "2026-05-10T21:00", "2026-05-10T22:00"));
        hourly.setCloudCoverLow(List.of(40, lowAtEventHour, 90));
        hourly.setCloudCoverMid(List.of(50, 55, 60));
        hourly.setCloudCoverHigh(List.of(30, 35, 40));
        OpenMeteoForecastResponse response = new OpenMeteoForecastResponse();
        response.setHourly(hourly);
        return response;
    }

    private CloudVerificationPair pair(boolean building, int upwindCurrent, int upwindDistanceKm,
            int windDirection, int observedGapLow) {
        return new CloudVerificationPair("Durham UK", DATE, TargetType.SUNSET, 0, 2,
                30, observedGapLow, 60, 40, 55, 40,
                building, upwindCurrent, upwindDistanceKm, windDirection, 250,
                null, null, null, null);
    }

    private CloudVerificationPair conePair(int coneMin, int coneMax) {
        return new CloudVerificationPair("Durham UK", DATE, TargetType.SUNSET, 0, 2,
                30, 80, 60, 40, 55, 40, false, 20, 120, 240, 250,
                null, coneMin, coneMax, null);
    }

    private CloudVerificationPair corridorPair(int nearLow, int farLow, int canvasMid,
            int canvasHigh) {
        return new CloudVerificationPair("Durham UK", DATE, TargetType.SUNSET, 0, 2,
                30, nearLow, 60, 40, canvasMid, canvasHigh, false, 20, 120, 240, 250,
                25, null, null, farLow);
    }
}
