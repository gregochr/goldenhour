package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.CloudVerificationEntity;
import com.gregochr.goldenhour.model.BackfillBatch;
import com.gregochr.goldenhour.model.CloudVerificationBucket;
import com.gregochr.goldenhour.model.CloudVerificationPair;
import com.gregochr.goldenhour.model.CloudVerificationReport;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.VerificationCandidate;
import com.gregochr.goldenhour.repository.CloudVerificationRepository;
import com.gregochr.goldenhour.util.TimeSlotUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Scores past forecasts' cloud claims against reanalysed cloud from Open-Meteo's archive.
 *
 * <p>Exists because {@code actual_outcome} is empty: no photographer has ever recorded what a sky
 * did, so every scoring rule — including the cloud-approach veto, which forces rating 1–2 on a
 * large share of evaluations — is unvalidated. Aesthetic quality still needs a human, but every
 * threshold those rules actually turn on is a <em>cloud</em> claim, and cloud is machine-checkable.
 *
 * <p>Two claims are verified per evaluation: low cloud at the solar horizon decides whether the
 * low sun gets through (the gap), and mid/high cloud overhead is what that light lands on (the
 * canvas). Alongside them, the <em>measurement pass</em> records what the forecast's own
 * persistence discards, so the sampling geometry itself can be evaluated: the cone's low-cloud
 * extremes (is the horizon a uniform deck or a wall with a gap the mean hides?) and the 226 km
 * far-solar reading (is the canvas-underlighting corridor behaving differently from the 113 km
 * point the gate actually looks at? 226 km is the exact centre of a 4 km mid canvas's blocking
 * corridor and only the near edge of an 8 km high canvas's, whose centre is ~319 km — see
 * {@code DirectionalSamplingGeometry.FAR_SOLAR_OFFSET_METRES}).
 */
@Service
public class CloudVerificationService {

    private static final Logger LOG = LoggerFactory.getLogger(CloudVerificationService.class);

    /**
     * Days the reanalysis archive trails real time. Target dates newer than this are skipped
     * rather than recorded as unverifiable, so they are picked up by a later pass.
     */
    static final int ARCHIVE_LAG_DAYS = 6;

    /** Hourly variables requested from the archive — the same three layers the forecast uses. */
    private static final String ARCHIVE_HOURLY = "cloud_cover_low,cloud_cover_mid,cloud_cover_high";

    /**
     * Archive points sampled per evaluation: the three solar-cone bearings, the observer, and the
     * 226 km far-solar corridor point.
     */
    private static final int POINTS_PER_CANDIDATE =
            DirectionalSamplingGeometry.SOLAR_CONE_POINT_COUNT + 2;

    /** Offset of the observer point within a candidate's slice of the batch. */
    private static final int OBSERVER_OFFSET = DirectionalSamplingGeometry.SOLAR_CONE_POINT_COUNT;

    /** Offset of the far-solar point within a candidate's slice of the batch. */
    private static final int FAR_SOLAR_OFFSET =
            DirectionalSamplingGeometry.SOLAR_CONE_POINT_COUNT + 1;

    /** Cone spread (pp) at or above which the analysed horizon reads as broken rather than uniform. */
    private static final int CONE_SPREAD_MIXED_PP = 20;

    /**
     * Cone spread (pp) at or above which the analysed horizon is a wall-with-gap — one bearing
     * clear while another is blocked, the exact structure a 3-point mean cannot represent.
     */
    private static final int CONE_SPREAD_GAPPED_PP = 40;

    /**
     * Near-vs-far low cloud divergence (pp) treated as structural. Mirrors the production
     * strip-vs-blanket rule's ≥30pp drop threshold, and is offset-immune here because both
     * readings come from the same reanalysis baseline.
     */
    private static final int FAR_DIVERGENCE_PP = 30;

    /** Wind-to-sun separation (degrees) below which cloud approaches from the sun's direction. */
    private static final int ALIGNED_MAX_DEG = 45;

