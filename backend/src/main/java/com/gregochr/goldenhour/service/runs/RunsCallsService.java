package com.gregochr.goldenhour.service.runs;

import com.gregochr.goldenhour.entity.ForecastBatchEntity;
import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.PipelineRunEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.ServiceName;
import com.gregochr.goldenhour.model.ApiCallErrorGroup;
import com.gregochr.goldenhour.model.ApiCallGroup;
import com.gregochr.goldenhour.model.runs.RunCallsResponse;
import com.gregochr.goldenhour.model.runs.RunCallsResponse.CallService;
import com.gregochr.goldenhour.model.runs.RunCallsResponse.CallStatus;
import com.gregochr.goldenhour.model.runs.RunDeepType;
import com.gregochr.goldenhour.repository.ApiCallLogRepository;
import com.gregochr.goldenhour.repository.ForecastBatchRepository;
import com.gregochr.goldenhour.repository.JobRunRepository;
import com.gregochr.goldenhour.repository.PipelineRunRepository;
import com.gregochr.goldenhour.repository.SkyRatingEvalRunRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The external calls of one run, for the Day view's expanded row: the logged calls summed per service by SQL on the
 * indexed {@code job_run_id}, never by loading call rows.
 *
 * <p>A cycle's calls are those of its member job runs (its batch runs and disposition anchor); its chained briefing and
 * ask-ready runs have rows of their own and are not included. Anthropic batch calls (one logged row per request) are
 * served as "n batches, m requests" with the batches' wall time from the batch rows. A sky-rating eval logs no calls,
 * which is served as {@code logged: false}, not as an empty table.
 */
@Service
public class RunsCallsService {

    /** A run id the Runs views serve. */
    private static final Pattern RUN_ID = Pattern.compile("^(cycle|job|eval)-(\\d{1,18})$");

    private final JobRunRepository jobRuns;
    private final PipelineRunRepository pipelineRuns;
    private final SkyRatingEvalRunRepository evalRuns;
    private final ForecastBatchRepository batches;
    private final ApiCallLogRepository calls;
    private final CycleFactsLoader cycleFacts;
    private final RunFxLoader fxLoader;

    /**
     * Constructs the service.
     *
     * @param jobRuns      job run store
     * @param pipelineRuns pipeline run store
     * @param evalRuns     sky-rating eval run store
     * @param batches      Anthropic batch store
     * @param calls        API call log
     * @param cycleFacts   loads a cycle's batches and member job runs
     * @param fxLoader     reads stored exchange rates
     */
    RunsCallsService(JobRunRepository jobRuns, PipelineRunRepository pipelineRuns,
            SkyRatingEvalRunRepository evalRuns, ForecastBatchRepository batches, ApiCallLogRepository calls,
            CycleFactsLoader cycleFacts, RunFxLoader fxLoader) {
        this.jobRuns = jobRuns;
        this.pipelineRuns = pipelineRuns;
        this.evalRuns = evalRuns;
        this.batches = batches;
        this.calls = calls;
        this.cycleFacts = cycleFacts;
        this.fxLoader = fxLoader;
    }

    /**
     * The calls of one run.
     *
     * @param runId {@code cycle-N}, {@code job-N} or {@code eval-N}
     * @return the calls, or empty when no such run exists
     * @throws RunsRequestException if the id is not one of those shapes
     */
    public Optional<RunCallsResponse> calls(String runId) {
        Matcher m = runId == null ? null : RUN_ID.matcher(runId);
        if (m == null || !m.matches()) {
            throw new RunsRequestException("runId must be cycle-N, job-N or eval-N");
        }
        long id = Long.parseLong(m.group(2));
        return switch (m.group(1)) {
            case "cycle" -> pipelineRuns.findById(id).map(c -> forCycle(runId, c));
            case "job" -> jobRuns.findById(id).map(j -> forJob(runId, j));
            default -> evalRuns.existsById(id)
                    ? Optional.of(new RunCallsResponse(runId, false, List.of())) : Optional.empty();
        };
    }

    private RunCallsResponse forCycle(String runId, PipelineRunEntity cycle) {
        CycleFacts facts = cycleFacts.load(List.of(cycle));
        List<JobRunEntity> members = facts.members(cycle.getId());
        return build(runId, RunDeepType.ofCycle(cycle.getCycleType()), null, members,
                facts.batches(cycle.getId()), cycle.getTriggerTime());
    }

    private RunCallsResponse forJob(String runId, JobRunEntity job) {
        List<ForecastBatchEntity> own = batches.findByJobRunIdIn(List.of(job.getId()));
        return build(runId, RunDeepType.of(job.getRunType()), job.getRunType(), List.of(job), own,
                job.getStartedAt().toInstant(ZoneOffset.UTC));
    }

