package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.LocationTideFact;
import com.gregochr.goldenhour.model.PlanRenderedEvent;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.model.comingup.ComingUpResponse;
import com.gregochr.goldenhour.service.AlmanacService;
import com.gregochr.goldenhour.service.BriefingService;
import com.gregochr.goldenhour.service.SolarEventFreshness;
import com.gregochr.goldenhour.service.TravelDayService;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.when;

/**
 * Builders for the served-briefing shapes the Ask tests read. Mirrors the Plan tab's tree:
 * day -> event summary (with its window) -> region -> slot.
 */
final class AskFixtures {

    /** A Monday. */
    static final LocalDate TODAY = LocalDate.of(2026, 10, 5);

    /** Midday UTC on {@link #TODAY}. */
    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 5, 12, 0);

    /** The briefing build time: 05:02 UTC, which is 06:02 in London in October. */
    static final LocalDateTime GENERATED_AT = LocalDateTime.of(2026, 10, 5, 5, 2, 11);

    private AskFixtures() {
    }

    static Clock clockAt(LocalDateTime utc) {
        return Clock.fixed(utc.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    }

    /** A sky slot with a rating and no tide. */
    static BriefingSlot slot(Long id, String name, Integer rating) {
        return slotWith(id, name, rating, false, BriefingSlot.TideInfo.NONE, null);
    }

    /** A wood: its rating means the opposite of a sky rating. */
    static BriefingSlot wood(Long id, String name, Integer rating) {
        return slotWith(id, name, rating, true, BriefingSlot.TideInfo.NONE, null);
    }

    /** A coastal slot carrying a served tide state and the location's own preference fact. */
    static BriefingSlot coastal(Long id, String name, Integer rating, String tideState,
            boolean tideAligned) {
        BriefingSlot.TideInfo tide = new BriefingSlot.TideInfo(tideState, tideAligned, null, null,
                false, false, null, null, null, null, null, null, null, null, null, null, null,
                tideAligned ? "Tide suits this spot" : "Wrong water", null);
        return slotWith(id, name, rating, false, tide, null);
    }

    /** The slot as served after the tide strip: the same slot with no tide of its own. */
    static BriefingSlot withoutTide(BriefingSlot base) {
        return new BriefingSlot(base.locationId(), base.locationName(), base.solarEventTime(),
                base.verdict(), base.weather(), null, base.flags(), base.standdownReason(),
                base.claudeRating(), base.skyRating(), null, null, null, base.displayVerdict(),
                base.claudeHeadline(), base.canopy(), null);
    }

    /** A served tide fact, as the projector would publish it. */
    static LocationTideFact fact(Long id, String name, String tideState, boolean aligned,
            String fitPhrase) {
        return new LocationTideFact(id, name, tideState, aligned, null, null, null, null, null, null,
                null, fitPhrase);
    }

    /** The window with exactly these tide facts, replacing any it carried. */
    static BriefingWindow withFacts(BriefingWindow window, LocationTideFact... facts) {
        return new BriefingWindow(window.eventTime(), window.verdict(), window.bestRating(),
                window.confidence(), window.pick(), window.badges(), window.topRarityRank(),
                window.tide(), List.of(facts));
    }

    static BriefingSlot withHeadline(BriefingSlot base, String headline) {
        return new BriefingSlot(base.locationId(), base.locationName(), base.solarEventTime(),
                base.verdict(), base.weather(), base.tide(), base.flags(), base.standdownReason(),
                base.claudeRating(), base.skyRating(), null, null, null, base.displayVerdict(),
                headline, base.canopy(), null);
    }

    private static BriefingSlot slotWith(Long id, String name, Integer rating, boolean canopy,
            BriefingSlot.TideInfo tide, String headline) {
        return new BriefingSlot(id, name, NOW.plusHours(6), Verdict.GO, null, tide, List.of(), null,
                rating, rating, null, null, null,
                DisplayVerdict.resolve(rating, Verdict.GO), headline, canopy, null);
    }

    /** A region whose verdict sample is sufficient or not. */
    static BriefingRegion region(String name, boolean eligible, BriefingSlot... slots) {
        return new BriefingRegion(name, Verdict.GO, "summary", List.of(), List.of(slots), null,
                null, null, null, "Gloss headline", "Gloss detail", DisplayVerdict.WORTH_IT,
                slots.length, null, false, null, 4.0, 4, null, eligible, null);
    }

    static BriefingWindow window(LocalDateTime eventTime, DisplayVerdict verdict, Integer best,
            BriefingWindow.Pick pick) {
        return new BriefingWindow(eventTime, verdict, best, null, pick, List.of(), null, null);
    }

    static BriefingWindow.Pick pick(BriefingWindow.PickKind kind, String region, String location,
            Long locationId) {
        return new BriefingWindow.Pick(kind, region, "Headline", "Detail", 4.2, location,
                locationId);
    }

    /** An event summary with a window attached. */
    static BriefingEventSummary summary(TargetType type, BriefingWindow window,
            BriefingRegion... regions) {
        return new BriefingEventSummary(type, List.of(regions), List.of(), null,
                withDerivedTideFacts(window, regions));
    }

    /**
     * The window with the tide facts its regions' slots carry, as the served projection would attach
     * them (Ask reads tide from the window, never from a slot). A window that already carries facts
     * is returned unchanged.
     */
    private static BriefingWindow withDerivedTideFacts(BriefingWindow window, BriefingRegion... regions) {
        if (window == null || window.tideFacts() != null) {
            return window;
        }
        List<LocationTideFact> facts = java.util.Arrays.stream(regions)
                .flatMap(r -> r.slots().stream())
                .map(LocationTideFact::from)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (facts.isEmpty()) {
            return window;
        }
        return new BriefingWindow(window.eventTime(), window.verdict(), window.bestRating(),
                window.confidence(), window.pick(), window.badges(), window.topRarityRank(),
                window.tide(), facts);
    }

    /** An event summary with no window: events past the Plan tab's six carry none. */
    static BriefingEventSummary summaryWithoutWindow(TargetType type, BriefingRegion... regions) {
        return new BriefingEventSummary(type, List.of(regions), List.of());
    }

    static BriefingDay day(LocalDate date, BriefingEventSummary... summaries) {
        return new BriefingDay(date, List.of(summaries));
    }

    /**
     * A served briefing whose {@code renderedEvents} lists every summary that carries a window, as
     * the projector would for a forecast of six events or fewer.
     */
    static DailyBriefingResponse briefing(List<BriefingDay> days, List<HotTopic> topics) {
        List<PlanRenderedEvent> rendered = days.stream()
                .flatMap(d -> d.eventSummaries().stream()
                        .filter(s -> s.window() != null)
                        .map(s -> new PlanRenderedEvent(d.date(), s.targetType())))
                .toList();
        return briefing(days, topics, rendered);
    }

    /** A served briefing with an explicit {@code renderedEvents}, which may be null or empty. */
    static DailyBriefingResponse briefing(List<BriefingDay> days, List<HotTopic> topics,
            List<PlanRenderedEvent> rendered) {
        return new DailyBriefingResponse(GENERATED_AT, "headline", days, List.of(), null, null,
                false, false, 0, "Haiku", topics, List.of(), null, null, rendered);
    }

    static HotTopic topic(String type, String label, String detail, LocalDate date,
            List<String> regions) {
        return new HotTopic(type, label, detail, date, 1, null, regions, "description", null);
    }

    /** A sunset window at 18:00 UTC on the given date whose single region holds the slots. */
    static BriefingDay sunsetDay(LocalDate date, BriefingWindow.Pick pick,
            BriefingRegion... regions) {
        BriefingWindow window = window(date.atTime(18, 0), DisplayVerdict.WORTH_IT, 4, pick);
        return day(date, summary(TargetType.SUNSET, window, regions));
    }

    /**
     * Builds a real snapshot from a served briefing through the real builder, with nothing away,
     * an empty almanac and the clock at {@link #NOW}.
     */
    static AskSnapshot snapshotOf(DailyBriefingResponse briefing) {
        return snapshotOf(briefing, List.of());
    }

    static AskSnapshot snapshotOf(DailyBriefingResponse briefing,
            List<com.gregochr.goldenhour.model.comingup.ComingUpEntry> almanac) {
        return snapshotAt(NOW, briefing, almanac);
    }

    /** As {@link #snapshotOf} with the clock, and the solar-event "now", at {@code now} (UTC). */
    static AskSnapshot snapshotAt(LocalDateTime now, DailyBriefingResponse briefing,
            List<com.gregochr.goldenhour.model.comingup.ComingUpEntry> almanac) {
        BriefingService briefingService = Mockito.mock(BriefingService.class);
        TravelDayService travelDays = Mockito.mock(TravelDayService.class);
        AlmanacService almanacService = Mockito.mock(AlmanacService.class);
        SolarEventFreshness freshness = Mockito.mock(SolarEventFreshness.class);
        when(briefingService.getCachedBriefingForApi()).thenReturn(briefing);
        when(freshness.now()).thenReturn(now);
        when(almanacService.getFeed(AlmanacService.DEFAULT_DAYS))
                .thenReturn(new ComingUpResponse(TODAY, null, null, List.of(), new ArrayList<>(almanac)));
        return new AskSnapshotBuilder(briefingService, travelDays, almanacService, freshness,
                clockAt(now)).build().orElseThrow();
    }

    static Instant instant(LocalDateTime utc) {
        return utc.toInstant(ZoneOffset.UTC);
    }
}
