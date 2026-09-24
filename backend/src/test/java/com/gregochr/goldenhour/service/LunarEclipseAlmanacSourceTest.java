package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.AlmanacEvent;
import com.gregochr.goldenhour.model.AlmanacKind;
import com.gregochr.goldenhour.model.LunarEclipseSight;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.service.LunarEclipseCatalog.LunarEclipse;
import com.gregochr.goldenhour.util.LunarEclipseCalculator;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link LunarEclipseAlmanacSource} — the 90-day feed's lunar eclipse entry.
 *
 * <p>What is tested here is the source's contract: that it answers for the whole range it is
 * given, that its figures are this roster's rather than the design mockup's rounded ones, and that
 * it degrades by carrying fewer numbers (and the catalogue-only {@code next}/{@code since} facts)
 * rather than by inventing any.
 */
@ExtendWith(MockitoExtension.class)
class LunarEclipseAlmanacSourceTest {

    private static final LocalDate ECLIPSE_DAY = LocalDate.of(2026, 8, 28);
    private static final LocalDate NINETY_DAYS_BEFORE = ECLIPSE_DAY.minusDays(60);
    private static final LocalDate NINETY_DAYS_AFTER = ECLIPSE_DAY.plusDays(30);

    private static final double BAMBURGH_LAT = 55.6089;
    private static final double BAMBURGH_LON = -1.7188;
    private static final double SCILLY_LAT = 49.9160;
    private static final double SCILLY_LON = -6.3220;

    @Mock
    private LocationRepository locationRepository;

    @Mock
    private LunarEclipseCalculator calculator;

    private LunarEclipseAlmanacSource source;

    @BeforeEach
    void setUp() {
        source = new LunarEclipseAlmanacSource(locationRepository, calculator);
    }

    private static LocationEntity location(String name, double lat, double lon) {
        LocationEntity entity = new LocationEntity();
        entity.setName(name);
        entity.setLat(lat);
        entity.setLon(lon);
        entity.setEnabled(true);
        return entity;
    }

    private void stubRoster(LocationEntity... locations) {
        when(locationRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(locations));
    }

    private static LunarEclipse eclipseOn(LocalDate date) {
        return LunarEclipseCatalog.on(date).orElseThrow();
    }

    private static LunarEclipseSight visibleSight(int altAtMax, int azAtMax, String cardinal,
            LocalDateTime moonset, boolean setsInShadow, LocalDateTime umbraStart, LocalDateTime umbraEnd) {
        return new LunarEclipseSight(altAtMax, azAtMax, cardinal, moonset, null, setsInShadow, false,
                umbraStart, umbraEnd, true);
    }

    private static LunarEclipseSight invisibleSight() {
        return new LunarEclipseSight(-5, 0, "N", null, null, false, false,
                LocalDateTime.of(2026, 8, 28, 3, 33), LocalDateTime.of(2026, 8, 28, 3, 33), false);
    }

    private AlmanacEvent theEntry() {
        List<AlmanacEvent> events = source.events(NINETY_DAYS_BEFORE, NINETY_DAYS_AFTER);
        assertThat(events).hasSize(1);
        return events.get(0);
    }

    @Nested
    @DisplayName("The entry it emits")
    class Entry {

        @BeforeEach
        void setUp() {
            stubRoster(location("Bamburgh", BAMBURGH_LAT, BAMBURGH_LON));
            when(calculator.sight(eq(eclipseOn(ECLIPSE_DAY)), eq(BAMBURGH_LAT), eq(BAMBURGH_LON))).thenReturn(
                    visibleSight(8, 241, "WSW", LocalDateTime.of(2026, 8, 28, 6, 16), true,
                            LocalDateTime.of(2026, 8, 28, 3, 33), LocalDateTime.of(2026, 8, 28, 6, 16)));
        }

