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
 * <p>Two points are verified per evaluation, matching the two claims a forecast makes: low cloud
 * at the solar horizon decides whether the low sun gets through (the gap), and mid/high cloud
 * overhead is what that light lands on (the canvas).
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
     * Deletes verification rows that carry no observations, making them candidates again.
     *
     * <p>A row written during an upstream outage records only that an attempt happened. Because
     * candidate selection is an anti-join, such a row would otherwise mask that evaluation
     * permanently. Clearing them is what makes the backfill self-healing rather than needing
     * manual SQL after every throttled run.
     *
     * @return the number of blank rows removed
     */
    @Transactional
    public int clearBlankVerifications() {
        int cleared = repository.deleteBlankVerifications();
        if (cleared > 0) {
            LOG.info("[CLOUD VERIFY] cleared {} blank verification row(s) for re-attempt", cleared);
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
     * <p>Points are laid out two per candidate — horizon at {@code 2i}, observer at
     * {@code 2i+1} — so a single batch covers both claims for the whole date and the responses
     * map back positionally.
     *
     * @param date       the shared solar-event date
     * @param candidates candidates on that date
     * @return one verification row per candidate, with null observations where the archive failed
     */
    private List<CloudVerificationEntity> verifyDate(LocalDate date,
            List<VerificationCandidate> candidates) {
        List<double[]> points = new ArrayList<>(candidates.size() * 2);
        for (VerificationCandidate candidate : candidates) {
            points.add(DirectionalSamplingGeometry.computeSolarHorizonPoint(
                    candidate.lat(), candidate.lon(), candidate.azimuthDeg()));
            points.add(new double[]{candidate.lat(), candidate.lon()});
        }

        List<OpenMeteoForecastResponse> responses =
                archiveClient.fetchArchiveBatch(points, date, ARCHIVE_HOURLY);

        List<CloudVerificationEntity> rows = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            VerificationCandidate candidate = candidates.get(i);
            double[] horizon = points.get(i * 2);
            rows.add(buildRow(candidate, horizon,
                    responseAt(responses, i * 2), responseAt(responses, i * 2 + 1)));
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
     * Builds one verification row from the two archive responses for a candidate.
     *
     * @param candidate       the evaluation being verified
     * @param horizon         the sampled solar-horizon coordinate
     * @param horizonArchive  archive response at the horizon, or {@code null}
     * @param observerArchive archive response at the observer, or {@code null}
     * @return the verification row, with null observations where the archive had no data
     */
    private CloudVerificationEntity buildRow(VerificationCandidate candidate, double[] horizon,
            OpenMeteoForecastResponse horizonArchive, OpenMeteoForecastResponse observerArchive) {
        CloudVerificationEntity.CloudVerificationEntityBuilder builder =
                CloudVerificationEntity.builder()
                        .forecastEvaluationId(candidate.evaluationId())
                        .horizonSampleLat(horizon[0])
                        .horizonSampleLon(horizon[1])
                        .verifiedAt(LocalDateTime.now(clock));

        applyLayers(builder, horizonArchive, candidate, true);
        applyLayers(builder, observerArchive, candidate, false);
        return builder.build();
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
     * @param isHorizon true to write the horizon columns, false for the observer columns
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
                windSunBuckets(fired));

        LOG.info("[CLOUD VERIFY] window={}..{} verified={} vetoFired={} "
                + "gapActuallyOpenWhenVetoed={} gapActuallyBlockedWhenVetoed={}",
                from, to, report.verifiedCount(), fired.size(),
                report.vetoFired().gapActuallyOpen(), report.vetoFired().gapActuallyBlocked());
        return report;
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
}
