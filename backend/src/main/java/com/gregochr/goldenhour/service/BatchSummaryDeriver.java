package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.ApiCallLogEntity;
import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BatchSummary;
import com.gregochr.goldenhour.repository.ApiCallLogRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.evaluation.CustomIdFactory;
import com.gregochr.goldenhour.service.evaluation.ParsedCustomId;
import com.gregochr.goldenhour.util.HorizonRangeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Derives the read-time {@link BatchSummary} shown next to batch job runs on the admin
 * metrics page. Inspects the {@code api_call_log} rows linked to a run by {@code jobRunId},
 * never writes back to the database.
 *
 * <p>The summary is empty for non-batch run types, and empty for batch runs that have no
 * associated API call rows (e.g. a batch that failed before any result was logged).
 */
@Service
public class BatchSummaryDeriver {

    private static final Logger LOG = LoggerFactory.getLogger(BatchSummaryDeriver.class);

    private static final Set<RunType> BATCH_RUN_TYPES = Set.of(
            RunType.SCHEDULED_BATCH, RunType.BATCH_NEAR_TERM, RunType.BATCH_FAR_TERM);

    private final ApiCallLogRepository apiCallLogRepository;
    private final LocationRepository locationRepository;

    /**
     * Constructs a {@code BatchSummaryDeriver}.
     *
     * @param apiCallLogRepository repository for API call log entities
     * @param locationRepository   repository for location entities (used for region lookup)
     */
    public BatchSummaryDeriver(ApiCallLogRepository apiCallLogRepository,
            LocationRepository locationRepository) {
        this.apiCallLogRepository = apiCallLogRepository;
        this.locationRepository = locationRepository;
    }

    /**
     * Derives the summary for the given job run, if it is a batch run with logged calls.
     *
     * @param jobRun the job run to summarise
     * @return the summary, or {@link Optional#empty()} for non-batch runs and batch runs
     *         with no usable API call rows
     */
    public Optional<BatchSummary> derive(JobRunEntity jobRun) {
        if (jobRun == null || jobRun.getRunType() == null) {
            return Optional.empty();
        }
        if (!BATCH_RUN_TYPES.contains(jobRun.getRunType())) {
            return Optional.empty();
        }

        List<ApiCallLogEntity> calls = apiCallLogRepository
                .findByJobRunIdOrderByCalledAtAsc(jobRun.getId()).stream()
                .filter(c -> c.getService() == ServiceName.ANTHROPIC)
                .filter(c -> c.getTargetDate() != null)
                .toList();

        if (calls.isEmpty()) {
            return Optional.empty();
        }

        LocalDate runStartDate = jobRun.getStartedAt() != null
                ? jobRun.getStartedAt().toLocalDate()
                : LocalDateTime.now().toLocalDate();

        TreeSet<Integer> daysAhead = calls.stream()
                .map(c -> (int) ChronoUnit.DAYS.between(runStartDate, c.getTargetDate()))
                .collect(Collectors.toCollection(TreeSet::new));
        String horizonRange = HorizonRangeFormatter.format(daysAhead);

        List<String> eventTypes = calls.stream()
                .map(ApiCallLogEntity::getTargetType)
                .filter(t -> t != null)
                .map(TargetType::name)
                .distinct()
                .sorted()
                .toList();

        EvaluationModel model = calls.stream()
                .map(ApiCallLogEntity::getEvaluationModel)
                .filter(m -> m != null)
                .findFirst()
                .orElse(null);
        String modelName = model != null ? model.name() : "UNKNOWN";
        Boolean extendedThinking = model != null ? model.isExtendedThinking() : null;

        int locationCount = (int) calls.stream()
                .map(ApiCallLogEntity::getCustomId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .count();

        int regionCount = computeRegionCount(calls);

        return Optional.of(new BatchSummary(
                horizonRange,
                eventTypes,
                modelName,
                locationCount,
                regionCount,
                extendedThinking));
    }

    private int computeRegionCount(List<ApiCallLogEntity> calls) {
        Set<Long> locationIds = new HashSet<>();
        for (ApiCallLogEntity call : calls) {
            String customId = call.getCustomId();
            if (customId == null || customId.isBlank()) {
                continue;
            }
            try {
                ParsedCustomId parsed = CustomIdFactory.parse(customId);
                Long locationId = extractLocationId(parsed);
                if (locationId != null) {
                    locationIds.add(locationId);
                }
            } catch (IllegalArgumentException e) {
                LOG.debug("Skipping unparseable customId for region count: {}", customId);
            }
        }
        if (locationIds.isEmpty()) {
            return 0;
        }
        List<LocationEntity> locations = locationRepository.findAllById(new ArrayList<>(locationIds));
        Set<Long> regionIds = locations.stream()
                .map(LocationEntity::getRegion)
                .filter(r -> r != null)
                .map(RegionEntity::getId)
                .collect(Collectors.toSet());
        return regionIds.size();
    }

    private static Long extractLocationId(ParsedCustomId parsed) {
        return switch (parsed) {
            case ParsedCustomId.Forecast f -> f.locationId();
            case ParsedCustomId.Bluebell b -> b.locationId();
            case ParsedCustomId.Jfdi j -> j.locationId();
            case ParsedCustomId.ForceSubmit fs -> fs.locationId();
            case ParsedCustomId.Aurora a -> null;
        };
    }

    /**
     * Returns an immutable snapshot of the run types this deriver considers.
     *
     * @return the set of batch run types
     */
    public static Set<RunType> batchRunTypes() {
        return Collections.unmodifiableSet(BATCH_RUN_TYPES);
    }
}
