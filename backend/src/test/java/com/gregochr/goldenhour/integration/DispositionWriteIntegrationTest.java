package com.gregochr.goldenhour.integration;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.DispositionCategory;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastRunDispositionEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.CandidateDisposition;
import com.gregochr.goldenhour.repository.ApiCallLogRepository;
import com.gregochr.goldenhour.repository.CachedEvaluationRepository;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.repository.ForecastRunDispositionRepository;
import com.gregochr.goldenhour.repository.JobRunRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.RegionRepository;
import com.gregochr.goldenhour.service.EvaluationViewService;
import com.gregochr.goldenhour.service.batch.BatchTriggerSource;
import com.gregochr.goldenhour.service.evaluation.EvaluationHandle;
import com.gregochr.goldenhour.service.evaluation.EvaluationService;
import com.gregochr.goldenhour.service.evaluation.EvaluationTask;
import com.gregochr.goldenhour.service.batch.ForecastDispositionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static com.gregochr.goldenhour.integration.AnthropicWireMockFixtures.RequestCounts;
import static com.gregochr.goldenhour.integration.AnthropicWireMockFixtures.stubBatchCreate;
import static com.gregochr.goldenhour.integration.AnthropicWireMockFixtures.stubBatchRetrieve;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-path integration test for the V101 disposition write — the test that would
 * have caught the seam bug that hid for two days in production.
 *
 * <p><b>Why this test exists.</b> The original V101 unit tests mocked
 * {@code EvaluationService.submit} to return an {@code EvaluationHandle} with a
 * non-null {@code jobRunId} — but the real seam in {@code EvaluationServiceImpl}
 * hard-coded {@code null} when constructing the handle, because {@code
 * BatchSubmitResult} did not carry the field. So {@code
 * ScheduledBatchEvaluationService.doSubmitForecastBatch} always passed null into
 * {@code dispositionService.persist}, which silently no-opped on null. Result:
 * 0 rows in {@code forecast_run_disposition} from V101 onward despite the unit
 * tests passing.
 *
 * <p>This test drives the production code through the REAL
 * {@code EvaluationServiceImpl} → {@code BatchSubmissionService} → {@code
 * JobRunService} → {@code ForecastDispositionService} path with only the
 * Anthropic HTTP API stubbed (via WireMock), and queries the real Postgres table
 * to assert rows landed. The reconciliation check
 * (rows = dispositions handed to persist) is the contract the spec called out.
 */
class DispositionWriteIntegrationTest extends IntegrationTestBase {

    @Autowired
    private EvaluationService evaluationService;

    @Autowired
    private ForecastDispositionService dispositionService;

    @Autowired
    private ForecastRunDispositionRepository dispositionRepository;

    @Autowired
    private LocationRepository locationRepository;

    @Autowired
    private RegionRepository regionRepository;

    @Autowired
    private JobRunRepository jobRunRepository;

    @Autowired
    private ForecastBatchRepository batchRepository;

    @Autowired
    private CachedEvaluationRepository cachedEvaluationRepository;

    @Autowired
    private ApiCallLogRepository apiCallLogRepository;

    @Autowired
    private EvaluationViewService evaluationViewService;

    @AfterEach
    void clearDataBetweenTests() {
        // Order matters — child rows first, then parents.
        dispositionRepository.deleteAll();
        apiCallLogRepository.deleteAll();
        cachedEvaluationRepository.deleteAll();
        batchRepository.deleteAll();
        jobRunRepository.deleteAll();
        locationRepository.deleteAll();
        regionRepository.deleteAll();
    }

