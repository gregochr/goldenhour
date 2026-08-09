package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.model.AlmanacEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the three ephemeris sources whose defining behaviour is that they report
 * <em>every</em> occurrence in a range, not the first one.
 */
@ExtendWith(MockitoExtension.class)
class AlmanacEphemerisSourcesTest {

    @Mock
    private LunarPhaseService lunarPhaseService;

    @Mock
    private NlcClarityService nlcClarityService;

    @Nested
    @DisplayName("MeteorAlmanacSource")
    class Meteor {

        @Test
        @DisplayName("reports every shower peaking in the range, where the hot-topic strategy "
                + "reports only the first")
        void reportsEveryShowerNotJustTheFirst() {
            when(lunarPhaseService.getIlluminationFraction(any())).thenReturn(0.1);

            // 1 Aug to 31 Dec covers Perseids (12 Aug), Orionids (21 Oct) and Geminids (14 Dec).
            List<AlmanacEvent> events = new MeteorAlmanacSource(lunarPhaseService)
                    .events(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 12, 31));

            assertThat(events).extracting(AlmanacEvent::title)
                    .containsExactlyInAnyOrder("Perseids", "Orionids", "Geminids");
        }

        @Test
        @DisplayName("a moonlit peak is still reported, with the illumination as a caveat — an "
                + "almanac states when the shower is, not whether tonight is worth it")
        void aWashedOutShowerIsKeptAndFlagged() {
            when(lunarPhaseService.getIlluminationFraction(any())).thenReturn(0.92);

            AlmanacEvent perseids = new MeteorAlmanacSource(lunarPhaseService)
                    .events(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 14)).getFirst();