    /** Wind-to-sun separation (degrees) above which cloud approaches from behind the observer. */
    private static final int OPPOSED_MIN_DEG = 135;

    private final OpenMeteoArchiveClient archiveClient;
    private final CloudVerificationRepository repository;
    private final Clock clock;

    /**
     * Constructs the verification service.
     *
     * @param archiveClient batched Open-Meteo historical weather reader
     * @param repository    verification persistence and reporting queries
     * @param clock         UTC clock, for the archive-lag cutoff
     */
    public CloudVerificationService(OpenMeteoArchiveClient archiveClient,
            CloudVerificationRepository repository, Clock clock) {
        this.archiveClient = archiveClient;
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Verifies up to {@code maxRows} unverified evaluations against the archive.
     *
     * <p>Resumable and idempotent: candidates are selected by anti-join, and every attempt writes
     * a row — including one that found no archive data — so nothing is fetched twice.
     *
     * @param maxRows maximum evaluations to verify in this pass
     * @return counts of rows written and rows that actually carry observations
     */
    @Transactional
    public BackfillBatch backfill(int maxRows) {
        LocalDate cutoff = LocalDate.now(clock).minusDays(ARCHIVE_LAG_DAYS);
        List<VerificationCandidate> candidates =
                repository.findUnverified(cutoff, Limit.of(maxRows));
        if (candidates.isEmpty()) {
            LOG.info("[CLOUD VERIFY] nothing to verify at or before {}", cutoff);
            return BackfillBatch.EMPTY;
        }

        // Group by date: the archive takes one date range per request, but accepts many
        // coordinates within it. Each candidate contributes two points (horizon + observer), so
        // batching cuts request count by BATCH_COORD_LIMIT — the difference between a backfill
        // that finishes in one session and one that spends days against the daily rate ceiling.
        Map<LocalDate, List<VerificationCandidate>> byDate = candidates.stream()
                .collect(Collectors.groupingBy(c -> c.solarEventTime().toLocalDate(),
                        LinkedHashMap::new, Collectors.toList()));

        List<CloudVerificationEntity> verified = new ArrayList<>(candidates.size());
        for (Map.Entry<LocalDate, List<VerificationCandidate>> entry : byDate.entrySet()) {
            verified.addAll(verifyDate(entry.getKey(), entry.getValue()));
        }
        repository.saveAll(verified);

        int withData = (int) verified.stream()
                .filter(v -> v.getHorizonLowCloud() != null).count();
        LOG.info("[CLOUD VERIFY] verified={} withObservations={} dates={} cutoff={}",
                verified.size(), withData, byDate.size(), cutoff);
        return new BackfillBatch(verified.size(), withData);
    }

    /**
     * Deletes verification rows missing any observation the current sampling records, making them
     * candidates again.
     *
     * <p>A row written during an upstream outage records only that an attempt happened. Because
     * candidate selection is an anti-join, such a row would otherwise mask that evaluation
     * permanently. Clearing them is what makes the backfill self-healing rather than needing
     * manual SQL after every throttled run.
     *
     * <p>The rule covers <em>incomplete</em> rows, not just empty ones: a row verified before the
     * measurement pass added the cone extremes and the far-solar reading lacks those columns, so
     * the first run after that deploy deliberately re-verifies the whole history. That is the
     * measurement pass — the old rows kept only the cone mean, which cannot answer the questions
     * the new columns exist for.
     *
     * @return the number of incomplete rows removed
     */
    @Transactional
    public int clearIncompleteVerifications() {
        int cleared = repository.deleteIncompleteVerifications();
        if (cleared > 0) {
            LOG.info("[CLOUD VERIFY] cleared {} incomplete verification row(s) for re-attempt",
                    cleared);
        }
        return cleared;
    }

    /**
     * Counts evaluations still awaiting verification, for backfill progress reporting.
     *
     * @return the number of unverified evaluations old enough for the archive
     */
    @Transactional(readOnly = true)
    public long countRemaining() {
        return repository.countUnverified(LocalDate.now(clock).minusDays(ARCHIVE_LAG_DAYS));
    }

    /**
     * Verifies every candidate sharing one date, in batched archive requests.
     *
     * <p>Points are laid out {@link #POINTS_PER_CANDIDATE} per candidate — the three solar-cone
     * bearings, then the observer, then the far-solar point — so a single batch covers all claims
     * for the whole date and the responses map back positionally.
     *
     * @param date       the shared solar-event date
     * @param candidates candidates on that date
     * @return one verification row per candidate, with null observations where the archive failed
     */
    private List<CloudVerificationEntity> verifyDate(LocalDate date,
            List<VerificationCandidate> candidates) {
        // The cone matters — the forecast's own solar reading is a 3-point average, so sampling a
        // single centre point here would compare an average against a spot reading and attribute
        // the difference to forecast error. The far-solar point reads the canvas-underlighting
        // corridor the 113 km gate never sees (centre of the 4 km mid-canvas corridor, near edge
        // of the 8 km high-canvas one — see FAR_SOLAR_OFFSET_METRES).
        List<double[]> points = new ArrayList<>(candidates.size() * POINTS_PER_CANDIDATE);
        for (VerificationCandidate candidate : candidates) {
            points.addAll(DirectionalSamplingGeometry.computeSolarConePoints(
                    candidate.lat(), candidate.lon(), candidate.azimuthDeg()));
            points.add(new double[]{candidate.lat(), candidate.lon()});
            points.add(DirectionalSamplingGeometry.computeFarSolarPoint(
                    candidate.lat(), candidate.lon(), candidate.azimuthDeg()));
        }

        List<OpenMeteoForecastResponse> responses =
                archiveClient.fetchArchiveBatch(points, date, ARCHIVE_HOURLY);

        List<CloudVerificationEntity> rows = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            VerificationCandidate candidate = candidates.get(i);
            int base = i * POINTS_PER_CANDIDATE;
            List<OpenMeteoForecastResponse> cone = new ArrayList<>(
                    DirectionalSamplingGeometry.SOLAR_CONE_POINT_COUNT);
            for (int c = 0; c < DirectionalSamplingGeometry.SOLAR_CONE_POINT_COUNT; c++) {
                cone.add(responseAt(responses, base + c));
            }
            // Centre bearing is the representative coordinate recorded on the row.
            double[] centre = points.get(base + 1);
            rows.add(buildRow(candidate, centre, cone,
                    responseAt(responses, base + OBSERVER_OFFSET),
                    responseAt(responses, base + FAR_SOLAR_OFFSET)));
        }
        return rows;
    }

