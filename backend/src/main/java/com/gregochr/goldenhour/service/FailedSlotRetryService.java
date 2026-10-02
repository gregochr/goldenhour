package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.JobRunEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.LocationTaskSnapshot;
import com.gregochr.goldenhour.model.RunProgress;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * "Retry failed": starts a new hand-started run that re-evaluates exactly the slots a finished run
 * reported as FAILED, under the original run's type.
 *
 * <p>What it does, and what it deliberately does not:
 * <ul>
 *   <li><b>Exactly the failed slots.</b> The slots are the (location, date, sunrise/sunset) triples
 *       that are FAILED in the original run's tracker entry, handed to the executor as an explicit
 *       list ({@link ForecastCommand#slots()}). It does not re-run the failed places over the failed
 *       dates over both events, which re-evaluated (and re-billed) slots that had succeeded.</li>
 *   <li><b>The original run type.</b> The run type is read from the original run's {@code job_run}
 *       row, and the new run is started under it, so the model and the optimisation strategies are
 *       those of that run type and not always Short-Term's. ⚠️ <b>"Original settings" can only mean
 *       the <em>current</em> configuration of the original run type</b>: a hand-started run stores no
 *       {@code active_strategies} snapshot and a null {@code evaluation_model}, so what it ran under
 *       is not recoverable, and a model or strategy changed on the Run Config screen since is picked
 *       up by the retry. Sentinel sampling is not applied to the retry (see
 *       {@link ForecastCommandExecutor}).</li>
 *   <li><b>Never a slot dated before today.</b> The executor's already-past gate guards only today's slots,
 *       so a slot dated before today's UK civil date ({@link ForecastHorizon}) is left out here and listed
 *       in {@code skipped}; when every slot is past there is nothing to retry.</li>
 *   <li><b>Never a slot dated before today.</b> The executor's already-past gate guards only today's slots,
 *       so a slot dated before today's UK civil date ({@link ForecastHorizon}) is left out here and listed
 *       in {@code skipped}; when every slot is past there is nothing to retry.</li>
 *   <li><b>Only a finished run, and only once.</b> A run that is still going is refused (its failures
 *       are not final, and the original run could still finish the very slots a retry would re-run),
 *       and so is a run a retry has already been started from, with the retry's id in the sentence: a
 *       run is retried at most once and further retries chain from the retry run. The "already
 *       retried" fact is held on the run's {@link RunProgress} entry and recorded under
 *       {@link RunProgress#retryLock()} together with the decision to start, so two concurrent
 *       requests (a double press, two admins) cannot both start one. It lives and dies with the
 *       tracker's entry: it does not survive a restart, and is forgotten when the entry is evicted
 *       (after which the run answers 404 anyway).</li>
 *   <li><b>Never a rejected key, never a light-pollution run.</b> {@link RunProgress#getRetryBlock()}
 *       is the one answer the {@code run-complete} payload also carries, so the panel never offers a
 *       retry the server would refuse.</li>
 * </ul>
 *
 * <p>The run is started on the HTTP thread (the job run exists before the 202 is returned) and
 * executed on {@code forecastExecutor}. The executor's own guard completes the run toward the admin
 * whatever happens, and the future is not observed here, as at every other hand-started run.
 */
@Service
public class FailedSlotRetryService {

    private static final Logger LOG = LoggerFactory.getLogger(FailedSlotRetryService.class);

    /** Why a run stopped on a rejected key is not retried. */
    public static final String REFUSED_KEY_REJECTED =
            "This run was stopped because Claude rejected the API key. Fix the key, then start the run again.";

    /** Why a light-pollution run is not retried here. */
    public static final String REFUSED_LIGHT_POLLUTION =
            "Light-pollution failures are retried by pressing Refresh Light Pollution again.";

    /** Why a run whose failed tasks are not forecast slots is not retried. */
    public static final String REFUSED_NOT_FORECAST_SLOTS =
            "This run's failed tasks are not forecast slots, so there is nothing to retry here.";

    /** Why a run that has not finished is not retried. */
    public static final String REFUSED_STILL_RUNNING =
            "This run is still going. Retry is offered when it has finished.";

    /** Why a run of a type that does not evaluate forecast slots is not retried. */
    public static final String REFUSED_RUN_TYPE = "This kind of run cannot be retried here.";

    /** Why a failed slot was left out of the retry: its place is not an enabled place any more. */
    public static final String SKIPPED_NOT_ENABLED =
            "The place is disabled, renamed or no longer exists.";

    /** Why a failed slot was left out of the retry: its date is before today, so its event is over. */
    public static final String SKIPPED_PAST = "The event has already happened.";

    /** Why a failed slot was left out of the retry: its place is no longer a sky subject. */
    public static final String SKIPPED_NOT_SKY = "The place is no longer a sky location.";

    /** Why a failed slot was left out of the retry: its task does not name a date and event. */
    public static final String SKIPPED_MALFORMED = "The failed task does not name a date and event.";

    /** The run types whose slots a retry can re-evaluate (the colour pipeline's). */
    private static final Set<RunType> RETRYABLE_RUN_TYPES =
            Set.of(RunType.VERY_SHORT_TERM, RunType.SHORT_TERM, RunType.LONG_TERM);

    private final RunProgressTracker progressTracker;
    private final JobRunService jobRunService;
    private final LocationService locationService;
    private final ForecastCommandFactory commandFactory;
    private final ForecastCommandExecutor commandExecutor;
    private final Executor forecastExecutor;
    private final Clock clock;

    /**
     * Constructs the service.
     *
     * @param progressTracker  holds the original run's per-task outcomes
     * @param jobRunService    reads the original run's type and starts the retry's {@code job_run}
     * @param locationService  resolves the failed places against the enabled roster
     * @param commandFactory   builds the retry's command under the original run type
     * @param commandExecutor  executes it
     * @param forecastExecutor runs it off the HTTP thread
     * @param clock            supplies today's UK civil date, via {@link ForecastHorizon}, so a slot dated
     *                         before it is left out (the executor's own already-past gate guards only today)
     */
    public FailedSlotRetryService(RunProgressTracker progressTracker, JobRunService jobRunService,
            LocationService locationService, ForecastCommandFactory commandFactory,
            ForecastCommandExecutor commandExecutor, Executor forecastExecutor,
            Clock clock) {
        this.progressTracker = progressTracker;
        this.jobRunService = jobRunService;
        this.locationService = locationService;
        this.commandFactory = commandFactory;
        this.commandExecutor = commandExecutor;
        this.forecastExecutor = forecastExecutor;
        this.clock = clock;
    }

    /**
     * A failed slot that was left out of the retry, and why.
     *
     * @param locationName the place
     * @param date         the failed task's date as the tracker held it
     * @param targetType   the failed task's event as the tracker held it
     * @param reason       a fixed sentence
     */
    public record SkippedSlot(String locationName, String date, String targetType, String reason) {
    }

    /** What a retry request came to. */
    public sealed interface Outcome permits Started, NothingToRetry, Refused {
    }

    /**
     * A retry run was started.
     *
     * @param jobRunId the new run's id
     * @param runType  the run type it was started under (the original's)
     * @param slots    how many slots it was given
     * @param skipped  the failed slots left out, with reasons
     */
    public record Started(long jobRunId, RunType runType, int slots, List<SkippedSlot> skipped)
            implements Outcome {
    }

    /** There is nothing this run can retry: unknown or evicted, no failures, or no failed slot left to run. */
    public record NothingToRetry() implements Outcome {
    }

    /**
     * The run's failures cannot be retried, and why.
     *
     * @param message a fixed sentence for the admin
     */
    public record Refused(String message) implements Outcome {
    }

    /**
     * Retries the failed slots of a finished run.
     *
     * @param runId the original run's job run id
     * @return what happened; nothing is started unless it is {@link Started}
     */
    public Outcome retry(long runId) {
        RunProgress progress = progressTracker.getProgress(runId);
        if (progress == null) {
            return new NothingToRetry();
        }
        if (!progressTracker.isComplete(runId)) {
            LOG.info("Retry of run {} refused: the run has not finished", runId);
            return new Refused(REFUSED_STILL_RUNNING);
        }
        if (progress.getFailedTasks().isEmpty()) {
            return new NothingToRetry();
        }
        // The check, the start and the record are one step: a second request waits here and then sees
        // the first one's retry. Only a retry that STARTED is recorded, so a request that came to
        // nothing (every slot past, a refusal, an error) leaves the run retryable.
        synchronized (progress.retryLock()) {
            Long alreadyRetriedAs = progress.getRetriedAs();
            if (alreadyRetriedAs != null) {
                LOG.info("Retry of run {} refused: already retried as run {}", runId, alreadyRetriedAs);
                return new Refused("This run has already been retried as run " + alreadyRetriedAs
                        + ". Retry that run's failures instead.");
            }
            Outcome outcome = startRetry(runId, progress);
            if (outcome instanceof Started started) {
                progress.recordRetry(started.jobRunId());
            }
            return outcome;
        }
    }

    /** Works out the failed slots of a finished, not-yet-retried run and starts the retry (under the retry lock). */
    private Outcome startRetry(long runId, RunProgress progress) {
        RunProgress.RetryBlock block = progress.getRetryBlock();
        if (block != null) {
            LOG.info("Retry of run {} refused: {}", runId, block);
            return new Refused(refusalFor(block));
        }
        Optional<JobRunEntity> original = jobRunService.findRun(runId);
        if (original.isEmpty()) {
            // Without the row there is no run type to retry under, and guessing one is the defect this
            // service exists to remove.
            LOG.info("Retry of run {}: its job_run row is gone, so its run type is unknown", runId);
            return new NothingToRetry();
        }
        RunType runType = original.get().getRunType();
        if (!RETRYABLE_RUN_TYPES.contains(runType)) {
            LOG.info("Retry of run {} refused: run type {} evaluates no forecast slots", runId, runType);
            return new Refused(REFUSED_RUN_TYPE);
        }

        Map<String, LocationEntity> enabledByName = locationService.findAllEnabled().stream()
                .collect(Collectors.toMap(LocationEntity::getName, Function.identity(), (a, b) -> a));
        LocalDate today = ForecastHorizon.today(clock);
        Set<ForecastSlot> slots = new LinkedHashSet<>();
        Set<String> placeNames = new LinkedHashSet<>();
        List<SkippedSlot> skipped = new ArrayList<>();
        for (LocationTaskSnapshot task : progress.getFailedTasks()) {
            ForecastSlot slot = parse(task);
            LocationEntity place = enabledByName.get(task.locationName());
            String why = slot == null ? SKIPPED_MALFORMED
                    : slot.date().isBefore(today) ? SKIPPED_PAST
                    : place == null ? SKIPPED_NOT_ENABLED
                    : !place.hasColourTypes() ? SKIPPED_NOT_SKY
                    : null;
            if (why != null) {
                skipped.add(new SkippedSlot(task.locationName(), task.targetDate(), task.targetType(), why));
            } else if (slots.add(slot)) {
                placeNames.add(place.getName());
            }
        }
        if (slots.isEmpty()) {
            LOG.info("Retry of run {}: none of the {} failed slot(s) can be run again", runId, skipped.size());
            return new NothingToRetry();
        }

        List<LocationEntity> places = placeNames.stream().map(enabledByName::get).toList();
        ForecastCommand command = commandFactory.createForSlots(runType, true, places, slots);
        JobRunEntity jobRun = jobRunService.startRun(runType, true, null, null);
        CompletableFuture.runAsync(() -> commandExecutor.execute(command, jobRun), forecastExecutor);
        LOG.info("Retry of run {} started as run {} under {}: {} slot(s), {} left out",
                runId, jobRun.getId(), runType, slots.size(), skipped.size());
        return new Started(jobRun.getId(), runType, slots.size(), List.copyOf(skipped));
    }

    /** The sentence that says why a run is not retried. */
    private static String refusalFor(RunProgress.RetryBlock block) {
        return switch (block) {
            case API_KEY_REJECTED -> REFUSED_KEY_REJECTED;
            case LIGHT_POLLUTION -> REFUSED_LIGHT_POLLUTION;
            case NOT_FORECAST_SLOTS -> REFUSED_NOT_FORECAST_SLOTS;
        };
    }

    /** The slot a failed task names, or {@code null} when it names no date or no sunrise/sunset event. */
    private static ForecastSlot parse(LocationTaskSnapshot task) {
        if (task.targetDate() == null || task.targetType() == null) {
            return null;
        }
        try {
            return new ForecastSlot(task.locationName(), LocalDate.parse(task.targetDate()),
                    TargetType.valueOf(task.targetType()));
        } catch (DateTimeParseException | IllegalArgumentException e) {
            return null;
        }
    }
}