    @Test
    @DisplayName("EvaluationHandle returned by the real seam carries a non-null jobRunId "
            + "(direct guard for the V101 hidden bug)")
    void evaluationHandleFromRealSeam_carriesNonNullJobRunId() {
        // Seed minimum to call submit with a valid forecast task.
        LocationEntity location = seedLocation("Castlerigg", 54.6029, -3.0980);
        String batchId = "msgbatch_dispo_seam_guard";
        WIRE_MOCK.stubFor(stubBatchCreate(batchId));
        WIRE_MOCK.stubFor(stubBatchRetrieve(batchId, "in_progress",
                new RequestCounts(1, 0, 0, 0, 0)));

        EvaluationTask.Forecast task = buildTask(location, LocalDate.now().plusDays(1));

        EvaluationHandle handle = evaluationService.submit(
                List.of(task), BatchTriggerSource.SCHEDULED);

        // The single assertion the V101 unit tests should have made — and didn't,
        // because they mocked EvaluationService.submit and never reached the seam.
        assertThat(handle.jobRunId())
                .as("EvaluationHandle.jobRunId must carry the JobRunEntity id created "
                        + "by BatchSubmissionService — null here is the V101 hidden bug")
                .isNotNull();
        assertThat(handle.batchId()).isEqualTo(batchId);
        // The captured jobRunId must point to an actual row in job_run.
        assertThat(jobRunRepository.findById(handle.jobRunId()))
                .as("Captured jobRunId must resolve to a real JobRunEntity row")
                .isPresent();
    }

    @Test
    @DisplayName("Reconciliation: dispositions handed to persist() land as rows in "
            + "forecast_run_disposition; SELECT COUNT(*) matches input count")
    void persist_writesEveryDispositionRowAgainstRealJobRun() {
        // Step 1: drive a real submission to get a real jobRunId out of the seam.
        LocationEntity loc1 = seedLocation("Castlerigg", 54.6029, -3.0980);
        LocationEntity loc2 = seedLocation("Bamburgh",  55.6093, -1.7099);
        String batchId = "msgbatch_dispo_reconciliation";
        WIRE_MOCK.stubFor(stubBatchCreate(batchId));
        WIRE_MOCK.stubFor(stubBatchRetrieve(batchId, "in_progress",
                new RequestCounts(1, 0, 0, 0, 0)));

        EvaluationTask.Forecast task = buildTask(loc1, LocalDate.now().plusDays(1));
        EvaluationHandle handle = evaluationService.submit(
                List.of(task), BatchTriggerSource.SCHEDULED);

        Long realJobRunId = handle.jobRunId();
        assertThat(realJobRunId).isNotNull();

        // Step 2: hand persist() the full cycle's disposition set — one EVALUATED,
        // two SKIPPED_TRIAGED, one SKIPPED_HARD_CONSTRAINT, one SKIPPED_PAST_DATE,
        // one SKIPPED_CACHED. Six rows total — the reconciliation count this
        // assertion is checking against.
        LocalDate today = LocalDate.now();
        List<CandidateDisposition> dispositions = List.of(
                new CandidateDisposition(loc1.getId(), loc1.getName(),
                        today.plusDays(1), TargetType.SUNRISE, 1,
                        DispositionCategory.EVALUATED, null),
                new CandidateDisposition(loc2.getId(), loc2.getName(),
                        today.plusDays(1), TargetType.SUNRISE, 1,
                        DispositionCategory.SKIPPED_TRIAGED,
                        "Solar horizon low cloud 94% — sun blocked"),
                new CandidateDisposition(loc2.getId(), loc2.getName(),
                        today.plusDays(1), TargetType.SUNSET, 1,
                        DispositionCategory.SKIPPED_TRIAGED, "Heavy cloud"),
                // A hand-built fixture, not a live pipeline claim: production no longer writes a
                // SKIPPED_HARD_CONSTRAINT row for a tide mismatch since the tide gate lift
                // (2026-09-18, docs/engineering/tide-window-plan.md §6 Q1) emptied
                // BriefingGatingPolicy.HARD_CONSTRAINT_REASONS. The category and this write path
                // stay tested generically — a future hard constraint could still use them.
                new CandidateDisposition(null, "Coastal Tide Loc",
                        today.plusDays(1), TargetType.SUNRISE, 1,
                        DispositionCategory.SKIPPED_HARD_CONSTRAINT, "Tide mismatch"),
                new CandidateDisposition(null, "Yesterday Loc",
                        today.minusDays(1), TargetType.SUNRISE, -1,
                        DispositionCategory.SKIPPED_PAST_DATE, "Date in past"),
                new CandidateDisposition(null, "Cached Loc",
                        today.plusDays(1), TargetType.SUNRISE, 1,
                        DispositionCategory.SKIPPED_CACHED,
                        "Fresh cached evaluation within 6h (SETTLED)")
        );

        dispositionService.persist(realJobRunId, dispositions);

        // Step 3: query the actual table. This is the assertion the spec called for
        // — verified by querying the table, not by tests passing in isolation.
        List<ForecastRunDispositionEntity> persisted =
                dispositionRepository.findByJobRunIdOrderByDispositionAscLocationNameAsc(
                        realJobRunId);

        // Reconciliation: rows = candidates handed in.
        assertThat(persisted)
                .as("Every disposition in the input list must land as a row")
                .hasSize(dispositions.size());

        // Stronger reconciliation: per-category counts match.
        Map<DispositionCategory, Long> inputCounts = dispositions.stream()
                .collect(groupingBy(CandidateDisposition::category, counting()));
        Map<String, Long> persistedCounts = persisted.stream()
                .collect(groupingBy(ForecastRunDispositionEntity::getDisposition, counting()));
        for (Map.Entry<DispositionCategory, Long> e : inputCounts.entrySet()) {
            assertThat(persistedCounts.get(e.getKey().name()))
                    .as("Persisted count for %s must equal input count", e.getKey())
                    .isEqualTo(e.getValue());
        }

        // Field-level integrity: every row carries the real jobRunId, the correct
        // disposition string, and the detail we provided.
        assertThat(persisted)
                .allSatisfy(row -> assertThat(row.getJobRunId()).isEqualTo(realJobRunId));
        ForecastRunDispositionEntity triagedRow = persisted.stream()
                .filter(r -> "SKIPPED_TRIAGED".equals(r.getDisposition()))
                .filter(r -> "Bamburgh".equals(r.getLocationName())
                        && r.getEventType().equals("SUNRISE"))
                .findFirst()
                .orElseThrow();
        assertThat(triagedRow.getDetail())
                .isEqualTo("Solar horizon low cloud 94% — sun blocked");
        assertThat(triagedRow.getLocationId()).isEqualTo(loc2.getId());
        assertThat(triagedRow.getDaysAhead()).isEqualTo(1);
    }

