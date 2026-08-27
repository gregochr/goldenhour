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

import java.math.BigDecimal;
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
 * point the gate actually looks at? 226 km is the geometric centre of a 4 km mid canvas's blocking
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

    /**
     * Forecast solar-horizon low cloud (%) below which the prompt's IDEAL-scenario band applies.
     *
     * <p>Mirrors {@code PromptBuilder}'s "Solar horizon low cloud &lt;20% = strong light
     * penetration likely" / "IDEAL scenario: solar horizon low cloud &lt;20%" rules. If that
     * prompt band ever moves, this must move with it — otherwise the report sizes a population
     * the rule no longer fires over.
     */
    private static final int PROMPT_CLEAR_BAND_MAX_PCT = 20;

    /**
     * Forecast solar-horizon low cloud (%) above which the prompt's BLOCKED hard ceiling applies.
     *
     * <p>Mirrors {@code PromptBuilder}'s "Solar horizon low cloud &gt;60% = light is BLOCKED …
     * rating 1-2" rule, and must track it if the prompt band ever changes.
     */
    private static final int PROMPT_BLOCKED_BAND_MIN_PCT = 60;

    /**
     * Forecast mid cloud (%) below which the prompt's IDEAL scenario's canvas precondition holds.
     *
     * <p>Takes its figure from the "AND mid cloud &lt;50%" clause of {@code PromptBuilder}'s IDEAL
     * rule, and must track it if the prompt band ever changes. The <em>figure</em> is the prompt's;
     * the <em>reading</em> it is applied to is not — see {@link #forecastWasIdeal}.
     */
    private static final int PROMPT_IDEAL_MID_MAX_PCT = 50;

    /**
     * Forecast high cloud (%) at or above which a canvas is treated as present for the IDEAL rule.
     *
     * <p>Stands in for the "with high cloud present on either horizon as canvas" clause of
     * {@code PromptBuilder}'s IDEAL rule, which states no percentage of its own. 20 is the bottom
     * of the prompt's adjacent canvas band ("mid/high cloud 20-60% = ideal canvas") — the thinnest
     * canvas the prompt is willing to call ideal — so track that band rather than the IDEAL clause
     * if either moves.
     */
    private static final int PROMPT_IDEAL_HIGH_MIN_PCT = 20;

    /**
     * Forecast near-minus-far drop (percentage points) at or above which the prompt's THIN STRIP
     * override applies, lifting the BLOCKED ceiling.
     *
     * <p>Mirrors {@code PromptBuilder.THIN_STRIP_DROP_POINTS} / {@code isThinStrip} and must track
     * them if either moves. Deliberately mirrors the <em>label logic</em> (drop-only) rather than
     * the prompt prose's additional "far low cloud &le;30%" clause: the label is what this bucket's
     * conditioning quantity — the forecast data block — actually carries. The text/label
     * discrepancy is a known, separately-tracked issue in {@code PromptBuilder} itself; observed
     * here, not fixed.
     */
    private static final int PROMPT_STRIP_DROP_PP = 30;

    /**
     * Forecast low cloud (%) at or above which both arms of the prompt's EXTENSIVE BLANKET label
     * are satisfied.
     *
     * <p>Mirrors {@code PromptBuilder.SOLAR_LOW_CLOUD_SIGNIFICANT_PERCENT} and the label's own
     * condition at {@code PromptBuilder} ~:489 — near &ge; 50 <em>and</em> far &ge; 50, both arms
     * inclusive — and must track it if that band ever moves.
     *
     * <p>Deliberately <em>not</em> {@link #PROMPT_BLOCKED_BAND_MIN_PCT}. The blocked ceiling
     * (&gt; 60, one reading) and the blanket label (&ge; 50, two readings) are different rules at
     * different figures, and a bucket that reused 60 here would size a population the label never
     * fired over — which is the exact defect the {@code fcst*} family exists to avoid.
     */
    private static final int PROMPT_BLANKET_MIN_PCT = 50;

    /**
     * Observed far low cloud (%) at or below which the 226 km corridor really was open.
     *
     * <p>Takes its figure from the prompt prose's own strip level ("beyond-horizon low cloud
     * &le;30%") — the reading at which the prompt itself stops calling a corridor blocked. Applied
     * here to the <em>analysed</em> reading, where the prompt applies it to the forecast one.
     */
    private static final int OBS_CORRIDOR_OPEN_MAX_PCT = 30;

    /**
     * Observed far low cloud (%) at or above which the 226 km corridor really was blanketed.
     *
     * <p>The blanket label's own arm, applied to the analysed reading. The 31–49 band between this
     * and {@link #OBS_CORRIDOR_OPEN_MAX_PCT} is deliberately in neither sub-bucket: a corridor that
     * was neither open nor blanketed must not inflate either precision figure.
     */
    private static final int OBS_CORRIDOR_BLANKET_MIN_PCT = 50;

    /**
     * Forecast solar-horizon low cloud (%) above which triage stands the slot down before any
     * prompt is built.
     *
     * <p>Mirrors {@code WeatherTriageEvaluator.CLOUD_THRESHOLD}, whose rule 1 reads
     * {@code directionalCloud().solarLowCloudPercent()} — the same reading projected here as
     * {@code forecastGapLow} — and must track it if that rule ever moves. Unlike the
     * {@code PROMPT_*} constants this mirrors a <em>gate in front of</em> the prompt rather than a
     * band inside it, which is why it is named for triage and not for a label.
     *
     * <p>At or below it a slot <em>may</em> have reached Claude; above it, it did not. Neither
     * direction is exact — see {@link #corridorBuckets} — so the bucket keyed on it names the cut
     * rather than claiming a prompt was or was not built.
     */
    private static final int TRIAGE_SOLAR_LOW_CLOUD_MAX_PCT = 80;

    /**
     * Forecast precipitation (mm) above which triage stands the slot down — rule 2.
     *
     * <p>Mirrors {@code WeatherTriageEvaluator.PRECIP_THRESHOLD}, operator and all: the rule is
     * strictly-greater, so exactly 2.0 mm passes. Must track that rule if it ever moves.
     */
    private static final BigDecimal TRIAGE_PRECIP_MAX_MM = new BigDecimal("2.0");

    /**
     * Forecast visibility (m) below which triage stands the slot down — rule 3.
     *
     * <p>Mirrors {@code WeatherTriageEvaluator.VISIBILITY_THRESHOLD}, operator and all: the rule
     * is strictly-less, so exactly 5,000 m passes. Must track that rule if it ever moves.
     */
    private static final int TRIAGE_VISIBILITY_MIN_M = 5000;

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
                triageCutBuckets(pairs),
                promptableBuckets(pairs),
                null,
                null);
        report = new CloudVerificationReport(
                report.from(), report.to(), report.verifiedCount(), report.overall(),
                report.vetoFired(), report.vetoNotFired(), report.vetoUncapped(),
                report.vetoCapped(), report.byWindSunAngle(),
                report.byConeStructure(), report.byCorridor(), report.byTriageCut(),
                report.byPromptable(),
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
     * <p>The 226 km point is the geometric centre of a 4 km mid canvas's low-cloud blocking corridor
     * (113–339 km) and the near edge of an 8 km high canvas's (206–432 km, centred ~319 km) — see
     * {@code DirectionalSamplingGeometry.FAR_SOLAR_OFFSET_METRES}. The 113 km gate sees neither.
     * {@code farClearer} counts skies where the near deck ends before the far corridor (the gate
     * reads blocked while a lit canvas is still possible — over-pessimism); {@code farCloudier}
     * counts the reverse (the gate reads clear while the underlighting corridor is blanketed —
     * false optimism, uncovered by any current rule). The {@code &highCanvas} variants isolate the
     * cases where the analysed canvas was actually high-dominant; for those the 226 km reading is
     * a near-edge proxy for a corridor centred ~90 km further out, correlated at synoptic scale
     * (frontal bands are 100–300 km wide) but not a direct measurement. The {@code &midCanvas}
     * variants are therefore the rigorous cut of this data — the point sits near the centre of
     * their corridor rather than at its edge (exactly so at 4 km geometric, ~16–21 km short once
     * refraction is allowed for) — and sit beside the high-canvas proxy rather than replacing it.
     *
     * <p>Those observed-structure buckets answer <em>how often does the sky diverge</em>. The
     * {@code fcst*} buckets answer a different question — <em>how often does each prompt rule
     * actually fire over a divergent sky</em> — and the two are different populations: a bucket
     * cut on observed structure can be dominated by evaluations whose forecast never tripped the
     * rule it was built to size. Measured 2026-08-16, {@code farCloudier&midCanvas}'s mean member
     * carried a forecast gap near 87%, so the IDEAL rule had not fired for it and the bucket could
     * not size that rule's false optimism. Conditioning on the forecast band is what makes each
     * bucket the firing population of the rule it names.
     *
     * <p>One caveat on {@code farClearer&fcstBlocked(>60)}: the prompt's hard ceiling is itself
     * conditional — it applies "when solar horizon low cloud &gt;60% <em>and no THIN STRIP
     * override applies</em>", and that override fires on the forecast's own near-vs-far pair
     * (horizon ≥50% dropping to ≤30% at 226 km), which is precisely the shape this bucket selects
     * for. So it counts skies that <em>entered</em> the blocked band, some of which the strip rule
     * then exempted. {@code forecastFarLow} is projected onto the pair, so a later cut can
     * separate the two; this one deliberately does not, because the band entry is what the key
     * names.
     *
     * <p>The two THIN STRIP sub-buckets resolve that caveat: {@code stripSeen} means the
     * override's <em>preconditions</em> held in the forecast data the prompt was given — the
     * prompt's actual softening behaviour is not persisted anywhere, so this is plausibility, not
     * proof the override fired. {@code stripMissed} is the unmitigated firing population: the hard
     * ceiling applied with no mitigating signal present in the forecast data, which is the number
     * the blocked-rule's over-pessimism design decision keys on.
     *
     * <p>The three {@code fcstBlanket} buckets exist to read the EXTENSIVE BLANKET label's
     * <strong>precision</strong> straight off the counts: false-blanket rate = {@code
     * corridorOpen / fcstBlanket}, which is the number {@code blanket-confirmation-plan.md}'s
     * pre-registered decision rule keys on (&ge; ~25% ships the reworded rule; well under ~10%
     * narrows to language only; between the two is the user's call). They hang off {@code withFar}
     * rather than {@code clearer} or {@code cloudier} on purpose — the question is the label's full
     * firing population, whatever the observed divergence class turned out to be. The strip split
     * above cannot supply that denominator: it sizes the BLOCKED rule's unmitigated firing
     * population, a neighbouring set rather than a subset — gated on one reading at &gt;60, and
     * admitting members that carry no forecast far reading at all, for which no label prints.
     *
     * <p>Three caveats belong beside that ratio. The first two move it in <em>opposite</em>
     * directions and their net sign is unestablished, so it is not an upper bound on anything.
     * <ul>
     *   <li><strong>Much of the parent was never prompted at all.</strong>
     *       {@code WeatherTriageEvaluator} stands a slot down at solar-horizon low cloud
     *       &gt; {@link #TRIAGE_SOLAR_LOW_CLOUD_MAX_PCT}% — the same reading projected here as
     *       {@code forecastGapLow} — and {@code ForecastService} persists the row and returns
     *       before {@code PromptBuilder} runs, while candidate selection filters on none of that.
     *       So a member above the cut was never prompted on the nightly path, and those are the
     *       heaviest-cloud members, likeliest to have been genuinely blanketed — which
     *       <em>depletes</em> {@code corridorOpen}. {@code fcstBlanket&underTriageCut(<=80)} and
     *       its {@code corridorOpen} sub-bucket are the same ratio over the members that could
     *       have been prompted; read them beside the pre-registered pair. The cut is neither
     *       necessary nor sufficient, which is why it is named for itself rather than for
     *       "prompted": below it a member can still have been stood down by the other two triage
     *       rules (precipitation, visibility) or by tide alignment, or routed to a canopy lane
     *       whose builder never emits this label; above it a JFDI run submits the triaged
     *       candidate anyway. Those are examples, not a closed list — this is the one gate reading
     *       the same column the report projects, which is what makes it checkable at all.</li>
     *   <li><strong>The sub-buckets cut analysed readings at absolute figures</strong>, which is
     *       the one thing {@link CloudVerificationBucket} warns against: the reanalysis baseline
     *       sits ~25pp below the forecast model at the horizon (the 226 km point's own offset has
     *       never been measured separately), so a corridor the forecast model would call 50% can
     *       read nearer 25% here — under the open cut. That <em>inflates</em> {@code corridorOpen}
     *       and depletes {@code corridorBlanketed}. There is no offset-immune form of "was it
     *       really open" — the question is absolute by construction — so read the parent bucket's
     *       own {@code meanFarError} beside the ratio, which carries the same offset and so shows
     *       how much of the gap is baseline rather than forecast. (The sub-buckets' own
     *       {@code meanFarError} is floored by their definitions and says nothing.)</li>
     *   <li><strong>{@code fcstBlanket} mirrors the label's arms, not its printing.</strong> In
     *       {@code PromptBuilder} the blanket label sits in an {@code else} after
     *       {@code isThinStrip}, so a forecast of near &ge; far + 30 with far &ge; 50 satisfies
     *       both arms yet prints THIN STRIP instead. That needs near &ge; 80, so it lies almost
     *       entirely in the triaged band the first caveat describes; inside
     *       {@code underTriageCut} it collapses to the single point near = 80 with far = 50.</li>
     * </ul>
     *
     * @param pairs every verified pair in the window
     * @return corridor buckets over the pairs that carry both near and far readings
     */
    private List<CloudVerificationBucket> corridorBuckets(List<CloudVerificationPair> pairs) {
        List<CloudVerificationPair> withFar = pairs.stream()
                .filter(p -> p.farDrop() != null).toList();
        List<CloudVerificationPair> clearer = withFar.stream()
                .filter(CloudVerificationService::farCorridorCleared).toList();
        List<CloudVerificationPair> cloudier = withFar.stream()
                .filter(p -> p.farDrop() <= -FAR_DIVERGENCE_PP).toList();
        List<CloudVerificationPair> fcstBlocked = clearer.stream()
                .filter(CloudVerificationService::forecastWasBlocked).toList();
        List<CloudVerificationPair> fcstBlanket = withFar.stream()
                .filter(CloudVerificationService::forecastWasBlanket).toList();
        List<CloudVerificationPair> underTriageCut = fcstBlanket.stream()
                .filter(CloudVerificationService::forecastGapUnderTriageCut).toList();
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
                        .filter(p -> Boolean.TRUE.equals(p.midCanvasDominant())).toList()),
                CloudVerificationBucket.of("farCloudier&fcstClear(<20)", cloudier.stream()
                        .filter(p -> p.forecastGapLow() != null
                                && p.forecastGapLow() < PROMPT_CLEAR_BAND_MAX_PCT).toList()),
                CloudVerificationBucket.of("farClearer&fcstBlocked(>60)", fcstBlocked),
                CloudVerificationBucket.of("farCloudier&fcstIdeal", cloudier.stream()
                        .filter(CloudVerificationService::forecastWasIdeal).toList()),
                CloudVerificationBucket.of("farClearer&fcstBlocked&stripSeen", fcstBlocked.stream()
                        .filter(CloudVerificationService::thinStripPreconditionsHeld).toList()),
                CloudVerificationBucket.of("farClearer&fcstBlocked&stripMissed", fcstBlocked.stream()
                        .filter(p -> !thinStripPreconditionsHeld(p)).toList()),
                CloudVerificationBucket.of("fcstBlanket", fcstBlanket),
                CloudVerificationBucket.of("fcstBlanket&corridorOpen(obs<=30)", fcstBlanket.stream()
                        .filter(CloudVerificationService::corridorWasOpen).toList()),
                CloudVerificationBucket.of("fcstBlanket&corridorBlanketed(obs>=50)",
                        fcstBlanket.stream()
                                .filter(p -> p.observedFarLow() != null
                                        && p.observedFarLow() >= OBS_CORRIDOR_BLANKET_MIN_PCT)
                                .toList()),
                CloudVerificationBucket.of("fcstBlanket&underTriageCut(<=80)", underTriageCut),
                CloudVerificationBucket.of("fcstBlanket&underTriageCut&corridorOpen(obs<=30)",
                        underTriageCut.stream()
                                .filter(CloudVerificationService::corridorWasOpen).toList()));
    }

    /**
     * Re-reads the veto and blocked families over the pairs a prompt could have been built for.
     *
     * <p>Both families select on persisted forecast columns rather than on whether a prompt ever
     * ran, and {@code WeatherTriageEvaluator} stands a slot down above
     * {@link #TRIAGE_SOLAR_LOW_CLOUD_MAX_PCT}% solar-horizon low cloud before
     * {@code PromptBuilder} is reached. So both of the headlines the prompt-change session is about
     * to be argued from are read partly over slots Claude never saw: the veto's anti-selection
     * finding (the {@code vetoSeparation} scalar, computed over every pair) and the blocked
     * family's share of the window ({@code farClearer&fcstBlocked(>60)} and its {@code stripMissed}
     * subset). These four buckets state the same two things over the promptable population, and
     * neither headline should be cited in that session without them.
     *
     * <p>The caveats on {@link #corridorBuckets}' first bullet apply verbatim and are deliberately
     * not restated: the cut is neither necessary nor sufficient for "prompted", and its two biases
     * run in opposite directions, so nothing read here is an upper bound on anything. One addition
     * specific to this list — a pair carrying no forecast gap reading is in none of the four
     * buckets, because it cannot be shown to sit under a cut on a reading it does not have. That
     * makes {@code vetoNotFired&underTriageCut} narrower than its parent for two separable reasons,
     * the cut and the missing reading.
     *
     * <p><strong>The under-cut veto separation is derived by the reader</strong>, as
     * {@code vetoFired&underTriageCut}'s {@code meanObservedGapLow} minus
     * {@code vetoNotFired&underTriageCut}'s, and read beside {@link CloudVerificationReport}'s
     * {@code vetoSeparation}, which is that same quantity over the whole window. Deliberately no
     * new scalar: the record grows by one list and nothing else. The first two buckets partition
     * the under-cut population between them, so their sample counts sum to its size — which is the
     * denominator to read the two blocked variants against, the window's own
     * {@code verifiedCount} being the contaminated total this list exists to get away from.
     *
     * @param pairs every verified pair in the window
     * @return the two veto variants then the two blocked-family variants
     */
    private List<CloudVerificationBucket> triageCutBuckets(List<CloudVerificationPair> pairs) {
        List<CloudVerificationPair> underCut = pairs.stream()
                .filter(CloudVerificationService::forecastGapUnderTriageCut).toList();
        // Same parent chain the corridor list's blocked family hangs off — a divergent corridor
        // first, the forecast's own BLOCKED band second — with the cut applied last.
        List<CloudVerificationPair> blockedUnderCut = underCut.stream()
                .filter(CloudVerificationService::farCorridorCleared)
                .filter(CloudVerificationService::forecastWasBlocked)
                .toList();
        return List.of(
                CloudVerificationBucket.of("vetoFired&underTriageCut(<=80)", underCut.stream()
                        .filter(CloudVerificationPair::vetoFired).toList()),
                CloudVerificationBucket.of("vetoNotFired&underTriageCut(<=80)", underCut.stream()
                        .filter(p -> !p.vetoFired()).toList()),
                CloudVerificationBucket.of("farClearer&fcstBlocked&underTriageCut(<=80)",
                        blockedUnderCut),
                CloudVerificationBucket.of(
                        "farClearer&fcstBlocked&stripMissed&underTriageCut(<=80)",
                        blockedUnderCut.stream()
                                .filter(p -> !thinStripPreconditionsHeld(p)).toList()));
    }

    /**
     * Re-reads the veto, blocked and blanket families over the pairs passing ALL of triage's
     * stand-down rules — the faithful version of {@link #triageCutBuckets}.
     *
     * <p>That list's cut reproduces only {@code WeatherTriageEvaluator}'s first rule, so a slot
     * stood down for rain or fog under an 80% horizon still counts as "promptable" there — a
     * cross-vendor review finding (2026-08-27, `promptable-cut-plan.md`). These buckets apply
     * {@link #promptable}, which reproduces all three rules, and add the blanket-precision pair
     * so every §9 headline figure has a fully-cut counterpart. The old list stays untouched as
     * the comparison baseline: each bucket here reads against its {@code byTriageCut} or
     * {@code byCorridor} namesake, and can only be equal or smaller — a larger count is a
     * predicate bug, not a finding.
     *
     * <p>Same conventions as {@link #triageCutBuckets}, deliberately: no scalar of its own (the
     * promptable veto separation is the first two buckets' {@code meanObservedGapLow} difference,
     * derived by the reader), the same parent chains for the blocked family, and the same
     * corridor-open test for the blanket precision split.
     *
     * @param pairs every verified pair in the window
     * @return the veto pair, the two blocked-family variants, then the blanket-precision pair
     */
    private List<CloudVerificationBucket> promptableBuckets(List<CloudVerificationPair> pairs) {
        List<CloudVerificationPair> promptable = pairs.stream()
                .filter(CloudVerificationService::promptable).toList();
        List<CloudVerificationPair> blockedPromptable = promptable.stream()
                .filter(CloudVerificationService::farCorridorCleared)
                .filter(CloudVerificationService::forecastWasBlocked)
                .toList();
        List<CloudVerificationPair> blanketPromptable = promptable.stream()
                .filter(CloudVerificationService::forecastWasBlanket).toList();
        return List.of(
                CloudVerificationBucket.of("vetoFired&promptable", promptable.stream()
                        .filter(CloudVerificationPair::vetoFired).toList()),
                CloudVerificationBucket.of("vetoNotFired&promptable", promptable.stream()
                        .filter(p -> !p.vetoFired()).toList()),
                CloudVerificationBucket.of("farClearer&fcstBlocked&promptable",
                        blockedPromptable),
                CloudVerificationBucket.of("farClearer&fcstBlocked&stripMissed&promptable",
                        blockedPromptable.stream()
                                .filter(p -> !thinStripPreconditionsHeld(p)).toList()),
                CloudVerificationBucket.of("fcstBlanket&promptable", blanketPromptable),
                CloudVerificationBucket.of("fcstBlanket&promptable&corridorOpen(obs<=30)",
                        blanketPromptable.stream()
                                .filter(CloudVerificationService::corridorWasOpen).toList()));
    }

    /**
     * Returns whether the slot passed all three of triage's stand-down rules.
     *
     * <p>Mirrors {@code WeatherTriageEvaluator.evaluate} rule by rule, operators and null
     * semantics included: cloud at or under {@link #TRIAGE_SOLAR_LOW_CLOUD_MAX_PCT} (via
     * {@link #forecastGapUnderTriageCut} — the evaluator falls back to observer low cloud when
     * directional data is absent, but this report projects only the coned reading, so a pair
     * with no gap reading stays a non-member exactly as it is for the narrower cut);
     * precipitation not over {@link #TRIAGE_PRECIP_MAX_MM}, with {@code null} passing as the
     * evaluator's own null guard does; and visibility not under {@link #TRIAGE_VISIBILITY_MIN_M},
     * where {@code null} passes here although the evaluator reads the live value unguarded — a
     * persisted null cannot be shown to have stood the slot down, and treating it as a
     * stand-down would shrink the population on missing data rather than on evidence.
     *
     * <p>Still neither necessary nor sufficient for "prompted" — a JFDI run submits triaged
     * candidates, and the canopy lanes prompt elsewhere — but unlike
     * {@link #forecastGapUnderTriageCut} alone it reproduces every rule triage applies, so the
     * gap between this cut and "prompted" is no longer a rule this report simply ignored.
     *
     * @param pair one verified pair
     * @return true when no triage rule can be shown to have stood the slot down
     */
    private static boolean promptable(CloudVerificationPair pair) {
        return forecastGapUnderTriageCut(pair)
                && !(pair.precipitationMm() != null
                        && pair.precipitationMm().compareTo(TRIAGE_PRECIP_MAX_MM) > 0)
                && !(pair.visibilityMetres() != null
                        && pair.visibilityMetres() < TRIAGE_VISIBILITY_MIN_M);
    }

    /**
     * Returns whether the analysed corridor cleared with distance — the {@code farClearer} class.
     *
     * <p>Extracted so the corridor list's parent and the under-the-triage-cut variant of its
     * blocked family select the identical population. The whole value of a variant is that it
     * applies the same tests to a narrower parent, which a second inline copy of this comparison
     * would let drift.
     *
     * @param pair one verified pair
     * @return true when the analysed near-minus-far drop reached {@link #FAR_DIVERGENCE_PP}
     */
    private static boolean farCorridorCleared(CloudVerificationPair pair) {
        return pair.farDrop() != null && pair.farDrop() >= FAR_DIVERGENCE_PP;
    }

    /**
     * Returns whether the forecast's solar-horizon low cloud sat inside the prompt's BLOCKED band.
     *
     * <p>Extracted so the parent bucket and both of its THIN STRIP sub-buckets apply the identical
     * test — three inline copies of a threshold comparison is how such a predicate drifts.
     *
     * @param pair one verified pair
     * @return true when the forecast gap reading exceeded {@link #PROMPT_BLOCKED_BAND_MIN_PCT}
     */
    private static boolean forecastWasBlocked(CloudVerificationPair pair) {
        return pair.forecastGapLow() != null
                && pair.forecastGapLow() > PROMPT_BLOCKED_BAND_MIN_PCT;
    }

    /**
     * Returns whether both arms of the prompt's EXTENSIVE BLANKET label were satisfied.
     *
     * <p>Extracted so the parent bucket and both of its corridor sub-buckets apply the identical
     * test — three inline copies of a threshold comparison is how such a predicate drifts.
     *
     * <p>Both arms are inclusive and both sit at {@link #PROMPT_BLANKET_MIN_PCT}, mirroring
     * {@code PromptBuilder} ~:489. A missing forecast far reading is a non-member rather than an
     * unknown: the label is only ever appended to a far-field line, so with no far figure it
     * cannot have fired.
     *
     * <p>This mirrors the label's <em>arms</em>, not its printing — the label sits in an
     * {@code else} after {@code PromptBuilder.isThinStrip}, so a forecast satisfying both arms
     * with a near-minus-far drop of at least {@link #PROMPT_STRIP_DROP_PP} (which takes near
     * &ge; 80) prints THIN STRIP and is counted here regardless. See {@link #corridorBuckets} for
     * why that is left in and how to size it.
     *
     * @param pair one verified pair
     * @return true when the forecast gap and the forecast far reading both reached
     *         {@link #PROMPT_BLANKET_MIN_PCT}
     */
    private static boolean forecastWasBlanket(CloudVerificationPair pair) {
        return pair.forecastGapLow() != null
                && pair.forecastGapLow() >= PROMPT_BLANKET_MIN_PCT
                && pair.forecastFarLow() != null
                && pair.forecastFarLow() >= PROMPT_BLANKET_MIN_PCT;
    }

    /**
     * Returns whether the analysed 226 km corridor read as open.
     *
     * <p>Extracted because two buckets score the same observed band — the pre-registered
     * {@code corridorOpen} and its under-the-triage-cut counterpart — and the whole value of the
     * second is that it applies the identical test to a narrower parent. Two inline copies of the
     * comparison would let exactly that assumption drift.
     *
     * @param pair one verified pair
     * @return true when the analysed far reading was at or below
     *         {@link #OBS_CORRIDOR_OPEN_MAX_PCT}
     */
    private static boolean corridorWasOpen(CloudVerificationPair pair) {
        return pair.observedFarLow() != null
                && pair.observedFarLow() <= OBS_CORRIDOR_OPEN_MAX_PCT;
    }

    /**
     * Returns whether the forecast gap sat at or below the triage cut, so the slot could have
     * reached Claude.
     *
     * <p>Extracted so every bucket cut on the cut applies the identical test — the blanket
     * sub-bucket and its own corridor cut in {@link #corridorBuckets}, and all four variants in
     * {@link #triageCutBuckets}. This is a gate in front of the prompt, not one of the prompt's
     * bands, which is why it sits apart from the {@code PROMPT_*} predicates.
     *
     * <p>States only what it measures. Passing the cut does not establish that a prompt was built
     * (precipitation and visibility triage can still have fired, and the canopy lanes prompt
     * elsewhere), and failing it does not establish that none was (a JFDI run submits triaged
     * candidates). It is the one triage rule reading the same column this report projects, which
     * is what makes it checkable here at all.
     *
     * @param pair one verified pair; a pair carrying no forecast gap reading is a non-member,
     *            since it cannot be shown to sit under a cut on a reading it does not have
     * @return true when the forecast gap was at or below {@link #TRIAGE_SOLAR_LOW_CLOUD_MAX_PCT}
     */
    private static boolean forecastGapUnderTriageCut(CloudVerificationPair pair) {
        return pair.forecastGapLow() != null
                && pair.forecastGapLow() <= TRIAGE_SOLAR_LOW_CLOUD_MAX_PCT;
    }

    /**
     * Returns whether the forecast's own near-vs-far pair meets the THIN STRIP override's
     * preconditions.
     *
     * <p>Mirrors {@code PromptBuilder.isThinStrip}'s <em>label logic</em> — a non-null far reading
     * and a near-minus-far drop of at least {@link #PROMPT_STRIP_DROP_PP} — not the prompt prose's
     * additional "far low cloud &le;30%" clause, because the label is what the forecast data block
     * this report reads actually carries.
     *
     * <p>The override's other precondition, near low cloud &ge;50%
     * ({@code SOLAR_LOW_CLOUD_SIGNIFICANT_PERCENT}), is implied rather than re-checked here: every
     * caller passes pairs already filtered to the BLOCKED band (&gt;60%), which sits strictly
     * inside the &ge;50% arm.
     *
     * @param pair one verified pair, already known to carry a non-null {@code forecastGapLow}
     * @return true when a non-null far reading dropped at least {@link #PROMPT_STRIP_DROP_PP} below
     *         the forecast gap reading
     */
    private static boolean thinStripPreconditionsHeld(CloudVerificationPair pair) {
        return pair.forecastFarLow() != null
                && pair.forecastGapLow() - pair.forecastFarLow() >= PROMPT_STRIP_DROP_PP;
    }

    /**
     * Returns whether the forecast handed Claude the IDEAL scenario's preconditions.
     *
     * <p>The gap term is exact — {@code forecastGapLow} is the coned solar-horizon low cloud the
     * rule branches on. The two canvas terms are an <strong>approximation</strong>, and the
     * substitution is worth stating precisely, because getting a bucket's conditioning quantity
     * wrong is the defect this whole section exists to correct. The prompt's IDEAL rule is
     * horizon-scoped ("mid cloud &lt;50% at solar horizon", "high cloud present on either
     * horizon"); these clauses read the <em>observer-overhead</em> forecast layers instead.
     *
     * <p>Not because the horizon layers are missing: {@code solar_mid_cloud},
     * {@code solar_high_cloud} and {@code antisolar_high_cloud} have been persisted on
     * {@code forecast_evaluation} since V48, and are non-null whenever the gap reading is. They
     * are simply not <em>projected</em> — {@code CloudVerificationRepository.findVerifiedPairs}
     * binds the canvas components to {@code e.midCloud}/{@code e.highCloud}. Narrowing this to the
     * rule's real preconditions is therefore a projection widening (three columns onto
     * {@link CloudVerificationPair}), not a migration or a re-verification.
     *
     * <p>So read this bucket as the population that entered the IDEAL band with a canvas overhead,
     * which overlaps the rule's true firing set without equalling it in either direction: a sky
     * with thick solar-horizon mid cloud and a thin overhead deck is counted here though the
     * prompt routes it elsewhere, and the converse is missed.
     *
     * @param pair one verified pair
     * @return true when the forecast's gap and overhead canvas both sat inside the IDEAL bands
     */
    private static boolean forecastWasIdeal(CloudVerificationPair pair) {
        return pair.forecastGapLow() != null && pair.forecastGapLow() < PROMPT_CLEAR_BAND_MAX_PCT
                && pair.forecastCanvasMid() != null
                && pair.forecastCanvasMid() < PROMPT_IDEAL_MID_MAX_PCT
                && pair.forecastCanvasHigh() != null
                && pair.forecastCanvasHigh() >= PROMPT_IDEAL_HIGH_MIN_PCT;
    }
}