    /**
     * Returns the response at an index, or {@code null} if the batch came back short.
     *
     * @param responses the batch responses
     * @param idx       the positional index
     * @return the response, or {@code null}
     */
    private OpenMeteoForecastResponse responseAt(List<OpenMeteoForecastResponse> responses,
            int idx) {
        return responses == null || idx >= responses.size() ? null : responses.get(idx);
    }

    /**
     * Builds one verification row from a candidate's archive responses.
     *
     * <p>The horizon layers are averaged across the solar cone, mirroring how the forecast's own
     * {@code solarLow} reading is produced. Comparing a coned forecast value against a single-point
     * observation would charge the sampling difference to forecast error.
     *
     * @param candidate       the evaluation being verified
     * @param centre          the centre-bearing coordinate, recorded as representative
     * @param coneArchives    archive responses at the three cone bearings; entries may be null
     * @param observerArchive archive response at the observer, or {@code null}
     * @param farArchive      archive response at the 226 km far-solar point, or {@code null}
     * @return the verification row, with null observations where the archive had no data
     */
    private CloudVerificationEntity buildRow(VerificationCandidate candidate, double[] centre,
            List<OpenMeteoForecastResponse> coneArchives,
            OpenMeteoForecastResponse observerArchive, OpenMeteoForecastResponse farArchive) {
        CloudVerificationEntity.CloudVerificationEntityBuilder builder =
                CloudVerificationEntity.builder()
                        .forecastEvaluationId(candidate.evaluationId())
                        .horizonSampleLat(centre[0])
                        .horizonSampleLon(centre[1])
                        .verifiedAt(LocalDateTime.now(clock));

        applyConedHorizon(builder, coneArchives, candidate);
        applyLayers(builder, observerArchive, candidate, false);
        applyFarSolar(builder, farArchive, candidate);
        return builder.build();
    }

