package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.HotTopicFact;
import com.gregochr.goldenhour.repository.LocationRepository;

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
 * Unit tests for {@link EclipseHotTopicStrategy}.
 *
 * <p>The reduction itself is covered by {@code EclipseCalculatorTest} against published
 * circumstances; what is tested here is the topic the strategy builds from it — that the figures
 * belong to this roster rather than to a quoted city, that the pill's clock time is the eclipse's
 * own maximum and not the sunset it shares a window with, that the safety warning is on its own
 * field, and that the topic withdraws once it is over.
 */
@ExtendWith(MockitoExtension.class)
class EclipseHotTopicStrategyTest {

    /** The catalogued eclipse. */
    private static final LocalDate ECLIPSE_DAY = LocalDate.of(2026, 8, 12);

    private static final double BAMBURGH_LAT = 55.6089;
    private static final double BAMBURGH_LON = -1.7188;
    private static final double SCILLY_LAT = 49.9160;
    private static final double SCILLY_LON = -6.3220;

    @Mock
    private LocationRepository locationRepository;

    @Mock
    private SolarService solarService;

    @Mock
    private SolarEventFreshness freshness;

    private EclipseHotTopicStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new EclipseHotTopicStrategy(locationRepository, solarService, freshness);
    }

    private static LocationEntity location(String name, double lat, double lon, String region) {
        LocationEntity entity = new LocationEntity();
        entity.setName(name);
        entity.setLat(lat);
        entity.setLon(lon);
        entity.setEnabled(true);
        RegionEntity regionEntity = new RegionEntity();
        regionEntity.setName(region);
        entity.setRegion(regionEntity);
        return entity;
    }

    private void stubRoster(LocationEntity... locations) {
        when(locationRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of(locations));
    }

    /** The eclipse has not finished yet, so freshness does not withdraw the topic. */
    private void stubStillAhead() {
        when(freshness.isAhead(org.mockito.ArgumentMatchers.any(LocalDateTime.class))).thenReturn(true);
    }

    /**
     * Sunset at Bamburgh, stubbed per test rather than in a shared setup.
     *
     * <p>Three tests in this class need a DIFFERENT sunset (before last contact, and null), and a
     * shared stub they each overrode would leave the original unused — which Mockito's strict
     * stubbing reports as an error, and which the project's test standards forbid silencing with
     * {@code lenient()}.
     */
    private void stubSunset(LocalDateTime utc) {
        when(solarService.sunsetUtc(eq(BAMBURGH_LAT), eq(BAMBURGH_LON), eq(ECLIPSE_DAY))).thenReturn(utc);
    }

    private HotTopic detectOne() {
        List<HotTopic> topics = strategy.detect(ECLIPSE_DAY, ECLIPSE_DAY.plusDays(3));
        assertThat(topics).hasSize(1);
        return topics.get(0);
    }

    private static HotTopicFact factWithKey(HotTopic topic, String key) {
        return topic.facts().stream()
                .filter(f -> key.equals(f.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no fact keyed '" + key + "' in " + topic.facts()));
    }

    @Nested
    @DisplayName("The topic it builds")
    class TopicShape {

        @BeforeEach
        void setUp() {
            stubRoster(location("Bamburgh", BAMBURGH_LAT, BAMBURGH_LON, "Northumberland"));
            stubStillAhead();
            stubSunset(ECLIPSE_DAY.atTime(19, 47));
        }

        @Test
        @DisplayName("fires on the eclipse date with the ECLIPSE type at the between-bands priority")
        void firesWithTypeAndPriority() {
            HotTopic topic = detectOne();

            assertThat(topic.type()).isEqualTo("ECLIPSE");
            assertThat(topic.date()).isEqualTo(ECLIPSE_DAY);
            // 4 is the gap between the act-on-it band (1-3) and the calendar band (5-7).
            assertThat(topic.priority()).isEqualTo(4);
            assertThat(topic.filterAction()).isNull();
        }

        @Test
        @DisplayName("labels a 91% eclipse 'deep partial', not 'total'")
        void labelsByDepth() {
            assertThat(detectOne().label()).isEqualTo("Deep partial eclipse");
        }

        @Test
        @DisplayName("leads the pill with the eclipse's own maximum, never the sunset it shares a window with")
        void eventTimeIsMaximumNotSunset() {
            HotTopic topic = detectOne();

            // The single most consequential assertion in this class. The topic is bucketed onto the
            // SUNSET window so it lands on the right card, and HotTopicEventEnricher resolves a
            // SUNSET topic's time by calling sunsetUtc — which here is 20:47 BST, an hour and three
            // quarters after the eclipse peaks. The strategy sets the event itself precisely so the
            // enricher passes it through untouched.
            assertThat(topic.eventType()).isEqualTo("SUNSET");
            assertThat(topic.eventTime()).isEqualTo("19:06");
            assertThat(topic.eventTime()).isNotEqualTo("20:47");
        }

        @Test
        @DisplayName("states obscuration and the low sun in the detail line")
        void detailStatesCoverageAndAltitude() {
            // Bamburgh sees 90% of the sun's AREA covered with the sun 13 degrees up. Both figures
            // are reduced for Bamburgh; London's widely published 91% at 10 degrees is a different
            // place and must not appear.
            assertThat(detectOne().detail()).isEqualTo("90% of the sun covered, and it sits only 13° up");
        }

        @Test
        @DisplayName("carries the region of every location that sees it")
        void carriesRegions() {
            assertThat(detectOne().regions()).containsExactly("Northumberland");
        }
    }

    @Nested
    @DisplayName("The fact chips")
    class Facts {

        @BeforeEach
        void setUp() {
            stubRoster(location("Bamburgh", BAMBURGH_LAT, BAMBURGH_LON, "Northumberland"));
            stubStillAhead();
        }

        @Test
        @DisplayName("the headline chip pairs coverage with the sun's altitude and bearing")
        void headlineChipCarriesCoverageAltitudeAndBearing() {
            HotTopicFact max = factWithKey(detectOne(), "max");

            assertThat(max.value()).isEqualTo("90% covered · sun only 13° up");
            // W, not the design's WNW: the reduction puts the sun at 277.6 degrees over Bamburgh and
            // WNW does not begin until 281.25. The compass point is taken from the geometry.
            assertThat(max.dir()).isEqualTo("W");
            assertThat(max.emphasis()).isTrue();
            assertThat(max.optional()).isFalse();
        }

        @Test
        @DisplayName("the contacts chip gives all three, in Europe/London clock time")
        void contactsChipGivesAllThree() {
            assertThat(factWithKey(detectOne(), "contacts").value())
                    .isEqualTo("18:09 → 19:06 → 20:00");
        }

        @Test
        @DisplayName("the sunset chip states the gap after last contact, and is the one dropped on a phone")
        void sunsetChipIsOptionalAndRelatesToLastContact() {
            stubSunset(ECLIPSE_DAY.atTime(19, 47));

            HotTopicFact sun = factWithKey(detectOne(), "sun");

            // Last contact 20:00 BST, sunset 20:47 BST.
            assertThat(sun.value()).isEqualTo("sets 20:47, 47 min after last contact");
            assertThat(sun.optional()).isTrue();
        }

        @Test
        @DisplayName("says so when the sun sets before the eclipse ends, rather than printing a negative gap")
        void sunsetDuringTheEclipseIsWordedDifferently() {
            // A sunset 20 minutes BEFORE last contact. Subtracting these the other way round would
            // print "-20 min after last contact", which states the opposite of what happens.
            stubSunset(ECLIPSE_DAY.atTime(18, 40));

            assertThat(factWithKey(detectOne(), "sun").value())
                    .isEqualTo("sets 19:40, while the eclipse is still running");
        }

        @Test
        @DisplayName("drops the sunset chip entirely when sunset cannot be computed")
        void noSunsetChipWhenSunsetIsUnknown() {
            stubSunset(null);

            assertThat(detectOne().facts()).extracting(HotTopicFact::key)
                    .containsExactly("max", "contacts");
        }

        @Test
        @DisplayName("carries the design's where-to-look note")
        void carriesTheNote() {
            assertThat(detectOne().note())
                    .isEqualTo("a clear low western horizon matters more than the last 2%");
        }
    }

    @Nested
    @DisplayName("Safety")
    class Safety {

        @BeforeEach
        void setUp() {
            stubRoster(location("Bamburgh", BAMBURGH_LAT, BAMBURGH_LON, "Northumberland"));
            stubStillAhead();
            stubSunset(ECLIPSE_DAY.atTime(19, 47));
        }

        @Test
        @DisplayName("the filter warning rides its own field, never the fact row or the note")
        void warningIsOnItsOwnField() {
            HotTopic topic = detectOne();

            assertThat(topic.safetyNote())
                    .isEqualTo("Certified solar filter on the lens — not only over your eye");

            // The whole reason for the separate field: `facts` and `note` render only inside the
            // pill's fact row, which is blurred and dimmed for LITE users. A blurred eye-safety
            // instruction is worse than none, so it must not be reachable only from there.
            assertThat(topic.note()).doesNotContain("filter");
            assertThat(topic.facts()).extracting(HotTopicFact::value)
                    .noneMatch(v -> v.contains("filter"));
        }

        @Test
        @DisplayName("names the lens, not only the eye — the clause that matters")
        void warningNamesTheLens() {
            assertThat(detectOne().safetyNote()).contains("on the lens").contains("not only over your eye");
        }
    }

    @Nested
    @DisplayName("Choosing who speaks for the roster")
    class Representative {

        @Test
        @DisplayName("the deepest view on the roster supplies the figures, not the first alphabetically")
        void deepestViewWins() {
            // Scilly sees 96% and Bamburgh 90%. Bamburgh sorts first by name, so a strategy taking
            // the first location would report the shallower view as the topic's headline.
            stubRoster(
                    location("Bamburgh", BAMBURGH_LAT, BAMBURGH_LON, "Northumberland"),
                    location("St Mary's", SCILLY_LAT, SCILLY_LON, "Scilly"));
            stubStillAhead();
            when(solarService.sunsetUtc(eq(SCILLY_LAT), eq(SCILLY_LON), eq(ECLIPSE_DAY)))
                    .thenReturn(ECLIPSE_DAY.atTime(20, 15));

            HotTopic topic = detectOne();

            assertThat(factWithKey(topic, "max").value()).startsWith("96% covered");
            assertThat(topic.eventTime()).isEqualTo("19:16");
            assertThat(topic.regions()).containsExactly("Northumberland", "Scilly");
        }

        @Test
        @DisplayName("names the representative and the roster size in the tooltip, since maximum sweeps the country")
        void tooltipNamesTheRepresentative() {
            stubRoster(
                    location("Bamburgh", BAMBURGH_LAT, BAMBURGH_LON, "Northumberland"),
                    location("St Mary's", SCILLY_LAT, SCILLY_LON, "Scilly"));
            stubStillAhead();
            when(solarService.sunsetUtc(eq(SCILLY_LAT), eq(SCILLY_LON), eq(ECLIPSE_DAY)))
                    .thenReturn(ECLIPSE_DAY.atTime(20, 15));

            assertThat(detectOne().description())
                    .contains("Times and figures are for St Mary's, the deepest view of 2 locations");
        }
    }

    @Nested
    @DisplayName("When no topic should appear")
    class NoTopic {

        @Test
        @DisplayName("no eclipse in the window means no topic")
        void silentOutsideTheWindow() {
            assertThat(strategy.detect(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 4))).isEmpty();
        }

        @Test
        @DisplayName("withdraws once last contact has passed, rather than pointing at a finished evening")
        void withdrawsAfterLastContact() {
            stubRoster(location("Bamburgh", BAMBURGH_LAT, BAMBURGH_LON, "Northumberland"));
            when(freshness.isAhead(org.mockito.ArgumentMatchers.any(LocalDateTime.class))).thenReturn(false);

            assertThat(strategy.detect(ECLIPSE_DAY, ECLIPSE_DAY.plusDays(3))).isEmpty();
        }

        @Test
        @DisplayName("an empty roster yields nothing rather than a topic with no figures")
        void silentWithNoLocations() {
            when(locationRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of());

            assertThat(strategy.detect(ECLIPSE_DAY, ECLIPSE_DAY.plusDays(3))).isEmpty();
        }

        @Test
        @DisplayName("a roster that cannot see the eclipse at all yields nothing")
        void silentWhereTheEclipseIsInvisible() {
            // Sydney: the penumbra never reaches it.
            stubRoster(location("Bondi", -33.8688, 151.2093, "New South Wales"));

            assertThat(strategy.detect(ECLIPSE_DAY, ECLIPSE_DAY.plusDays(3))).isEmpty();
        }
    }

    @Nested
    @DisplayName("The catalogue")
    class Catalogue {

        @Test
        @DisplayName("the 2026 eclipse is rare enough to earn a rarity line, and knows its return period")
        void rarityIsSeededAndGated() {
            EclipseCatalog.Eclipse eclipse = EclipseCatalog.on(ECLIPSE_DAY).orElseThrow();

            assertThat(eclipse.returnYears()).isEqualTo(55);
            assertThat(eclipse.isRare()).isTrue();
            assertThat(eclipse.promoteFromDays()).isEqualTo(1);
        }

        @Test
        @DisplayName("a short return period earns no rarity line — the gate that keeps it off every spring tide")
        void aCommonEventEarnsNoRarityLine() {
            EclipseCatalog.Eclipse frequent = new EclipseCatalog.Eclipse(
                    EclipseCatalog.on(ECLIPSE_DAY).orElseThrow().elements(), 2029, 0);

            assertThat(frequent.returnYears()).isEqualTo(3);
            assertThat(frequent.isRare()).isFalse();
        }

        @Test
        @DisplayName("an unresearched return period is null, never guessed")
        void unknownRarityIsNull() {
            EclipseCatalog.Eclipse unknown = new EclipseCatalog.Eclipse(
                    EclipseCatalog.on(ECLIPSE_DAY).orElseThrow().elements(), null, 0);

            assertThat(unknown.returnYears()).isNull();
            assertThat(unknown.isRare()).isFalse();
        }

        @Test
        @DisplayName("between() is inclusive of both ends")
        void betweenIsInclusive() {
            assertThat(EclipseCatalog.between(ECLIPSE_DAY, ECLIPSE_DAY)).hasSize(1);
            assertThat(EclipseCatalog.between(ECLIPSE_DAY.plusDays(1), ECLIPSE_DAY.plusDays(9))).isEmpty();
            assertThat(EclipseCatalog.between(ECLIPSE_DAY.minusDays(9), ECLIPSE_DAY.minusDays(1))).isEmpty();
        }

        @Test
        @DisplayName("on() finds nothing on a day with no eclipse")
        void onFindsNothingOnAnOrdinaryDay() {
            assertThat(EclipseCatalog.on(ECLIPSE_DAY.plusDays(1))).isEmpty();
        }
    }
}
