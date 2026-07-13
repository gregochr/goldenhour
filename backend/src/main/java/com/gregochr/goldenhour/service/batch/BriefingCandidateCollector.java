package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.entity.DispositionCategory;
import com.gregochr.goldenhour.entity.ForecastStability;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.CandidateDisposition;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.StabilitySummaryResponse;
import com.gregochr.goldenhour.service.BriefingEvaluationService;
import com.gregochr.goldenhour.service.BriefingGatingPolicy;
import com.gregochr.goldenhour.service.FreshnessResolver;
import com.gregochr.goldenhour.service.LocationService;
import com.gregochr.goldenhour.service.StabilitySnapshotProvider;
import com.gregochr.goldenhour.service.TravelDayService;
import com.gregochr.goldenhour.service.evaluation.CacheKeyFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * First briefing pass: turns the cached daily briefing into the set of forecast
 * candidates that survive the past-date, travel-day, cache, verdict, and
 * unknown-location gates, while recording a disposition for every slot considered.
 *
 * <p>Instance-scoped seam extracted from {@code ForecastTaskCollector}. A pure
 * producer — it makes no API calls and mutates no shared state; the caller merges
 * the returned dispositions into its own list. Logs under the
 * {@link ForecastTaskCollector} category so collection diagnostics stay grouped
 * with the collector's own output.
 */
public final class BriefingCandidateCollector {

    private static final Logger LOG = LoggerFactory.getLogger(ForecastTaskCollector.class);

    /** UK civil-date zone for "today" derivation. */
    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    private final TravelDayService travelDayService;
    private final LocationService locationService;
    private final BriefingEvaluationService briefingEvaluationService;
    private final FreshnessResolver freshnessResolver;
    private final StabilitySnapshotProvider stabilitySnapshotProvider;
    private final Clock clock;

    /**
     * Constructs a {@code BriefingCandidateCollector}.
     *
     * @param travelDayService          gates out candidates whose target date is a travel day
     * @param locationService           service for retrieving enabled locations
     * @param briefingEvaluationService evaluation cache (read-only — freshness check)
     * @param freshnessResolver         per-stability cache freshness thresholds
     * @param stabilitySnapshotProvider provides the latest stability snapshot
     * @param clock                     UTC clock supplying "today" (via London)
     */
    public BriefingCandidateCollector(TravelDayService travelDayService,
            LocationService locationService,
            BriefingEvaluationService briefingEvaluationService,
            FreshnessResolver freshnessResolver,
            StabilitySnapshotProvider stabilitySnapshotProvider,
            Clock clock) {
        this.travelDayService = travelDayService;
        this.locationService = locationService;
        this.briefingEvaluationService = briefingEvaluationService;
        this.freshnessResolver = freshnessResolver;
        this.stabilitySnapshotProvider = stabilitySnapshotProvider;
        this.clock = clock;
    }

    /**
     * The candidates surviving the first briefing pass, paired with the dispositions
     * recorded for every slot the pass considered (both inclusions and skips).
     *
     * @param candidates   slots that survived to the triage loop
     * @param dispositions one disposition per briefing slot considered
     */
    public record Result(List<ForecastCandidate> candidates,
            List<CandidateDisposition> dispositions) {
    }

    /**
     * Days from today (Europe/London) to the given date. Negative for past
     * dates. Matches the {@code today} computation used in
     * {@link #collectForecastCandidates}.
     *
     * @param date  the target date
     * @param clock UTC clock supplying "today" (via London)
     * @return the number of days ahead (negative for past dates)
     */
    public static int daysAheadFor(LocalDate date, Clock clock) {
        LocalDate today = LocalDate.now(clock.withZone(LONDON));
        return (int) ChronoUnit.DAYS.between(today, date);
    }

