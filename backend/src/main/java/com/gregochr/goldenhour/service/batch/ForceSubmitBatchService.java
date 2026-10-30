package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RunType;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.ForecastPreEvalResult;
import com.gregochr.goldenhour.service.ForecastService;
import com.gregochr.goldenhour.service.LocationService;
import com.gregochr.goldenhour.service.ModelSelectionService;
import com.gregochr.goldenhour.service.evaluation.EvaluationHandle;
import com.gregochr.goldenhour.service.evaluation.EvaluationService;
import com.gregochr.goldenhour.service.evaluation.EvaluationTask;
import com.gregochr.goldenhour.util.ForecastHorizon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Submits a JFDI batch to the Anthropic Batch API, bypassing all gates
 * (triage, stability, cache). The admin "force a batch now" route.
 *
 * <p><b>"Bypassing triage" means the triage <em>verdict</em> is ignored, not that triage does not
 * run.</b> The JFDI entry point calls {@code ForecastService.fetchWeatherAndTriage}, which evaluates
 * the heuristics itself and, when they fire, <em>persists a {@code forecast_evaluation} row</em>
 * before returning. It returns that row's atmospheric data too, so the null check below passes and
 * the slot is submitted to Claude regardless — which is the bypass. The consequence worth knowing:
 * a JFDI run writes rows, so anything wrong with the dates it chooses lands in the table. That is
 * how a UTC-anchored range here came to persist {@code days_ahead = -1} (and, via
 * {@code ConfidenceDeriver.fromHorizon}, a {@code confidence} of HIGH) for a day already over.
 *
 * <p>⚠️ <b>The entry point writes no {@code slot_atmosphere} reading itself.</b>
 * {@code fetchWeatherAndTriage} records the candidate's atmospheric readings as part of its own
 * body, before the triage check it runs — the one seam every caller of that method shares (see
 * its own javadoc) — so a JFDI slot is recorded exactly like a scheduled cycle's,
 * with no extra call needed here. This class briefly carried its own {@code SlotAtmosphereWriter}
 * dependency and explicit write calls (one per entry point); they were removed once the seam
 * moved inside {@code fetchWeatherAndTriage}, to avoid writing the same fetch twice.
 */
@Service
public class ForceSubmitBatchService {

    private static final Logger LOG = LoggerFactory.getLogger(ForceSubmitBatchService.class);

    private final LocationService locationService;
    private final ForecastService forecastService;
    private final ModelSelectionService modelSelectionService;
    private final EvaluationService evaluationService;
    private final Clock clock;

    /**
     * Constructs the JFDI batch service.
     *
     * @param locationService       service for retrieving enabled locations
     * @param forecastService       service for weather fetch and data assembly
     * @param modelSelectionService resolves the active Claude model
     * @param evaluationService     Pass 3.2 engine — submits forecast tasks via the
     *                              canonical batch path (request build + observability)
     * @param clock                 supplies "today" for the JFDI range, resolved in
     *                              {@code Europe/London} by {@link ForecastHorizon} — the same
     *                              calendar {@code ForecastService} measures the horizon on
     */
    public ForceSubmitBatchService(LocationService locationService,
            ForecastService forecastService,
            ModelSelectionService modelSelectionService,
            EvaluationService evaluationService,
            Clock clock) {
        this.locationService = locationService;
        this.forecastService = forecastService;
        this.modelSelectionService = modelSelectionService;
        this.evaluationService = evaluationService;
        this.clock = clock;
    }

