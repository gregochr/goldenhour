package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.config.RewindAwareClock;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AlmanacEvent;
import com.gregochr.goldenhour.model.AlmanacKind;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.LocationTideFact;
import com.gregochr.goldenhour.model.PlanRenderedEvent;
import com.gregochr.goldenhour.model.comingup.ComingUpEntry;
import com.gregochr.goldenhour.model.comingup.ComingUpResponse;
import com.gregochr.goldenhour.repository.AstroConditionsRepository;
import com.gregochr.goldenhour.repository.AuroraForecastResultRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.AlmanacService;
import com.gregochr.goldenhour.service.AstroConditionsService;
import com.gregochr.goldenhour.service.BriefingService;
import com.gregochr.goldenhour.service.EclipseHotTopicStrategy;
import com.gregochr.goldenhour.service.SolarEventFreshness;
import com.gregochr.goldenhour.service.TravelDayService;
import com.gregochr.goldenhour.service.aurora.AuroraForecastRunService;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import com.gregochr.goldenhour.util.Rewind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.gregochr.goldenhour.service.ask.AskFixtures.NOW;
import static com.gregochr.goldenhour.service.ask.AskFixtures.TODAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for {@link AskSnapshotBuilder}: the window set, the joins and the memo. */
@ExtendWith(MockitoExtension.class)
class AskSnapshotBuilderTest {

    private static final LocalDate TOMORROW = TODAY.plusDays(1);

    @Mock
    private BriefingService briefingService;

    @Mock
    private TravelDayService travelDayService;

    @Mock
    private AlmanacService almanacService;

    @Mock
    private SolarEventFreshness freshness;

    @Mock
    private AstroConditionsRepository astroRepository;

    @Mock
    private AuroraForecastResultRepository auroraRepository;

    @Mock
    private AstroConditionsService astroService;

    @Mock
    private AuroraForecastRunService nightSelector;

    private final AuroraStateCache auroraStateCache = new AuroraStateCache();