    /**
     * Averages the cone's archive readings onto the horizon columns.
     *
     * <p>Averages over whichever bearings returned data, so a partly-failed cone still yields a
     * usable reading rather than none. If no bearing returned data the horizon columns stay null,
     * which is what marks the row blank and returns it to the candidate pool on the next run.
     *
     * @param builder      the row under construction
     * @param coneArchives responses at the cone bearings; entries may be null
     * @param candidate    the candidate being verified
     */
    private void applyConedHorizon(CloudVerificationEntity.CloudVerificationEntityBuilder builder,
            List<OpenMeteoForecastResponse> coneArchives, VerificationCandidate candidate) {
        int lowSum = 0;
        int lowMin = Integer.MAX_VALUE;
        int lowMax = Integer.MIN_VALUE;
        int midSum = 0;
        int highSum = 0;
        int count = 0;
        LocalDateTime observedAt = null;

        for (OpenMeteoForecastResponse archive : coneArchives) {
            if (archive == null || archive.getHourly() == null
                    || archive.getHourly().getTime() == null
                    || archive.getHourly().getTime().isEmpty()) {
                continue;
            }
            OpenMeteoForecastResponse.Hourly hourly = archive.getHourly();
            int idx = TimeSlotUtils.findBestIndex(
                    hourly.getTime(), candidate.solarEventTime(), candidate.targetType());
            Integer low = layerAt(hourly.getCloudCoverLow(), idx);
            if (low == null) {
                continue;
            }
            lowSum += low;
            lowMin = Math.min(lowMin, low);
            lowMax = Math.max(lowMax, low);
            midSum += orZero(layerAt(hourly.getCloudCoverMid(), idx));
            highSum += orZero(layerAt(hourly.getCloudCoverHigh(), idx));
            count++;
            if (observedAt == null) {
                observedAt = LocalDateTime.parse(hourly.getTime().get(idx));
            }
        }

        if (count == 0) {
            return;
        }
        // The extremes are what the forecast's own persistence discards: a mean of 60 could be a
        // uniform deck (min≈max) or a wall with a clear third (min 0, max 90), and only the pair
        // of extremes lets the report tell those apart.
        builder.horizonLowCloud(lowSum / count)
                .horizonLowMin(lowMin)
                .horizonLowMax(lowMax)
                .horizonMidCloud(midSum / count)
                .horizonHighCloud(highSum / count)
                .observedAt(observedAt);
    }

    /**
     * Reads the far-solar archive hour matching the solar event and records its low cloud.
     *
     * <p>Only the low layer is kept: the far point exists to measure the canvas-underlighting
     * corridor (dead centre of a 4 km mid canvas's 113–339 km blocking corridor; near edge of an
     * 8 km high canvas's 206–432 km one), and low cloud is the only layer that blocks there.
     *
     * @param builder   the verification row under construction
     * @param archive   the far-solar archive response, or {@code null} if the fetch failed
     * @param candidate the candidate being verified
     */
    private void applyFarSolar(CloudVerificationEntity.CloudVerificationEntityBuilder builder,
            OpenMeteoForecastResponse archive, VerificationCandidate candidate) {
        if (archive == null || archive.getHourly() == null
                || archive.getHourly().getTime() == null
                || archive.getHourly().getTime().isEmpty()) {
            return;
        }
        OpenMeteoForecastResponse.Hourly hourly = archive.getHourly();
        int idx = TimeSlotUtils.findBestIndex(
                hourly.getTime(), candidate.solarEventTime(), candidate.targetType());
        builder.farLowCloud(layerAt(hourly.getCloudCoverLow(), idx));
    }

