package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.BriefingRegionSnapshotEntity;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.repository.BriefingRegionSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Records what each briefing build displayed per region, and reads the previous build back so the
 * Plan tab can say which way a window moved.
 *
 * <p>It exists as its own bean rather than as two more methods on {@code BriefingService} because
 * the two halves answer to different clocks and different failure rules: the writer is a fire-and-
 * forget side effect of a build (a failure costs the chip, never the briefing), while the reader
 * runs on the serve path and must be cheap and total.
 *
 * <h2>One previous BUILD, not one previous row per region</h2>
 *
 * <p>The delta's basis is a single build stamp — the newest strictly before the live response's own
 * — and every chip on the screen is measured against that one build. Reaching further back per
 * region (taking each region's own latest earlier row) would give a screen whose chips have
 * different ages while a single "since the last forecast run" line describes them all. A region
 * absent from that build simply gets no chip, which is the documented degrade: silence.
 *
 * <h2>The current side is the SERVE-time mean</h2>
 *
 * <p>Stated here as well as on the payload field because it is the one semantic a reader of a chip
 * could get wrong. {@code BriefingService.enrichWithCachedScores} re-runs on every serve, so the
 * current mean can have moved since the build that wrote this run's snapshot — the delta therefore
 * includes post-build batch drift, not only run-to-run change. That is the honest quantity: it is
 * what the reader's screen moved by. It is just not literally "since the last build".
 */
@Service
public class BriefingRegionSnapshotService {

    private static final Logger LOG = LoggerFactory.getLogger(BriefingRegionSnapshotService.class);

    /**
     * How long snapshots are kept.
     *
     * <p>Ninety days is a retention rule, not a feature requirement — the chip reads exactly one
     * build back. The history is kept so the movement channel can later be checked against
     * outcomes; the ceiling exists so a table written on every build cannot grow without bound.
     * Measured: 4 enabled regions × 5 briefed dates × 2 events ≈ 40 rows per build, and V103
     * retired the standalone briefing cron so builds now ride the pipeline — roughly two a day, not
     * the three the old schedule implied. About 7k rows at steady state, so the ceiling is
     * comfortable rather than necessary.
     */
    static final Duration RETENTION = Duration.ofDays(90);

    /** Deltas are published at the same 1dp the means themselves carry. */
    private static final int DELTA_SCALE = 1;

    /**
     * The precision every build stamp is reduced to before it is stored or compared.
     *
     * <p>⚠️ <b>Without this the channel silently compares a build against ITSELF, on Linux only.</b>
     * {@code briefing_generated_at} is a Postgres {@code TIMESTAMP}, which holds MICROseconds, while
     * {@code LocalDateTime.now(clock)} on Linux carries NANOseconds (macOS's {@code gettimeofday}
     * gives microseconds, which is why no local run and no reviewer's laptop can reproduce it).
     * pgjdbc rounds half-up on the way in, so for every build whose sub-microsecond remainder is
     * ≤ 499 ns — about half of them — the value in the row is strictly LESS than the value held in
     * memory. {@link BriefingRegionSnapshotRepository#findPreviousBuildStamp} then answers with the
     * current build's own row, every delta becomes {@code serveMean − sameBuildMean}, and the strip
     * fills with the muted {@code —} that {@code movement.js} documents as "a real measured zero".
     * A wrong number asserted confidently, on the production platform, invisible everywhere else.
     *
     * <p>Truncating BOTH the stored value and the comparand to the column's own precision makes the
     * two sides identical by construction, so the strict {@code <} means what it says.
     */
    private static final ChronoUnit STAMP_PRECISION = ChronoUnit.MICROS;

    /**
     * Reduces a build stamp to the precision the column can hold.
     *
     * @param stamp the in-memory stamp, or null
     * @return the stamp at storage precision, or null
     */
    static LocalDateTime atStoragePrecision(LocalDateTime stamp) {
        return stamp == null ? null : stamp.truncatedTo(STAMP_PRECISION);
    }

    private final BriefingRegionSnapshotRepository repository;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param repository the snapshot sink
     * @param clock      the injected clock, for the write instant and the retention cutoff
     */
    public BriefingRegionSnapshotService(BriefingRegionSnapshotRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * One build's numbers, keyed {@code regionName|date|targetType}.
     *
     * @param generatedAt the build stamp these means came from, or {@code null} when there is no
     *                    previous build on record
     * @param meanByKey   the voting mean each region displayed, absent where none was scored
     */
    public record PreviousBuild(LocalDateTime generatedAt, Map<String, Double> meanByKey) {

        /**
         * The empty answer — no previous build, so no chip anywhere.
         *
         * @return a basis-free result carrying neither a stamp nor any mean
         */
        public static PreviousBuild none() {
            return new PreviousBuild(null, Map.of());
        }

        /**
         * Whether there is any basis to measure against.
         *
         * @return true when no previous build's mean is available
         */
        public boolean isEmpty() {
            return meanByKey.isEmpty();
        }
    }

    /**
     * The lookup key both sides of the delta are built from.
     *
     * <p>Region names are joined byte-identically — nothing here trims or case-folds — because the
     * same string is the heat field's focus key and the rail's label, and a normalisation on one
     * side of a join is how a whole region silently stops matching.
     *
     * @param regionName the region's display name
     * @param date       the briefed date
     * @param targetType the solar event
     * @return the composite key
     */
    static String key(String regionName, LocalDate date, String targetType) {
        return regionName + "|" + date + "|" + targetType;
    }

    /**
     * Writes one row per region × date × event for the build just completed, then prunes.
     *
     * <p><b>The CALLER isolates this — {@code BriefingService.recordRegionSnapshots} — and this
     * method itself throws.</b> Stated that way round on purpose: the catch has to sit outside the
     * transactional proxy, or a failed statement resurfaces at commit time past it. That is exactly
     * the defect {@link #previousBuild} was carrying. Called at the tail of
     * {@code BriefingService.refreshBriefing} alongside the NLC, meteor and surge refreshes, and
     * isolated for the same reason they are: an optional enrichment sink must not be able to abort
     * a briefing cycle or leave its job run dangling.
     *
     * @param generatedAt the build's own stamp, {@code DailyBriefingResponse.generatedAt}
     * @param days        the ENRICHED day hierarchy — the regions must already carry the
     *                    {@code meanRating} the payload will publish, or this records a different
     *                    number from the one the screen showed
     */
    @Transactional
    public void record(LocalDateTime generatedAt, List<BriefingDay> days) {
        if (generatedAt == null || days == null || days.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        List<BriefingRegionSnapshotEntity> rows = new ArrayList<>();
        for (BriefingDay day : days) {
            for (BriefingEventSummary es : day.eventSummaries()) {
                for (BriefingRegion region : es.regions()) {
                    rows.add(toEntity(region, day.date(), es, generatedAt, now));
                }
            }
        }
        if (rows.isEmpty()) {
            return;
        }
        repository.saveAll(rows);
        int pruned = repository.deleteByGeneratedAtBefore(now.minus(RETENTION));
        LOG.info("[MOVEMENT] recorded {} region snapshots for build {} ({} pruned beyond {}d)",
                rows.size(), generatedAt, pruned, RETENTION.toDays());
    }

    private BriefingRegionSnapshotEntity toEntity(BriefingRegion region, LocalDate date,
            BriefingEventSummary es, LocalDateTime generatedAt, Instant now) {
        BriefingRegionSnapshotEntity row = new BriefingRegionSnapshotEntity();
        row.setRegionName(region.regionName());
        row.setEvaluationDate(date);
        row.setTargetType(es.targetType().name());
        // Null stays null: "nothing that votes was scored" is not a zero, and a zero here would
        // manufacture a large fictitious delta on the next build.
        row.setMeanRating(region.meanRating() == null
                ? null
                : BigDecimal.valueOf(region.meanRating()).setScale(DELTA_SCALE, RoundingMode.HALF_UP));
        row.setVotingCount(ratedVotingCount(region));
        row.setDisplayVerdict(region.displayVerdict() == null ? null
                : region.displayVerdict().name());
        row.setGeneratedAt(now);
        // Truncated, and the read truncates to match — see STAMP_PRECISION for why a nanosecond
        // that survives here makes every build compare against itself.
        row.setBriefingGeneratedAt(atStoragePrecision(generatedAt));
        return row;
    }

    /**
     * How many slots the recorded mean was actually taken over.
     *
     * <p>⚠️ <b>The RATED voting slots, not the voting roster</b> — and the distinction is the whole
     * reason the column exists. {@code meanRating} comes from {@code BriefingRatingStats.compute},
     * whose {@code count} increments only for slots that pass {@code RatingValidator}, so a region
     * with twelve voting locations of which two are rated publishes a mean over TWO. Recording
     * twelve beside it would answer the opposite of the question the entity's javadoc asks ("a mean
     * over one location or over twelve"), and it is unrecoverable afterwards: the payload that mean
     * came from is overwritten by the next build, which is the reason for this table at all.
     *
     * <p>It runs the same computation rather than a hand-rolled null check, so the recorded
     * {@code n} and the recorded mean cannot come from two different rules.
     *
     * @param region the enriched region
     * @return the number of voting slots that carried a valid rating
     */
    private static int ratedVotingCount(BriefingRegion region) {
        if (region.slots() == null) {
            return 0;
        }
        List<BriefingRatingStats.Entry> entries = BriefingSlot.votingSlots(region.slots()).stream()
                .map(slot -> new BriefingRatingStats.Entry(slot.locationName(), slot.claudeRating()))
                .toList();
        return BriefingRatingStats.compute(entries, region.regionName(), null, null).count();
    }

    /**
     * Loads the build immediately before the given stamp.
     *
     * <p>Two indexed queries, run once per serve of {@code /api/briefing}. No rows and no earlier
     * build both resolve to {@link PreviousBuild#none()}, and the frontend renders nothing at all
     * for a null delta.
     *
     * <p>⚠️ <b>It THROWS on a repository failure, and the caller must catch — deliberately, after a
     * review found the opposite shape broken.</b> An earlier cut was
     * {@code @Transactional(readOnly = true)} with the {@code try/catch} INSIDE the method, which
     * cannot work: a JPA exception marks the transaction rollback-only, so swallowing it merely
     * defers the failure to the proxy's commit, which then throws
     * {@code UnexpectedRollbackException} past the catch and 500s {@code GET /api/briefing} and
     * {@code GET /api/user/settings/reach} together. The isolation therefore lives OUTSIDE the bean
     * boundary, in {@code BriefingService.attachMovement} — the shape
     * {@code recordRegionSnapshots} already uses on the write side. No transaction is declared here
     * at all: two read-only queries need none, and declaring one only re-creates the trap.
     *
     * @param currentGeneratedAt the live response's own build stamp
     * @return the previous build's means, or {@link PreviousBuild#none()}
     */
    public PreviousBuild previousBuild(LocalDateTime currentGeneratedAt) {
        if (currentGeneratedAt == null) {
            return PreviousBuild.none();
        }
        Optional<LocalDateTime> stamp =
                repository.findPreviousBuildStamp(atStoragePrecision(currentGeneratedAt));
        if (stamp.isEmpty() || stamp.get() == null) {
            return PreviousBuild.none();
        }
        LocalDateTime previous = stamp.get();
        Map<String, Double> means = new HashMap<>();
        for (BriefingRegionSnapshotEntity row : repository.findByBriefingGeneratedAt(previous)) {
            if (row.getMeanRating() == null) {
                continue;
            }
            means.put(key(row.getRegionName(), row.getEvaluationDate(), row.getTargetType()),
                    row.getMeanRating().doubleValue());
        }
        return new PreviousBuild(previous, means);
    }

    /**
     * The published delta between two means, or {@code null} when either side is unknown.
     *
     * <p>Rounded to 1dp <em>after</em> the subtraction. Both inputs are already 1dp, so the
     * arithmetic is exact in decimal and inexact in binary — {@code 3.7 - 3.1} is
     * {@code 0.5999999999999996} as a double, and publishing that would put a fifteen-digit
     * number behind a chip reading {@code ▲0.6}.
     *
     * <p>A measured {@code 0.0} is returned rather than nulled. It is a real answer — this region
     * did not move — and the strip renders it as its own mark; collapsing it into null would make
     * "did not move" indistinguishable from "we have no idea".
     *
     * @param current  the serve-time mean, or null
     * @param previous the previous build's mean, or null
     * @return the 1dp delta, or null
     */
    public static Double delta(Double current, Double previous) {
        if (current == null || previous == null) {
            return null;
        }
        return BigDecimal.valueOf(current)
                .subtract(BigDecimal.valueOf(previous))
                .setScale(DELTA_SCALE, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