    private MutableClock clock;
    private AskSnapshotBuilder builder;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW.toInstant(ZoneOffset.UTC));
        builder = new AskSnapshotBuilder(briefingService, travelDayService, almanacService,
                freshness, clock, astroRepository, auroraRepository, astroService, nightSelector,
                auroraStateCache);
    }

    @Test
    @DisplayName("no briefing built yet gives an empty snapshot and reads nothing else")
    void build_noBriefing_isEmpty() {
        when(briefingService.getCachedBriefingForApi()).thenReturn(null);

        assertThat(builder.build()).isEmpty();
        verify(almanacService, never()).getFeed(AlmanacService.DEFAULT_DAYS);
    }

    @Test
    @DisplayName("a served slot's summary, sky score and both potentials come through; a retracted slot has none")
    void build_slotCarriesTheForecastsReason() {
        BriefingSlot served = AskFixtures.slot(1L, "Bamburgh", 3).withClaudeScores(3, 4, 62, 71,
                "Thick low cloud at the western horizon.", "Heavy west");
        BriefingSlot retracted = AskFixtures.slot(2L, "Alnmouth", null);
        BriefingRegion region = AskFixtures.region("Coast", true, served, retracted);
        stub(AskFixtures.briefing(List.of(AskFixtures.sunsetDay(TODAY, null, region)), List.of()));

        List<AskSnapshot.Slot> slots = builder.build().orElseThrow().windows().getFirst().regions().getFirst().slots();

        AskSnapshot.Slot first = slots.stream().filter(s -> s.locationId() == 1L).findFirst().orElseThrow();
        assertThat(first.summary()).isEqualTo("Thick low cloud at the western horizon.");
        assertThat(first.skyRating()).isEqualTo(4);
        assertThat(first.fierySky()).isEqualTo(62);
        assertThat(first.goldenHour()).isEqualTo(71);
        AskSnapshot.Slot second = slots.stream().filter(s -> s.locationId() == 2L).findFirst().orElseThrow();
        assertThat(second.summary()).isNull();
        assertThat(second.fierySky()).isNull();
        assertThat(second.goldenHour()).isNull();
    }

    @Test
    @DisplayName("events 7-8 carry a window but are not in renderedEvents: they are not in the window set")
    void build_eventsBeyondTheRenderedSix_areExcluded() {
        // PlanWindowProjector attaches a window to EVERY summary; the six-event cap is
        // renderedEvents. Eight sunsets over eight days, the last two outside renderedEvents.
        BriefingRegion region = AskFixtures.region("Coast", true, AskFixtures.slot(1L, "A", 4));
        List<com.gregochr.goldenhour.model.BriefingDay> days = new java.util.ArrayList<>();
        List<PlanRenderedEvent> rendered = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            LocalDate date = TODAY.plusDays(i);
            BriefingWindow w = AskFixtures.window(date.atTime(18, 0), DisplayVerdict.WORTH_IT, 4, null);
            days.add(AskFixtures.day(date, AskFixtures.summary(TargetType.SUNSET, w, region)));
            assertThat(days.getLast().eventSummaries().getFirst().window()).isNotNull();
            if (i < 6) {
                rendered.add(new PlanRenderedEvent(date, TargetType.SUNSET));
            }
        }
        stub(AskFixtures.briefing(days, List.of(), rendered));

        AskSnapshot snapshot = builder.build().orElseThrow();

        assertThat(snapshot.windows()).hasSize(6);
        assertThat(snapshot.windows()).extracting(AskSnapshot.Window::id)
                .doesNotContain("2026-10-11_sunset", "2026-10-12_sunset")
                .contains("2026-10-10_sunset");
    }

    @Test
    @DisplayName("a rendered event matches on date and target type: the other event of that day is not admitted")
    void build_renderedKeyIncludesTheTargetType() {
        BriefingRegion region = AskFixtures.region("Coast", true, AskFixtures.slot(1L, "A", 4));
        BriefingWindow sunrise = AskFixtures.window(TOMORROW.atTime(5, 40), DisplayVerdict.MAYBE, 3, null);
        BriefingWindow sunset = AskFixtures.window(TOMORROW.atTime(18, 0), DisplayVerdict.MAYBE, 3, null);
        stub(AskFixtures.briefing(List.of(AskFixtures.day(TOMORROW,
                AskFixtures.summary(TargetType.SUNRISE, sunrise, region),
                AskFixtures.summary(TargetType.SUNSET, sunset, region))), List.of(),
                List.of(new PlanRenderedEvent(TOMORROW, TargetType.SUNSET))));

        assertThat(builder.build().orElseThrow().windows()).extracting(AskSnapshot.Window::id)
                .containsExactly("2026-10-06_sunset");
    }

    @Test
    @DisplayName("null or empty renderedEvents (an unprojected payload) offers no windows, never all of them")
    void build_noRenderedEvents_offersNoWindows() {
        BriefingRegion region = AskFixtures.region("Coast", true, AskFixtures.slot(1L, "A", 4));
        List<com.gregochr.goldenhour.model.BriefingDay> days =
                List.of(AskFixtures.sunsetDay(TODAY, null, region));
        stub(AskFixtures.briefing(days, List.of(), null));
        assertThat(builder.build().orElseThrow().windows()).as("null").isEmpty();

        stub(AskFixtures.briefing(days, List.of(), List.of()));
        assertThat(builder.build().orElseThrow().windows()).as("empty").isEmpty();
    }

    @Test
    @DisplayName("a summary with no window is not a window even when renderedEvents lists it")
    void build_summaryWithoutWindow_isExcluded() {
        BriefingRegion region = AskFixtures.region("Coast", true, AskFixtures.slot(1L, "A", 4));
        stub(AskFixtures.briefing(List.of(AskFixtures.day(TODAY,
                AskFixtures.summaryWithoutWindow(TargetType.SUNSET, region))), List.of(),
                List.of(new PlanRenderedEvent(TODAY, TargetType.SUNSET))));

        assertThat(builder.build().orElseThrow().windows()).isEmpty();
    }

    @Test
    @DisplayName("a window is current for 30 minutes after its time and gone one minute later")
    void build_passedWindowBoundary() {
        // PlanWindowProjector.hasPassed keeps a window for 30 minutes of afterglow.
        BriefingRegion region = AskFixtures.region("Coast", true, AskFixtures.slot(1L, "A", 4));
        LocalDateTime eventTime = NOW.minusMinutes(30);
        BriefingWindow w = AskFixtures.window(eventTime, DisplayVerdict.WORTH_IT, 4, null);
        stub(AskFixtures.briefing(List.of(AskFixtures.day(TODAY,
                AskFixtures.summary(TargetType.SUNRISE, w, region))), List.of()));

        assertThat(builder.build().orElseThrow().windows())
                .as("exactly at the afterglow limit it is still current").hasSize(1);

        when(freshness.now()).thenReturn(NOW.plusSeconds(1));
        assertThat(builder.build().orElseThrow().windows())
                .as("one second past it, it has passed").isEmpty();
    }

    @Test
    @DisplayName("a window with no time at all counts as current, as the Plan tab reads it")
    void build_nullEventTime_isCurrent() {
        BriefingRegion region = AskFixtures.region("Coast", true, AskFixtures.slot(1L, "A", 4));
        BriefingWindow w = AskFixtures.window(null, DisplayVerdict.WORTH_IT, 4, null);
        stub(AskFixtures.briefing(List.of(AskFixtures.day(TODAY,
                AskFixtures.summary(TargetType.SUNSET, w, region))), List.of()));

        assertThat(builder.build().orElseThrow().windows()).hasSize(1);
    }

    @Test
    @DisplayName("a travel day is excluded; the check runs once per date")
    void build_travelDay_isExcluded() {
        BriefingRegion region = AskFixtures.region("Coast", true, AskFixtures.slot(1L, "A", 4));
        BriefingWindow w1 = AskFixtures.window(TOMORROW.atTime(5, 30), DisplayVerdict.WORTH_IT, 4, null);
        BriefingWindow w2 = AskFixtures.window(TOMORROW.atTime(18, 0), DisplayVerdict.WORTH_IT, 4, null);
        stub(AskFixtures.briefing(List.of(
                AskFixtures.sunsetDay(TODAY, null, region),
                AskFixtures.day(TOMORROW,
                        AskFixtures.summary(TargetType.SUNRISE, w1, region),
                        AskFixtures.summary(TargetType.SUNSET, w2, region))), List.of()));
        when(travelDayService.isTravelDay(TODAY)).thenReturn(false);
        when(travelDayService.isTravelDay(TOMORROW)).thenReturn(true);

        AskSnapshot snapshot = builder.build().orElseThrow();

        assertThat(snapshot.windows()).extracting(AskSnapshot.Window::id)
                .containsExactly("2026-10-05_sunset");
        verify(travelDayService, times(1)).isTravelDay(TOMORROW);
    }

    @Test
    @DisplayName("a non-solar summary is not a window")
    void build_hourlySummary_isExcluded() {
        BriefingRegion region = AskFixtures.region("Coast", true, AskFixtures.slot(1L, "A", 4));
        BriefingWindow w = AskFixtures.window(NOW.plusHours(1), DisplayVerdict.MAYBE, 3, null);
        stub(AskFixtures.briefing(List.of(AskFixtures.day(TODAY,
                AskFixtures.summary(TargetType.HOURLY, w, region))), List.of()));

        assertThat(builder.build().orElseThrow().windows()).isEmpty();
    }

    @Test
    @DisplayName("windows carry exactly what BriefingWindow serves: verdict, best rating and pick")
    void build_windowFiguresAgreeWithServedWindow() {
        BriefingWindow.Pick pick = AskFixtures.pick(BriefingWindow.PickKind.BEST, "Coast",
                "Whitby", 7L);
        BriefingWindow served = AskFixtures.window(NOW.plusHours(6), DisplayVerdict.MAYBE, 3, pick);
        BriefingRegion region = AskFixtures.region("Coast", true, AskFixtures.slot(7L, "Whitby", 3));
        stub(AskFixtures.briefing(List.of(AskFixtures.day(TODAY,
                AskFixtures.summary(TargetType.SUNSET, served, region))), List.of()));

        AskSnapshot.Window window = builder.build().orElseThrow().windows().getFirst();

        assertThat(window.verdict()).isEqualTo(served.verdict());
        assertThat(window.bestRating()).isEqualTo(served.bestRating());
        assertThat(window.pick()).isEqualTo(served.pick());
        assertThat(window.eventTime()).isEqualTo(served.eventTime());
        assertThat(window.date()).isEqualTo(TODAY);
        assertThat(window.targetType()).isEqualTo(TargetType.SUNSET);
    }

    @Test
    @DisplayName("slots are joined from served facts, tide state and preference kept separate")
    void build_slotsAreJoinedFromServedFacts() {
        BriefingRegion region = AskFixtures.region("Coast", true,
                AskFixtures.withHeadline(AskFixtures.coastal(7L, "Whitby", 4, "HIGH", false),
                        "Clear west"),
                AskFixtures.slot(8L, "Inland", 3), AskFixtures.wood(9L, "Wood", 5));
        stub(AskFixtures.briefing(List.of(AskFixtures.sunsetDay(TODAY, null, region)), List.of()));

        AskSnapshot.Region joined = builder.build().orElseThrow().windows().getFirst()
                .regions().getFirst();

        assertThat(joined.verdictEligible()).isTrue();
        assertThat(joined.pickEligible()).isTrue();
        assertThat(joined.slots()).hasSize(3);
        AskSnapshot.Slot whitby = joined.slots().getFirst();
        assertThat(whitby.locationId()).isEqualTo(7L);
        assertThat(whitby.rating()).isEqualTo(4);
        assertThat(whitby.headline()).isEqualTo("Clear west");
        assertThat(whitby.tideState()).isEqualTo("HIGH");
        assertThat(whitby.tideAligned()).isFalse();
        assertThat(whitby.coastal()).isTrue();
        assertThat(joined.slots().get(1).coastal()).isFalse();
        assertThat(joined.slots().get(2).canopy()).isTrue();
    }

    @Test
    @DisplayName("tide comes from the window's facts when the slot carries none")
    void build_tideComesFromWindowFactsWhenSlotTideIsNull() {
        BriefingWindow window = AskFixtures.withFacts(
                AskFixtures.window(NOW.plusHours(6), DisplayVerdict.WORTH_IT, 4, null),
                AskFixtures.fact(7L, "Whitby", "HIGH", true, "Tide suits this spot"));
        BriefingRegion region = AskFixtures.region("Coast", true,
                AskFixtures.withoutTide(AskFixtures.slot(7L, "Whitby", 4)),
                AskFixtures.withoutTide(AskFixtures.slot(8L, "Inland", 3)));
        stub(AskFixtures.briefing(List.of(AskFixtures.day(TODAY,
                AskFixtures.summary(TargetType.SUNSET, window, region))), List.of()));

        List<AskSnapshot.Slot> slots = builder.build().orElseThrow().windows().getFirst()
                .regions().getFirst().slots();

        assertThat(slots.getFirst().tideState()).isEqualTo("HIGH");
        assertThat(slots.getFirst().tideAligned()).isTrue();
        assertThat(slots.getFirst().tideFitPhrase()).isEqualTo("Tide suits this spot");
        assertThat(slots.getFirst().coastal()).isTrue();
        assertThat(slots.get(1).tideState()).isNull();
        assertThat(slots.get(1).tideAligned()).isFalse();
        assertThat(slots.get(1).tideFitPhrase()).isNull();
        assertThat(slots.get(1).coastal()).isFalse();
    }

    @Test
    @DisplayName("the window's fact is what is read, even where the slot's own tide disagrees")
    void build_windowFactWinsOverSlotTide() {
        BriefingWindow window = AskFixtures.withFacts(
                AskFixtures.window(NOW.plusHours(6), DisplayVerdict.WORTH_IT, 4, null),
                AskFixtures.fact(7L, "Whitby", "LOW", true, "from the fact"));
        BriefingRegion region = AskFixtures.region("Coast", true,
                AskFixtures.coastal(7L, "Whitby", 4, "HIGH", false));
        stub(AskFixtures.briefing(List.of(AskFixtures.day(TODAY,
                AskFixtures.summary(TargetType.SUNSET, window, region))), List.of()));

        AskSnapshot.Slot slot = builder.build().orElseThrow().windows().getFirst()
                .regions().getFirst().slots().getFirst();

        assertThat(slot.tideState()).isEqualTo("LOW");
        assertThat(slot.tideAligned()).isTrue();
        assertThat(slot.tideFitPhrase()).isEqualTo("from the fact");
    }

    @Test
    @DisplayName("slot tide and window facts that agree give the same slot as before the move")
    void build_agreeingSlotTideAndFactsGiveTheSameSlot() {
        BriefingSlot coastal = AskFixtures.coastal(7L, "Whitby", 4, "HIGH", true);
        BriefingWindow window = AskFixtures.window(NOW.plusHours(6), DisplayVerdict.WORTH_IT, 4, null);
        BriefingWindow withFacts = AskFixtures.withFacts(window, LocationTideFact.from(coastal));
        stub(AskFixtures.briefing(List.of(AskFixtures.day(TODAY, AskFixtures.summary(TargetType.SUNSET,
                withFacts, AskFixtures.region("Coast", true, coastal)))), List.of()));

        AskSnapshot.Slot slot = builder.build().orElseThrow().windows().getFirst()
                .regions().getFirst().slots().getFirst();

        assertThat(slot.tideState()).isEqualTo(coastal.tide().tideState());
        assertThat(slot.tideAligned()).isEqualTo(coastal.tide().tideAligned());
        assertThat(slot.tideFitPhrase()).isEqualTo(coastal.tide().tideFitPhrase());
    }

    @Test
    @DisplayName("a fact with no slot offers no pick and does not crash")
    void build_factWithoutSlotIsHarmless() {
        BriefingWindow window = AskFixtures.withFacts(
                AskFixtures.window(NOW.plusHours(6), DisplayVerdict.WORTH_IT, 4, null),
                AskFixtures.fact(99L, "Gone", "HIGH", true, "x"));
        stub(AskFixtures.briefing(List.of(AskFixtures.day(TODAY, AskFixtures.summary(TargetType.SUNSET,
                window, AskFixtures.region("Coast", true)))), List.of()));

        AskSnapshot snapshot = builder.build().orElseThrow();

        assertThat(snapshot.windows()).hasSize(1);
        assertThat(snapshot.windows().getFirst().regions().getFirst().slots()).isEmpty();
        assertThat(snapshot.candidates()).isEmpty();
    }

    @Test
    @DisplayName("a window with no tideFacts leaves every slot non-coastal, whatever the slot carries")
    void build_windowWithoutFactsHasNoTide() {
        BriefingWindow window = AskFixtures.window(NOW.plusHours(6), DisplayVerdict.WORTH_IT, 4, null);
        BriefingRegion region = AskFixtures.region("Coast", true,
                AskFixtures.coastal(7L, "Whitby", 4, "HIGH", true));
        BriefingEventSummary summary = new BriefingEventSummary(TargetType.SUNSET, List.of(region),
                List.of(), null, window);
        stub(AskFixtures.briefing(List.of(AskFixtures.day(TODAY, summary)), List.of()));

        AskSnapshot.Slot slot = builder.build().orElseThrow().windows().getFirst()
                .regions().getFirst().slots().getFirst();

        assertThat(slot.tideState()).isNull();
        assertThat(slot.tideAligned()).isFalse();
        assertThat(slot.tideFitPhrase()).isNull();
    }

    @Test
    @DisplayName("a fact for a slot with no id is joined by name; a slot with an id joins by id")
    void build_factJoinedByNameWhenIdIsAbsent() {
        BriefingWindow window = AskFixtures.withFacts(
                AskFixtures.window(NOW.plusHours(6), DisplayVerdict.WORTH_IT, 4, null),
                AskFixtures.fact(null, "Legacy", "MID", true, "by name"),
                AskFixtures.fact(8L, "Whitby", "LOW", false, "by id"));
        BriefingRegion region = AskFixtures.region("Coast", true,
                AskFixtures.withoutTide(AskFixtures.slot(null, "Legacy", 4)),
                AskFixtures.withoutTide(AskFixtures.slot(8L, "Whitby", 4)));
        stub(AskFixtures.briefing(List.of(AskFixtures.day(TODAY,
                AskFixtures.summary(TargetType.SUNSET, window, region))), List.of()));

        List<AskSnapshot.Slot> slots = builder.build().orElseThrow().windows().getFirst()
                .regions().getFirst().slots();

        assertThat(slots.get(0).tideFitPhrase()).isEqualTo("by name");
        assertThat(slots.get(1).tideFitPhrase()).isEqualTo("by id");
    }

    @Test
    @DisplayName("nearestTide is copied verbatim from the window's fact, by id and by the name fallback; "
            + "a slot with no fact has none")
    void build_nearestTideIsCopiedFromTheFact() {
        BriefingWindow window = AskFixtures.withFacts(
                AskFixtures.window(NOW.plusHours(6), DisplayVerdict.WORTH_IT, 4, null),
                AskFixtures.factNearest(null, "Legacy", "MID", false, "by name", "LW 18:03 \u00b7 at sunset"),
                AskFixtures.factNearest(8L, "Whitby", "LOW", false, "by id", "HW 06:42 \u00b7 40m after sunrise"));
        BriefingRegion region = AskFixtures.region("Coast", true,
                AskFixtures.withoutTide(AskFixtures.slot(null, "Legacy", 4)),
                AskFixtures.withoutTide(AskFixtures.slot(8L, "Whitby", 4)),
                AskFixtures.withoutTide(AskFixtures.slot(9L, "Inland", 4)));
        stub(AskFixtures.briefing(List.of(AskFixtures.day(TODAY,
                AskFixtures.summary(TargetType.SUNSET, window, region))), List.of()));

        List<AskSnapshot.Slot> slots = builder.build().orElseThrow().windows().getFirst()
                .regions().getFirst().slots();

        assertThat(slots.get(0).nearestTide()).isEqualTo("LW 18:03 \u00b7 at sunset");
        assertThat(slots.get(1).nearestTide()).isEqualTo("HW 06:42 \u00b7 40m after sunrise");
        assertThat(slots.get(2).nearestTide()).isNull();
    }

    @Test
    @DisplayName("slots with no region are not offered: the region gate cannot apply to them")
    void build_unregionedSlotsAreIgnored() {
        BriefingEventSummary summary = new BriefingEventSummary(TargetType.SUNSET, List.of(),
                List.of(AskFixtures.slot(1L, "Orphan", 5)), null,
                AskFixtures.window(NOW.plusHours(6), DisplayVerdict.WORTH_IT, 5, null));
        stub(AskFixtures.briefing(List.of(AskFixtures.day(TODAY, summary)), List.of()));

        AskSnapshot snapshot = builder.build().orElseThrow();

        assertThat(snapshot.candidates()).isEmpty();
    }

    @Test
    @DisplayName("windows are chronological whatever order the tree arrives in")
    void build_windowsAreChronological() {
        BriefingRegion region = AskFixtures.region("Coast", true, AskFixtures.slot(1L, "A", 4));
        BriefingWindow sunrise = AskFixtures.window(TOMORROW.atTime(5, 30), DisplayVerdict.MAYBE, 3, null);
        BriefingWindow sunset = AskFixtures.window(TOMORROW.atTime(18, 0), DisplayVerdict.MAYBE, 3, null);
        stub(AskFixtures.briefing(List.of(
                AskFixtures.day(TOMORROW,
                        AskFixtures.summary(TargetType.SUNSET, sunset, region),
                        AskFixtures.summary(TargetType.SUNRISE, sunrise, region)),
                AskFixtures.sunsetDay(TODAY, null, region)), List.of()));

        assertThat(builder.build().orElseThrow().windows()).extracting(AskSnapshot.Window::id)
                .containsExactly("2026-10-05_sunset", "2026-10-06_sunrise", "2026-10-06_sunset");
    }

    @Test
    @DisplayName("the snapshot carries the label, build time and the UK civil date")
    void build_carriesLabelsAndToday() {
        BriefingRegion region = AskFixtures.region("Coast", true, AskFixtures.slot(1L, "A", 4));
        stub(AskFixtures.briefing(List.of(AskFixtures.sunsetDay(TODAY, null, region)), List.of()));

        AskSnapshot snapshot = builder.build().orElseThrow();

        assertThat(snapshot.generatedAt()).isEqualTo(AskFixtures.GENERATED_AT);
        assertThat(snapshot.runLabel()).isEqualTo("06:02");
        assertThat(snapshot.today()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("the UK date, not the UTC date, is today in the hour after UK midnight in summer")
    void build_todayIsTheUkCivilDate() {
        clock.set(LocalDateTime.of(2026, 7, 14, 23, 30).toInstant(ZoneOffset.UTC));
        stub(AskFixtures.briefing(List.of(), List.of()));

        assertThat(builder.build().orElseThrow().today()).isEqualTo(LocalDate.of(2026, 7, 15));
    }

    @Test
    @DisplayName("hot topics and the almanac are carried as served")
    void build_carriesTopicsAndAlmanac() {
        stubAlmanac(List.of(almanacEntry("SUPERMOON", "Supermoon", TOMORROW, TOMORROW, "Big moon")));
        when(briefingService.getCachedBriefingForApi()).thenReturn(AskFixtures.briefing(List.of(),
                List.of(AskFixtures.topic("AURORA", "Aurora", "Kp 6", TODAY, List.of("Coast")))));
        when(freshness.now()).thenReturn(NOW);

        AskSnapshot snapshot = builder.build().orElseThrow();

        assertThat(snapshot.hotTopics()).singleElement().satisfies(t -> {
            assertThat(t.type()).isEqualTo("AURORA");
            assertThat(t.label()).isEqualTo("Aurora");
            assertThat(t.detail()).isEqualTo("Kp 6");
            assertThat(t.date()).isEqualTo(TODAY);
            assertThat(t.regions()).containsExactly("Coast");
        });
        assertThat(snapshot.comingUp()).singleElement().satisfies(e -> {
            assertThat(e.type()).isEqualTo("SUPERMOON");
            assertThat(e.title()).isEqualTo("Supermoon");
            assertThat(e.startDate()).isEqualTo(TOMORROW);
            assertThat(e.detail()).isEqualTo("Big moon");
        });
    }

    @Test
    @DisplayName("a hot topic's safety note is carried into the snapshot; a topic without one carries none")
    void build_carriesTheSafetyNote() {
        stub(AskFixtures.briefing(List.of(), List.of(
                AskFixtures.topic("ECLIPSE", "Partial solar eclipse", "62%", TOMORROW, List.of())
                        .withSafety("Certified solar filter on the lens"),
                AskFixtures.topic("AURORA", "Aurora", "Kp 6", TODAY, List.of()))));

        AskSnapshot snapshot = builder.build().orElseThrow();

        assertThat(snapshot.hotTopics()).extracting(AskSnapshot.Topic::safetyNote)
                .containsExactly("Certified solar filter on the lens", null);
    }

    @Test
    @DisplayName("only the solar eclipse almanac entry gets the safety note; the lunar eclipse and others do not")
    void safetyNoteFor_isTheSolarEclipseOnly() {
        assertThat(AskSnapshotBuilder.safetyNoteFor("eclipse"))
                .isEqualTo(EclipseHotTopicStrategy.SAFETY_NOTE);
        assertThat(AskSnapshotBuilder.safetyNoteFor("ECLIPSE"))
                .isEqualTo(EclipseHotTopicStrategy.SAFETY_NOTE);
        assertThat(AskSnapshotBuilder.safetyNoteFor("lunar-eclipse")).isNull();
        assertThat(AskSnapshotBuilder.safetyNoteFor("supermoon")).isNull();
        assertThat(AskSnapshotBuilder.safetyNoteFor(null)).isNull();
    }

    @Test
    @DisplayName("a failing almanac degrades to no entries; windows still answer")
    void build_almanacFailure_degrades() {
        when(briefingService.getCachedBriefingForApi()).thenReturn(
                AskFixtures.briefing(List.of(), null));
        when(freshness.now()).thenReturn(NOW);
        when(almanacService.getFeed(AlmanacService.DEFAULT_DAYS))
                .thenThrow(new IllegalStateException("cold cache"));

        AskSnapshot snapshot = builder.build().orElseThrow();

        assertThat(snapshot.comingUp()).isEmpty();
        assertThat(snapshot.hotTopics()).isEmpty();
    }

    @Test
    @DisplayName("current() reuses the snapshot for 30 seconds, then rebuilds")
    void current_memoisesForThirtySeconds() {
        stub(AskFixtures.briefing(List.of(), List.of()));

        Optional<AskSnapshot> first = builder.current();
        clock.set(NOW.toInstant(ZoneOffset.UTC).plusMillis(29_999));
        Optional<AskSnapshot> second = builder.current();

        assertThat(second.orElseThrow()).isSameAs(first.orElseThrow());
        // one peek per call (the served briefing is itself memoised), one build
        verify(briefingService, times(2)).getCachedBriefingForApi();

        clock.set(NOW.toInstant(ZoneOffset.UTC).plusSeconds(30));
        Optional<AskSnapshot> third = builder.current();

        assertThat(third.orElseThrow()).isNotSameAs(first.orElseThrow());
        verify(briefingService, times(3)).getCachedBriefingForApi();
    }

    @Test
    @DisplayName("a new served briefing (a serve-epoch bump) leads to a new Ask snapshot on the next call, "
            + "inside the 30 s TTL")
    void current_servedBriefingChange_rebuildsInsideTheTtl() {
        when(freshness.now()).thenReturn(NOW);
        stubAlmanac(List.of());
        when(briefingService.getCachedBriefingForApi())
                .thenReturn(AskFixtures.briefing(List.of(), List.of()))
                .thenReturn(AskFixtures.briefing(List.of(), List.of()));

        AskSnapshot first = builder.current().orElseThrow();
        AskSnapshot second = builder.current().orElseThrow();

        assertThat(second).isNotSameAs(first);
    }

    @Test
    @DisplayName("a clock that has gone backwards is never 'younger than 30 seconds': the memo is rebuilt, "
            + "not reused, and then holds the snapshot built at the earlier time")
    void current_negativeAgeRebuilds() {
        stub(AskFixtures.briefing(List.of(), List.of()));

        Optional<AskSnapshot> first = builder.current();
        clock.set(NOW.toInstant(ZoneOffset.UTC).minus(Duration.ofHours(1)));
        Optional<AskSnapshot> second = builder.current();
        Optional<AskSnapshot> third = builder.current();

        assertThat(second.orElseThrow()).isNotSameAs(first.orElseThrow());
        assertThat(third.orElseThrow()).isSameAs(second.orElseThrow());
        verify(briefingService, times(3)).getCachedBriefingForApi();
    }

    // -- an admin's rewind -------------------------------------------------------------------

    /** A builder on the real rewind-aware clock bean, over the test's own moving clock. */
    private AskSnapshotBuilder rewindAwareBuilder() {
        return new AskSnapshotBuilder(briefingService, travelDayService, almanacService, freshness,
                new RewindAwareClock(clock), astroRepository, auroraRepository, astroService, nightSelector,
                auroraStateCache);
    }

    @Test
    @DisplayName("while a rewind is active the memo is neither read nor written: the request gets a snapshot "
            + "built for the rewound moment, the memo still holds the live one, and a second rewound "
            + "request builds again")
    void current_rewoundRequestBypassesTheMemo() {
        stub(AskFixtures.briefing(List.of(), List.of()));
        AskSnapshotBuilder rewound = rewindAwareBuilder();
        Optional<AskSnapshot> live = rewound.current();

        Instant twoDaysBack = NOW.toInstant(ZoneOffset.UTC).minus(Duration.ofDays(2));
        Optional<AskSnapshot> first;
        Optional<AskSnapshot> second;
        Rewind.set(twoDaysBack);
        try {
            first = rewound.current();
            second = rewound.current();
        } finally {
            Rewind.clear();
        }
        Optional<AskSnapshot> after = rewound.current();

        assertThat(live.orElseThrow().today()).isEqualTo(TODAY);
        assertThat(first.orElseThrow().today()).isEqualTo(TODAY.minusDays(2));
        assertThat(second.orElseThrow()).isNotSameAs(first.orElseThrow());
        assertThat(after.orElseThrow()).isSameAs(live.orElseThrow());
        verify(briefingService, times(4)).getCachedBriefingForApi();
    }

    @Test
    @DisplayName("a rewound request an hour back does not reuse the live memo even though its age is negative "
            + "and it was built moments ago")
    void current_rewoundAnHourBackDoesNotReuseTheLiveMemo() {
        stub(AskFixtures.briefing(List.of(), List.of()));
        AskSnapshotBuilder rewound = rewindAwareBuilder();
        AskSnapshot live = rewound.current().orElseThrow();

        Rewind.set(NOW.toInstant(ZoneOffset.UTC).minus(Duration.ofHours(1)));
        try {
            assertThat(rewound.current().orElseThrow()).isNotSameAs(live);
        } finally {
            Rewind.clear();
        }

        assertThat(rewound.current().orElseThrow()).isSameAs(live);
    }

    @Test
    @DisplayName("an empty result is not memoised: the next call asks again")
    void current_doesNotMemoiseAbsence() {
        when(briefingService.getCachedBriefingForApi()).thenReturn(null);

        assertThat(builder.current()).isEmpty();
        assertThat(builder.current()).isEmpty();

        verify(briefingService, times(2)).getCachedBriefingForApi();
    }

    // -- named areas and location aliases ----------------------------------------------------

    private AskSnapshotBuilder builderWith(AskAreas areas) {
        return new AskSnapshotBuilder(briefingService, travelDayService, almanacService, freshness, clock,
                astroRepository, auroraRepository, astroService, nightSelector, auroraStateCache, areas);
    }

    @Test
    @DisplayName("the areas are read once per build, and a memoised current() does not read them again")
    void build_readsTheAreasOncePerBuild() {
        stub(AskFixtures.briefing(List.of(), List.of()));
        AskAreas areas = org.mockito.Mockito.mock(AskAreas.class);
        AskSnapshot.Area wall = new AskSnapshot.Area("Hadrian's Wall", List.of("Roman Wall"), java.util.Set.of(1L));
        when(areas.load()).thenReturn(new AskAreas.Names(List.of(wall), Map.of(5L, List.of("The Heugh"))));
        AskSnapshotBuilder withAreas = builderWith(areas);

        AskSnapshot built = withAreas.build().orElseThrow();
        withAreas.current();
        withAreas.current();

        assertThat(built.areas()).containsExactly(wall);
        assertThat(built.locationAliases()).containsEntry(5L, List.of("The Heugh"));
        verify(areas, times(2)).load();
    }

    @Test
    @DisplayName("an AskAreas that throws gives empty areas and aliases, and the snapshot is still built")
    void build_aThrowingAreaReaderStillBuilds() {
        stub(AskFixtures.briefing(List.of(), List.of()));
        AskAreas areas = org.mockito.Mockito.mock(AskAreas.class);
        when(areas.load()).thenThrow(new IllegalStateException("database is down"));

        AskSnapshot built = builderWith(areas).build().orElseThrow();

        assertThat(built.areas()).isEmpty();
        assertThat(built.locationAliases()).isEmpty();
        assertThat(built.today()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("the ten-argument builder, and a reader that returns null, read as no areas and no aliases")
    void build_noAreasByDefault() {
        stub(AskFixtures.briefing(List.of(), List.of()));
        AskAreas nothing = () -> null;

        assertThat(builder.build().orElseThrow().areas()).isEmpty();
        assertThat(builderWith(nothing).build().orElseThrow().locationAliases()).isEmpty();
    }

    // -- later windows (ask-place-tide plan T2-1, T2-2) --------------------------------------------

    private static final String HW_PHRASE = "HW 06:42 · 40m after sunrise";

    private static LocationEntity place(long id, String name, String region, boolean enabled) {
        return LocationEntity.builder().id(id).name(name).enabled(enabled)
                .region(region == null ? null : RegionEntity.builder().name(region).build()).build();
    }

    private AskSnapshotBuilder builderWithRoster(LocationRepository roster) {
        return new AskSnapshotBuilder(briefingService, travelDayService, almanacService, freshness, clock,
                astroRepository, auroraRepository, astroService, nightSelector, auroraStateCache,
                () -> AskAreas.Names.NONE, roster);
    }

    private static BriefingEventSummary blankedWithFacts(TargetType type, LocalDateTime eventTime,
            LocationTideFact... facts) {
        BriefingWindow window = AskFixtures.withFacts(
                AskFixtures.window(eventTime, DisplayVerdict.WORTH_IT, 4, null), facts);
        return AskFixtures.summary(type, window, AskFixtures.blankedRegion("Coast",
                com.gregochr.goldenhour.model.CoverageReason.UNKNOWN));
    }

    private LocationRepository roster(LocationEntity... places) {
        LocationRepository repository = org.mockito.Mockito.mock(LocationRepository.class);
        when(repository.findEnabledFetchingRegion()).thenReturn(List.of(places));
        return repository;
    }

    @Test
    @DisplayName("an unrendered window whose slots are empty but whose facts are set becomes a later window, one "
            + "tide per fact, with the region from the roster and the nearest tide; windows() is unchanged")
    void build_laterWindowComesFromTheFacts() {
        LocalDate later = TODAY.plusDays(3);
        BriefingRegion region = AskFixtures.region("Coast", true, AskFixtures.slot(1L, "A", 4));
        List<com.gregochr.goldenhour.model.BriefingDay> days = List.of(
                AskFixtures.sunsetDay(TODAY, null, region),
                AskFixtures.day(later, blankedWithFacts(TargetType.SUNSET, later.atTime(18, 0),
                        AskFixtures.factNearest(3L, "Bamburgh", "MID", false, "wants low water", HW_PHRASE),
                        AskFixtures.fact(4L, "Seahouses", "HIGH", true, "high water"),
                        AskFixtures.fact(null, "No id", "HIGH", true, "skipped"),
                        AskFixtures.fact(99L, "Unknown", "HIGH", true, "skipped"),
                        AskFixtures.fact(5L, "Disabled", "HIGH", true, "skipped"),
                        AskFixtures.fact(6L, "Regionless", "HIGH", true, "skipped"))));
        stub(AskFixtures.briefing(days, List.of(), List.of(new PlanRenderedEvent(TODAY, TargetType.SUNSET))));
        AskSnapshotBuilder withRoster = builderWithRoster(roster(place(3L, "Bamburgh", "Coast", true),
                place(4L, "Seahouses", "Coast", true), place(5L, "Disabled", "Coast", false),
                place(6L, "Regionless", null, true)));

        AskSnapshot snapshot = withRoster.build().orElseThrow();

        assertThat(snapshot.windows()).extracting(AskSnapshot.Window::id).containsExactly("2026-10-05_sunset");
        assertThat(snapshot.laterWindows()).hasSize(1);
        AskSnapshot.LaterWindow window = snapshot.laterWindows().getFirst();
        assertThat(window.id()).isEqualTo("2026-10-08_sunset");
        assertThat(window.date()).isEqualTo(later);
        assertThat(window.targetType()).isEqualTo(TargetType.SUNSET);
        assertThat(window.tides()).containsExactly(
                new AskSnapshot.LaterTide(3L, "Bamburgh", "Coast", "MID", true, false, "wants low water", HW_PHRASE),
                new AskSnapshot.LaterTide(4L, "Seahouses", "Coast", "HIGH", true, true, "high water", null));
        assertThat(snapshot.window("2026-10-08_sunset")).isEmpty();
        assertThat(snapshot.laterWindow("2026-10-08_sunset")).contains(window);
    }

    @Test
    @DisplayName("a travel day inside the rendered six becomes a later window and is still not in windows()")
    void build_travelDayIsALaterWindow() {
        BriefingEventSummary away = blankedWithFacts(TargetType.SUNRISE, TOMORROW.atTime(5, 40),
                AskFixtures.fact(3L, "Bamburgh", "HIGH", true, "high water"));
        stub(AskFixtures.briefing(List.of(AskFixtures.day(TOMORROW, away)), List.of(),
                List.of(new PlanRenderedEvent(TOMORROW, TargetType.SUNRISE))));
        when(travelDayService.isTravelDay(TOMORROW)).thenReturn(true);

        AskSnapshot snapshot = builderWithRoster(roster(place(3L, "Bamburgh", "Coast", true)))
                .build().orElseThrow();

        assertThat(snapshot.windows()).isEmpty();
        assertThat(snapshot.laterWindows()).extracting(AskSnapshot.LaterWindow::id)
                .containsExactly("2026-10-06_sunrise");
        verify(travelDayService, times(1)).isTravelDay(TOMORROW);
    }

    @Test
    @DisplayName("a passed window, a non-solar one and one with no tide facts give no later window")
    void build_noLaterWindowForPassedNonSolarOrFactless() {
        LocalDate later = TODAY.plusDays(2);
        LocationTideFact fact = AskFixtures.fact(3L, "Bamburgh", "HIGH", true, "high water");
        BriefingEventSummary passed = blankedWithFacts(TargetType.SUNRISE, NOW.minusHours(2), fact);
        BriefingEventSummary hourly = blankedWithFacts(TargetType.HOURLY, later.atTime(12, 0), fact);
        BriefingEventSummary noFacts = AskFixtures.summary(TargetType.SUNSET,
                AskFixtures.window(later.atTime(18, 0), DisplayVerdict.WORTH_IT, 4, null),
                AskFixtures.blankedRegion("Coast", com.gregochr.goldenhour.model.CoverageReason.UNKNOWN));
        stub(AskFixtures.briefing(List.of(AskFixtures.day(TODAY, passed), AskFixtures.day(later, hourly, noFacts)),
                List.of(), List.of()));

        AskSnapshot snapshot = builderWithRoster(roster(place(3L, "Bamburgh", "Coast", true)))
                .build().orElseThrow();

        assertThat(snapshot.laterWindows()).isEmpty();
    }

    @Test
    @DisplayName("a window whose facts all fail the roster is no later window")
    void build_noLaterWindowWhenNoFactResolves() {
        LocalDate later = TODAY.plusDays(3);
        stub(AskFixtures.briefing(List.of(AskFixtures.day(later, blankedWithFacts(TargetType.SUNSET,
                later.atTime(18, 0), AskFixtures.fact(null, "No id", "HIGH", true, "x"),
                AskFixtures.fact(99L, "Unknown", "HIGH", true, "x")))), List.of(), List.of()));

        assertThat(builderWithRoster(roster()).build().orElseThrow().laterWindows()).isEmpty();
    }

    @Test
    @DisplayName("a roster read that throws gives a snapshot with no later windows, never no snapshot")
    void build_rosterFailureIsFailSoft() {
        LocalDate later = TODAY.plusDays(3);
        BriefingRegion region = AskFixtures.region("Coast", true, AskFixtures.slot(1L, "A", 4));
        stub(AskFixtures.briefing(List.of(AskFixtures.sunsetDay(TODAY, null, region),
                AskFixtures.day(later, blankedWithFacts(TargetType.SUNSET, later.atTime(18, 0),
                        AskFixtures.fact(3L, "Bamburgh", "HIGH", true, "high water")))), List.of(),
                List.of(new PlanRenderedEvent(TODAY, TargetType.SUNSET))));
        LocationRepository broken = org.mockito.Mockito.mock(LocationRepository.class);
        when(broken.findEnabledFetchingRegion()).thenThrow(new IllegalStateException("db down"));

        AskSnapshot snapshot = builderWithRoster(broken).build().orElseThrow();

        assertThat(snapshot.laterWindows()).isEmpty();
        assertThat(snapshot.windows()).hasSize(1);
    }

    @Test
    @DisplayName("the roster is read once per build, however many later windows there are")
    void build_rosterIsReadOncePerBuild() {
        LocalDate d3 = TODAY.plusDays(3);
        LocalDate d4 = TODAY.plusDays(4);
        LocationTideFact fact = AskFixtures.fact(3L, "Bamburgh", "HIGH", true, "high water");
        stub(AskFixtures.briefing(List.of(
                AskFixtures.day(d3, blankedWithFacts(TargetType.SUNRISE, d3.atTime(5, 40), fact),
                        blankedWithFacts(TargetType.SUNSET, d3.atTime(18, 0), fact)),
                AskFixtures.day(d4, blankedWithFacts(TargetType.SUNRISE, d4.atTime(5, 40), fact))),
                List.of(), List.of()));
        LocationRepository repository = roster(place(3L, "Bamburgh", "Coast", true));

        AskSnapshot snapshot = builderWithRoster(repository).build().orElseThrow();

        assertThat(snapshot.laterWindows()).extracting(AskSnapshot.LaterWindow::id)
                .containsExactly("2026-10-08_sunrise", "2026-10-08_sunset", "2026-10-09_sunrise");
        verify(repository, times(1)).findEnabledFetchingRegion();
    }

    @Test
    @DisplayName("withNames carries the later windows through")
    void withNamesKeepsTheLaterWindows() {
        AskSnapshot.LaterWindow later = new AskSnapshot.LaterWindow("2026-10-08_sunset", TODAY.plusDays(3),
                TargetType.SUNSET, TODAY.plusDays(3).atTime(18, 0), List.of());
        AskSnapshot base = new AskSnapshot(null, null, TODAY, List.of(), List.of(), List.of(), false,
                AskSnapshot.Nights.NONE, List.of(), Map.of(), List.of(later));

        AskSnapshot renamed = base.withNames(List.of(), Map.of(1L, List.of("The Heugh")));

        assertThat(renamed.laterWindows()).containsExactly(later);
        assertThat(renamed.locationAliases()).containsKey(1L);
    }

    private void stub(DailyBriefingResponse briefing) {
        when(briefingService.getCachedBriefingForApi()).thenReturn(briefing);
        when(freshness.now()).thenReturn(NOW);
        stubAlmanac(List.of());
    }

    private void stubAlmanac(List<ComingUpEntry> entries) {
        when(almanacService.getFeed(AlmanacService.DEFAULT_DAYS))
                .thenReturn(new ComingUpResponse(TODAY, null, null, entries));
    }

    static ComingUpEntry almanacEntry(String type, String title, LocalDate start, LocalDate end,
            String detail) {
        return ComingUpEntry.from(new AlmanacEvent(start, end, AlmanacKind.ALMANAC, type, title,
                detail, Map.of(), List.of()), TODAY);
    }

    /** A clock whose instant a test moves. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            this.now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    @DisplayName("the filter's coverage reason is copied onto the snapshot's region, and a region with none "
            + "carries none")
    void copiesTheCoverageReason() {
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(List.of(AskFixtures.sunsetDay(TODAY, null,
                AskFixtures.blankedRegion("Coast", com.gregochr.goldenhour.model.CoverageReason.STOOD_DOWN),
                AskFixtures.blankedRegion("Hills", com.gregochr.goldenhour.model.CoverageReason.UNKNOWN),
                AskFixtures.region("Dales", true, AskFixtures.slot(1L, "A", 4)))), List.of()));

        assertThat(snapshot.windows().getFirst().regions()).extracting(AskSnapshot.Region::coverageReason)
                .containsExactly(com.gregochr.goldenhour.model.CoverageReason.STOOD_DOWN,
                        com.gregochr.goldenhour.model.CoverageReason.UNKNOWN, null);
    }
}