            assertThat(perseids.title()).isEqualTo("Perseids");
            assertThat(perseids.meta()).containsEntry("washedOut", "true");
            assertThat(perseids.meta()).containsEntry("moonIllumination", "92%");
            assertThat(perseids.detail()).contains("92% lit");
        }

        @Test
        @DisplayName("a dark peak carries no washedOut key at all rather than washedOut=false")
        void aDarkPeakOmitsTheFlag() {
            when(lunarPhaseService.getIlluminationFraction(any())).thenReturn(0.05);

            AlmanacEvent perseids = new MeteorAlmanacSource(lunarPhaseService)
                    .events(LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 14)).getFirst();

            assertThat(perseids.meta()).doesNotContainKey("washedOut");
            assertThat(perseids.detail()).doesNotContain("lit");
        }

        @Test
        @DisplayName("a range straddling new year picks up both December and January showers")
        void straddlesTheYearBoundary() {
            when(lunarPhaseService.getIlluminationFraction(any())).thenReturn(0.1);

            List<AlmanacEvent> events = new MeteorAlmanacSource(lunarPhaseService)
                    .events(LocalDate.of(2026, 12, 10), LocalDate.of(2027, 1, 10));

            assertThat(events).extracting(AlmanacEvent::title)
                    .containsExactlyInAnyOrder("Geminids", "Quadrantids");
        }

        @Test
        @DisplayName("catalogue facts are carried through from the shared shower table")
        void carriesCatalogueFacts() {
            when(lunarPhaseService.getIlluminationFraction(any())).thenReturn(0.1);

            AlmanacEvent geminids = new MeteorAlmanacSource(lunarPhaseService)
                    .events(LocalDate.of(2026, 12, 13), LocalDate.of(2026, 12, 15)).getFirst();

            assertThat(geminids.meta())
                    .containsEntry("zhr", "150")
                    .containsEntry("radiant", "E")
                    .containsEntry("bestHours", "22:00–02:00");
            assertThat(geminids.dayCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("a quiet range yields nothing")
        void aQuietRangeIsEmpty() {
            assertThat(new MeteorAlmanacSource(lunarPhaseService)
                    .events(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30))).isEmpty();
        }
    }

    @Nested
    @DisplayName("SupermoonAlmanacSource")
    class Supermoon {

        private final LocalDate start = LocalDate.of(2026, 9, 1);

        /** Full-moon-at-perigee on the given offsets from start, over a span of days. */
        private void supermoonOn(int span, int... offsets) {
            // -1 and span are probed by the outward walk that finds a span's true first and last
            // night; unstubbed they would be a strict-stubbing miss rather than "not a supermoon".
            for (int i = -1; i <= span; i++) {
                LocalDate date = start.plusDays(i);
                boolean hit = false;
                for (int offset : offsets) {
                    if (offset == i) {
                        hit = true;
                        break;
                    }
                }
                lenient().when(lunarPhaseService.isFullMoon(date)).thenReturn(hit);
                lenient().when(lunarPhaseService.daysFromNearestPerigee(date))
                        .thenReturn(hit ? 1.0 : 99.0);
            }
        }

        @Test
        @DisplayName("adjacent qualifying nights collapse into one supermoon, not three")
        void adjacentNightsBecomeOneEvent() {
            supermoonOn(5, 1, 2, 3);

            List<AlmanacEvent> events =
                    new SupermoonAlmanacSource(lunarPhaseService).events(start, start.plusDays(4));

            assertThat(events).hasSize(1);
            assertThat(events.getFirst().startDate()).isEqualTo(start.plusDays(1));
            assertThat(events.getFirst().endDate()).isEqualTo(start.plusDays(3));
            assertThat(events.getFirst().dayCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("two separated supermoons are reported separately, where the hot-topic "
                + "strategy returns on the first")
        void reportsEverySupermoonInTheRange() {
            supermoonOn(6, 0, 4);

            List<AlmanacEvent> events =
                    new SupermoonAlmanacSource(lunarPhaseService).events(start, start.plusDays(5));

            assertThat(events).hasSize(2);
            assertThat(events).extracting(AlmanacEvent::startDate)
                    .containsExactly(start, start.plusDays(4));
        }

        @Test
        @DisplayName("the peak night named is the one nearest perigee, not the middle of the span")
        void namesTheNightNearestPerigee() {
            for (int i = 0; i < 3; i++) {
                when(lunarPhaseService.isFullMoon(start.plusDays(i))).thenReturn(true);
            }
            when(lunarPhaseService.daysFromNearestPerigee(start)).thenReturn(2.5);
            when(lunarPhaseService.daysFromNearestPerigee(start.plusDays(1))).thenReturn(1.4);
            when(lunarPhaseService.daysFromNearestPerigee(start.plusDays(2))).thenReturn(0.2);

            AlmanacEvent event = new SupermoonAlmanacSource(lunarPhaseService)
                    .events(start, start.plusDays(2)).getFirst();

            assertThat(event.meta()).containsEntry("peakDate", start.plusDays(2).toString());
        }

        @Test
        @DisplayName("a full moon outside the perigee window is not a supermoon")
        void aFullMoonAloneDoesNotQualify() {
            when(lunarPhaseService.isFullMoon(start)).thenReturn(true);
            when(lunarPhaseService.daysFromNearestPerigee(start)).thenReturn(6.0);

            assertThat(new SupermoonAlmanacSource(lunarPhaseService).events(start, start))
                    .isEmpty();
        }

        @Test
        @DisplayName("a supermoon still open on the last day of the range is closed and kept")
        void closesAnOpenSpanAtTheEndOfTheRange() {
            supermoonOn(2, 1);

            List<AlmanacEvent> events =
                    new SupermoonAlmanacSource(lunarPhaseService).events(start, start.plusDays(1));

            assertThat(events).hasSize(1);
            assertThat(events.getFirst().endDate()).isEqualTo(start.plusDays(1));
        }
    }

    @Nested
    @DisplayName("NlcSeasonAlmanacSource")
    class NlcSeason {

        @Test
        @DisplayName("the in-season stretch becomes one span, whatever its length")
        void groupsTheSeasonIntoOneSpan() {
            LocalDate from = LocalDate.of(2026, 5, 20);
            for (int i = 0; i <= 10; i++) {
                LocalDate date = from.plusDays(i);
                when(nlcClarityService.isNlcSeason(date)).thenReturn(i >= 5);
            }

            List<AlmanacEvent> events =
                    new NlcSeasonAlmanacSource(nlcClarityService).events(from, from.plusDays(10));

            assertThat(events).hasSize(1);
            assertThat(events.getFirst().startDate()).isEqualTo(from.plusDays(5));
            assertThat(events.getFirst().endDate()).isEqualTo(from.plusDays(10));
            assertThat(events.getFirst().type()).isEqualTo(NlcSeasonAlmanacSource.TYPE);
        }

        @Test
        @DisplayName("a season clipped by the end of the window says so, so a reader does not "
                + "mistake the window edge for the season's end")
        void flagsAClippedEnd() {
            LocalDate from = LocalDate.of(2026, 6, 1);
            for (int i = -1; i <= 4; i++) {
                when(nlcClarityService.isNlcSeason(from.plusDays(i))).thenReturn(true);
            }

            AlmanacEvent event = new NlcSeasonAlmanacSource(nlcClarityService)
                    .events(from, from.plusDays(3)).getFirst();

            assertThat(event.meta()).containsEntry("startsBeforeWindow", "true");
            assertThat(event.meta()).containsEntry("endsAfterWindow", "true");
        }

        @Test
        @DisplayName("a season that genuinely opens on the feed's first day is NOT flagged as "
                + "clipped — the flags ask the season, not the window")
        void doesNotFlagAGenuineSeasonBoundary() {
            // The old test could not tell these apart: it stubbed every day in season, so
            // "clipped" and "really starts here" produced identical input. Once a year the real
            // boundary was reported as a rendering artefact.
            LocalDate from = LocalDate.of(2026, 5, 25);
            when(nlcClarityService.isNlcSeason(from.minusDays(1))).thenReturn(false);
            for (int i = 0; i <= 3; i++) {
                when(nlcClarityService.isNlcSeason(from.plusDays(i))).thenReturn(true);
            }
            when(nlcClarityService.isNlcSeason(from.plusDays(4))).thenReturn(true);

            AlmanacEvent event = new NlcSeasonAlmanacSource(nlcClarityService)
                    .events(from, from.plusDays(3)).getFirst();

            assertThat(event.meta()).doesNotContainKey("startsBeforeWindow");
            assertThat(event.meta()).containsEntry("endsAfterWindow", "true");
        }

        @Test
        @DisplayName("a season entirely inside the window carries neither clipping flag")
        void anUnclippedSeasonCarriesNoFlags() {
            LocalDate from = LocalDate.of(2026, 5, 1);
            for (int i = 0; i <= 4; i++) {
                when(nlcClarityService.isNlcSeason(from.plusDays(i)))
                        .thenReturn(i == 1 || i == 2 || i == 3);
            }
            // Probed by the clip check; both ends are genuine season boundaries here.
            when(nlcClarityService.isNlcSeason(from)).thenReturn(false);

            AlmanacEvent event = new NlcSeasonAlmanacSource(nlcClarityService)
                    .events(from, from.plusDays(4)).getFirst();

            assertThat(event.meta()).isEmpty();
            assertThat(event.isDatesOnly()).isTrue();
        }

        @Test
        @DisplayName("out of season entirely yields nothing — and never claims a display")
        void outOfSeasonIsEmpty() {
            LocalDate from = LocalDate.of(2026, 1, 1);
            for (int i = 0; i <= 2; i++) {
                when(nlcClarityService.isNlcSeason(from.plusDays(i))).thenReturn(false);
            }

            assertThat(new NlcSeasonAlmanacSource(nlcClarityService).events(from, from.plusDays(2)))
                    .isEmpty();
        }

        @Test
        @DisplayName("the copy states NLC cannot be forecast, since the fixed dates could "
                + "otherwise read as a promise of a display")
        void theCopyCarriesTheUnforecastableCaveat() {
            LocalDate from = LocalDate.of(2026, 6, 1);
            when(nlcClarityService.isNlcSeason(from)).thenReturn(true);

            AlmanacEvent event =
                    new NlcSeasonAlmanacSource(nlcClarityService).events(from, from).getFirst();

            assertThat(event.detail()).contains("cannot be forecast");
        }
    }
}
