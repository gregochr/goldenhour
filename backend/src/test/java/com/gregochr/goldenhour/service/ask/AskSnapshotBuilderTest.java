package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.config.RewindAwareClock;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AlmanacEvent;
import com.gregochr.goldenhour.model.AlmanacKind;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.PlanRenderedEvent;
import com.gregochr.goldenhour.model.comingup.ComingUpEntry;
import com.gregochr.goldenhour.model.comingup.ComingUpResponse;
import com.gregochr.goldenhour.service.AlmanacService;
import com.gregochr.goldenhour.service.BriefingService;
import com.gregochr.goldenhour.service.EclipseHotTopicStrategy;
import com.gregochr.goldenhour.service.SolarEventFreshness;
import com.gregochr.goldenhour.service.TravelDayService;
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

    private MutableClock clock;
    private AskSnapshotBuilder builder;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW.toInstant(ZoneOffset.UTC));
        builder = new AskSnapshotBuilder(briefingService, travelDayService, almanacService,
                freshness, clock);
    }

    @Test
    @DisplayName("no briefing built yet gives an empty snapshot and reads nothing else")
    void build_noBriefing_isEmpty() {
        when(briefingService.getCachedBriefingForApi()).thenReturn(null);

        assertThat(builder.build()).isEmpty();
        verify(almanacService, never()).getFeed(AlmanacService.DEFAULT_DAYS);
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
    @DisplayName("runLabel is the build time in London: 05:02 UTC is 06:02 BST, 06:02 UTC is 06:02 GMT")
    void runLabel_isLondonLocal() {
        assertThat(AskSnapshotBuilder.runLabel(LocalDateTime.of(2026, 10, 5, 5, 2, 11)))
                .isEqualTo("06:02");
        assertThat(AskSnapshotBuilder.runLabel(LocalDateTime.of(2026, 12, 5, 6, 2, 11)))
                .isEqualTo("06:02");
        assertThat(AskSnapshotBuilder.runLabel(null)).isNull();
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
        verify(briefingService, times(1)).getCachedBriefingForApi();

        clock.set(NOW.toInstant(ZoneOffset.UTC).plusSeconds(30));
        Optional<AskSnapshot> third = builder.current();

        assertThat(third.orElseThrow()).isNotSameAs(first.orElseThrow());
        verify(briefingService, times(2)).getCachedBriefingForApi();
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
        verify(briefingService, times(2)).getCachedBriefingForApi();
    }

    // -- an admin's rewind -------------------------------------------------------------------

    /** A builder on the real rewind-aware clock bean, over the test's own moving clock. */
    private AskSnapshotBuilder rewindAwareBuilder() {
        return new AskSnapshotBuilder(briefingService, travelDayService, almanacService, freshness,
                new RewindAwareClock(clock));
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
        verify(briefingService, times(3)).getCachedBriefingForApi();
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

    private void stub(DailyBriefingResponse briefing) {
        when(briefingService.getCachedBriefingForApi()).thenReturn(briefing);
        when(freshness.now()).thenReturn(NOW);
        stubAlmanac(List.of());
    }

    private void stubAlmanac(List<ComingUpEntry> entries) {
        when(almanacService.getFeed(AlmanacService.DEFAULT_DAYS))
                .thenReturn(new ComingUpResponse(TODAY, null, null, List.of(), entries));
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
}
