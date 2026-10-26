package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.SeenTag;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.CalibrationBucket;
import com.gregochr.goldenhour.model.CalibrationPair;
import com.gregochr.goldenhour.model.CalibrationReport;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.repository.OutcomeRatingRepository;
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
    private OutcomeRatingRepository outcomeRatingRepository;

    private ForecastCalibrationService service;

    @BeforeEach
    void setUp() {
        service = new ForecastCalibrationService(
                forecastEvaluationRepository, outcomeRatingRepository);
    }

    @Test
    @DisplayName("report() keeps only the newest run for a slot at the same horizon")
    void report_sameSlotAndHorizon_keepsNewestRun() {
        // The nightly batch and the intraday refresh both wrote T+0 for this slot. Counting both
        // would weight one sunset twice; the later run is the one that was actually on screen.
        CalibrationPair stale = pair(0, LocalDateTime.of(2026, 5, 10, 1, 0), 1, 5);
        CalibrationPair fresh = pair(0, LocalDateTime.of(2026, 5, 10, 14, 0), 5, 5);
        when(forecastEvaluationRepository.findCalibrationPairsFromRatings(FROM, TO))
                .thenReturn(List.of(stale, fresh));

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

        when(forecastEvaluationRepository.findCalibrationPairsFromRatings(FROM, TO))
                .thenReturn(List.of(noTimestamp, timestamped));
        assertThat(service.report(FROM, TO).overall().missedOpportunities()).isZero();

        when(forecastEvaluationRepository.findCalibrationPairsFromRatings(FROM, TO))
                .thenReturn(List.of(timestamped, noTimestamp));
        assertThat(service.report(FROM, TO).overall().missedOpportunities()).isZero();
    }

    @Test
    @DisplayName("report() keeps both horizons for the same slot")
    void report_differentHorizons_keepsBoth() {
        when(forecastEvaluationRepository.findCalibrationPairsFromRatings(FROM, TO)).thenReturn(List.of(
                pair(0, LocalDateTime.of(2026, 5, 10, 1, 0), 4, 4),
                pair(3, LocalDateTime.of(2026, 5, 7, 1, 0), 1, 4)));

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
        when(forecastEvaluationRepository.findCalibrationPairsFromRatings(FROM, TO)).thenReturn(List.of(
                pairAtDate(DATE.plusDays(1), 10, 3, 3),
                pairAtDate(DATE.plusDays(2), 2, 3, 3),
                pairAtDate(DATE.plusDays(3), 0, 3, 3)));

        CalibrationReport report = service.report(FROM, TO);

        assertThat(report.byDaysAhead()).extracting(CalibrationBucket::key)
                .containsExactly("T+0", "T+2", "T+10");
    }

    // ── the mist-prone bucket (valley-mist-plan M3) ────────────────────────────────

    /** A reader's-rating pair at a given forecast star, actual star, mist band and seen tag. */
    private CalibrationPair mistPair(long userId, int predicted, int actual, String mist, SeenTag seen) {
        return new CalibrationPair("Brathay", DATE, TargetType.SUNRISE, 0,
                LocalDateTime.of(2026, 5, 9, 18, 0), EvaluationModel.HAIKU, predicted, null, null,
                actual, userId, null, null, mist, seen);
    }

    @Test
    @DisplayName("report() builds a mist-prone bucket from LIKELY pairs only, with its own missed "
            + "opportunities - the number the valley-mist plan exists to drive to zero")
    void report_mistProneBucketHoldsLikelyPairsOnly() {
        when(forecastEvaluationRepository.findCalibrationPairsFromRatings(FROM, TO)).thenReturn(List.of(
                // told to stay home (2) on a morning the reader rated 5, and tagged mist: THE miss
                mistPair(1, 2, 5, "LIKELY", SeenTag.MIST),
                // a LIKELY forecast that was right
                mistPair(2, 4, 4, "LIKELY", SeenTag.MIST),
                // a LIKELY forecast that sent someone for a dud
                mistPair(3, 5, 1, "LIKELY", SeenTag.FLAT),
                // the same miss shape, but the forecast carried no LIKELY band: not in the bucket
                mistPair(4, 2, 5, "POSSIBLE", SeenTag.MIST),
                mistPair(5, 2, 5, "NONE", SeenTag.COLOUR),
                mistPair(6, 2, 5, null, null)));

        CalibrationReport report = service.report(FROM, TO);

        assertThat(report.scoredPairs()).isEqualTo(6);
        assertThat(report.overall().missedOpportunities()).isEqualTo(4);
        CalibrationBucket mist = report.mistProne();
        assertThat(mist).isNotNull();
        assertThat(mist.key()).isEqualTo("MIST_LIKELY");
        assertThat(mist.sampleCount()).isEqualTo(3);
        assertThat(mist.missedOpportunities()).isEqualTo(1);
        assertThat(mist.wastedTrips()).isEqualTo(1);
    }

    @Test
    @DisplayName("report() leaves FOGBOUND out of the mist-prone bucket: a white frame is not LIKELY mist")
    void report_fogboundIsNotMistProne() {
        when(forecastEvaluationRepository.findCalibrationPairsFromRatings(FROM, TO))
                .thenReturn(List.of(mistPair(1, 2, 5, "FOGBOUND", SeenTag.MIST)));

        assertThat(service.report(FROM, TO).mistProne()).isNull();
    }

    @Test
    @DisplayName("report() builds the bucket from the DEDUPED pairs: when the newer run of a reader's "
            + "forecast is not LIKELY, the older LIKELY run does not count")
    void report_mistProneUsesTheNewestRunOnly() {
        CalibrationPair older = new CalibrationPair("Brathay", DATE, TargetType.SUNRISE, 0,
                LocalDateTime.of(2026, 5, 9, 6, 0), EvaluationModel.HAIKU, 2, null, null, 5, 1L, null,
                null, "LIKELY", SeenTag.MIST);
        CalibrationPair newer = new CalibrationPair("Brathay", DATE, TargetType.SUNRISE, 0,
                LocalDateTime.of(2026, 5, 9, 18, 0), EvaluationModel.HAIKU, 4, null, null, 5, 1L, null,
                null, "POSSIBLE", SeenTag.MIST);
        when(forecastEvaluationRepository.findCalibrationPairsFromRatings(FROM, TO))
                .thenReturn(List.of(older, newer));

        CalibrationReport report = service.report(FROM, TO);

        assertThat(report.scoredPairs()).isEqualTo(1);
        assertThat(report.mistProne()).isNull();
    }

    @Test
    @DisplayName("report() keeps the seen tally in enum order, UNTAGGED last")
    void report_seenCountsKeepTheirOrder() {
        when(forecastEvaluationRepository.findCalibrationPairsFromRatings(FROM, TO)).thenReturn(List.of(
                mistPair(1, 3, 3, "LIKELY", null),
                mistPair(2, 3, 3, "LIKELY", SeenTag.FLAT),
                mistPair(3, 3, 3, "LIKELY", SeenTag.COLOUR),
                mistPair(4, 3, 3, "LIKELY", SeenTag.MIST)));

        assertThat(service.report(FROM, TO).mistProneSeenCounts().keySet())
                .containsExactly("COLOUR", "MIST", "FLAT", "UNTAGGED");
    }

    @Test
    @DisplayName("report() tallies what readers saw across the mist-prone pairs, UNTAGGED included, "
            + "summing to the bucket's sample")
    void report_mistProneSeenCounts() {
        when(forecastEvaluationRepository.findCalibrationPairsFromRatings(FROM, TO)).thenReturn(List.of(
                mistPair(1, 2, 5, "LIKELY", SeenTag.MIST),
                mistPair(2, 4, 4, "LIKELY", SeenTag.MIST),
                mistPair(3, 3, 3, "LIKELY", SeenTag.INVERSION),
                mistPair(4, 3, 3, "LIKELY", null),
                // outside the bucket: must not be counted
                mistPair(5, 3, 3, "POSSIBLE", SeenTag.COLOUR)));

        CalibrationReport report = service.report(FROM, TO);

        assertThat(report.mistProneSeenCounts()).containsExactlyInAnyOrderEntriesOf(
                java.util.Map.of("MIST", 2, "INVERSION", 1, "UNTAGGED", 1));
        assertThat(report.mistProneSeenCounts().values().stream().mapToInt(Integer::intValue).sum())
                .isEqualTo(report.mistProne().sampleCount());
    }

    @Test
    @DisplayName("report() has no mist-prone bucket, and an empty tally, when no pair carried LIKELY")
    void report_noMistProne_isNull() {
        when(forecastEvaluationRepository.findCalibrationPairsFromRatings(FROM, TO)).thenReturn(List.of(
                pair(0, LocalDateTime.of(2026, 5, 10, 1, 0), 3, 3),
                mistPair(2, 2, 5, "POSSIBLE", SeenTag.MIST)));

        CalibrationReport report = service.report(FROM, TO);

        assertThat(report.mistProne()).isNull();
        assertThat(report.mistProneSeenCounts()).isEmpty();
    }

    @Test
    @DisplayName("report() groups by model and labels a missing model rather than dropping it")
    void report_groupsByModel() {
        CalibrationPair haiku = pair(0, LocalDateTime.of(2026, 5, 10, 1, 0), 3, 3);
        CalibrationPair noModel = new CalibrationPair("Edinburgh", DATE, TargetType.SUNSET, 0,
                LocalDateTime.of(2026, 5, 10, 1, 0), null, 3, null, null, 3, 1L, null, null);
        when(forecastEvaluationRepository.findCalibrationPairsFromRatings(FROM, TO))
                .thenReturn(List.of(haiku, noModel));

        CalibrationReport report = service.report(FROM, TO);

        assertThat(report.byModel()).extracting(CalibrationBucket::key)
                .containsExactly("HAIKU", "UNKNOWN");
        assertThat(report.scoredPairs()).isEqualTo(2);
    }

    @Test
    @DisplayName("V182: report() buckets by the build that scored the forecast, readers' ratings included, "
            + "and labels a row from before builds were recorded UNKNOWN")
    void report_groupsByBuild() {
        when(forecastEvaluationRepository.findCalibrationPairsFromRatings(FROM, TO)).thenReturn(List.of(
                stamped(0, 3, 3, 1L, "v2.28.0", null),
                stamped(1, 5, 1, 2L, "v2.27.0", null),
                stamped(2, 4, 4, 3L, null, null),
                new CalibrationPair("Whitby", DATE, TargetType.SUNRISE, 0,
                        LocalDateTime.of(2026, 5, 10, 1, 0), EvaluationModel.HAIKU, 4, null, null, 2, 7L,
                        "v2.28.0", null)));

        CalibrationReport report = service.report(FROM, TO);

        assertThat(report.bucketsByBuild()).extracting(CalibrationBucket::key)
                .containsExactly("UNKNOWN", "v2.27.0", "v2.28.0");
        assertThat(report.bucketsByBuild()).extracting(CalibrationBucket::sampleCount)
                .containsExactly(1, 1, 2);
        CalibrationBucket current = report.bucketsByBuild().get(2);
        // The first rating (3 vs 3) and the second reader's rating (4 vs 2) share a release.
        assertThat(current.meanRatingError()).isEqualTo(1.0);
        assertThat(report.bucketsByBuild().get(1).wastedTrips())
                .as("v2.27.0 told someone to go on a day that was 1 star").isEqualTo(1);
    }

    @Test
    @DisplayName("V182: report() buckets by prompt fingerprint so an edit to the prompt shows as its own bucket")
    void report_groupsByPromptHash() {
        String before = "a".repeat(64);
        String after = "b".repeat(64);
        when(forecastEvaluationRepository.findCalibrationPairsFromRatings(FROM, TO)).thenReturn(List.of(
                stamped(0, 3, 3, 1L, "v2.28.0", before),
                stamped(1, 4, 2, 2L, "v2.28.0", before),
                stamped(2, 4, 4, 3L, "v2.28.0", after),
                stamped(3, 2, 2, 4L, "v2.28.0", null)));

        CalibrationReport report = service.report(FROM, TO);

        assertThat(report.bucketsByPromptHash()).extracting(CalibrationBucket::key)
                .containsExactly("UNKNOWN", before, after);
        assertThat(report.bucketsByPromptHash()).extracting(CalibrationBucket::sampleCount)
                .containsExactly(1, 2, 1);
        assertThat(report.bucketsByBuild()).as("one release, however many prompts").hasSize(1);
    }

    @Test
    @DisplayName("V182: a blank build or prompt hash is UNKNOWN, never an empty-named bucket")
    void report_blankBuildIsUnknown() {
        when(forecastEvaluationRepository.findCalibrationPairsFromRatings(FROM, TO)).thenReturn(List.of(
                stamped(0, 3, 3, 1L, " ", ""),
                stamped(1, 3, 3, 2L, null, null)));

        CalibrationReport report = service.report(FROM, TO);

        assertThat(report.bucketsByBuild()).extracting(CalibrationBucket::key).containsExactly("UNKNOWN");
        assertThat(report.bucketsByBuild().getFirst().sampleCount()).isEqualTo(2);
        assertThat(report.bucketsByPromptHash()).extracting(CalibrationBucket::key)
                .containsExactly("UNKNOWN");
    }

    @Test
    @DisplayName("report() returns an empty report rather than failing when nothing is recorded")
    void report_noPairs_returnsEmptyReport() {

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

    @Test
    @DisplayName("report() keeps two readers' ratings of one slot at one horizon as two pairs")
    void report_twoUsersRatingOneSlot_keepsBoth() {
        LocalDateTime run = LocalDateTime.of(2026, 5, 10, 1, 0);
        when(forecastEvaluationRepository.findCalibrationPairsFromRatings(FROM, TO)).thenReturn(List.of(
                ratingPair(11L, 0, run, 4, 5),
                ratingPair(12L, 0, run, 4, 2)));

        CalibrationReport report = service.report(FROM, TO);

        // One forecast, two observations: a calibration sample counts each reader.
        assertThat(report.scoredPairs()).isEqualTo(2);
        assertThat(report.overall().sampleCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("report() still collapses two runs of one forecast for one reader to the newest")
    void report_twoRunsForOneReader_keepsNewest() {
        CalibrationPair stale = ratingPair(11L, 0, LocalDateTime.of(2026, 5, 10, 1, 0), 1, 5);
        CalibrationPair fresh = ratingPair(11L, 0, LocalDateTime.of(2026, 5, 10, 14, 0), 5, 5);
        when(forecastEvaluationRepository.findCalibrationPairsFromRatings(FROM, TO))
                .thenReturn(List.of(stale, fresh));

        CalibrationReport report = service.report(FROM, TO);

        assertThat(report.scoredPairs()).isEqualTo(1);
        // The newer run said 5 and the reader saw 5; the stale 1-vs-5 miss is gone.
        assertThat(report.overall().meanRatingError()).isZero();
        assertThat(report.overall().missedOpportunities()).isZero();
    }

    @Test
    @DisplayName("report() counts every rating in the window, not-seen ratings included")
    void report_outcomesInRange_countsRatings() {
        when(forecastEvaluationRepository.findCalibrationPairsFromRatings(FROM, TO)).thenReturn(List.of());
        when(outcomeRatingRepository.countByEventDateBetween(FROM, TO)).thenReturn(3L);

        CalibrationReport report = service.report(FROM, TO);

        assertThat(report.outcomesInRange()).isEqualTo(3);
        assertThat(report.scoredPairs()).isZero();
    }

    private CalibrationPair ratingPair(long userId, int daysAhead, LocalDateTime runAt, int predicted,
            int actual) {
        return new CalibrationPair("Durham", DATE, TargetType.SUNSET, daysAhead, runAt,
                EvaluationModel.HAIKU, predicted, null, null, actual, userId, null, null);
    }

    private CalibrationPair stamped(int daysAhead, int predicted, int actual, long userId,
            String appBuild, String promptHash) {
        return new CalibrationPair("Durham", DATE, TargetType.SUNSET, daysAhead,
                LocalDateTime.of(2026, 5, 9, 1, 0), EvaluationModel.HAIKU, predicted, null, null, actual,
                userId, appBuild, promptHash);
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
                EvaluationModel.HAIKU, predicted, null, null, actual, 1L, null, null);
    }
}
