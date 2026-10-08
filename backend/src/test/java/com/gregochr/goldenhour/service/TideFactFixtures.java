package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.Verdict;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Shared builders for the window-tide-facts tests: slots carrying a fully populated served tide,
 * regions at a chosen coverage, and summaries.
 */
public final class TideFactFixtures {

    private TideFactFixtures() {
    }

    /**
     * A coastal slot whose every served tide field is populated and deliberately inconsistent
     * ({@code tideAligned} false beside a "suits" fit phrase and an on-the-light true), so a
     * projector that re-derived anything would be caught.
     *
     * @param id    the location id, or null for a legacy slot
     * @param name  the location name
     * @param state the served tide state
     * @return the slot
     */
    public static BriefingSlot coastal(Long id, String name, String state) {
        BriefingSlot.TideInfo tide = new BriefingSlot.TideInfo(state, false,
                LocalDateTime.of(2026, 10, 11, 5, 40), new BigDecimal("4.9"), true, true,
                null, "Waxing", true, 25, "HW", true, "HW 06:12 · 25m after sunrise",
                0.83, "RISING", "4.9 m", "wants higher", "Tide suits this spot", 0.61);
        return slot(id, name, tide, false);
    }

    /**
     * An inland slot: {@link BriefingSlot.TideInfo#NONE}.
     *
     * @param id   the location id
     * @param name the location name
     * @return the slot
     */
    public static BriefingSlot inland(Long id, String name) {
        return slot(id, name, BriefingSlot.TideInfo.NONE, false);
    }

    /**
     * A canopy slot, which also carries {@link BriefingSlot.TideInfo#NONE}.
     *
     * @param id   the location id
     * @param name the location name
     * @return the slot
     */
    public static BriefingSlot canopy(Long id, String name) {
        return slot(id, name, BriefingSlot.TideInfo.NONE, true);
    }

    private static BriefingSlot slot(Long id, String name, BriefingSlot.TideInfo tide,
            boolean canopy) {
        return new BriefingSlot(id, name, LocalDateTime.of(2026, 10, 11, 6, 0), Verdict.GO, null,
                tide, List.of(), null, null, null, null, null, null,
                DisplayVerdict.resolve(null, Verdict.GO), null, canopy, null);
    }

    /**
     * A region with the given count of Claude-scored locations; zero with a scoreable slot is the
     * honesty filter's full-rewrite case.
     *
     * @param name   the region name
     * @param scored the {@code scoredLocationCount}
     * @param slots  the slots
     * @return the region
     */
    public static BriefingRegion region(String name, int scored, BriefingSlot... slots) {
        return new BriefingRegion(name, Verdict.GO, "summary", List.of(), List.of(slots), null,
                null, null, null, null, null, DisplayVerdict.WORTH_IT, scored, null, false, null);
    }

    /**
     * A summary holding the regions and unregioned slots.
     *
     * @param type       the solar event
     * @param regions    the regions
     * @param unregioned the unregioned slots
     * @return the summary
     */
    public static BriefingEventSummary summary(TargetType type, List<BriefingRegion> regions,
            List<BriefingSlot> unregioned) {
        return new BriefingEventSummary(type, regions, unregioned);
    }

    /**
     * A day of the given summaries.
     *
     * @param date      the date
     * @param summaries the event summaries
     * @return the day
     */
    public static BriefingDay day(LocalDate date, BriefingEventSummary... summaries) {
        return new BriefingDay(date, List.of(summaries));
    }
}
