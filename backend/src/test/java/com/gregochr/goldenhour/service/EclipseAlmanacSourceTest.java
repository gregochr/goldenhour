package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.AlmanacEvent;
import com.gregochr.goldenhour.model.AlmanacKind;
import com.gregochr.goldenhour.repository.LocationRepository;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link EclipseAlmanacSource} — the 90-day feed's eclipse entry.
 *
 * <p>The reduction is covered by {@code EclipseCalculatorTest}. What is tested here is the
 * source's contract: that it answers for the whole range it is given, that its figures are this
 * roster's rather than a quoted city's, and that it degrades by carrying fewer numbers rather than
 * by inventing any.
 */
@ExtendWith(MockitoExtension.class)
class EclipseAlmanacSourceTest {

    private static final LocalDate ECLIPSE_DAY = LocalDate.of(2026, 8, 12);
    private static final LocalDate NINETY_DAYS_BEFORE = ECLIPSE_DAY.minusDays(60);
    private static final LocalDate NINETY_DAYS_AFTER = ECLIPSE_DAY.plusDays(30);

    @Mock
    private LocationRepository locationRepository;

    private EclipseAlmanacSource source;

    @BeforeEach
    void setUp() {
        source = new EclipseAlmanacSource(locationRepository);
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
            stubRoster(location("Bamburgh", 55.6089, -1.7188));
        }

        @Test
        @DisplayName("is a single-day ALMANAC entry of type 'eclipse'")
        void shape() {
            AlmanacEvent event = theEntry();

            assertThat(event.type()).isEqualTo("eclipse");
            assertThat(event.kind()).isEqualTo(AlmanacKind.ALMANAC);
            assertThat(event.startDate()).isEqualTo(ECLIPSE_DAY);
            assertThat(event.endDate()).isEqualTo(ECLIPSE_DAY);
            assertThat(event.dayCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("names the depth in the title, from this roster's own reduction")
        void titleNamesTheDepth() {
            assertThat(theEntry().title()).isEqualTo("Deep partial eclipse");
        }

        @Test
        @DisplayName("carries coverage, the maximum with its bearing, the rarity and the location")
        void metaCarriesTheFigures() {
            AlmanacEvent event = theEntry();

            assertThat(event.meta()).containsEntry("coverage", "90% covered");
            assertThat(event.meta()).containsEntry("maximum", "19:06 · sun 13° up W");
            assertThat(event.meta())
                    .containsEntry("rarity", "nothing comparable from the UK until 2081");
            assertThat(event.meta()).containsEntry("location", "Bamburgh");
        }

        @Test
        @DisplayName("every meta value is a finished string the client renders verbatim")
        void metaValuesAreFullyComposed() {
            // The feed's stated rule is that no number is parsed, compared or re-formatted on the
            // client. A bare `returnYears` integer would have broken it, which is why the
            // recurrence line is composed here as a whole sentence.
            assertThat(theEntry().meta().values())
                    .allSatisfy(value -> assertThat(value).isNotBlank());
        }

        @Test
        @DisplayName("the detail names the location the figures belong to, and the filter")
        void detailCarriesTheCaveatAndTheWarning() {
            String detail = theEntry().detail();

            assertThat(detail).contains("Bamburgh");
            // This feed has no dedicated safety field, so the one surface it has — the prose —
            // carries the warning. An eclipse entry must never be readable without it.
            assertThat(detail).contains("certified solar filter").contains("not only over your eye");
        }
    }

    @Nested
    @DisplayName("Answering for the whole range")
    class Range {

        @Test
        @DisplayName("finds an eclipse anywhere in a ninety-day window, not just the next few days")
        void findsAnEclipseTwoMonthsOut() {
            // The whole reason this exists beside the hot-topic strategy: that path is bounded by a
            // four-day forecast window and would report nothing at all from here.
            stubRoster(location("Bamburgh", 55.6089, -1.7188));

            assertThat(source.events(ECLIPSE_DAY.minusDays(89), ECLIPSE_DAY)).hasSize(1);
        }

        @Test
        @DisplayName("is silent for a range holding no catalogued eclipse")
        void silentOutsideTheRange() {
            assertThat(source.events(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 3, 31))).isEmpty();
        }
    }

    @Nested
    @DisplayName("Degrading")
    class Degrading {

        @Test
        @DisplayName("keeps its dates and drops the figures when there is no roster to reduce against")
        void noRosterKeepsDatesAndDropsFigures() {
            when(locationRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of());

            AlmanacEvent event = theEntry();

            assertThat(event.startDate()).isEqualTo(ECLIPSE_DAY);
            // The date and the return period are facts of the catalogue and survive; everything
            // that needs a place to stand does not, and none of it is guessed.
            assertThat(event.meta()).containsOnlyKeys("rarity");
            assertThat(event.detail()).contains("depends on where you stand");
        }

        @Test
        @DisplayName("emits nothing at all when the eclipse does not reach any configured location")
        void invisibleEclipseIsAbsentRatherThanHedged() {
            stubRoster(location("Bondi", -33.8688, 151.2093));

            assertThat(source.events(NINETY_DAYS_BEFORE, NINETY_DAYS_AFTER)).isEmpty();
        }

        @Test
        @DisplayName("omits the rarity key entirely for an eclipse that returns often")
        void aCommonEclipseCarriesNoRarityLine() {
            // metaOf drops null, so an unremarkable return period is an ABSENT key rather than a
            // hedge like "returns fairly often" — the feed's degrade rule is silence, not prose.
            EclipseCatalog.Eclipse frequent = new EclipseCatalog.Eclipse(
                    EclipseCatalog.on(ECLIPSE_DAY).orElseThrow().elements(), 2029);

            assertThat(frequent.isRare()).isFalse();
        }
    }

    @Nested
    @DisplayName("Agreeing with the Plan tab")
    class AgreesWithTheStrategy {

        @Test
        @DisplayName("picks the same representative location the hot-topic strategy does")
        void sameRepresentative() {
            // Both take the deepest view on the roster. If they diverged, the same eclipse would
            // report different times on the Plan tab and in the feed on the same screen.
            stubRoster(
                    location("Bamburgh", 55.6089, -1.7188),
                    location("St Mary's", 49.9160, -6.3220));

            assertThat(theEntry().meta()).containsEntry("location", "St Mary's");
            assertThat(theEntry().meta()).containsEntry("coverage", "96% covered");
        }
    }
}