    /**
     * Returns the value, or zero when absent, for summing a layer that may not be reported.
     *
     * @param value the layer reading
     * @return the value or zero
     */
    private int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * Reads the archive hour matching the solar event and copies its layers onto the builder.
     *
     * <p>Uses the same {@code findBestIndex} slot rule the forecast used, so the observation is
     * compared against the hour the forecast actually scored.
     *
     * @param builder   the verification row under construction
     * @param archive   the archive response, or {@code null} if the fetch failed
     * @param candidate the candidate being verified
     * @param isHorizon retained for the horizon path; the observer path passes false
     */
    private void applyLayers(CloudVerificationEntity.CloudVerificationEntityBuilder builder,
            OpenMeteoForecastResponse archive, VerificationCandidate candidate,
            boolean isHorizon) {
        if (archive == null || archive.getHourly() == null
                || archive.getHourly().getTime() == null
                || archive.getHourly().getTime().isEmpty()) {
            return;
        }
        OpenMeteoForecastResponse.Hourly hourly = archive.getHourly();
        int idx = TimeSlotUtils.findBestIndex(
                hourly.getTime(), candidate.solarEventTime(), candidate.targetType());
        Integer low = layerAt(hourly.getCloudCoverLow(), idx);
        Integer mid = layerAt(hourly.getCloudCoverMid(), idx);
        Integer high = layerAt(hourly.getCloudCoverHigh(), idx);

        if (isHorizon) {
            builder.horizonLowCloud(low).horizonMidCloud(mid).horizonHighCloud(high)
                    .observedAt(LocalDateTime.parse(hourly.getTime().get(idx)));
        } else {
            builder.observerLowCloud(low).observerMidCloud(mid).observerHighCloud(high);
        }
    }

    /**
     * Returns the value at an index, or {@code null} if the list is absent or too short.
     *
     * @param values the hourly series
     * @param idx    the resolved index
     * @return the value, or {@code null}
     */
    private Integer layerAt(List<Integer> values, int idx) {
        return values == null || idx < 0 || idx >= values.size() ? null : values.get(idx);
    }

