package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.DriveStatus;
import com.gregochr.goldenhour.model.PlanRenderedEvent;
import com.gregochr.goldenhour.model.ReachEntry;
import com.gregochr.goldenhour.model.ReachVerdictResponse;
import com.gregochr.goldenhour.service.PlanWindowProjector.ScopedWindow;
import com.gregochr.goldenhour.service.PlanWindowProjector.WindowKey;
import com.gregochr.goldenhour.service.evaluation.RatingValidator;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recomputes the Plan headline for each drive tier, server-side, by the same engine that computes it for the whole
 * roster (docs/engineering/reach-scoped-card-plan.md D4, R2).
 *
 * <h2>What it does</h2>
 *
 * <p>For each tier in {@link #TIERS} it copies the served briefing, prunes the slots to the places inside the tier,
 * re-runs {@link BriefingRegionEvaluationRollup#rerollRegion} (the extracted per-region arithmetic) on every region
 * that lost a place, and re-runs {@link PlanWindowProjector#reproject}'s ranking, window verdict and pick on the
 * pruned tree. The meaning is the served headline's meaning on a smaller map. <b>Nothing is pooled across regions,
 * no threshold is added, and the client derives no verdict.</b>
 *
 * <p><b>In reach</b> is {@code MEASURED <= tier}, or {@code PENDING} (still unknown, so kept); an
 * {@code UNROUTABLE} place (no road route, confirmed) and a slot with no location id are out. This is
 * {@link DriveStatus}'s rule and the same one the client's {@code gateSpotsByReach} applies, so the headline and the
 * Spread row count one set of places. A slot whose location is not on the caller's reach list (a place switched off
 * since the briefing was built) is unknown and kept, exactly as the client keeps a spot with no reach entry.
 *
 * <h2>Home and away</h2>
 *
 * <p><b>Home</b> prunes every region and re-ranks them. <b>Away</b> ({@code originRegionId}) prunes only the origin
 * region, from that region's base-to-place drive times, and the window then wears the origin region's own verdict:
 * an away card stays its own region's card, so a tier tap can never swap the region it is about (D3). A pick is kept
 * only where it names the origin region, as the client already withholds any other.
 *
 * <h2>One source</h2>
 *
 * <p>It starts from {@link BriefingService#getCachedBriefingForApi()}, the memoised served briefing whose slots
 * carry the enriched ratings and the {@code forced}/{@code triaged} stamps, and never re-reads the rating stores
 * (D5). The result echoes that briefing's {@code briefingVersion}. No Claude call is made.
 */
@Service
public class ReachVerdictService {

    /** The drive tiers the lens offers, in minutes; {@code reachLens.js} {@code REACH_TIERS} is the client's twin. */
    static final List<Integer> TIERS = List.of(45, 90, 150);

    private final BriefingService briefingService;
    private final BriefingRegionEvaluationRollup rollup;
    private final ReachService reachService;
    private final RegionService regionService;
    private final RegionDriveDurationService regionDriveDurationService;
    private final LocationService locationService;

    /**
     * Constructs a {@code ReachVerdictService}.
     *
     * @param briefingService            supplies the memoised served briefing
     * @param rollup                     the rollup whose per-region arithmetic is re-run on pruned slots
     * @param reachService               supplies the caller's home reach (drive minutes and status per place)
     * @param regionService              resolves the away origin region
     * @param regionDriveDurationService supplies the region base drive-time matrix
     * @param locationService            supplies the enabled roster, for each place's drive-target stamp
     */
    public ReachVerdictService(BriefingService briefingService, BriefingRegionEvaluationRollup rollup,
            ReachService reachService, RegionService regionService,
            RegionDriveDurationService regionDriveDurationService, LocationService locationService) {
        this.briefingService = briefingService;
        this.rollup = rollup;
        this.reachService = reachService;
        this.regionService = regionService;
        this.regionDriveDurationService = regionDriveDurationService;
        this.locationService = locationService;
    }

    /**
     * What one place's drive is, from the origin the tiers are measured from.
     *
     * @param minutes the stored drive time, or null
     * @param status  MEASURED, PENDING or UNROUTABLE
     */
    private record Cell(Integer minutes, DriveStatus status) {

        /** A stored drive is compared to the tier; with none, only a confirmed UNROUTABLE place is out. */
        boolean inReach(int tierMinutes) {
            if (minutes != null) {
                return minutes <= tierMinutes;
            }
            return status != DriveStatus.UNROUTABLE;
        }
    }

    /**
     * Computes every window's headline at each tier.
     *
     * @param auth           the caller; their home reach is the origin unless {@code originRegionId} is given
     * @param originRegionId the away origin's region id, or null for home
     * @return the three tiers in one body; every tier list empty when the origin has no measured drive or there is
     *         no briefing
     * @throws IllegalArgumentException if {@code originRegionId} names a region with no base (a 400)
     * @throws java.util.NoSuchElementException if no such region exists (a 404)
     */
    public ReachVerdictResponse compute(Authentication auth, Long originRegionId) {
        RegionEntity origin = originRegionId == null ? null : regionService.findById(originRegionId);
        if (origin != null && !RegionDriveDurationService.hasBase(origin)) {
            throw new IllegalArgumentException("Region " + origin.getName() + " has no base to plan from");
        }
        Map<Long, Cell> cells = origin == null ? homeCells(auth) : awayCells(origin);
        boolean measured = cells.values().stream().anyMatch(cell -> cell.minutes() != null);
        DailyBriefingResponse served = briefingService.getCachedBriefingForApi();
        String version = served == null ? null : served.briefingVersion();
        Map<String, List<ReachVerdictResponse.Window>> tiers = new LinkedHashMap<>();
        for (int tier : TIERS) {
            tiers.put(String.valueOf(tier),
                    measured && served != null ? windowsAt(served, cells, tier, origin) : List.of());
        }
        return new ReachVerdictResponse(measured, version, tiers);
    }

    private Map<Long, Cell> homeCells(Authentication auth) {
        Map<Long, Cell> cells = new HashMap<>();
        for (ReachEntry entry : reachService.getReach(auth)) {
            cells.put(entry.locationId(), new Cell(entry.driveMinutes(), entry.driveStatus()));
        }
        return cells;
    }

    private Map<Long, Cell> awayCells(RegionEntity origin) {
        Map<Long, Integer> row = regionDriveDurationService.getMatrix().getOrDefault(origin.getId(), Map.of());
        Map<Long, Cell> cells = new HashMap<>();
        for (LocationEntity location : locationService.findAllEnabled()) {
            if (location.getId() == null) {
                continue;
            }
            boolean hasRow = row.containsKey(location.getId());
            cells.put(location.getId(), new Cell(row.get(location.getId()),
                    DriveStatus.of(hasRow, origin.getDrivesMeasuredAt(), location.getDriveTargetChangedAt(),
                            DriveTimeRefreshJob.ROSTER_VISIBILITY_MARGIN)));
        }
        return cells;
    }

    private List<ReachVerdictResponse.Window> windowsAt(DailyBriefingResponse served, Map<Long, Cell> cells,
            int tier, RegionEntity origin) {
        String originName = origin == null ? null : origin.getName();
        List<BriefingDay> days = new ArrayList<>(served.days().size());
        for (BriefingDay day : served.days()) {
            List<BriefingEventSummary> events = new ArrayList<>(day.eventSummaries().size());
            for (BriefingEventSummary summary : day.eventSummaries()) {
                List<BriefingRegion> regions = new ArrayList<>(summary.regions().size());
                for (BriefingRegion region : summary.regions()) {
                    boolean scoped = originName == null || originName.equals(region.regionName());
                    BriefingRegion kept = scoped ? prune(region, cells, tier, day, summary) : region;
                    if (kept != null) {
                        regions.add(kept);
                    }
                }
                events.add(summary.withRegions(regions));
            }
            days.add(day.withEventSummaries(events));
        }
        Map<WindowKey, ScopedWindow> windows = PlanWindowProjector.reproject(days, renderedOf(served));
        List<ReachVerdictResponse.Window> out = new ArrayList<>(windows.size());
        windows.forEach((key, scopedWindow) -> out.add(windowBody(key, scopedWindow, originName)));
        return out;
    }

    /**
     * The region over its in-reach places: the served region untouched when nothing is pruned or it has no places
     * to prune (a blanked region keeps its blanked answer), null when every place is pruned (the region is dropped
     * before ranking), else the extracted arithmetic re-run on the survivors.
     */
    private BriefingRegion prune(BriefingRegion region, Map<Long, Cell> cells, int tier, BriefingDay day,
            BriefingEventSummary summary) {
        // A region with no place to measure (the honesty filter blanked it) has no place in reach either, so at
        // a finite tier it is dropped like any region the tier empties: a distant blanked region must not speak
        // for a window it cannot be reached for.
        if (region.slots().isEmpty()) {
            return null;
        }
        List<BriefingSlot> inReach = region.slots().stream()
                .filter(slot -> isInReach(slot, cells, tier))
                .toList();
        if (inReach.size() == region.slots().size()) {
            return region;
        }
        if (inReach.isEmpty()) {
            return null;
        }
        BriefingRegion rerolled = rollup.rerollRegion(region, inReach, day.date(), summary.targetType());
        // Nothing rated survived: word it as the served pipeline words a zero-coverage region, not as a rating.
        boolean scoreable = inReach.stream().anyMatch(BriefingSlot::couldCarryRating);
        if (rerolled.scoredLocationCount() == 0 && scoreable) {
            return BriefingHonestyFilter.blankedForReach(rerolled, inReach.stream().anyMatch(BriefingSlot::triaged));
        }
        return rerolled;
    }

    private static boolean isInReach(BriefingSlot slot, Map<Long, Cell> cells, int tier) {
        if (slot.locationId() == null) {
            return false;
        }
        Cell cell = cells.get(slot.locationId());
        return cell == null || cell.inReach(tier);
    }

    /** The served render horizon, so a pruned pick can only land on a window that has a card. */
    private static Set<WindowKey> renderedOf(DailyBriefingResponse served) {
        Set<WindowKey> rendered = new LinkedHashSet<>();
        if (served.renderedEvents() == null) {
            served.days().forEach(day -> day.eventSummaries()
                    .forEach(es -> rendered.add(new WindowKey(day.date(), es.targetType()))));
            return rendered;
        }
        for (PlanRenderedEvent event : served.renderedEvents()) {
            rendered.add(new WindowKey(event.date(), event.targetType()));
        }
        return rendered;
    }

    private ReachVerdictResponse.Window windowBody(WindowKey key, ScopedWindow scoped, String originName) {
        BriefingWindow window = scoped.window();
        BriefingRegion region;
        DisplayVerdict verdict;
        BriefingWindow.Pick pick;
        List<ReachVerdictResponse.RegionVerdict> regions;
        if (originName == null) {
            region = scoped.ranked().isEmpty() ? null : scoped.ranked().get(0);
            verdict = window.verdict();
            pick = window.pick();
            regions = scoped.ranked().stream().map(ReachVerdictService::regionVerdict).toList();
        } else {
            // Away: the card is the origin region's, whatever the window's top region is.
            // Found among ALL the window's regions, not the ranked ones: the projector's window-level canopy rule
            // thins `ranked`, and an away card about a base whose only place in reach is a wood is still that card.
            region = scoped.all().stream()
                    .filter(r -> originName.equals(r.regionName()))
                    .findFirst()
                    .orElse(null);
            verdict = region == null || region.displayVerdict() == null
                    ? DisplayVerdict.AWAITING : region.displayVerdict();
            pick = window.pick() != null && originName.equals(window.pick().regionName()) ? window.pick() : null;
            regions = region == null ? List.of() : List.of(regionVerdict(region));
        }
        return new ReachVerdictResponse.Window(key.date(), key.targetType(), verdict,
                printableMean(region, verdict), region == null ? null : region.meanRating(),
                region == null ? null : region.confidence(), region == null ? null : region.regionName(),
                verdict == DisplayVerdict.STAND_DOWN && region != null && region.slots().isEmpty(),
                thinCount(region, verdict), pick, pick == null ? null : pick.regionName(), regions);
    }

    private static ReachVerdictResponse.RegionVerdict regionVerdict(BriefingRegion region) {
        return new ReachVerdictResponse.RegionVerdict(region.regionName(), region.displayVerdict(),
                region.meanRating());
    }

    /**
     * The mean the card may print beside its word: the region's own mean when the word is a reading of it
     * (verdict-eligible) or an UNRATED word whose evidence it is, else null. The rule {@code verdictMeanOf} applies
     * to a served region on the client, applied here to the pruned one.
     */
    private static Double printableMean(BriefingRegion region, DisplayVerdict verdict) {
        if (region == null || region.meanRating() == null) {
            return null;
        }
        return region.verdictEligible() || verdict == DisplayVerdict.UNRATED ? region.meanRating() : null;
    }

    /**
     * The count behind a rated word whose sample is thin: a rated word over a region that is verdict-eligible only
     * because a force-evaluation exempted it from the sample gate. Null for every other state.
     */
    private static Integer thinCount(BriefingRegion region, DisplayVerdict verdict) {
        boolean rated = verdict == DisplayVerdict.WORTH_IT || verdict == DisplayVerdict.MAYBE;
        if (region == null || !rated || Boolean.TRUE.equals(region.sampleSufficient())) {
            return null;
        }
        long count = BriefingSlot.votingSlots(region.slots()).stream()
                .filter(slot -> RatingValidator.isInRange(slot.claudeRating()))
                .count();
        return count > 0 ? (int) count : null;
    }
}