    /**
     * First pass over the briefing: collects all GO/MARGINAL slots that are not
     * already cached. No API calls are made here.
     *
     * <p>The returned {@link Result} pairs the surviving candidates with a
     * {@link CandidateDisposition} for every slot the briefing considered, both
     * inclusions (passed to the triage loop) and skips (PAST_DATE, CACHED,
     * HARD_CONSTRAINT, UNKNOWN_LOCATION). Skips are recorded with
     * {@code location_id = null} since the verdict/cache/past-date paths never
     * look the location up. The inclusion entries are NOT finalised here; the
     * triage loop assigns the final disposition (EVALUATED / SKIPPED_TRIAGED /
     * SKIPPED_STABILITY / SKIPPED_ERROR) once weather + stability data is available.
     *
     * @param briefing          the cached briefing being collected
     * @param candidateStrategy filter deciding which event slots enter the candidate set
     * @return the surviving candidates paired with the first-pass dispositions
     */
    public Result collectForecastCandidates(DailyBriefingResponse briefing,
            CandidateCollectionStrategy candidateStrategy) {
        List<CandidateDisposition> dispositions = new ArrayList<>();
        List<ForecastCandidate> candidates = new ArrayList<>();
        int skippedCache = 0;
        int skippedVerdict = 0;
        int skippedUnknown = 0;
        int skippedPastDate = 0;
        int skippedTravelDay = 0;
        int totalSlots = 0;
        Map<ForecastStability, int[]> cachedByStability = new HashMap<>();
        Map<ForecastStability, int[]> eligibleByStability = new HashMap<>();
        for (ForecastStability s : ForecastStability.values()) {
            cachedByStability.put(s, new int[]{0});
            eligibleByStability.put(s, new int[]{0});
        }

        Map<String, ForecastStability> stabilityByLocation = buildStabilityLookup();

        // Use Europe/London because solar events are for UK locations — a sunrise
        // in Northumberland on April 19th BST is what matters, not the UTC date.
        LocalDate today = LocalDate.now(clock.withZone(LONDON));

        for (BriefingDay day : briefing.days()) {
            LocalDate date = day.date();
            int daysAhead = (int) ChronoUnit.DAYS.between(today, date);
            if (date.isBefore(today)) {
                int daySlots = 0;
                for (BriefingEventSummary eventSummary : day.eventSummaries()) {
                    TargetType targetType = eventSummary.targetType();
                    for (BriefingRegion region : eventSummary.regions()) {
                        if (region.slots() == null) {
                            continue;
                        }
                        for (BriefingSlot slot : region.slots()) {
                            dispositions.add(new CandidateDisposition(
                                    null, slot.locationName(), date, targetType, daysAhead,
                                    DispositionCategory.SKIPPED_PAST_DATE, "Date in past"));
                            daySlots++;
                        }
                    }
                }
                skippedPastDate += daySlots;
                totalSlots += daySlots;
                LOG.warn("[BATCH DIAG] SKIP date {} | reason=PAST_DATE ({} slots skipped)",
                        date, daySlots);
                continue;
            }
            // Travel-day gate (per target date): the operator is away on this date
            // and cannot shoot, so every slot's forecast is unactionable spend. Skip
            // the whole day's slots with a SKIPPED_TRAVEL_DAY disposition. An empty
            // travel_day table makes this a no-op.
            if (travelDayService.isTravelDay(date)) {
                int daySlots = 0;
                for (BriefingEventSummary eventSummary : day.eventSummaries()) {
                    TargetType targetType = eventSummary.targetType();
                    for (BriefingRegion region : eventSummary.regions()) {
                        if (region.slots() == null) {
                            continue;
                        }
                        for (BriefingSlot slot : region.slots()) {
                            dispositions.add(new CandidateDisposition(
                                    null, slot.locationName(), date, targetType, daysAhead,
                                    DispositionCategory.SKIPPED_TRAVEL_DAY, "Travel day — away"));
                            daySlots++;
                        }
                    }
                }
                skippedTravelDay += daySlots;
                totalSlots += daySlots;
                LOG.warn("[BATCH DIAG] SKIP date {} | reason=TRAVEL_DAY ({} slots skipped)",
                        date, daySlots);
                continue;
            }
            for (BriefingEventSummary eventSummary : day.eventSummaries()) {
                TargetType targetType = eventSummary.targetType();
                // Cycle-specific window filter. Slots outside the cycle's window
                // are silently skipped — they are not "decided against", they
                // simply aren't this cycle's responsibility. Nightly's strategy
                // accepts everything; the intraday refresh will use this to
                // restrict to its decision window.
                if (!candidateStrategy.includes(date, targetType)) {
                    continue;
                }
                for (BriefingRegion region : eventSummary.regions()) {
                    String cacheKey = CacheKeyFactory
                            .build(region.regionName(), date, targetType);
                    ForecastStability regionStability = mostVolatileStability(
                            region, stabilityByLocation);
                    Duration freshness = freshnessResolver.maxAgeFor(regionStability);
                    int regionSlots = region.slots() != null ? region.slots().size() : 0;
                    eligibleByStability.get(regionStability)[0] += regionSlots;
                    if (briefingEvaluationService.hasFreshEvaluation(cacheKey, freshness)) {
                        LOG.warn("[BATCH DIAG] SKIP region {} | reason=CACHED "
                                        + "(stability={}, threshold={}h, {} slots skipped)",
                                cacheKey, regionStability,
                                freshness.toHours(), regionSlots);
                        String cachedDetail = String.format(
                                "Fresh cached evaluation within %dh (%s)",
                                freshness.toHours(), regionStability);
                        if (region.slots() != null) {
                            for (BriefingSlot slot : region.slots()) {
                                dispositions.add(new CandidateDisposition(
                                        null, slot.locationName(), date, targetType, daysAhead,
                                        DispositionCategory.SKIPPED_CACHED, cachedDetail));
                            }
                        }
                        cachedByStability.get(regionStability)[0] += regionSlots;
                        skippedCache += regionSlots;
                        totalSlots += regionSlots;
                        continue;
                    }
                    for (BriefingSlot slot : region.slots()) {
                        totalSlots++;
                        if (!BriefingGatingPolicy.isEligibleForEvaluation(slot)) {
                            String reasonLabel = BriefingGatingPolicy.isHardConstraintSkip(slot)
                                    ? "HARD_CONSTRAINT"
                                    : "VERDICT_" + slot.verdict();
                            LOG.warn("[BATCH DIAG] SKIP {} | date={} event={} | "
                                            + "reason={} ({})",
                                    slot.locationName(), date, targetType,
                                    reasonLabel, slot.standdownReason());
                            // Both hard-constraint tide skips and the (now rare,
                            // Gate-2-redesigned) VERDICT_* skips are hard physical
                            // gates — fold them into SKIPPED_HARD_CONSTRAINT with the
                            // standdown reason as detail.
                            dispositions.add(new CandidateDisposition(
                                    null, slot.locationName(), date, targetType, daysAhead,
                                    DispositionCategory.SKIPPED_HARD_CONSTRAINT,
                                    slot.standdownReason() != null ? slot.standdownReason()
                                            : reasonLabel));
                            skippedVerdict++;
                            continue;
                        }
                        LocationEntity location = findLocation(slot.locationName());
                        if (location == null) {
                            LOG.warn("[BATCH DIAG] SKIP {} | date={} event={} | "
                                            + "reason=UNKNOWN_LOCATION",
                                    slot.locationName(), date, targetType);
                            dispositions.add(new CandidateDisposition(
                                    null, slot.locationName(), date, targetType, daysAhead,
                                    DispositionCategory.SKIPPED_UNKNOWN_LOCATION,
                                    "Location not found in enabled set"));
                            skippedUnknown++;
                            continue;
                        }
                        candidates.add(new ForecastCandidate(location, date, targetType));
                    }
                }
            }
        }

        LOG.warn("[BATCH DIAG] Task collection complete — {} tasks from {} total slots "
                        + "(pastDate={}, travelDay={}, cached={}, verdict={}, unknownLoc={})",
                candidates.size(), totalSlots, skippedPastDate, skippedTravelDay, skippedCache,
                skippedVerdict, skippedUnknown);
        logStabilityBreakdown(eligibleByStability, cachedByStability);
        return new Result(candidates, dispositions);
    }

