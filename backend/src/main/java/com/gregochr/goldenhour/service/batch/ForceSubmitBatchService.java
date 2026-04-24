package com.gregochr.goldenhour.service.batch;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.batches.BatchCreateParams;
import com.anthropic.models.messages.batches.MessageBatch;
import com.anthropic.models.messages.batches.MessageBatchIndividualResponse;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.ForecastBatchEntity.BatchType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.ForecastPreEvalResult;
import com.gregochr.goldenhour.repository.RegionRepository;
import com.gregochr.goldenhour.service.ForecastService;
import com.gregochr.goldenhour.service.LocationService;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.goldenhour.service.evaluation.BatchRequestFactory;
import com.gregochr.goldenhour.service.evaluation.CustomIdFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Submits a force-test batch to the Anthropic Batch API, bypassing all gates
 * (triage, stability, cache). Used for diagnostics to prove the SDK wiring works.
 */
@Service
public class ForceSubmitBatchService {

    private static final Logger LOG = LoggerFactory.getLogger(ForceSubmitBatchService.class);

    private final AnthropicClient anthropicClient;
    private final RegionRepository regionRepository;
    private final LocationService locationService;
    private final ForecastService forecastService;
    private final ModelSelectionService modelSelectionService;
    private final BatchRequestFactory batchRequestFactory;
    private final BatchSubmissionService batchSubmissionService;

    /** Max tokens used historically by force/JFDI paths — preserved verbatim. */
    private static final int FORCE_JFDI_MAX_TOKENS = 512;

    /**
     * Constructs the force-submit batch service.
     *
     * @param anthropicClient         raw Anthropic SDK client (still used by {@link #getResult}
     *                                which bypasses {@code BatchPollingService} — see Pass 2.5)
     * @param regionRepository        repository for looking up regions by ID
     * @param locationService         service for retrieving enabled locations
     * @param forecastService         service for weather fetch and data assembly
     * @param modelSelectionService   resolves the active Claude model
     * @param batchRequestFactory     builds forecast batch requests (builder selection + cache control)
     * @param batchSubmissionService  unified Anthropic batch submitter
     */
    public ForceSubmitBatchService(AnthropicClient anthropicClient,
            RegionRepository regionRepository,
            LocationService locationService,
            ForecastService forecastService,
            ModelSelectionService modelSelectionService,
            BatchRequestFactory batchRequestFactory,
            BatchSubmissionService batchSubmissionService) {
        this.anthropicClient = anthropicClient;
        this.regionRepository = regionRepository;
        this.locationService = locationService;
        this.forecastService = forecastService;
        this.modelSelectionService = modelSelectionService;
        this.batchRequestFactory = batchRequestFactory;
        this.batchSubmissionService = batchSubmissionService;
    }

    /**
     * Submits a JFDI batch for locations in the given regions, bypassing all triage gates.
     * Evaluates all dates T+0 to T+3 and both SUNRISE and SUNSET for every location.
     *
     * @param regionIds region IDs to include — null or empty means all regions
     * @return submission result, or null if no requests were built
     */
    public BatchSubmitResult submitJfdiBatch(
            List<Long> regionIds) {
        java.util.Set<Long> regionFilter = (regionIds != null && !regionIds.isEmpty())
                ? new java.util.HashSet<>(regionIds) : null;

        List<LocationEntity> locations = locationService.findAllEnabled().stream()
                .filter(loc -> loc.getRegion() != null)
                .filter(loc -> regionFilter == null
                        || regionFilter.contains(loc.getRegion().getId()))
                .filter(loc -> {
                    var types = loc.getLocationType();
                    if (types == null || types.isEmpty()) {
                        return true;
                    }
                    return types.contains(
                            com.gregochr.goldenhour.entity.LocationType.LANDSCAPE)
                            || types.contains(
                            com.gregochr.goldenhour.entity.LocationType.SEASCAPE)
                            || types.contains(
                            com.gregochr.goldenhour.entity.LocationType.WATERFALL);
                })
                .toList();

        if (locations.isEmpty()) {
            LOG.warn("[JFDI BATCH] No eligible locations found");
            return null;
        }

        EvaluationModel model = modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM);
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        List<LocalDate> dates = List.of(today, today.plusDays(1),
                today.plusDays(2), today.plusDays(3));
        TargetType[] events = {TargetType.SUNRISE, TargetType.SUNSET};