    /**
     * Builds the verification report for a window of target dates.
     *
     * @param from start of the window (inclusive)
     * @param to   end of the window (inclusive)
     * @return forecast-vs-observed cloud accuracy, split by veto firing, cap, and wind alignment
     * @throws IllegalArgumentException if the window is null or inverted
     */
    @Transactional(readOnly = true)
    public CloudVerificationReport report(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from and to are required");
        }
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("to must not be before from");
        }

        List<CloudVerificationPair> pairs = repository.findVerifiedPairs(from, to);
        List<CloudVerificationPair> fired = pairs.stream().filter(CloudVerificationPair::vetoFired)
                .toList();
        List<CloudVerificationPair> notFired = pairs.stream()
                .filter(p -> !p.vetoFired()).toList();

        CloudVerificationReport report = new CloudVerificationReport(
                from,
                to,
                repository.countVerifiedInWindow(from, to),
                CloudVerificationBucket.of("ALL", pairs),
                CloudVerificationBucket.of("VETO_FIRED", fired),
                CloudVerificationBucket.of("VETO_NOT_FIRED", notFired),
                CloudVerificationBucket.of("VETO_UNCAPPED",
                        fired.stream().filter(p -> !p.upwindCapped()).toList()),
                CloudVerificationBucket.of("VETO_CAPPED",
                        fired.stream().filter(CloudVerificationPair::upwindCapped).toList()),
                windSunBuckets(fired),
                coneStructureBuckets(pairs),
                corridorBuckets(pairs),
                null,
                null);
        report = new CloudVerificationReport(
                report.from(), report.to(), report.verifiedCount(), report.overall(),
                report.vetoFired(), report.vetoNotFired(), report.vetoUncapped(),
                report.vetoCapped(), report.byWindSunAngle(),
                report.byConeStructure(), report.byCorridor(),
                separation(report.vetoFired(), report.vetoNotFired()),
                separation(report.vetoUncapped(), report.vetoCapped()));

        LOG.info("[CLOUD VERIFY] window={}..{} verified={} vetoFired={} "
                + "vetoSeparation={} capSeparation={}",
                from, to, report.verifiedCount(), fired.size(),
                report.vetoSeparation(), report.capSeparation());
        return report;
    }

    /**
     * Returns the difference in mean observed horizon cloud between two buckets.
     *
     * <p>The offset-immune statistic. Each bucket's absolute mean carries the reanalysis baseline's
     * systematic offset from the forecast model; the difference between two buckets does not,
     * because that offset applies equally to both.
     *
     * @param a the bucket expected to be cloudier if the signal is real
     * @param b the comparison bucket
     * @return a's mean observed horizon cloud minus b's, or {@code null} if either is unavailable
     */
    private Double separation(CloudVerificationBucket a, CloudVerificationBucket b) {
        if (a == null || b == null
                || a.meanObservedGapLow() == null || b.meanObservedGapLow() == null) {
            return null;
        }
        return Math.round((a.meanObservedGapLow() - b.meanObservedGapLow()) * 100.0) / 100.0;
    }

    /**
     * Buckets veto-fired pairs by how closely the wind bearing aligns with the solar azimuth.
     *
     * @param fired the veto-fired pairs
     * @return one bucket per alignment band that has data
     */
    private List<CloudVerificationBucket> windSunBuckets(List<CloudVerificationPair> fired) {
        List<CloudVerificationBucket> buckets = new ArrayList<>();
        buckets.add(CloudVerificationBucket.of("aligned(<45)", inBand(fired, 0, ALIGNED_MAX_DEG)));
        buckets.add(CloudVerificationBucket.of("oblique(45-135)",
                inBand(fired, ALIGNED_MAX_DEG, OPPOSED_MIN_DEG)));
        buckets.add(CloudVerificationBucket.of("opposed(>135)",
                inBand(fired, OPPOSED_MIN_DEG, Integer.MAX_VALUE)));
        return buckets;
    }

    /**
     * Filters pairs whose wind-to-sun separation falls in {@code [minInclusive, maxExclusive)}.
     *
     * @param pairs        the pairs to filter
     * @param minInclusive lower bound in degrees, inclusive
     * @param maxExclusive upper bound in degrees, exclusive
     * @return the matching pairs
     */
    private List<CloudVerificationPair> inBand(List<CloudVerificationPair> pairs,
            int minInclusive, int maxExclusive) {
        return pairs.stream()
                .filter(p -> p.windSunAngle() != null)
                .filter(p -> p.windSunAngle() >= minInclusive && p.windSunAngle() < maxExclusive)
                .toList();
    }

    /**
     * Buckets pairs by the analysed cone's low-cloud spread — the cone-aggregation question.
     *
     * <p>The forecast collapses its three cone samples to a mean before anything downstream sees
     * them. These buckets measure how often the analysed horizon has structure that a mean cannot
     * carry (one bearing clear, another blocked), and whether the forecast's gap error grows in
     * exactly those cases. Spread is computed within one reanalysis baseline, so the systematic
     * forecast-vs-reanalysis offset cancels.
     *
     * @param pairs every verified pair in the window
     * @return one bucket per spread band, over the pairs that carry cone extremes
     */
    private List<CloudVerificationBucket> coneStructureBuckets(List<CloudVerificationPair> pairs) {
        List<CloudVerificationPair> withSpread = pairs.stream()
                .filter(p -> p.coneSpread() != null).toList();
        return List.of(
                CloudVerificationBucket.of("uniform(spread<20)", withSpread.stream()
                        .filter(p -> p.coneSpread() < CONE_SPREAD_MIXED_PP).toList()),
                CloudVerificationBucket.of("mixed(20-39)", withSpread.stream()
                        .filter(p -> p.coneSpread() >= CONE_SPREAD_MIXED_PP
                                && p.coneSpread() < CONE_SPREAD_GAPPED_PP).toList()),
                CloudVerificationBucket.of("gapped(>=40)", withSpread.stream()
                        .filter(p -> p.coneSpread() >= CONE_SPREAD_GAPPED_PP).toList()));
    }

    /**
     * Buckets pairs by near-vs-far corridor divergence — the canvas-height gate question.
     *
     * <p>The 226 km point is the exact centre of a 4 km mid canvas's low-cloud blocking corridor
     * (113–339 km) and the near edge of an 8 km high canvas's (206–432 km, centred ~319 km) — see
     * {@code DirectionalSamplingGeometry.FAR_SOLAR_OFFSET_METRES}. The 113 km gate sees neither.
     * {@code farClearer} counts skies where the near deck ends before the far corridor (the gate
     * reads blocked while a lit canvas is still possible — over-pessimism); {@code farCloudier}
     * counts the reverse (the gate reads clear while the underlighting corridor is blanketed —
     * false optimism, uncovered by any current rule). The {@code &highCanvas} variants isolate the
     * cases where the analysed canvas was actually high-dominant; for those the 226 km reading is
     * a near-edge proxy for a corridor centred ~90 km further out, correlated at synoptic scale
     * (frontal bands are 100–300 km wide) but not a direct measurement. The {@code &midCanvas}
     * variants are therefore the rigorous cut of this data — the point centres their corridor, so
     * they measure it directly — and sit beside the high-canvas proxy rather than replacing it.
     *
     * @param pairs every verified pair in the window
     * @return corridor buckets over the pairs that carry both near and far readings
     */
    private List<CloudVerificationBucket> corridorBuckets(List<CloudVerificationPair> pairs) {
        List<CloudVerificationPair> withFar = pairs.stream()
                .filter(p -> p.farDrop() != null).toList();
        List<CloudVerificationPair> clearer = withFar.stream()
                .filter(p -> p.farDrop() >= FAR_DIVERGENCE_PP).toList();
        List<CloudVerificationPair> cloudier = withFar.stream()
                .filter(p -> p.farDrop() <= -FAR_DIVERGENCE_PP).toList();
        return List.of(
                CloudVerificationBucket.of("farSimilar(|drop|<30)", withFar.stream()
                        .filter(p -> Math.abs(p.farDrop()) < FAR_DIVERGENCE_PP).toList()),
                CloudVerificationBucket.of("farClearer(drop>=30)", clearer),
                CloudVerificationBucket.of("farClearer&highCanvas", clearer.stream()
                        .filter(p -> Boolean.TRUE.equals(p.highCanvasDominant())).toList()),
                CloudVerificationBucket.of("farClearer&midCanvas", clearer.stream()
                        .filter(p -> Boolean.TRUE.equals(p.midCanvasDominant())).toList()),
                CloudVerificationBucket.of("farCloudier(drop<=-30)", cloudier),
                CloudVerificationBucket.of("farCloudier&highCanvas", cloudier.stream()
                        .filter(p -> Boolean.TRUE.equals(p.highCanvasDominant())).toList()),
                CloudVerificationBucket.of("farCloudier&midCanvas", cloudier.stream()
                        .filter(p -> Boolean.TRUE.equals(p.midCanvasDominant())).toList()));
    }
}
