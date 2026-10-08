package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.LocationTideFact;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reshapes the per-location tide on a briefing's slots into the per-window
 * {@link LocationTideFact} lists served on {@code BriefingWindow.tideFacts}.
 *
 * <p><b>Run it on the raw cached days, never the served ones.</b> {@code BriefingHonestyFilter}
 * empties a zero-coverage region's slots on the API read path, and that is exactly the case these
 * facts exist to survive: tide comes from stored tide tables, not from Claude. The caller is
 * {@code ServedBriefingAssembler.assembleForPlan}, which feeds it the snapshot before the filter
 * runs.
 *
 * <p>Pure: reads no repository and no clock, and never mutates its input. The key is
 * {@link PlanWindowProjector.WindowKey}, as that type's javadoc requires of any second producer of
 * per-window data. Like {@code WindowTideRollupBuilder} it is a reshape of what the build already
 * wrote, so it can never disagree with {@code BriefingSlotBuilder} about a slot's tide.
 * See {@code docs/engineering/window-tide-facts-plan.md}.
 */
final class WindowTideFactProjector {

    private WindowTideFactProjector() {
    }

    /**
     * Builds the tide facts for every window that has at least one coastal slot.
     *
     * <p>Regioned slots are walked first, then unregioned ones. One fact per location per window:
     * the first slot to name a location wins (keyed by id, or by name for a legacy slot with no
     * id). A slot with no tide state yields no fact, and a window with no fact at all has no
     * entry, so a caller can map absence to a null component and never serve an empty list.
     *
     * @param days the raw cached days (may be null)
     * @return the facts per window; empty when there are none, never null
     */
    static Map<PlanWindowProjector.WindowKey, List<LocationTideFact>> project(
            List<BriefingDay> days) {
        Map<PlanWindowProjector.WindowKey, List<LocationTideFact>> byWindow = new HashMap<>();
        if (days == null) {
            return byWindow;
        }
        for (BriefingDay day : days) {
            for (BriefingEventSummary summary : day.eventSummaries()) {
                List<LocationTideFact> facts = factsOf(summary);
                if (!facts.isEmpty()) {
                    byWindow.put(new PlanWindowProjector.WindowKey(day.date(),
                            summary.targetType()), List.copyOf(facts));
                }
            }
        }
        return byWindow;
    }

    private static List<LocationTideFact> factsOf(BriefingEventSummary summary) {
        List<LocationTideFact> facts = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (BriefingRegion region : summary.regions()) {
            collect(region.slots(), facts, seen);
        }
        collect(summary.unregioned(), facts, seen);
        return facts;
    }

    private static void collect(List<BriefingSlot> slots, List<LocationTideFact> facts,
            Set<String> seen) {
        for (BriefingSlot slot : slots) {
            LocationTideFact fact = LocationTideFact.from(slot);
            if (fact != null && seen.add(identityOf(fact))) {
                facts.add(fact);
            }
        }
    }

    /** The location's identity: its id when it has one, else its name. */
    private static String identityOf(LocationTideFact fact) {
        return fact.locationId() != null ? "id:" + fact.locationId() : "name:" + fact.locationName();
    }
}