        List<BatchCreateParams.Request> requests = new ArrayList<>();
        int failedCount = 0;

        for (LocationEntity location : locations) {
            for (LocalDate date : dates) {
                for (TargetType event : events) {
                    try {
                        ForecastPreEvalResult preEval = forecastService.fetchWeatherAndTriage(
                                location, date, event, location.getTideType(),
                                model, false, null);

                        AtmosphericData data = preEval.atmosphericData();
                        if (data == null) {
                            failedCount++;
                            continue;
                        }

                        String customId = CustomIdFactory.forJfdi(
                                location.getId(), date, event);
                        BatchCreateParams.Request request = batchRequestFactory.buildForecastRequest(
                                customId, model, data, FORCE_JFDI_MAX_TOKENS);

                        requests.add(request);
                    } catch (Exception e) {
                        LOG.warn("[JFDI BATCH] Failed data assembly for {} {} {}: {}",
                                location.getName(), date, event, e.getMessage());
                        failedCount++;
                    }
                }
            }
        }

        if (requests.isEmpty()) {
            LOG.warn("[JFDI BATCH] No requests built (all {} failed)", failedCount);
            return null;
        }

        LOG.info("[JFDI BATCH] Submitting {} requests ({} locations x {} dates x 2 events, "
                        + "{} failed)",
                requests.size(), locations.size(), dates.size(), failedCount);

