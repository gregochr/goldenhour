package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.HotTopic;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Served-briefing shapes for the Ready tests: days with a sunrise and/or a sunset window over a
 * handful of regions, read through the real {@link AskSnapshotBuilder}. October 2026: the 5th is a
 * Monday, so the 10th and 11th are the weekend.
 */
final class ReadyFixtures {

    static final LocalDateTime MONDAY_NOON = LocalDateTime.of(2026, 10, 5, 12, 0);
    static final LocalDateTime MONDAY_BEFORE_DAWN = LocalDateTime.of(2026, 10, 5, 4, 0);
    static final LocalDateTime THURSDAY_NOON = LocalDateTime.of(2026, 10, 8, 12, 0);
    static final LocalDateTime FRIDAY_NOON = LocalDateTime.of(2026, 10, 9, 12, 0);
    static final LocalDateTime SATURDAY_NOON = LocalDateTime.of(2026, 10, 10, 12, 0);

    private ReadyFixtures() {
    }

    /** The given day of October 2026. */
    static LocalDate oct(int day) {
        return LocalDate.of(2026, 10, day);
    }

    /** Northumberland: a coastal 5★ (high water, wanted), an inland 4★, a 5★ wood and a 2★ spot. */
    static BriefingRegion northumberland() {
        return AskFixtures.region("Northumberland", true,
                AskFixtures.coastal(1L, "Bamburgh", 5, "HIGH", true),
                AskFixtures.slot(2L, "Cheviot Edge", 4),
                AskFixtures.wood(3L, "Kielder Wood", 5),
                AskFixtures.slot(4L, "Grey Moor", 2));
    }

    /** Teesdale: one inland 4★. */
    static BriefingRegion teesdale() {
        return AskFixtures.region("Teesdale", true, AskFixtures.slot(10L, "Hamsterley", 4));
    }

    /** A region the Plan tab refuses a verdict: its 4★ must never be a pick. */
    static BriefingRegion ineligible() {
        return AskFixtures.region("Dales", false, AskFixtures.slot(20L, "Hand-run Hill", 4));
    }

    /** Northumberland with the coastal spot at the given tide state and rating. */
    static BriefingRegion coastAt(String tideState, int rating) {
        return AskFixtures.region("Northumberland", true,
                AskFixtures.coastal(1L, "Bamburgh", rating, tideState, true),
                AskFixtures.slot(2L, "Cheviot Edge", 4));
    }

    /**
     * A day with a sunrise and/or a sunset window (06:00 and 18:00 UTC) over the given regions.
     *
     * @param sunsetPick the sunset window's served pick, or null
     */
    static BriefingDay day(LocalDate date, boolean sunrise, boolean sunset,
            BriefingWindow.Pick sunsetPick, BriefingRegion... regions) {
        List<BriefingEventSummary> summaries = new ArrayList<>();
        if (sunrise) {
            summaries.add(AskFixtures.summary(TargetType.SUNRISE,
                    AskFixtures.window(date.atTime(6, 0), DisplayVerdict.WORTH_IT, 4, null), regions));
        }
        if (sunset) {
            summaries.add(AskFixtures.summary(TargetType.SUNSET,
                    AskFixtures.window(date.atTime(18, 0), DisplayVerdict.WORTH_IT, 4, sunsetPick),
                    regions));
        }
        return new BriefingDay(date, summaries);
    }

    /** Both windows of a day over the given regions, no served pick. */
    static BriefingDay both(LocalDate date, BriefingRegion... regions) {
        return day(date, true, true, null, regions);
    }

    /** The snapshot at {@code now} of a briefing of these days and topics. */
    static AskSnapshot at(LocalDateTime now, List<BriefingDay> days, List<HotTopic> topics) {
        return AskFixtures.snapshotAt(now, AskFixtures.briefing(days, topics), List.of());
    }

    static AskSnapshot at(LocalDateTime now, BriefingDay... days) {
        return at(now, List.of(days), List.of());
    }

    /** The slot with its served display verdict replaced. */
    static BriefingSlot withVerdict(BriefingSlot base, DisplayVerdict verdict) {
        return new BriefingSlot(base.locationId(), base.locationName(), base.solarEventTime(),
                base.verdict(), base.weather(), base.tide(), base.flags(), base.standdownReason(),
                base.claudeRating(), base.skyRating(), null, null, null, verdict,
                base.claudeHeadline(), base.canopy(), null);
    }

    static AskPick pick(int rank, long locationId, String name, String region, String windowId,
            int rating, DisplayVerdict verdict) {
        AskWindowId.Parts parts = AskWindowId.parse(windowId).orElseThrow();
        return new AskPick(rank, locationId, name, region, parts.date(), parts.targetType(), windowId,
                "Clear sky.", rating, verdict.name());
    }

    /** An answer of these picks and no events. */
    static AskAnswer answer(AskPick... picks) {
        return new AskAnswer(true, "A good one.", List.of(picks), List.of(), null);
    }
}
