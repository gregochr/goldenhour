package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingDigestResponse;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.Confidence;
import com.gregochr.goldenhour.model.DailyBriefingResponse;
import com.gregochr.goldenhour.model.DisplayVerdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BriefingDigestService}.
 *
 * <p>The clock is fixed through a stubbed {@link SolarEventFreshness} rather than left to the wall
 * clock, so the afterglow boundary below is a real boundary on every day of the year.
 */
@ExtendWith(MockitoExtension.class)
class BriefingDigestServiceTest {

    private static final LocalDate DAY_ONE = LocalDate.of(2026, 3, 25);
    private static final LocalDate DAY_TWO = LocalDate.of(2026, 3, 26);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 25, 12, 0);

    @Mock
    private BriefingService briefingService;

    @Mock
    private SolarEventFreshness freshness;

    private BriefingDigestService service;

    @BeforeEach
    void setUp() {
        service = new BriefingDigestService(briefingService, freshness);
    }

    @Test
    @DisplayName("returns null when no briefing has been generated")
    void digest_noBriefing_returnsNull() {
        when(briefingService.getCachedBriefingForApi()).thenReturn(null);

        assertThat(service.digest(BriefingDigestService.DEFAULT_LIMIT)).isNull();
    }

    @Test
    @DisplayName("copies the window's own published figures rather than deriving them")
    void digest_copiesPublishedFigures() {
        BriefingWindow.Pick pick = new BriefingWindow.Pick(BriefingWindow.PickKind.BEST,
                "Lake District", "A long clear run to the west", "Detail", 4.25, "Keswick", 7L);
        BriefingWindow window = new BriefingWindow(LocalDateTime.of(2026, 3, 25, 18, 30),
                DisplayVerdict.WORTH_IT, 4, Confidence.HIGH, pick, List.of(), null, null);
        stubBriefing(day(DAY_ONE, summary(TargetType.SUNSET, window)));

        BriefingDigestResponse.Window only = service.digest(6).windows().getFirst();

        assertThat(only.date()).isEqualTo(DAY_ONE);
        assertThat(only.event()).isEqualTo(TargetType.SUNSET);
        assertThat(only.eventTime()).isEqualTo(LocalDateTime.of(2026, 3, 25, 18, 30));
        assertThat(only.verdict()).isEqualTo(DisplayVerdict.WORTH_IT);
        assertThat(only.bestRating()).isEqualTo(4);
        assertThat(only.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(only.pick()).isEqualTo(BriefingWindow.PickKind.BEST);
        assertThat(only.headline()).isEqualTo("A long clear run to the west");
        assertThat(only.regionName()).isEqualTo("Lake District");
        assertThat(only.locationName()).isEqualTo("Keswick");
    }

    @Test
    @DisplayName("publishes the briefing's own generatedAt so a client can show the forecast's age")
    void digest_publishesGeneratedAt() {
        stubBriefing(day(DAY_ONE, summary(TargetType.SUNSET, windowAt(DAY_ONE, 18, 30))));

        assertThat(service.digest(6).generatedAt()).isEqualTo(LocalDateTime.of(2026, 3, 25, 9, 0));
    }

    @Test
    @DisplayName("a window with no pick carries four nulls, never a partial narrative")
    void digest_noPick_nullsTheWholeNarrative() {
        stubBriefing(day(DAY_ONE, summary(TargetType.SUNSET, windowAt(DAY_ONE, 18, 30))));

        BriefingDigestResponse.Window only = service.digest(6).windows().getFirst();

        assertThat(only.pick()).isNull();
        assertThat(only.headline()).isNull();
        assertThat(only.regionName()).isNull();
        assertThat(only.locationName()).isNull();
    }

    @Test
    @DisplayName("orders by date then sunrise before sunset, whatever order the tree held them in")
    void digest_ordersChronologically() {
        stubBriefing(
                day(DAY_TWO, summary(TargetType.SUNSET, windowAt(DAY_TWO, 19, 0)),
                        summary(TargetType.SUNRISE, windowAt(DAY_TWO, 6, 0))),
                day(DAY_ONE, summary(TargetType.SUNSET, windowAt(DAY_ONE, 18, 30))));

        List<BriefingDigestResponse.Window> windows = service.digest(6).windows();

        assertThat(windows).extracting(BriefingDigestResponse.Window::date,
                        BriefingDigestResponse.Window::event)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(DAY_ONE, TargetType.SUNSET),
                        org.assertj.core.groups.Tuple.tuple(DAY_TWO, TargetType.SUNRISE),
                        org.assertj.core.groups.Tuple.tuple(DAY_TWO, TargetType.SUNSET));
    }

    @Test
    @DisplayName("a window exactly at the end of its afterglow is still current")
    void digest_afterglowBoundary_stillCurrent() {
        // NOW is 12:00 and the afterglow is 30 minutes, so 11:30 + 30 == 12:00, which is not before.
        stubBriefing(day(DAY_ONE, summary(TargetType.SUNSET, windowAt(DAY_ONE, 11, 30))));

        assertThat(service.digest(6).windows()).hasSize(1);
    }

    @Test
    @DisplayName("a window one minute past its afterglow has gone")
    void digest_afterglowBoundary_oneMinutePast_dropped() {
        stubBriefing(day(DAY_ONE, summary(TargetType.SUNSET, windowAt(DAY_ONE, 11, 29))));

        assertThat(service.digest(6).windows()).isEmpty();
    }

    @Test
    @DisplayName("a window with no event time counts as current, never as past")
    void digest_nullEventTime_kept() {
        BriefingWindow timeless = new BriefingWindow(null, DisplayVerdict.AWAITING, null, null,
                null, List.of(), null, null);
        stubBriefing(day(DAY_ONE, summary(TargetType.SUNSET, timeless)));

        assertThat(service.digest(6).windows()).hasSize(1);
        assertThat(service.digest(6).windows().getFirst().eventTime()).isNull();
    }

    @Test
    @DisplayName("summaries with no window, and hourly summaries, are not windows")
    void digest_dropsNonWindows() {
        stubBriefing(day(DAY_ONE,
                new BriefingEventSummary(TargetType.SUNRISE, List.of(), List.of()),
                summary(TargetType.HOURLY, windowAt(DAY_ONE, 13, 0)),
                summary(TargetType.SUNSET, windowAt(DAY_ONE, 18, 30))));

        assertThat(service.digest(6).windows())
                .extracting(BriefingDigestResponse.Window::event)
                .containsExactly(TargetType.SUNSET);
    }

    @Test
    @DisplayName("limit truncates to the earliest windows, keeping timeline order")
    void digest_limitTruncatesToEarliest() {
        stubBriefing(
                day(DAY_ONE, summary(TargetType.SUNSET, windowAt(DAY_ONE, 18, 30))),
                day(DAY_TWO, summary(TargetType.SUNRISE, windowAt(DAY_TWO, 6, 0)),
                        summary(TargetType.SUNSET, windowAt(DAY_TWO, 19, 0))));

        assertThat(service.digest(2).windows())
                .extracting(BriefingDigestResponse.Window::date)
                .containsExactly(DAY_ONE, DAY_TWO);
    }

    @Test
    @DisplayName("a limit below one is clamped up rather than returning nothing")
    void digest_limitBelowOne_clampedToOne() {
        stubBriefing(day(DAY_ONE, summary(TargetType.SUNSET, windowAt(DAY_ONE, 18, 30))),
                day(DAY_TWO, summary(TargetType.SUNRISE, windowAt(DAY_TWO, 6, 30))));

        assertThat(service.digest(0).windows()).hasSize(1);
        assertThat(service.digest(-5).windows()).hasSize(1);
    }

    @Test
    @DisplayName("a limit above the maximum is clamped down")
    void digest_limitAboveMax_clampedToMax() {
        BriefingDay[] days = new BriefingDay[BriefingDigestService.MAX_LIMIT + 2];
        for (int i = 0; i < days.length; i++) {
            days[i] = day(DAY_ONE.plusDays(i),
                    summary(TargetType.SUNSET, windowAt(DAY_ONE.plusDays(i), 18, 30)));
        }
        stubBriefing(days);

        assertThat(service.digest(BriefingDigestService.MAX_LIMIT + 1).windows())
                .hasSize(BriefingDigestService.MAX_LIMIT);
    }

    @Test
    @DisplayName("a day with no date cannot be placed on the timeline and is skipped")
    void digest_dayWithNoDate_skipped() {
        stubBriefing(new BriefingDay(null,
                        List.of(summary(TargetType.SUNSET, windowAt(DAY_ONE, 18, 30)))),
                day(DAY_TWO, summary(TargetType.SUNRISE, windowAt(DAY_TWO, 6, 30))));

        assertThat(service.digest(6).windows())
                .extracting(BriefingDigestResponse.Window::event)
                .containsExactly(TargetType.SUNRISE);
    }

    @Test
    @DisplayName("⚠️ retires a window exactly when PlanWindowProjector says it has, at every offset")
    void digest_sharesTheProjectorsElapsedRule() {
        // The property the `hasPassed` extraction exists to create, and the one thing no other test
        // here can see: inline a private 30-minute copy into BriefingDigestService and every other
        // test in this file still passes, because they all hardcode the same 30 minutes.
        //
        // This drives the expectation from `hasPassed` ITSELF, so the two move together. Scope,
        // stated rather than overclaimed: it catches the named failure — someone tunes
        // AFTERGLOW_MINUTES, and a digest carrying its own copy keeps the old one — but it cannot
        // catch a copy that happens to agree today. That is the same bargain
        // `TideSurfaceAgreementTest` strikes, and for the same reason: one input, two surfaces,
        // neither fixture able to pre-satisfy its own predicate.
        for (int minutesBack = 0; minutesBack <= 60; minutesBack += 5) {
            LocalDateTime eventTime = NOW.minusMinutes(minutesBack);
            BriefingWindow window = new BriefingWindow(eventTime, DisplayVerdict.MAYBE, 3,
                    Confidence.MEDIUM, null, List.of(), null, null);
            stubBriefing(day(DAY_ONE, summary(TargetType.SUNSET, window)));

            assertThat(service.digest(6).windows())
                    .as("%d minutes past the event, the digest and the Plan tab must agree",
                            minutesBack)
                    .hasSize(PlanWindowProjector.hasPassed(eventTime, NOW) ? 0 : 1);
        }
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────────────────

    /**
     * Stubs the served briefing AND the clock.
     *
     * <p>The clock stub lives here rather than in {@code @BeforeEach} because
     * {@code digest_noBriefing_returnsNull} returns before ever reading it, and under
     * {@code MockitoExtension}'s strict stubs an unnecessary stub is an error rather than a silent
     * one — which is the point: a refactor that stopped consulting the clock would otherwise leave
     * this standing and the class would quietly stop testing retirement at all.
     */
    private void stubBriefing(BriefingDay... days) {
        when(freshness.now()).thenReturn(NOW);
        when(briefingService.getCachedBriefingForApi()).thenReturn(new DailyBriefingResponse(
                LocalDateTime.of(2026, 3, 25, 9, 0), "headline", List.of(days), List.of(),
                null, null, false, false, 0, "Opus", List.of(), List.of()));
    }

    private static BriefingDay day(LocalDate date, BriefingEventSummary... summaries) {
        return new BriefingDay(date, List.of(summaries));
    }

    private static BriefingEventSummary summary(TargetType type, BriefingWindow window) {
        return new BriefingEventSummary(type, List.of(), List.of()).withWindow(window);
    }

    /**
     * A window whose event time sits on its own day — the only shape production builds.
     *
     * <p>The date is a parameter rather than a constant because the first cut of this fixture
     * hardcoded it, which put a day-two sunrise at a day-one 06:00 and silently retired it against
     * a NOON clock. The digest orders on the DAY's date and retires on the WINDOW's time, so a
     * fixture that lets those disagree tests neither rule.
     */
    private static BriefingWindow windowAt(LocalDate date, int hour, int minute) {
        return new BriefingWindow(date.atTime(hour, minute),
                DisplayVerdict.MAYBE, 3, Confidence.MEDIUM, null, List.of(), null, null);
    }
}
