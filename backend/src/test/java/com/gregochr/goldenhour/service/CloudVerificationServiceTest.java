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
import static org.assertj.core.api.Assertions.tuple;
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
    /** Forecast overhead mid cloud a corridor fixture carries unless it states its own. */
    private static final int DEFAULT_FORECAST_CANVAS_MID = 60;
    /** Forecast overhead high cloud a corridor fixture carries unless it states its own. */
    private static final int DEFAULT_FORECAST_CANVAS_HIGH = 40;
    /** Forecast 226 km far-solar reading a corridor fixture carries unless it states its own. */
    private static final Integer DEFAULT_FORECAST_FAR_LOW = 25;

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
        CloudVerificationPair similar = corridorPair(50, 40, 55, 40, 30);
        // Near strip over a clear far corridor, under a high-dominant canvas — the over-pessimism
        // candidate: the 113 km gate reads blocked while the cirrus corridor is open. Being
        // high-dominant it is proxy evidence only, and must stay out of the &midCanvas cut.
        CloudVerificationPair clearerHigh = corridorPair(80, 20, 10, 90, 30);
        // Clear near point, blanketed far corridor, mid-dominant canvas — the false-optimism case
        // measured directly, since 226 km centres a 4 km canvas's corridor. It belongs in
        // &midCanvas and must not reach &highCanvas.
        CloudVerificationPair cloudierMid = corridorPair(20, 70, 60, 40, 30);
        // Same divergence as clearerHigh but under a mid-dominant canvas, so the two clearer
        // sub-buckets split one parent between them. Without this the &midCanvas cut would only
        // ever be asserted as zero — a bucket wired to return nothing would pass.
        CloudVerificationPair clearerMid = corridorPair(80, 20, 70, 30, 30);
        // Every fixture here forecasts a mid-band gap (30), which is neither the prompt's clear
        // band nor its blocked one — so the three forecast-conditioned cuts are correctly empty,
        // and their members are asserted next door in report_bucketsCorridorByForecastBand.
        when(repository.findVerifiedPairs(FROM, TO))
                .thenReturn(List.of(similar, clearerHigh, cloudierMid, clearerMid));
        when(repository.countVerifiedInWindow(FROM, TO)).thenReturn(4L);

        CloudVerificationReport report = service.report(FROM, TO);

        assertThat(report.byCorridor()).extracting(CloudVerificationBucket::key)
                .containsExactly("farSimilar(|drop|<30)", "farClearer(drop>=30)",
                        "farClearer&highCanvas", "farClearer&midCanvas",
                        "farCloudier(drop<=-30)", "farCloudier&highCanvas",
                        "farCloudier&midCanvas", "farCloudier&fcstClear(<20)",
                        "farClearer&fcstBlocked(>60)", "farCloudier&fcstIdeal",
                        "farClearer&fcstBlocked&stripSeen", "farClearer&fcstBlocked&stripMissed",
                        "fcstBlanket", "fcstBlanket&corridorOpen(obs<=30)",
                        "fcstBlanket&corridorBlanketed(obs>=50)",
                        "fcstBlanket&underTriageCut(<=80)",
                        "fcstBlanket&underTriageCut&corridorOpen(obs<=30)");
        // Each canvas cut both holds a sample and excludes the other's: the two clearer pairs
        // split 1/1 by dominance, and the sole cloudier pair is mid-dominant so &highCanvas is 0.
        // None of these fixtures forecast a >60 gap, so fcstBlocked and both its THIN STRIP
        // sub-buckets are correctly empty — that split is exercised in
        // report_bucketsCorridorByForecastBand instead. The blanket family is empty for a
        // different reason: every fixture here forecasts a gap of 30 and a far reading of 25, so
        // neither of the label's two >=50 arms is reached — see report_bucketsBlanketPrecision.
        // Both underTriageCut buckets follow their parent to zero: a gap of 30 is under the triage
        // cut, but the cut is applied to blanket members only.
        assertThat(report.byCorridor()).extracting(CloudVerificationBucket::sampleCount)
                .containsExactly(1, 2, 1, 1, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    @Test
    @DisplayName("report cuts a divergent corridor by the forecast band each prompt rule fires on")
    void report_bucketsCorridorByForecastBand() {
        // Observed structure is held constant within each half — every cloudier pair diverges
        // 20 -> 70 and every clearer pair 80 -> 20 — so the ONLY thing separating members from
        // non-members below is the forecast, which is what these three buckets claim to cut on.
        // Cloudier, forecast gate clear, canvas overhead: the IDEAL rule's firing population.
        CloudVerificationPair ideal = corridorPair(20, 70, 60, 40, 10, 30, 40);
        // The same, with the forecast high canvas exactly at the threshold — a member, so the
        // clause stays >= and cannot silently become >.
        CloudVerificationPair idealAtCanvasEdge = corridorPair(20, 70, 60, 40, 10, 30, 20);
        // One point below that threshold: clear gate, no canvas. In fcstClear, out of fcstIdeal.
        CloudVerificationPair clearGateNoCanvas = corridorPair(20, 70, 60, 40, 10, 30, 19);
        // Clear gate under a forecast mid deck exactly at the threshold — out of fcstIdeal, so
        // the mid clause is load-bearing and its < cannot become <=.
        CloudVerificationPair clearGateThickMid = corridorPair(20, 70, 60, 40, 10, 50, 40);
        // Forecast gap exactly at the clear band's edge, under an otherwise ideal canvas: out of
        // BOTH forecast cuts. Pins the < in fcstClear and proves fcstIdeal's gap clause discards
        // a pair its canvas clauses would have admitted.
        CloudVerificationPair gapAtClearEdge = corridorPair(20, 70, 60, 40, 20, 30, 40);
        // Blocked gate over an ideal canvas, high-dominant observed sky: out of both cloudier
        // forecast cuts, and the second >60 gap on the cloudier side — so cutting fcstBlocked
        // from the wrong parent list reads 2 where the clearer side reads 1.
        CloudVerificationPair gapBlockedIdealCanvas = corridorPair(20, 70, 10, 90, 85, 30, 40);
        // Clearer half. Two members of the blocked band, one of them just above the edge.
        CloudVerificationPair blocked = corridorPair(80, 20, 70, 30, 85, 60, 40);
        CloudVerificationPair blockedJustOverEdge = corridorPair(80, 20, 70, 30, 61, 60, 40);
        // Exactly at the blocked band's edge: a non-member, pinning > against >=.
        CloudVerificationPair gapAtBlockedEdge = corridorPair(80, 20, 10, 90, 60, 60, 40);
        // A clearer pair carrying the IDEAL forecast: out of fcstBlocked, and the reason the
        // cloudier-only parent lists of fcstClear and fcstIdeal cannot be swapped unnoticed.
        CloudVerificationPair clearerIdealForecast = corridorPair(80, 20, 10, 90, 10, 30, 40);
        // --- THIN STRIP precondition split of farClearer&fcstBlocked, below. Every fixture in
        // this group reuses blocked's own near/far/canvas shape (80, 20, 70, 30) and forecast gap
        // 90, so the only thing varying is forecastFarLow — the strip precondition's other input.
        // Forecast drop 90-55=35 >= the 30pp threshold: the override's preconditions held.
        CloudVerificationPair stripSeen = corridorPair(80, 20, 70, 30, 90, 60, 40, 55);
        // Forecast drop 90-61=29, one point under the threshold: pins >= against > from below.
        CloudVerificationPair stripMissedJustUnder = corridorPair(80, 20, 70, 30, 90, 60, 40, 61);
        // Forecast drop exactly 30 — the edge itself, still a stripSeen member (>= cannot become >).
        CloudVerificationPair stripSeenAtEdge = corridorPair(80, 20, 70, 30, 90, 60, 40, 60);
        // No far reading in the forecast at all: the override could not have fired, so this is a
        // miss by design, not an unknown — it must land in stripMissed, not be silently dropped.
        CloudVerificationPair stripMissedNullFar = corridorPair(80, 20, 70, 30, 90, 60, 40, null);
        // Forecast gap 60, not >60: excluded from fcstBlocked entirely before the strip
        // precondition is even evaluated — its (unused) forecastFarLow of 10 would otherwise imply
        // a huge, easily-seen drop, so this pins that the fcstBlocked gate applies to both subs.
        CloudVerificationPair belowBlockedBand = corridorPair(80, 20, 70, 30, 60, 60, 40, 10);
        // A CLOUDIER pair (wrong-parent detector): forecast gap 90 and a forecast drop of 35 would
        // read as stripSeen if either sub-bucket were ever derived from the wrong parent list, but
        // this pair is not in "clearer" at all, so it must appear in neither sub-bucket.
        CloudVerificationPair cloudierWrongParent = corridorPair(20, 70, 60, 40, 90, 60, 40, 55);
        when(repository.findVerifiedPairs(FROM, TO))
                .thenReturn(List.of(ideal, idealAtCanvasEdge, clearGateNoCanvas, clearGateThickMid,
                        gapAtClearEdge, gapBlockedIdealCanvas, blocked, blockedJustOverEdge,
                        gapAtBlockedEdge, clearerIdealForecast, stripSeen, stripMissedJustUnder,
                        stripSeenAtEdge, stripMissedNullFar, belowBlockedBand, cloudierWrongParent));
        when(repository.countVerifiedInWindow(FROM, TO)).thenReturn(16L);

        CloudVerificationReport report = service.report(FROM, TO);

        // Named rather than positional, so a failure says which rule's population moved.
        assertThat(report.byCorridor())
                .extracting(CloudVerificationBucket::key, CloudVerificationBucket::sampleCount)
                .contains(tuple("farCloudier&fcstClear(<20)", 4),
                        tuple("farClearer&fcstBlocked(>60)", 6),
                        tuple("farCloudier&fcstIdeal", 2),
                        tuple("farClearer&fcstBlocked&stripSeen", 4),
                        tuple("farClearer&fcstBlocked&stripMissed", 2),
                        tuple("fcstBlanket", 4),
                        tuple("fcstBlanket&corridorOpen(obs<=30)", 3),
                        tuple("fcstBlanket&corridorBlanketed(obs>=50)", 1));
        // The whole vector, counted by hand. Observed: no similar pair; nine clearer (2
        // high-dominant from the original pairs, 7 mid- — the two originals plus the five new
        // strip-split fixtures, all mid-dominant); seven cloudier (gapBlockedIdealCanvas
        // high-dominant, the other six mid-, including cloudierWrongParent). Forecast: fcstClear
        // stays at the original four gap-10 cloudier pairs; fcstBlocked grows from 2 to 6 — the
        // original two plus stripSeen, stripMissedJustUnder, stripSeenAtEdge and
        // stripMissedNullFar (belowBlockedBand stays out at gap 60, cloudierWrongParent stays out
        // for being cloudier, not clearer); fcstIdeal stays at 2, since cloudierWrongParent's gap
        // (90) never enters the IDEAL band. Of the six fcstBlocked members, stripSeen holds the
        // original two (drops 60 and 36) plus stripSeen and stripSeenAtEdge (drops 35 and 30);
        // stripMissed holds stripMissedJustUnder (drop 29) and stripMissedNullFar (no far reading).
        // The blanket family cuts across all of that from the withFar parent: its four members are
        // the only fixtures whose forecast far reading reaches 50 (stripSeen 55, stripMissedJustUnder
        // 61, stripSeenAtEdge 60, cloudierWrongParent 55), all four forecasting a 90 gap. Three
        // observed an open corridor (far 20), cloudierWrongParent a blanketed one (far 70) — and
        // that pair, which must stay out of both strip sub-buckets for being cloudier, is a
        // legitimate blanket member, which is what makes the different parent visible. Three of the
        // four (stripSeen, stripSeenAtEdge and cloudierWrongParent — drops 35, 30 and 35) are pairs
        // the prompt would have labelled THIN STRIP rather than EXTENSIVE BLANKET: the deliberate
        // over-inclusion documented on forecastWasBlanket, pinned here so it cannot change
        // unnoticed. All four forecast a 90 gap, above the triage cut, so both underTriageCut
        // buckets are zero — which is the shape the caveat predicts, since a gap that high stands
        // the slot down before a prompt is ever built. The under-cut band is exercised in
        // report_bucketsBlanketPrecision.
        assertThat(report.byCorridor()).extracting(CloudVerificationBucket::sampleCount)
                .containsExactly(0, 9, 2, 7, 7, 1, 6, 4, 6, 2, 4, 2, 4, 3, 1, 0, 0);

        // By construction every fcstBlocked member falls into exactly one THIN STRIP sub-bucket.
        assertThat(bucketCount(report, "farClearer&fcstBlocked&stripSeen")
                + bucketCount(report, "farClearer&fcstBlocked&stripMissed"))
                .isEqualTo(bucketCount(report, "farClearer&fcstBlocked(>60)"));
        // The blanket sub-buckets never exceed their parent. No fixture here observed the 31-49
        // middle band, so the invariant holds with equality; report_bucketsBlanketPrecision covers
        // the strict case.
        assertThat(bucketCount(report, "fcstBlanket&corridorOpen(obs<=30)")
                + bucketCount(report, "fcstBlanket&corridorBlanketed(obs>=50)"))
                .isLessThanOrEqualTo(bucketCount(report, "fcstBlanket"));
    }

    @Test
    @DisplayName("report sizes the EXTENSIVE BLANKET label's firing population and scores it")
    void report_bucketsBlanketPrecision() {
        // Observed near is 45 and the observed canvas mid-dominant (70/30) throughout, so the only
        // observed thing that moves is the far reading — the axis these sub-buckets cut on — and
        // every &highCanvas bucket is empty by construction rather than by accident.
        // The forecast near arm, at the edge and one under it. Forecast far 55 clears the other
        // arm and observed far 20 is an open corridor, so the edge fixture is a member of the
        // parent and of the false-blanket sub-bucket at once.
        CloudVerificationPair nearArmAtEdge = corridorPair(45, 20, 70, 30, 50, 60, 40, 55);
        CloudVerificationPair nearArmOneUnder = corridorPair(45, 20, 70, 30, 49, 60, 40, 55);
        // The forecast far arm, likewise. The prompt would in fact print THIN STRIP over this pair
        // rather than EXTENSIVE BLANKET — a 90-to-50 drop satisfies isThinStrip, which wins the
        // else-if — so this pins the deliberate arms-not-printing over-inclusion documented on
        // forecastWasBlanket as much as it pins the >= at the far arm.
        CloudVerificationPair farArmAtEdge = corridorPair(45, 20, 70, 30, 90, 60, 40, 50);
        CloudVerificationPair farArmOneUnder = corridorPair(45, 20, 70, 30, 90, 60, 40, 49);
        // Forecast gap 55: inside the blanket's >=50 arm, outside the BLOCKED band's >60. Its
        // observed 45-over-10 also puts it in farClearer, so farClearer&fcstBlocked(>60) is a zero
        // with a live parent — wire this family to the blocked threshold and the pair moves
        // between those two counts, failing both.
        CloudVerificationPair blanketUnderBlockedBand = corridorPair(45, 10, 70, 30, 55, 60, 40, 55);
        // The observed corridor bands, all forecasting 90/90 — a blanket call with no strip drop —
        // so nothing but the observed far reading decides which sub-bucket each lands in.
        CloudVerificationPair corridorOpenAtEdge = corridorPair(45, 30, 70, 30, 90, 60, 40, 90);
        CloudVerificationPair corridorMiddleLow = corridorPair(45, 31, 70, 30, 90, 60, 40, 90);
        CloudVerificationPair corridorMiddleHigh = corridorPair(45, 49, 70, 30, 90, 60, 40, 90);
        CloudVerificationPair corridorBlanketedAtEdge =
                corridorPair(45, 50, 70, 30, 90, 60, 40, 90);
        // The triage cut, at the edge and one over it. WeatherTriageEvaluator stands a slot down
        // above 80% solar low cloud before any prompt is built, so the pair at exactly 80 could
        // have reached Claude and the one at 81 could not — while both satisfy the label's arms
        // and both sit in the pre-registered parent, which is the whole point of the sub-cut.
        CloudVerificationPair triageCutAtEdge = corridorPair(45, 20, 70, 30, 80, 60, 40, 55);
        CloudVerificationPair triageCutOneOver = corridorPair(45, 20, 70, 30, 81, 60, 40, 55);
        // Under the cut but over a blanketed corridor: keeps underTriageCut&corridorOpen strictly
        // smaller than underTriageCut, so a sub-bucket that simply echoed its parent would fail.
        CloudVerificationPair underCutBlanketedCorridor =
                corridorPair(45, 50, 70, 30, 80, 60, 40, 90);
        // Under the cut, observed far exactly at the open edge. The corridorOpen edge is pinned
        // for the pre-registered bucket by corridorOpenAtEdge/corridorMiddleLow above; without
        // this fixture the under-cut bucket carries no member at 30, so tightening its own <= to
        // < would leave every count unchanged and the suite green. Both now read the same shared
        // predicate, and both have a member sitting on it.
        CloudVerificationPair underCutCorridorOpenAtEdge =
                corridorPair(45, 30, 70, 30, 80, 60, 40, 90);
        // No forecast far reading at all: the label is only ever appended to a far-field line, so
        // it could not have fired. A non-member, not an unknown.
        CloudVerificationPair nullForecastFar = corridorPair(45, 20, 70, 30, 90, 60, 40, null);
        // Both forecast arms satisfied but nothing analysed at 226 km. Excluded by the withFar
        // parent, so it can neither pad the denominator nor be scored open or blanketed — which is
        // what keeps the ratio a precision figure rather than a coverage one.
        CloudVerificationPair noObservedFar = blanketPairWithoutObservedFar();
        when(repository.findVerifiedPairs(FROM, TO))
                .thenReturn(List.of(nearArmAtEdge, nearArmOneUnder, farArmAtEdge, farArmOneUnder,
                        blanketUnderBlockedBand, corridorOpenAtEdge, corridorMiddleLow,
                        corridorMiddleHigh, corridorBlanketedAtEdge, triageCutAtEdge,
                        triageCutOneOver, underCutBlanketedCorridor, underCutCorridorOpenAtEdge,
                        nullForecastFar, noObservedFar));
        when(repository.countVerifiedInWindow(FROM, TO)).thenReturn(15L);

        CloudVerificationReport report = service.report(FROM, TO);

        // Named rather than positional, so a failure says which claim moved. The blocked bucket is
        // asserted here too: its parent holds blanketUnderBlockedBand, so the zero is the >60
        // threshold refusing a pair the >=50 family counted, not an empty parent.
        assertThat(report.byCorridor())
                .extracting(CloudVerificationBucket::key, CloudVerificationBucket::sampleCount)
                .contains(tuple("fcstBlanket", 11),
                        tuple("fcstBlanket&corridorOpen(obs<=30)", 7),
                        tuple("fcstBlanket&corridorBlanketed(obs>=50)", 2),
                        tuple("fcstBlanket&underTriageCut(<=80)", 5),
                        tuple("fcstBlanket&underTriageCut&corridorOpen(obs<=30)", 4),
                        tuple("farClearer&fcstBlocked(>60)", 0));
        // The whole vector, counted by hand. Thirteen pairs sit in farSimilar (|drop| of 25, 25,
        // 25, 25, 15, 14, 4, 5, 25, 25, 5, 15 and 25) and blanketUnderBlockedBand alone in
        // farClearer (drop 35), mid-dominant; nothing is cloudier. Of the fourteen, eleven satisfy
        // both blanket arms — everything except nearArmOneUnder (gap 49), farArmOneUnder (far 49)
        // and nullForecastFar. Seven of those eleven observed an open corridor (far 20, 20, 10,
        // 30, 20, 20 and 30) and two a blanketed one (far 50 twice); the two middle-band pairs (31
        // and 49) are in neither. Five of the eleven are at or under the triage cut (gaps 50, 55,
        // 80, 80 and 80) and four of those five observed an open corridor —
        // underCutBlanketedCorridor is the fifth.
        assertThat(report.byCorridor()).extracting(CloudVerificationBucket::sampleCount)
                .containsExactly(13, 1, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 11, 7, 2, 5, 4);
        // Fifteen pairs verified, fourteen in the corridor parent: noObservedFar reached the
        // report and was dropped by the parent filter, not by one of the new predicates.
        assertThat(report.overall().sampleCount()).isEqualTo(15);

        // The invariant, from the report's own values. Strict here, because the 31-49 band is
        // deliberately unbucketed: those two members are counted in the denominator and scored as
        // neither open nor blanketed rather than being forced into whichever side is nearer.
        assertThat(bucketCount(report, "fcstBlanket&corridorOpen(obs<=30)")
                + bucketCount(report, "fcstBlanket&corridorBlanketed(obs>=50)"))
                .isEqualTo(9)
                .isLessThan(bucketCount(report, "fcstBlanket"));
        // The decontaminated cut is a strict subset of the pre-registered parent, and its own
        // corridorOpen a strict subset of it — so neither can be an alias of what it hangs off.
        // Both ratios are readable: 7/11 over the whole parent, 4/5 over the members that could
        // have been prompted, and they differ, which is the reason the sub-cut exists.
        assertThat(bucketCount(report, "fcstBlanket&underTriageCut(<=80)"))
                .isLessThan(bucketCount(report, "fcstBlanket"));
        assertThat(bucketCount(report, "fcstBlanket&underTriageCut&corridorOpen(obs<=30)"))
                .isLessThan(bucketCount(report, "fcstBlanket&underTriageCut(<=80)"));
    }

    private int bucketCount(CloudVerificationReport report, String key) {
        return report.byCorridor().stream()
                .filter(bucket -> bucket.key().equals(key))
                .findFirst()
                .orElseThrow()
                .sampleCount();
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

    /**
     * A pair whose forecast satisfies both EXTENSIVE BLANKET arms but which carries no analysed
     * far reading — the one shape the corridor parent filter exists to exclude.
     *
     * @return the pair
     */
    private CloudVerificationPair blanketPairWithoutObservedFar() {
        return new CloudVerificationPair("Durham UK", DATE, TargetType.SUNSET, 0, 2,
                90, 45, 60, 40, 70, 30, false, 20, 120, 240, 250,
                55, null, null, null);
    }

    private CloudVerificationPair corridorPair(int nearLow, int farLow, int canvasMid,
            int canvasHigh, int forecastGapLow) {
        return corridorPair(nearLow, farLow, canvasMid, canvasHigh, forecastGapLow,
                DEFAULT_FORECAST_CANVAS_MID, DEFAULT_FORECAST_CANVAS_HIGH);
    }

    private CloudVerificationPair corridorPair(int nearLow, int farLow, int canvasMid,
            int canvasHigh, int forecastGapLow, int forecastCanvasMid, int forecastCanvasHigh) {
        return corridorPair(nearLow, farLow, canvasMid, canvasHigh, forecastGapLow,
                forecastCanvasMid, forecastCanvasHigh, DEFAULT_FORECAST_FAR_LOW);
    }

    private CloudVerificationPair corridorPair(int nearLow, int farLow, int canvasMid,
            int canvasHigh, int forecastGapLow, int forecastCanvasMid, int forecastCanvasHigh,
            Integer forecastFarLow) {
        return new CloudVerificationPair("Durham UK", DATE, TargetType.SUNSET, 0, 2,
                forecastGapLow, nearLow, forecastCanvasMid, forecastCanvasHigh,
                canvasMid, canvasHigh, false, 20, 120, 240, 250,
                forecastFarLow, null, null, farLow);
    }
}