        return batchSubmissionService.submit(requests, BatchType.FORECAST,
                BatchTriggerSource.JFDI, "[JFDI BATCH]");
    }

    /**
     * Submits a force-test batch for all locations in a region, bypassing all gates.
     *
     * @param regionId the database ID of the target region
     * @param date     the forecast date
     * @param event    SUNRISE or SUNSET
     * @return submission result summary
     */
    public ForceSubmitResult forceSubmit(Long regionId, LocalDate date, TargetType event) {
        RegionEntity region = regionRepository.findById(regionId).orElse(null);
        if (region == null) {
            throw new IllegalArgumentException("Region not found: " + regionId);
        }

        List<LocationEntity> locations = locationService.findAllEnabled().stream()
                .filter(loc -> loc.getRegion() != null
                        && loc.getRegion().getId().equals(regionId))
                .toList();

        if (locations.isEmpty()) {
            throw new IllegalArgumentException(
                    "No enabled locations found in region: " + region.getName());
        }

        EvaluationModel model = modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM);

        List<BatchCreateParams.Request> requests = new ArrayList<>();
        List<String> failedLocations = new ArrayList<>();

        for (LocationEntity location : locations) {
            try {
                ForecastPreEvalResult preEval = forecastService.fetchWeatherAndTriage(
                        location, date, event, location.getTideType(),
                        model, false, null);

                AtmosphericData data = preEval.atmosphericData();
                if (data == null) {
                    LOG.warn("[FORCE BATCH] {} — null atmospheric data", location.getName());
                    failedLocations.add(location.getName());
                    continue;
                }

                String customId = CustomIdFactory.forForceSubmit(
                        region.getName(), location.getId(), date, event);
                BatchCreateParams.Request request = batchRequestFactory.buildForecastRequest(
                        customId, model, data, FORCE_JFDI_MAX_TOKENS);

                requests.add(request);
                LOG.info("[FORCE BATCH] INCLUDE {} | date={} event={}",
                        location.getName(), date, event);

            } catch (Exception e) {
                LOG.warn("[FORCE BATCH] FAILED data assembly for {}: {}",
                        location.getName(), e.getMessage());
                failedLocations.add(location.getName());
            }
        }

        if (requests.isEmpty()) {
            return new ForceSubmitResult(null, 0, null,
                    locations.size(), 0, locations.size(), failedLocations);
        }

        BatchSubmitResult submission = batchSubmissionService.submit(requests,
                BatchType.FORECAST, BatchTriggerSource.FORCE,
                "[FORCE BATCH] region=" + region.getName());
        if (submission == null) {
            return new ForceSubmitResult(null, 0, "failed",
                    locations.size(), requests.size(),
                    failedLocations.size(), failedLocations);
        }

        return new ForceSubmitResult(submission.batchId(), submission.requestCount(),
                "in_progress", locations.size(), submission.requestCount(),
                failedLocations.size(), failedLocations);
    }

    /**
     * Retrieves the status and results of a force-submitted batch.
     *
     * @param batchId the Anthropic batch ID
     * @return current status and any available results
     */
    public ForceResultResponse getResult(String batchId) {
        MessageBatch status = anthropicClient.messages().batches().retrieve(batchId);
        MessageBatch.ProcessingStatus processingStatus = status.processingStatus();

        if (!processingStatus.equals(MessageBatch.ProcessingStatus.ENDED)) {
            return new ForceResultResponse(batchId,
                    processingStatus.toString().toLowerCase(),
                    (int) status.requestCounts().processing(),
                    (int) status.requestCounts().succeeded(),
                    (int) status.requestCounts().errored(),
                    0, null, 0);
        }

        // Batch ended — stream results
        List<ForceResultEntry> results = new ArrayList<>();
        int succeeded = 0;
        int errored = 0;
        int totalResults = 0;

        try (var streamResp = anthropicClient.messages().batches()
                .resultsStreaming(batchId)) {
            for (MessageBatchIndividualResponse response :
                    (Iterable<MessageBatchIndividualResponse>)
                            streamResp.stream()::iterator) {
                totalResults++;

                String customId = response.customId();
                boolean ok = response.result().isSucceeded();

                if (ok) {
                    succeeded++;
                    String text = extractText(response);
                    String preview = text != null && text.length() > 500
                            ? text.substring(0, 500) : text;
                    if (results.size() < 5) {
                        results.add(new ForceResultEntry(customId, "succeeded", preview));
                    }
                } else {
                    errored++;
                    if (results.size() < 5) {
                        results.add(new ForceResultEntry(customId, "errored", null));
                    }
                }
            }
        }

        return new ForceResultResponse(batchId, "ended", 0,
                succeeded, errored, 0, results, totalResults);
    }

    private String extractText(MessageBatchIndividualResponse response) {
        return response.result().succeeded()
                .map(s -> s.message().content().stream()
                        .filter(ContentBlock::isText)
                        .map(ContentBlock::asText)
                        .map(TextBlock::text)
                        .findFirst()
                        .orElse(null))
                .orElse(null);
    }

    /**
     * Result of a force-submit operation.
     *
     * @param batchId             the Anthropic batch ID (null if no requests were built)
     * @param requestCount        number of requests submitted
     * @param status              batch status string
     * @param locationsAttempted  total locations in the region
     * @param locationsIncluded   locations that successfully built requests
     * @param locationsFailedData locations where data assembly failed
     * @param failedLocations     names of locations that failed
     */
    public record ForceSubmitResult(
            String batchId,
            int requestCount,
            String status,
            int locationsAttempted,
            int locationsIncluded,
            int locationsFailedData,
            List<String> failedLocations) {}

    /**
     * Response for batch result retrieval.
     *
     * @param batchId      the Anthropic batch ID
     * @param status       current processing status
     * @param processing   number of requests still processing
     * @param succeeded    number of succeeded requests
     * @param errored      number of errored requests
     * @param cancelled    number of cancelled requests
     * @param results      first 5 result entries (null if not ended)
     * @param totalResults total number of results (0 if not ended)
     */
    public record ForceResultResponse(
            String batchId,
            String status,
            int processing,
            int succeeded,
            int errored,
            int cancelled,
            List<ForceResultEntry> results,
            int totalResults) {}

    /**
     * A single result entry in the force-result response.
     *
     * @param customId        the custom ID from the batch request
     * @param status          succeeded or errored
     * @param responsePreview first 500 chars of the Claude response (null if errored)
     */
    public record ForceResultEntry(
            String customId,
            String status,
            String responsePreview) {}
}