    private LocationEntity findLocation(String name) {
        return locationService.findAllEnabled().stream()
                .filter(loc -> loc.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    private void logStabilityBreakdown(Map<ForecastStability, int[]> eligible,
            Map<ForecastStability, int[]> cached) {
        StringBuilder sb = new StringBuilder("[BATCH DIAG] Candidate breakdown by stability:");
        for (ForecastStability level : ForecastStability.values()) {
            int elig = eligible.get(level)[0];
            int cach = cached.get(level)[0];
            if (elig == 0) {
                continue;
            }
            int refreshed = elig - cach;
            double pct = elig > 0 ? (refreshed * 100.0 / elig) : 0;
            Duration threshold = freshnessResolver.maxAgeFor(level);
            sb.append(String.format(" %s: %d of %d (%.1f%% refreshed, threshold %dh) |",
                    level, refreshed, elig, pct, threshold.toHours()));
        }
        if (sb.charAt(sb.length() - 1) == '|') {
            sb.setLength(sb.length() - 1);
        }
        LOG.warn("{}", sb);
    }

    private Map<String, ForecastStability> buildStabilityLookup() {
        StabilitySummaryResponse snapshot = stabilitySnapshotProvider.getLatestStabilitySummary();
        if (snapshot == null || snapshot.cells() == null) {
            LOG.warn("[BATCH DIAG] Stability snapshot unavailable — no snapshot in memory or DB, "
                    + "all regions treated as UNSETTLED ({}h threshold)",
                    freshnessResolver.maxAgeFor(ForecastStability.UNSETTLED).toHours());
            return Map.of();
        }
        long ageHours = java.time.temporal.ChronoUnit.HOURS.between(
                snapshot.generatedAt(), java.time.Instant.now());
        String source = ageHours > 12 ? "DB (recovered after restart)" : "in-memory";
        LOG.info("[BATCH DIAG] Stability snapshot loaded from {}: age={}h, {} grid cells",
                source, ageHours, snapshot.cells().size());
        Map<String, ForecastStability> lookup = new HashMap<>();
        for (StabilitySummaryResponse.GridCellDetail cell : snapshot.cells()) {
            for (String locName : cell.locationNames()) {
                lookup.put(locName, cell.stability());
            }
        }
        return lookup;
    }

    private ForecastStability mostVolatileStability(BriefingRegion region,
            Map<String, ForecastStability> stabilityByLocation) {
        if (region.slots() == null || region.slots().isEmpty()
                || stabilityByLocation.isEmpty()) {
            return ForecastStability.UNSETTLED;
        }
        ForecastStability most = ForecastStability.SETTLED;
        for (BriefingSlot slot : region.slots()) {
            ForecastStability slotStability = stabilityByLocation.getOrDefault(
                    slot.locationName(), ForecastStability.UNSETTLED);
            if (slotStability == ForecastStability.UNSETTLED) {
                return ForecastStability.UNSETTLED;
            }
            if (slotStability == ForecastStability.TRANSITIONAL) {
                most = ForecastStability.TRANSITIONAL;
            }
        }
        return most;
    }
}
