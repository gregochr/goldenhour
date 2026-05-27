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
import com.gregochr.goldenhour.service.batch.BatchTriggerSource;
import com.gregochr.goldenhour.service.evaluation.EvaluationHandle;
import com.gregochr.goldenhour.service.evaluation.EvaluationService;
import com.gregochr.goldenhour.service.evaluation.EvaluationTask;
import com.gregochr.goldenhour.service.batch.ForecastDispositionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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