    @Test
    @DisplayName("findLatestStabilitySkipTimestamps: only SKIPPED_STABILITY counts, never CACHED "
            + "or any other category, for the same slot")
    void findLatestStabilitySkipTimestamps_onlyCountsStabilityCategory() {
        // Real Postgres, real SQL: the stale-rating retraction rule (EvaluationViewService
        // .isRetractedByStabilitySkip) may only ever fire on a nightly Gate 4 stability skip — a
        // region-level SKIPPED_CACHED ("fresh cache, deliberately reused") is not a decision
        // against the rating and must never retract it. This is the SQL-level proof; the Java-side
        // rule is exercised against a mocked repository in EvaluationViewServiceTest.
        LocalDate date = LocalDate.now().plusDays(2);
        dispositionService.persist(9001L, List.of(
                new CandidateDisposition(1L, "Cached Loc", date, TargetType.SUNRISE, 2,
                        DispositionCategory.SKIPPED_CACHED, "Fresh cached evaluation within 6h"),
                new CandidateDisposition(2L, "Triaged Loc", date, TargetType.SUNRISE, 2,
                        DispositionCategory.SKIPPED_TRIAGED, "Heavy cloud"),
                new CandidateDisposition(3L, "Error Loc", date, TargetType.SUNRISE, 2,
                        DispositionCategory.SKIPPED_ERROR, "Weather fetch failed"),
                new CandidateDisposition(4L, "Stability Loc", date, TargetType.SUNRISE, 2,
                        DispositionCategory.SKIPPED_STABILITY, "T+2 UNSETTLED")));

        List<Object[]> rows = dispositionRepository
                .findLatestStabilitySkipTimestamps(date, date);

        assertThat(rows).hasSize(1);
        Object[] row = rows.getFirst();
        assertThat(row[0]).isEqualTo("Stability Loc");
        assertThat(row[1]).isEqualTo(date);
        assertThat(row[2]).isEqualTo("SUNRISE");
        assertThat(row[3]).isInstanceOf(Instant.class);
    }