    private RunCallsResponse build(String runId, RunDeepType type, RunType runType, List<JobRunEntity> runs,
            List<ForecastBatchEntity> runBatches, Instant startedAt) {
        List<Long> ids = runs.stream().map(JobRunEntity::getId).toList();
        if (ids.isEmpty()) {
            return new RunCallsResponse(runId, true, List.of());
        }
        double rate = rateOf(runs, startedAt);
        Map<String, String> topError = new HashMap<>();
        for (ApiCallErrorGroup e : calls.groupErrorsByJobRuns(ids)) {
            topError.putIfAbsent(key(e.service(), e.batch()), e.message());
        }
        List<CallService> services = new ArrayList<>();
        for (ApiCallGroup g : calls.groupCallsByJobRuns(ids)) {
            services.add(callService(g, type, runType, runBatches, topError, rate));
        }
        services.sort(Comparator.comparing(CallService::label));
        return new RunCallsResponse(runId, true, services);
    }

    private CallService callService(ApiCallGroup g, RunDeepType type, RunType runType,
            List<ForecastBatchEntity> runBatches, Map<String, String> topError, double rate) {
        boolean batch = Boolean.TRUE.equals(g.batch());
        long count = g.calls() == null ? 0 : g.calls();
        long errors = g.errors() == null ? 0 : g.errors();
        long micro = g.costMicroDollars() == null ? 0 : g.costMicroDollars();
        Integer batchCount = null;
        Long requests = null;
        Long durationMs = g.durationMs();
        if (batch) {
            batchCount = runBatches.size();
            requests = count;
            durationMs = wallTimeMs(runBatches);
        }
        CallStatus status = errors == 0 ? CallStatus.OK : errors >= count ? CallStatus.FAILED : CallStatus.ERRORS;
        return new CallService(g.service().name(), label(g.service(), batch),
                purpose(g.service(), batch, type, runType), count, durationMs, batchCount, requests, micro,
                RunFx.pence(RunFx.gbp(micro, rate)), RunFx.dollars(micro), errors, status,
                errors == 0 ? null : topError.get(key(g.service(), g.batch())));
    }

    /** Earliest submission to latest end across the batches; null when none or any is still open. */
    private static Long wallTimeMs(List<ForecastBatchEntity> runBatches) {
        if (runBatches.isEmpty() || runBatches.stream().anyMatch(b -> b.getEndedAt() == null)) {
            return null;
        }
        Instant first = runBatches.stream().map(ForecastBatchEntity::getSubmittedAt).min(Comparator.naturalOrder())
                .orElseThrow();
        Instant last = runBatches.stream().map(ForecastBatchEntity::getEndedAt).max(Comparator.naturalOrder())
                .orElseThrow();
        return Duration.between(first, last).toMillis();
    }

    /** The latest member's own rate, else the stored rate for the run's day. */
    private double rateOf(List<JobRunEntity> runs, Instant startedAt) {
        return runs.stream().filter(r -> r.getExchangeRateGbpPerUsd() != null)
                .max(Comparator.comparing(JobRunEntity::getStartedAt))
                .map(JobRunEntity::getExchangeRateGbpPerUsd)
                .orElseGet(() -> fxLoader.load().rateOn(startedAt.atOffset(ZoneOffset.UTC).toLocalDate()));
    }

    private static String key(ServiceName service, Boolean batch) {
        return service.name() + ":" + Boolean.TRUE.equals(batch);
    }

    private static String label(ServiceName service, boolean batch) {
        return switch (service) {
            case OPEN_METEO_FORECAST -> "Open-Meteo Forecast";
            case OPEN_METEO_AIR_QUALITY -> "Open-Meteo Air quality";
            case OPEN_METEO_MARINE -> "Open-Meteo Marine";
            case WORLD_TIDES -> "WorldTides";
            case LIGHT_POLLUTION -> "Light pollution API";
            case ANTHROPIC -> batch ? "Anthropic Message Batches" : "Anthropic Messages";
        };
    }

    private static String purpose(ServiceName service, boolean batch, RunDeepType type, RunType runType) {
        return switch (service) {
            case OPEN_METEO_FORECAST -> "Forecast · cloud, visibility, wind";
            case OPEN_METEO_AIR_QUALITY -> "Air quality · aerosols";
            case OPEN_METEO_MARINE -> "Sea state · waves";
            case WORLD_TIDES -> "Tide times · coastal spots";
            case LIGHT_POLLUTION -> "Light pollution · Bortle class";
            case ANTHROPIC -> batch ? "Message Batches · sky scoring" : switch (type) {
                case ASK_READY -> "Messages · ready answers";
                case BRIEFING -> "Messages · briefing summary and picks";
                case OVERNIGHT, AFTERNOON, EVENING -> "Messages · sky scoring";
                case SKY_EVAL -> "Messages · eval scoring";
                case OTHER -> runType == RunType.AURORA_EVALUATION ? "Messages · aurora scoring" : "Messages";
            };
        };
    }
}