        @Test
        @DisplayName("is a single-day ALMANAC entry of type 'lunar-eclipse'")
        void shape() {
            AlmanacEvent event = theEntry();

            assertThat(event.type()).isEqualTo("lunar-eclipse");
            assertThat(event.kind()).isEqualTo(AlmanacKind.ALMANAC);
            assertThat(event.startDate()).isEqualTo(ECLIPSE_DAY);
            assertThat(event.endDate()).isEqualTo(ECLIPSE_DAY);
            assertThat(event.dayCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("names the depth in the title, from this roster's own reduction")
        void titleNamesTheDepth() {
            // 0.93187 magnitude, PARTIAL kind, >= 0.80 → "Deep partial".
            assertThat(theEntry().title()).isEqualTo("Deep partial lunar eclipse");
        }

        @Test
        @DisplayName("carries magnitude, maximum, shadow span, seen count, next and location")
        void metaCarriesTheFigures() {
            AlmanacEvent event = theEntry();

            assertThat(event.meta()).containsEntry("magnitude", "93% in shadow");
            assertThat(event.meta()).containsEntry("maximum", "05:12 · moon WSW 241°, 8° up");
            assertThat(event.meta()).containsEntry("shadow", "in shadow 03:33 → sets 06:16");
            assertThat(event.meta()).containsEntry("seen", "seen from 1 of 1 sites");
            assertThat(event.meta())
                    .containsEntry("next", "Next from the UK: a partial eclipse, Wed 12 Jan 2028");
            assertThat(event.meta()).containsEntry("since", "September 2025");
            assertThat(event.meta()).containsEntry("location", "Bamburgh");
        }

        @Test
        @DisplayName("every meta value is a finished string the client renders verbatim")
        void metaValuesAreFullyComposed() {
            assertThat(theEntry().meta().values())
                    .allSatisfy(value -> assertThat(value).isNotBlank());
        }

        @Test
        @DisplayName("the detail is the composed 'why' paragraph, naming the maximum and geometry")
        void detailIsTheWhyParagraph() {
            assertThat(theEntry().detail()).isEqualTo(
                    "Earth's shadow covers all but a sliver of the full moon, and the shadowed part"
                            + " turns copper. Maximum is at 05:12 with the moon 8° above the WSW"
                            + " horizon, and it sets at 06:16 still in shadow. A low, clear horizon"
                            + " is worth more than a dark site.");
        }
    }

    @Nested
    @DisplayName("Answering for the whole range")
    class Range {

        @Test
        @DisplayName("finds an eclipse anywhere in a ninety-day window, not just the next few days")
        void findsAnEclipseTwoMonthsOut() {
            stubRoster(location("Bamburgh", BAMBURGH_LAT, BAMBURGH_LON));
            when(calculator.sight(eq(eclipseOn(ECLIPSE_DAY)), eq(BAMBURGH_LAT), eq(BAMBURGH_LON))).thenReturn(
                    visibleSight(8, 241, "WSW", LocalDateTime.of(2026, 8, 28, 6, 16), true,
                            LocalDateTime.of(2026, 8, 28, 3, 33), LocalDateTime.of(2026, 8, 28, 6, 16)));

            assertThat(source.events(ECLIPSE_DAY.minusDays(89), ECLIPSE_DAY)).hasSize(1);
        }

        @Test
        @DisplayName("is silent for a range holding no catalogued lunar eclipse")
        void silentOutsideTheRange() {
            assertThat(source.events(LocalDate.of(2027, 2, 1), LocalDate.of(2027, 3, 31))).isEmpty();
        }
    }

    @Nested
    @DisplayName("Degrading")
    class Degrading {

        @Test
        @DisplayName("keeps its dates and its catalogue-only facts when there is no roster to reduce against")
        void noRosterKeepsDatesAndCatalogueFacts() {
            when(locationRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of());

            AlmanacEvent event = theEntry();

            assertThat(event.startDate()).isEqualTo(ECLIPSE_DAY);
            // magnitude/maximum/shadow/seen/location need a location to reduce against and are
            // absent; next/since are catalogue facts and survive.
            assertThat(event.meta()).containsOnlyKeys("next", "since");
            assertThat(event.meta())
                    .containsEntry("next", "Next from the UK: a partial eclipse, Wed 12 Jan 2028");
            assertThat(event.meta()).containsEntry("since", "September 2025");
            // Never "how much is in shadow" — that fraction is a catalogue fact true everywhere the
            // Moon is visible at all, unlike the solar eclipse's location-dependent magnitude.
            assertThat(event.detail()).isEqualTo(
                    "A lunar eclipse falls on this date. How long you can watch it, and whether the"
                            + " moon rises or sets mid-eclipse, depends on where you stand — add a"
                            + " location to see the figures.");
        }

        @Test
        @DisplayName("emits nothing at all when nothing on the roster can see it")
        void invisibleEclipseIsAbsentRatherThanHedged() {
            stubRoster(location("Bondi", -33.8688, 151.2093));
            when(calculator.sight(eq(eclipseOn(ECLIPSE_DAY)), eq(-33.8688), eq(151.2093)))
                    .thenReturn(invisibleSight());

            assertThat(source.events(NINETY_DAYS_BEFORE, NINETY_DAYS_AFTER)).isEmpty();
        }
    }

    @Nested
    @DisplayName("The catalogue-derived recurrence facts")
    class RecurrenceFacts {

        @Test
        @DisplayName("'since' names the previous catalogued UK-visible eclipse, not an authored date")
        void sinceNamesThePreviousEntry() {
            // The catalogue's own lesson (L0): the design draft assumed "since March 2025", which
            // the later 2025-09-07 UK-visible entry contradicts. This must read whatever the
            // catalogue's own chronology says, not a hardcoded assumption.
            stubRoster(location("Bamburgh", BAMBURGH_LAT, BAMBURGH_LON));
            when(calculator.sight(eq(eclipseOn(ECLIPSE_DAY)), eq(BAMBURGH_LAT), eq(BAMBURGH_LON))).thenReturn(
                    visibleSight(8, 241, "WSW", LocalDateTime.of(2026, 8, 28, 6, 16), true,
                            LocalDateTime.of(2026, 8, 28, 3, 33), LocalDateTime.of(2026, 8, 28, 6, 16)));

            assertThat(theEntry().meta()).containsEntry("since", "September 2025");
        }

        @Test
        @DisplayName("'since' is absent for the catalogue's very first entry, which has nothing earlier")
        void sinceIsAbsentForTheEarliestEntry() {
            LocalDate first = LocalDate.of(2025, 3, 14);
            stubRoster(location("Bamburgh", BAMBURGH_LAT, BAMBURGH_LON));
            when(calculator.sight(eq(eclipseOn(first)), eq(BAMBURGH_LAT), eq(BAMBURGH_LON))).thenReturn(
                    visibleSight(20, 270, "W", null, false,
                            LocalDateTime.of(2025, 3, 14, 6, 57), LocalDateTime.of(2025, 3, 14, 8, 48)));

            List<AlmanacEvent> events = source.events(first.minusDays(1), first.plusDays(1));

            assertThat(events).hasSize(1);
            assertThat(events.get(0).meta()).doesNotContainKey("since");
        }

        @Test
        @DisplayName("'next' is absent for the catalogue's last entry")
        void nextIsAbsentForTheLastEntry() {
            LocalDate last = LocalDate.of(2029, 12, 20);
            stubRoster(location("Bamburgh", BAMBURGH_LAT, BAMBURGH_LON));
            when(calculator.sight(eq(eclipseOn(last)), eq(BAMBURGH_LAT), eq(BAMBURGH_LON))).thenReturn(
                    visibleSight(45, 90, "E", null, false,
                            LocalDateTime.of(2029, 12, 20, 20, 55), LocalDateTime.of(2029, 12, 21, 1, 41)));

            List<AlmanacEvent> events = source.events(last.minusDays(1), last.plusDays(1));

            assertThat(events).hasSize(1);
            assertThat(events.get(0).meta()).doesNotContainKey("next");
        }
    }

    @Nested
    @DisplayName("Agreeing with the Plan tab")
    class AgreesWithTheStrategy {

        @Test
        @DisplayName("picks the same representative location the hot-topic strategy does — longest visible span")
        void sameRepresentative() {
            LunarEclipse eclipse = eclipseOn(ECLIPSE_DAY);
            stubRoster(
                    location("Bamburgh", BAMBURGH_LAT, BAMBURGH_LON),
                    location("St Mary's", SCILLY_LAT, SCILLY_LON));
            when(calculator.sight(eq(eclipse), eq(BAMBURGH_LAT), eq(BAMBURGH_LON))).thenReturn(
                    visibleSight(8, 241, "WSW", LocalDateTime.of(2026, 8, 28, 4, 33), true,
                            LocalDateTime.of(2026, 8, 28, 3, 33), LocalDateTime.of(2026, 8, 28, 4, 33)));
            when(calculator.sight(eq(eclipse), eq(SCILLY_LAT), eq(SCILLY_LON))).thenReturn(
                    visibleSight(20, 90, "E", null, false,
                            LocalDateTime.of(2026, 8, 28, 3, 33), LocalDateTime.of(2026, 8, 28, 6, 52)));

            assertThat(theEntry().meta()).containsEntry("location", "St Mary's");
        }
    }
}