    @Test
    @DisplayName("findLatestStabilitySkipTimestamps: returns the MOST RECENT skip when a slot "
            + "was stability-skipped on more than one cycle")
    void findLatestStabilitySkipTimestamps_returnsMostRecentPerSlot() throws InterruptedException {
        LocalDate date = LocalDate.now().plusDays(2);
        CandidateDisposition skip = new CandidateDisposition(5L, "Repeat Loc", date,
                TargetType.SUNSET, 2, DispositionCategory.SKIPPED_STABILITY, "T+2 UNSETTLED");

        // Two separate cycles, two separate job runs, the second strictly after the first.
        dispositionService.persist(9002L, List.of(skip));
        Thread.sleep(50);
        dispositionService.persist(9003L, List.of(skip));

        List<ForecastRunDispositionEntity> firstCycleRows =
                dispositionRepository.findByJobRunIdOrderByDispositionAscLocationNameAsc(9002L);
        List<ForecastRunDispositionEntity> secondCycleRows =
                dispositionRepository.findByJobRunIdOrderByDispositionAscLocationNameAsc(9003L);
        Instant firstCreatedAt = firstCycleRows.getFirst().getCreatedAt();
        Instant secondCreatedAt = secondCycleRows.getFirst().getCreatedAt();
        assertThat(secondCreatedAt).isAfter(firstCreatedAt);

        List<Object[]> rows = dispositionRepository
                .findLatestStabilitySkipTimestamps(date, date);

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()[3]).isEqualTo(secondCreatedAt);
    }

    @Test
    @DisplayName("findLatestStabilitySkipTimestamps: a skip outside the requested date range is excluded")
    void findLatestStabilitySkipTimestamps_respectsDateRange() {
        LocalDate inRange = LocalDate.now().plusDays(1);
        LocalDate outOfRange = LocalDate.now().plusDays(9);
        dispositionService.persist(9004L, List.of(
                new CandidateDisposition(6L, "In Range Loc", inRange, TargetType.SUNRISE, 1,
                        DispositionCategory.SKIPPED_STABILITY, "T+1 UNSETTLED"),
                new CandidateDisposition(7L, "Out Of Range Loc", outOfRange, TargetType.SUNRISE, 9,
                        DispositionCategory.SKIPPED_STABILITY, "T+9 beyond horizon")));

        List<Object[]> rows = dispositionRepository
                .findLatestStabilitySkipTimestamps(inRange, inRange);

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()[0]).isEqualTo("In Range Loc");
    }

    /**
     * SQL-level proof for {@link ForecastRunDispositionRepository#findLatestNonCachedDispositions}
     * — the verdict-minimum-sample rule's "examined" evidence (a Codex review of #943, P1-A). These
     * mirror the {@code findLatestStabilitySkipTimestamps} tests above in shape, with the one
     * deliberate difference the method itself documents: {@code SKIPPED_CACHED} is excluded from
     * BOTH sides of the correlated subquery, not merely filtered from the outer result, so a slot
     * triaged last night and reported {@code SKIPPED_CACHED} tonight still reads as examined via
     * last night's triage.
     */
    @Nested
    @DisplayName("findLatestNonCachedDispositions")
    class FindLatestNonCachedDispositions {

        @Test
        @DisplayName("names the most recent disposition: SKIPPED_TRIAGED on night one, "
                + "SKIPPED_STABILITY on night two → SKIPPED_STABILITY")
        void namesMostRecent_triagedThenStabilitySkipped() throws InterruptedException {
            LocalDate date = LocalDate.now().plusDays(2);
            dispositionService.persist(9201L, List.of(
                    new CandidateDisposition(40L, "Two Night NonCached Loc", date,
                            TargetType.SUNRISE, 2, DispositionCategory.SKIPPED_TRIAGED,
                            "Heavy cloud")));
            Thread.sleep(50);
            dispositionService.persist(9202L, List.of(
                    new CandidateDisposition(40L, "Two Night NonCached Loc", date,
                            TargetType.SUNRISE, 2, DispositionCategory.SKIPPED_STABILITY,
                            "T+2 UNSETTLED")));

            List<Object[]> rows = dispositionRepository
                    .findLatestNonCachedDispositions(date, date);

            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst()[0]).isEqualTo("Two Night NonCached Loc");
            assertThat(rows.getFirst()[3]).isEqualTo("SKIPPED_STABILITY");
        }

        @Test
        @DisplayName("names the most recent disposition in the reverse order too: "
                + "SKIPPED_STABILITY on night one, SKIPPED_TRIAGED on night two → SKIPPED_TRIAGED")
        void namesMostRecent_stabilitySkippedThenTriaged() throws InterruptedException {
            LocalDate date = LocalDate.now().plusDays(2);
            dispositionService.persist(9203L, List.of(
                    new CandidateDisposition(41L, "Reverse NonCached Loc", date,
                            TargetType.SUNSET, 2, DispositionCategory.SKIPPED_STABILITY,
                            "T+2 UNSETTLED")));
            Thread.sleep(50);
            dispositionService.persist(9204L, List.of(
                    new CandidateDisposition(41L, "Reverse NonCached Loc", date,
                            TargetType.SUNSET, 2, DispositionCategory.SKIPPED_TRIAGED,
                            "Heavy cloud")));

            List<Object[]> rows = dispositionRepository
                    .findLatestNonCachedDispositions(date, date);

            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst()[0]).isEqualTo("Reverse NonCached Loc");
            assertThat(rows.getFirst()[3]).isEqualTo("SKIPPED_TRIAGED");
        }

        @Test
        @DisplayName("SKIPPED_CACHED is excluded from BOTH sides of the correlated subquery: "
                + "triaged last night, SKIPPED_CACHED tonight still names SKIPPED_TRIAGED as "
                + "the latest — the A3 decision")
        void skippedCachedExcludedFromBothSides_earlierTriageStillNamedLatest()
                throws InterruptedException {
            LocalDate date = LocalDate.now().plusDays(2);
            dispositionService.persist(9205L, List.of(
                    new CandidateDisposition(42L, "Cached Tonight Loc", date, TargetType.SUNRISE,
                            2, DispositionCategory.SKIPPED_TRIAGED, "Heavy cloud")));
            Thread.sleep(50);
            // Tonight's cycle judged the region's existing (cached) ratings fresh and reused them
            // — a region-level decision, newer in wall-clock time than last night's triage, but
            // NOT a decision about this slot specifically.
            dispositionService.persist(9206L, List.of(
                    new CandidateDisposition(42L, "Cached Tonight Loc", date, TargetType.SUNRISE,
                            2, DispositionCategory.SKIPPED_CACHED,
                            "Fresh cached evaluation within 6h")));

            List<Object[]> rows = dispositionRepository
                    .findLatestNonCachedDispositions(date, date);

            // If SKIPPED_CACHED were merely filtered from the OUTER result (rather than excluded
            // from the inner MAX(created_at) scope too), this slot would be ABSENT here — its only
            // "latest" row would be the excluded SKIPPED_CACHED one, and the query's own
            // correlated subquery would never re-surface the SKIPPED_TRIAGED row underneath it.
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst()[0]).isEqualTo("Cached Tonight Loc");
            assertThat(rows.getFirst()[3]).isEqualTo("SKIPPED_TRIAGED");
        }

        @Test
        @DisplayName("a SKIPPED_CACHED-only slot (never triaged, never anything else) never "
                + "appears in the result at all")
        void skippedCachedOnlySlot_neverAppears() {
            LocalDate date = LocalDate.now().plusDays(2);
            dispositionService.persist(9207L, List.of(
                    new CandidateDisposition(43L, "Cached Only NonCached Loc", date,
                            TargetType.SUNRISE, 2, DispositionCategory.SKIPPED_CACHED,
                            "Fresh cached evaluation within 6h")));

            List<Object[]> rows = dispositionRepository
                    .findLatestNonCachedDispositions(date, date);

            assertThat(rows).isEmpty();
        }

        @Test
        @DisplayName("SUBMISSION_FAILED is NOT excluded, unlike SKIPPED_CACHED: triaged last "
                + "night, a later cycle re-includes the slot (tonight's own triage passed it) but "
                + "its bucket fails to submit — SUBMISSION_FAILED correctly becomes the latest "
                + "decision, shadowing the older SKIPPED_TRIAGED row rather than letting it "
                + "resurface as \"examined\"")
        void submissionFailedIsLatest_shadowsAnOlderTriage()
                throws InterruptedException {
            LocalDate date = LocalDate.now().plusDays(2);
            dispositionService.persist(9211L, List.of(
                    new CandidateDisposition(50L, "Submission Failed Tonight Loc", date,
                            TargetType.SUNRISE, 2, DispositionCategory.SKIPPED_TRIAGED,
                            "Heavy cloud")));
            Thread.sleep(50);
            // Tonight's cycle re-included this slot — its own fresh-weather triage PASSED it,
            // which is why it reached submission at all — but the bucket carrying it failed to
            // reach Anthropic. This is a real, newer, slot-specific fact (tonight's triage
            // disagreed with last night's), unlike a region-level SKIPPED_CACHED reuse, so it must
            // win "latest" rather than let the older SKIPPED_TRIAGED row stand.
            dispositionService.persist(9212L, List.of(
                    new CandidateDisposition(50L, "Submission Failed Tonight Loc", date,
                            TargetType.SUNRISE, 2, DispositionCategory.SUBMISSION_FAILED,
                            "near-term inland batch submission failed")));

            List<Object[]> rows = dispositionRepository
                    .findLatestNonCachedDispositions(date, date);

            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst()[0]).isEqualTo("Submission Failed Tonight Loc");
            assertThat(rows.getFirst()[3]).isEqualTo("SUBMISSION_FAILED");
        }

        @Test
        @DisplayName("a SUBMISSION_FAILED-only slot (its own fresh triage passed it, but the "
                + "bucket failed) DOES appear, named SUBMISSION_FAILED, unlike a SKIPPED_CACHED-"
                + "only slot which never appears at all")
        void submissionFailedOnlySlot_appearsAsSubmissionFailed() {
            LocalDate date = LocalDate.now().plusDays(2);
            dispositionService.persist(9213L, List.of(
                    new CandidateDisposition(51L, "Submission Failed Only Loc", date,
                            TargetType.SUNRISE, 2, DispositionCategory.SUBMISSION_FAILED,
                            "near-term inland batch submission failed")));

            List<Object[]> rows = dispositionRepository
                    .findLatestNonCachedDispositions(date, date);

            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst()[0]).isEqualTo("Submission Failed Only Loc");
            assertThat(rows.getFirst()[3]).isEqualTo("SUBMISSION_FAILED");
        }

        @Test
        @DisplayName("a same-instant tie between two different non-cached categories for one "
                + "slot: the query CAN return both rows, and EvaluationViewService folds that to "
                + "NOT examined")
        void tieBetweenTwoNonCachedCategories_queryMayReturnBoth_serviceFoldsToNotExamined() {
            // One persist() call is one transaction, and Postgres' CURRENT_TIMESTAMP is the
            // transaction START time —
            // constant across every row it writes — so these two rows for the same slot share an
            // identical created_at and both satisfy the correlated MAX(created_at) predicate.
            LocalDate date = LocalDate.now().plusDays(2);
            dispositionService.persist(9208L, List.of(
                    new CandidateDisposition(44L, "Tied NonCached Loc", date, TargetType.SUNRISE,
                            2, DispositionCategory.SKIPPED_TRIAGED, "Heavy cloud"),
                    new CandidateDisposition(44L, "Tied NonCached Loc", date, TargetType.SUNRISE,
                            2, DispositionCategory.SKIPPED_STABILITY, "T+2 UNSETTLED")));

            List<Object[]> rows = dispositionRepository
                    .findLatestNonCachedDispositions(date, date);

            assertThat(rows).hasSize(2);
            assertThat(rows).extracting(r -> r[0]).containsOnly("Tied NonCached Loc");
            assertThat(rows).extracting(r -> (String) r[3])
                    .containsExactlyInAnyOrder("SKIPPED_TRIAGED", "SKIPPED_STABILITY");

            // The safety net is in Java: EvaluationViewService.loadTriagedByBatch only includes a
            // key when EVERY row seen for it is SKIPPED_TRIAGED, so a tie against any other
            // category folds to NOT examined regardless of row order.
            java.util.Set<String> triagedByBatch =
                    evaluationViewService.loadTriagedByBatch(date, date);

            assertThat(triagedByBatch).doesNotContain("Tied NonCached Loc|" + date + "|SUNRISE");
        }

        @Test
        @DisplayName("date-range bounds are inclusive at both ends; a slot outside the range is absent")
        void respectsInclusiveDateRange() {
            LocalDate startBoundary = LocalDate.now().plusDays(1);
            LocalDate endBoundary = LocalDate.now().plusDays(3);
            LocalDate justOutside = LocalDate.now().plusDays(4);
            dispositionService.persist(9209L, List.of(
                    new CandidateDisposition(45L, "Start Boundary NonCached Loc", startBoundary,
                            TargetType.SUNRISE, 1, DispositionCategory.SKIPPED_TRIAGED,
                            "Heavy cloud"),
                    new CandidateDisposition(46L, "End Boundary NonCached Loc", endBoundary,
                            TargetType.SUNRISE, 3, DispositionCategory.SKIPPED_STABILITY,
                            "T+3 UNSETTLED"),
                    new CandidateDisposition(47L, "Just Outside NonCached Loc", justOutside,
                            TargetType.SUNRISE, 4, DispositionCategory.SKIPPED_TRIAGED,
                            "Heavy cloud")));

            List<Object[]> rows = dispositionRepository
                    .findLatestNonCachedDispositions(startBoundary, endBoundary);

            assertThat(rows).extracting(r -> r[0])
                    .containsExactlyInAnyOrder(
                            "Start Boundary NonCached Loc", "End Boundary NonCached Loc");
        }

        @Test
        @DisplayName("two different locations, and the same location on two dates and two event "
                + "types, do not bleed into each other")
        void distinctSlotsDoNotBleedTogether() {
            LocalDate dateOne = LocalDate.now().plusDays(2);
            LocalDate dateTwo = LocalDate.now().plusDays(3);
            dispositionService.persist(9210L, List.of(
                    // Two different locations, same date and event.
                    new CandidateDisposition(48L, "NonCached Location A", dateOne,
                            TargetType.SUNRISE, 2, DispositionCategory.SKIPPED_TRIAGED,
                            "Heavy cloud"),
                    new CandidateDisposition(49L, "NonCached Location B", dateOne,
                            TargetType.SUNRISE, 2, DispositionCategory.SKIPPED_STABILITY,
                            "T+2 UNSETTLED"),
                    // The SAME location as "NonCached Location A", but a different date...
                    new CandidateDisposition(48L, "NonCached Location A", dateTwo,
                            TargetType.SUNRISE, 3, DispositionCategory.SKIPPED_STABILITY,
                            "T+3 UNSETTLED"),
                    // ...and the same location, same first date, but the OTHER event type.
                    new CandidateDisposition(48L, "NonCached Location A", dateOne,
                            TargetType.SUNSET, 2, DispositionCategory.SKIPPED_STABILITY,
                            "T+2 UNSETTLED")));

            List<Object[]> rows = dispositionRepository
                    .findLatestNonCachedDispositions(dateOne, dateTwo);

            assertThat(rows).hasSize(4);
            assertThat(rowFor(rows, "NonCached Location A", dateOne, "SUNRISE"))
                    .isEqualTo("SKIPPED_TRIAGED");
            assertThat(rowFor(rows, "NonCached Location B", dateOne, "SUNRISE"))
                    .isEqualTo("SKIPPED_STABILITY");
            assertThat(rowFor(rows, "NonCached Location A", dateTwo, "SUNRISE"))
                    .isEqualTo("SKIPPED_STABILITY");
            assertThat(rowFor(rows, "NonCached Location A", dateOne, "SUNSET"))
                    .isEqualTo("SKIPPED_STABILITY");
        }

        private String rowFor(List<Object[]> rows, String locationName, LocalDate date,
                String eventType) {
            return rows.stream()
                    .filter(r -> locationName.equals(r[0]) && date.equals(r[1])
                            && eventType.equals(r[2]))
                    .map(r -> (String) r[3])
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "No row for " + locationName + "|" + date + "|" + eventType));
        }
    }

    @Test
    @DisplayName("Forward-compat: null jobRunId + non-empty dispositions logs WARN, "
            + "writes zero rows (the V101 silent failure now screams)")
    void persist_nullJobRunIdWithDispositions_writesNothingButLogsWarning() {
        // This is the exact scenario the V101 bug produced every cycle. The fix
        // upstream (jobRunId now propagates from BatchSubmitResult) means this
        // should never happen in practice, but the WARN-on-null-with-dispositions
        // guard means if it does, it surfaces in logs instead of silently
        // returning zero rows. We assert the no-op contract directly: nothing
        // written, no exception thrown.
        List<CandidateDisposition> dispositions = List.of(
                new CandidateDisposition(42L, "Loc", LocalDate.now(),
                        TargetType.SUNRISE, 0, DispositionCategory.EVALUATED, null));

        dispositionService.persist(null, dispositions);

        assertThat(dispositionRepository.count())
                .as("No rows should be written when jobRunId is null")
                .isZero();
    }

    private LocationEntity seedLocation(String name, double lat, double lon) {
        RegionEntity region = regionRepository.save(RegionEntity.builder()
                .name("Test Region " + name)
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .build());
        return locationRepository.save(LocationEntity.builder()
                .name(name)
                .lat(lat)
                .lon(lon)
                .region(region)
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .build());
    }

    private EvaluationTask.Forecast buildTask(LocationEntity location, LocalDate date) {
        return new EvaluationTask.Forecast(
                location, date, TargetType.SUNRISE,
                EvaluationModel.HAIKU,
                TestAtmosphericData.builder()
                        .locationName(location.getName())
                        .solarEventTime(date.atTime(5, 30))
                        .targetType(TargetType.SUNRISE)
                        .build(),
                EvaluationTask.Forecast.WriteTarget.BRIEFING_CACHE);
    }
}