    /**
     * Submits a JFDI batch for locations in the given regions, bypassing all triage gates.
     * Evaluates all dates T+0 to T+3 — on the <em>UK civil</em> calendar — and both SUNRISE and
     * SUNSET for every location.
     *
     * @param regionIds region IDs to include — null or empty means all regions
     * @return submission result, or null if no requests were built
     */
    public BatchSubmitResult submitJfdiBatch(
            List<Long> regionIds) {
        java.util.Set<Long> regionFilter = (regionIds != null && !regionIds.isEmpty())
                ? new java.util.HashSet<>(regionIds) : null;

        List<LocationEntity> locations = enabledSkyLocations(loc -> loc.getRegion() != null
                && (regionFilter == null || regionFilter.contains(loc.getRegion().getId())));

        if (locations.isEmpty()) {
            LOG.warn("[JFDI BATCH] No eligible locations found");
            return null;
        }

        EvaluationModel model = modelSelectionService.getActiveModel(RunType.BATCH_NEAR_TERM);
        // UK civil date, because ForecastService measures the horizon of each of these dates on
        // that calendar. On a UTC anchor the two disagreed for the hour *after* UK midnight in
        // summer (23:00–00:00 UTC = 00:00–01:00 BST), and this range is the side that was wrong:
        // it opened on a day already over,
        // which ForecastService then correctly labelled T-1.
        LocalDate today = ForecastHorizon.today(clock);
        List<LocalDate> dates = List.of(today, today.plusDays(1),
                today.plusDays(2), today.plusDays(3));
        TargetType[] events = {TargetType.SUNRISE, TargetType.SUNSET};

        List<EvaluationTask.Forecast> tasks = new ArrayList<>();
        int failedCount = 0;

        for (LocationEntity location : locations) {
            for (LocalDate date : dates) {
                for (TargetType event : events) {
                    try {
                        ForecastPreEvalResult preEval = forecastService.fetchWeatherAndTriage(
                                location, date, event, location.getTideType(),
                                model, null);

                        AtmosphericData data = preEval.atmosphericData();
                        if (data == null) {
                            failedCount++;
                            continue;
                        }

                        // R4: a JFDI submit of a previously-triaged slot gets a pending row
                        // too. fetchWeatherAndTriage returns a TRIAGED preEval WITH its data (and
                        // has already saved the triage row), so a triaged slot reaches this line:
                        // the triage row records the stand-down, this row records the override.
                        // `data == null` above means the weather fetch failed, not triage.
                        Long evalRowId = forecastService.persistPendingEvaluation(preEval);
                        tasks.add(new EvaluationTask.Forecast(
                                location, date, event, model, data,
                                EvaluationTask.Forecast.PromptKind.SKY, evalRowId));
                    } catch (Exception e) {
                        LOG.warn("[JFDI BATCH] Failed data assembly for {} {} {}: {}",
                                location.getName(), date, event, e.getMessage());
                        failedCount++;
                    }
                }
            }
        }

        if (tasks.isEmpty()) {
            LOG.warn("[JFDI BATCH] No requests built (all {} failed)", failedCount);
            return null;
        }

        LOG.info("[JFDI BATCH] Submitting {} tasks ({} locations x {} dates x 2 events, "
                        + "{} failed)",
                tasks.size(), locations.size(), dates.size(), failedCount);

        EvaluationHandle handle = evaluationService.submit(tasks, BatchTriggerSource.JFDI);
        if (handle == null || handle.batchId() == null) {
            return null;
        }
        return new BatchSubmitResult(handle.jobRunId(), handle.batchId(),
                handle.submittedCount());
    }

    /**
     * The one place the entry point chooses its locations: enabled, inside the caller's scope,
     * and a sky subject by the same {@link LocationEntity#hasColourTypes()} predicate the
     * synchronous engine and the scheduled batch use. The route builds SKY-prompt tasks only, and
     * has no woodland or bluebell lane, so a wildlife hide, a canopy wood or a bluebell wood
     * has nothing here to be evaluated for. Filtering before any fetch also means such a place is
     * never sent to {@code fetchWeatherAndTriage}.
     *
     * @param inScope the caller's own region test
     * @return the enabled sky locations the caller's scope admits
     */
    private List<LocationEntity> enabledSkyLocations(Predicate<LocationEntity> inScope) {
        return locationService.findAllEnabled().stream()
                .filter(inScope)
                .filter(LocationEntity::hasColourTypes)
                .toList();
    }
}
