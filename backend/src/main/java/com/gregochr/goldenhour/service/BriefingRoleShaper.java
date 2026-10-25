package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.LocationTideFact;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Shapes the served briefing for the reader's role at the HTTP edge.
 *
 * <p>The tide curve and the per-location tide match are Pro features
 * (docs/engineering/tide-pro-gate-plan.md §7), as are the Fiery Sky and Golden Hour scores and the
 * per-location written why (docs/engineering/pro-features-gate-plan.md §6). {@link #forLite}
 * removes the tide data from the window tide block, the window's tide facts and the slot flags, and
 * the scores and the written why from every slot; every free fact stays (the star, the verdict, the
 * region prose), and hot topics are untouched (the two-question rule).
 *
 * <p>Rebuilds only through the records' own withers and canonical constructors, so a component
 * added later is never defaulted away silently. Never used on {@code getCachedBriefingForApi}'s
 * internal callers (the digest, Ask), which keep the full data. The input is never mutated.
 */
@Component
public class BriefingRoleShaper {

    /** The slot flags that state the per-location match; Pro only. */
    static final Set<String> MATCH_FLAGS = Set.of("Tide aligned", "Tide not aligned");

    /**
     * Returns the briefing as a LITE reader may see it.
     *
     * @param response the full briefing (may be null)
     * @return a stripped copy, or null if the input was null
     */
    public DailyBriefingResponse forLite(DailyBriefingResponse response) {
        if (response == null) {
            return null;
        }
        List<BriefingDay> days = response.days().stream().map(BriefingRoleShaper::liteDay).toList();
        return response.withDays(days);
    }

    private static BriefingDay liteDay(BriefingDay day) {
        return day.withEventSummaries(day.eventSummaries().stream()
                .map(BriefingRoleShaper::liteEvent).toList());
    }

    private static BriefingEventSummary liteEvent(BriefingEventSummary es) {
        List<BriefingRegion> regions = es.regions().stream()
                .map(region -> region.withSlots(liteSlots(region.slots())))
                .toList();
        BriefingEventSummary shaped = es.withRegions(regions)
                .withUnregioned(liteSlots(es.unregioned()));
        return shaped.window() == null ? shaped : shaped.withWindow(liteWindow(shaped.window()));
    }

    private static List<BriefingSlot> liteSlots(List<BriefingSlot> slots) {
        return slots == null ? null : slots.stream().map(BriefingRoleShaper::liteSlot).toList();
    }

    private static BriefingSlot liteSlot(BriefingSlot slot) {
        // The scores and the written why go from EVERY slot, whether or not it carries a tide flag.
        BriefingSlot stripped = slot.forLite();
        if (stripped.flags().stream().noneMatch(MATCH_FLAGS::contains)) {
            return stripped;
        }
        return stripped.withFlags(stripped.flags().stream().filter(f -> !MATCH_FLAGS.contains(f)).toList());
    }

    private static BriefingWindow liteWindow(BriefingWindow window) {
        BriefingWindow shaped = window;
        if (window.tide() != null) {
            shaped = shaped.withTide(window.tide().forLite());
        }
        if (window.tideFacts() != null) {
            shaped = shaped.withTideFacts(
                    window.tideFacts().stream().map(LocationTideFact::forLite).toList());
        }
        return shaped;
    }
}
