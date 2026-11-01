package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.CoverageReason;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.LocationTideFact;
import com.gregochr.goldenhour.model.PlanRenderedEvent;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.model.comingup.ComingUpResponse;
import com.gregochr.goldenhour.repository.AstroConditionsRepository;
import com.gregochr.goldenhour.repository.AuroraForecastResultRepository;
import com.gregochr.goldenhour.service.AlmanacService;
import com.gregochr.goldenhour.service.AstroConditionsService;
import com.gregochr.goldenhour.service.aurora.AuroraForecastRunService;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
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

    /** The slot as the neighbour guard serves it when it marks it an outlier: same star, never a pick. */
    static BriefingSlot outlier(BriefingSlot base) {
        return base.withNeighbourOutlier(Boolean.TRUE);
    }

    /** A slot the briefing's own weather screen stood down, with no rating. */
    static BriefingSlot standDownSlot(Long id, String name) {
        return new BriefingSlot(id, name, NOW.plusHours(6), Verdict.STANDDOWN, null,
                BriefingSlot.TideInfo.NONE, List.of(), null, null, null, null, null,
                DisplayVerdict.STAND_DOWN, null, false, null);
    }

    /** A wood: its rating means the opposite of a sky rating. */
    static BriefingSlot wood(Long id, String name, Integer rating) {
        return slotWith(id, name, rating, true, BriefingSlot.TideInfo.NONE, null);
    }

    /** A coastal slot carrying a served tide state and the location's own preference fact. */
    static BriefingSlot coastal(Long id, String name, Integer rating, String tideState,
            boolean tideAligned) {
        return coastal(id, name, rating, tideState, tideAligned, tideAligned ? "Tide suits this spot" : "Wrong water");
    }

    /** As {@link #coastal(Long, String, Integer, String, boolean)} with the served tide-fit sentence given. */
    static BriefingSlot coastal(Long id, String name, Integer rating, String tideState,
            boolean tideAligned, String fitPhrase) {
        BriefingSlot.TideInfo tide = new BriefingSlot.TideInfo(tideState, tideAligned, null, null,
                false, false, null, null, null, null, null, null, null, null, null, null, null,
                fitPhrase, null);
        return slotWith(id, name, rating, false, tide, null);
    }

    /** A coastal slot whose tide also serves the nearest high or low water phrase. */
    static BriefingSlot coastalNearest(Long id, String name, Integer rating, String tideState,
            boolean tideAligned, String fitPhrase, String nearestTide) {
        BriefingSlot base = coastal(id, name, rating, tideState, tideAligned, fitPhrase);
        BriefingSlot.TideInfo t = base.tide();
        BriefingSlot.TideInfo withNearest = new BriefingSlot.TideInfo(t.tideState(), t.tideAligned(),
                t.nearestHighTideTime(), t.nearestHighTideHeight(), t.heightAboveP95(),
                t.heightAboveSpringThreshold(), t.lunarTideType(), t.lunarPhase(), t.moonAtPerigee(),
                t.nearestSolarOffsetMinutes(), t.nearestExtremeKind(), t.tideOnTheLight(), nearestTide,
                t.tideLevel(), t.tideDirection(), t.tideHeight(), t.tideShortfall(), t.tideFitPhrase(),
                t.tideAlignmentQuality());
        return new BriefingSlot(base.locationId(), base.locationName(), base.solarEventTime(),
                base.verdict(), base.weather(), withNearest, base.flags(), base.claudeRating(), base.skyRating(),
                null, null, null, base.displayVerdict(), base.claudeHeadline(), base.canopy(), null);
    }

    /** The slot as served after the tide strip: the same slot with no tide of its own. */
    static BriefingSlot withoutTide(BriefingSlot base) {
        return new BriefingSlot(base.locationId(), base.locationName(), base.solarEventTime(),
                base.verdict(), base.weather(), null, base.flags(), base.claudeRating(), base.skyRating(), null, null,
                null, base.displayVerdict(),
                base.claudeHeadline(), base.canopy(), null);
    }

    /** A served tide fact, as the projector would publish it. */
    static LocationTideFact fact(Long id, String name, String tideState, boolean aligned,
            String fitPhrase) {
        return new LocationTideFact(id, name, tideState, aligned, null, null, null, null, null, null,
                null, fitPhrase);
    }

    /** A served tide fact that also carries the nearest high or low water phrase. */
    static LocationTideFact factNearest(Long id, String name, String tideState, boolean aligned,
            String fitPhrase, String nearestTide) {
        return new LocationTideFact(id, name, tideState, aligned, null, null, nearestTide, null, null, null,
                null, fitPhrase);
    }

    /** The window with exactly these tide facts, replacing any it carried. */
    static BriefingWindow withFacts(BriefingWindow window, LocationTideFact... facts) {
        return new BriefingWindow(window.eventTime(), window.verdict(), window.bestRating(),
                window.confidence(), window.pick(), window.badges(), window.tide(), List.of(facts));
    }

    static BriefingSlot withHeadline(BriefingSlot base, String headline) {
        return new BriefingSlot(base.locationId(), base.locationName(), base.solarEventTime(),
                base.verdict(), base.weather(), base.tide(), base.flags(), base.claudeRating(), base.skyRating(),
                null, null, null, base.displayVerdict(),
                headline, base.canopy(), null);
    }

    /** The slot with the served Fiery Sky and Golden Hour potentials set, everything else as it was. */
    static BriefingSlot withPotentials(BriefingSlot base, Integer fiery, Integer golden) {
        return new BriefingSlot(base.locationId(), base.locationName(), base.solarEventTime(),
                base.verdict(), base.weather(), base.tide(), base.flags(), base.claudeRating(), base.skyRating(),
                fiery, golden, base.claudeSummary(), base.displayVerdict(),
                base.claudeHeadline(), base.canopy(), base.eclipse(), base.neighbourOutlier());
    }

    private static BriefingSlot slotWith(Long id, String name, Integer rating, boolean canopy,
            BriefingSlot.TideInfo tide, String headline) {
        return new BriefingSlot(id, name, NOW.plusHours(6), Verdict.GO, null, tide, List.of(), rating, rating, null,
        null, null,
                DisplayVerdict.resolve(rating, Verdict.GO), headline, canopy, null);
    }

    /** A region whose verdict sample is sufficient or not. */
    static BriefingRegion region(String name, boolean eligible, BriefingSlot... slots) {
        return new BriefingRegion(name, Verdict.GO, "summary", List.of(), List.of(slots), null,
                null, null, null, "Gloss headline", "Gloss detail", DisplayVerdict.WORTH_IT,
                slots.length, null, null, 4.0, 4, null, eligible, null);
    }

    /**
     * A region the honesty filter blanked, as the serve path hands it to Ask: no slots, a stand-down word
     * for a real stand-down only (every other reason, UNKNOWN included, is awaiting), and the filter's reason.
     */
    static BriefingRegion blankedRegion(String name, CoverageReason reason) {
        boolean weather = reason == CoverageReason.STOOD_DOWN;
        return new BriefingRegion(name, Verdict.STANDDOWN, "summary", List.of(), List.of(), null, null,
                null, null, null, "detail", weather ? DisplayVerdict.STAND_DOWN : DisplayVerdict.AWAITING, 0,
                "label", null, null, null, null, null, null, reason);
    }

    /**
     * A verdict-eligible region whose slots are given by band: {@code bands[i]} sky slots rated
     * {@code i + 1}, named {@code "<region> <rating>-<n>"} with ids counting up from {@code firstId}.
     */
    static BriefingRegion bandedRegion(String name, long firstId, int... bands) {
        return bandedRegion(name, true, firstId, bands);
    }

    /** As {@link #bandedRegion(String, long, int...)} with the sample gate's verdict given. */
    static BriefingRegion bandedRegion(String name, boolean eligible, long firstId, int... bands) {
        List<BriefingSlot> slots = new ArrayList<>();
        long id = firstId;
        for (int i = 0; i < bands.length; i++) {
            for (int n = 0; n < bands[i]; n++) {
                slots.add(slot(id++, name + " " + (i + 1) + "-" + n, i + 1));
            }
        }
        return region(name, eligible, slots.toArray(BriefingSlot[]::new));
    }

    static BriefingWindow window(LocalDateTime eventTime, DisplayVerdict verdict, Integer best,
            BriefingWindow.Pick pick) {
        return new BriefingWindow(eventTime, verdict, best, null, pick, List.of(), null);
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
                window.confidence(), window.pick(), window.badges(), window.tide(), facts);
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
                false, "Haiku", topics, List.of(), null, null, rendered);
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
                .thenReturn(new ComingUpResponse(TODAY, null, null, new ArrayList<>(almanac)));
        return new AskSnapshotBuilder(briefingService, travelDays, almanacService, freshness,
                clockAt(now), Mockito.mock(AstroConditionsRepository.class),
                Mockito.mock(AuroraForecastResultRepository.class), Mockito.mock(AstroConditionsService.class),
                Mockito.mock(AuroraForecastRunService.class), new AuroraStateCache()).build().orElseThrow();
    }

    static Instant instant(LocalDateTime utc) {
        return utc.toInstant(ZoneOffset.UTC);
    }
}
