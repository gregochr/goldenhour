package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.HotTopicFact;
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
 * Unit tests for {@link LunarEclipseHotTopicStrategy}.
 *
 * <p>{@link LunarEclipseCatalog}'s real, catalogue-load-time-validated entries are used throughout
 * rather than hand-built ones — the contact instants are exactly what production reduces, and
 * building a fake entry would risk pinning an invariant the compact constructor already enforces
 * rather than the strategy's own logic. {@link LunarEclipseCalculator} is mocked (unlike the solar
 * strategy's static {@code EclipseCalculator}, this one is a Spring bean over real astronomical
 * calculators), so every geometry figure in a test is a deliberately chosen, round value rather
 * than a hand-computed astronomical one — the one place this class's own arithmetic is exercised
 * unmocked is the clock conversion from the catalogue's UTC contacts to Europe/London time, which
 * every test that reads a time asserts against a value computed by hand from the catalogue's own
 * javadoc-published UTC instants (truncated to the minute — never rounded, matching
 * {@code EclipseHotTopicStrategy}'s own {@code londonTime}).
 */
@ExtendWith(MockitoExtension.class)
class LunarEclipseHotTopicStrategyTest {

    /** 2026-08-28 deep partial — the worked example throughout the plan. Max 04:12:52 UTC, a
     * pre-dawn (SUNRISE) eclipse; u1 02:33:21 UTC → 03:33 BST, u4 05:52:09 UTC → 06:52 BST. */
    private static final LocalDate ECLIPSE_DAY = LocalDate.of(2026, 8, 28);

    /** 2028-12-31 total — max 16:52:01 UTC, an afternoon (SUNSET) eclipse; no BST in force. */
    private static final LocalDate SUNSET_ECLIPSE_DAY = LocalDate.of(2028, 12, 31);

    /** 2029-12-20 total — the catalogue's last entry, so {@code nextComparable} is null. */
    private static final LocalDate LAST_ECLIPSE_DAY = LocalDate.of(2029, 12, 20);

    /** 2028-01-12 partial, magnitude 0.0679 — the SLIGHT band, ~7% of the Moon's diameter. */
    private static final LocalDate SLIGHT_ECLIPSE_DAY = LocalDate.of(2028, 1, 12);

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

    @Mock
    private LunarEclipseCalculator calculator;

    private LunarEclipseHotTopicStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new LunarEclipseHotTopicStrategy(locationRepository, solarService, freshness, calculator);
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

    private void stubStillAhead() {
        when(freshness.isAhead(any(LocalDateTime.class))).thenReturn(true);
    }

    private static LunarEclipse eclipseOn(LocalDate date) {
        return LunarEclipseCatalog.on(date).orElseThrow();
    }

    /** A visible sight with a chosen, round moonset that sets while still in shadow. */
    private static LunarEclipseSight setsInShadowSight(int altAtMax, int azAtMax, String cardinal,
            LocalDateTime moonset, LocalDateTime umbraStart, LocalDateTime umbraEnd) {
        return new LunarEclipseSight(altAtMax, azAtMax, cardinal, moonset, null, true, false,
                umbraStart, umbraEnd, true);
    }

    /** A visible sight that does NOT set in shadow but does set later (a chosen round moonset). */
    private static LunarEclipseSight setsLaterSight(int altAtMax, int azAtMax, String cardinal,
            LocalDateTime moonset, LocalDateTime umbraStart, LocalDateTime umbraEnd) {
        return new LunarEclipseSight(altAtMax, azAtMax, cardinal, moonset, null, false, false,
                umbraStart, umbraEnd, true);
    }

    /** A visible sight with no moonset data at all within the queried window (never sets). */
    private static LunarEclipseSight neverSetsSight(int altAtMax, int azAtMax, String cardinal,
            LocalDateTime umbraStart, LocalDateTime umbraEnd) {
        return new LunarEclipseSight(altAtMax, azAtMax, cardinal, null, null, false, false,
                umbraStart, umbraEnd, true);
    }

    private static LunarEclipseSight invisibleSight() {
        return new LunarEclipseSight(-5, 0, "N", null, null, false, false,
                LocalDateTime.of(2026, 8, 28, 3, 33), LocalDateTime.of(2026, 8, 28, 3, 33), false);
    }

    private HotTopic detectOne(LocalDate date) {
        List<HotTopic> topics = strategy.detect(date, date.plusDays(3));
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
            LunarEclipse eclipse = eclipseOn(ECLIPSE_DAY);
            stubRoster(location("Bamburgh", BAMBURGH_LAT, BAMBURGH_LON, "Northumberland"));
            stubStillAhead();
            when(calculator.sight(eq(eclipse), eq(BAMBURGH_LAT), eq(BAMBURGH_LON))).thenReturn(
                    setsInShadowSight(8, 241, "WSW", LocalDateTime.of(2026, 8, 28, 6, 16),
                            LocalDateTime.of(2026, 8, 28, 3, 33), LocalDateTime.of(2026, 8, 28, 6, 16)));
        }

        @Test
        @DisplayName("fires on the eclipse date with the LUNAR_ECLIPSE type at the between-bands priority")
        void firesWithTypeAndPriority() {
            HotTopic topic = detectOne(ECLIPSE_DAY);

            assertThat(topic.type()).isEqualTo("LUNAR_ECLIPSE");
            assertThat(topic.date()).isEqualTo(ECLIPSE_DAY);
            assertThat(topic.priority()).isEqualTo(4);
            assertThat(topic.filterAction()).isNull();
        }

        @Test
        @DisplayName("the label is the constant 'Lunar eclipse', never the kind or depth")
        void labelIsConstant() {
            assertThat(detectOne(ECLIPSE_DAY).label()).isEqualTo("Lunar eclipse");
        }

        @Test
        @DisplayName("leads the pill with the eclipse's own maximum, truncated to the minute — not rounded")
        void eventTimeIsMaximumTruncated() {
            HotTopic topic = detectOne(ECLIPSE_DAY);

            // 04:12:52 UTC → 05:12:52 BST, truncated (never rounded) to "05:12".
            assertThat(topic.eventType()).isEqualTo("SUNRISE");
            assertThat(topic.eventTime()).isEqualTo("05:12");
        }

        @Test
        @DisplayName("states the percentage in shadow, the maximum, the geometry and the moonset")
        void detailStatesShadowAndGeometry() {
            assertThat(detectOne(ECLIPSE_DAY).detail()).isEqualTo(
                    "93% in shadow at 05:12, moon 8° above WSW, sets 06:16 still in shadow");
        }

        @Test
        @DisplayName("carries the region of every location that sees it")
        void carriesRegions() {
            assertThat(detectOne(ECLIPSE_DAY).regions()).containsExactly("Northumberland");
        }

        @Test
        @DisplayName("carries the exact locations that see it, for the map overlay")
        void carriesLocationNames() {
            assertThat(detectOne(ECLIPSE_DAY).locationNames()).containsExactly("Bamburgh");
        }

        @Test
        @DisplayName("the tooltip substitutes the magnitude into the copper explanation")
        void descriptionSubstitutesMagnitude() {
            String description = detectOne(ECLIPSE_DAY).description();

            assertThat(description).contains("93% is a fraction of the moon's diameter in shadow");
            assertThat(description).contains("Earth's atmosphere bends a little sunlight");
        }
    }

    @Nested
    @DisplayName("The detail line's three shapes")
    class DetailShapes {

        private final LunarEclipse eclipse = eclipseOn(ECLIPSE_DAY);

        @BeforeEach
        void setUp() {
            stubRoster(location("Bamburgh", BAMBURGH_LAT, BAMBURGH_LON, "Northumberland"));
            stubStillAhead();
        }

        @Test
        @DisplayName("sets in shadow: names the exact moonset clock time")
        void setsInShadow() {
            when(calculator.sight(eq(eclipse), eq(BAMBURGH_LAT), eq(BAMBURGH_LON))).thenReturn(
                    setsInShadowSight(8, 241, "WSW", LocalDateTime.of(2026, 8, 28, 6, 16),
                            LocalDateTime.of(2026, 8, 28, 3, 33), LocalDateTime.of(2026, 8, 28, 6, 16)));

            assertThat(detectOne(ECLIPSE_DAY).detail())
                    .contains("sets 06:16 still in shadow")
                    .doesNotContain("before it sets");
        }

        @Test
        @DisplayName("does not set in shadow but sets later: states the duration in shadow before it sets")
        void setsLaterStatesDuration() {
            // Visible span 03:33 → 06:52 (u1 → u4, since it does not set in shadow) — 3h 19m.
            when(calculator.sight(eq(eclipse), eq(BAMBURGH_LAT), eq(BAMBURGH_LON))).thenReturn(
                    setsLaterSight(30, 90, "E", LocalDateTime.of(2026, 8, 28, 9, 0),
                            LocalDateTime.of(2026, 8, 28, 3, 33), LocalDateTime.of(2026, 8, 28, 6, 52)));

            assertThat(detectOne(ECLIPSE_DAY).detail())
                    .isEqualTo("93% in shadow at 05:12, moon 30° above E, 3h 19m in shadow before it sets")
                    .doesNotContain("sets 09:00");
        }

        @Test
        @DisplayName("never sets (no moonset data at all): no trailing clause")
        void neverSetsHasNoClause() {
            when(calculator.sight(eq(eclipse), eq(BAMBURGH_LAT), eq(BAMBURGH_LON))).thenReturn(
                    neverSetsSight(45, 90, "E",
                            LocalDateTime.of(2026, 8, 28, 3, 33), LocalDateTime.of(2026, 8, 28, 6, 52)));

            assertThat(detectOne(ECLIPSE_DAY).detail())
                    .isEqualTo("93% in shadow at 05:12, moon 45° above E");
        }
    }

    @Nested
    @DisplayName("The fact chips")
    class Facts {

        private final LunarEclipse eclipse = eclipseOn(ECLIPSE_DAY);

        @BeforeEach
        void setUp() {
            stubRoster(
                    location("Bamburgh", BAMBURGH_LAT, BAMBURGH_LON, "Northumberland"),
                    location("St Mary's", SCILLY_LAT, SCILLY_LON, "Scilly"));
            stubStillAhead();
        }

        @Test
        @DisplayName("the headline chip pairs percentage in shadow with altitude and bearing")
        void headlineChip() {
            when(calculator.sight(eq(eclipse), eq(BAMBURGH_LAT), eq(BAMBURGH_LON))).thenReturn(
                    setsInShadowSight(8, 241, "WSW", LocalDateTime.of(2026, 8, 28, 6, 16),
                            LocalDateTime.of(2026, 8, 28, 3, 33), LocalDateTime.of(2026, 8, 28, 6, 16)));
            when(calculator.sight(eq(eclipse), eq(SCILLY_LAT), eq(SCILLY_LON))).thenReturn(invisibleSight());

            HotTopicFact max = factWithKey(detectOne(ECLIPSE_DAY), "max");

            assertThat(max.value()).isEqualTo("93% in shadow · moon 8° up");
            assertThat(max.dir()).isEqualTo("WSW");
            assertThat(max.emphasis()).isTrue();
            assertThat(max.optional()).isFalse();
        }

        @Test
        @DisplayName("the 'in shadow' chip runs from u1 to 'sets' when the Moon sets while eclipsed")
        void inShadowChipWithSetsWording() {
            when(calculator.sight(eq(eclipse), eq(BAMBURGH_LAT), eq(BAMBURGH_LON))).thenReturn(
                    setsInShadowSight(8, 241, "WSW", LocalDateTime.of(2026, 8, 28, 6, 16),
                            LocalDateTime.of(2026, 8, 28, 3, 33), LocalDateTime.of(2026, 8, 28, 6, 16)));
            when(calculator.sight(eq(eclipse), eq(SCILLY_LAT), eq(SCILLY_LON))).thenReturn(invisibleSight());

            assertThat(factWithKey(detectOne(ECLIPSE_DAY), "in shadow").value())
                    .isEqualTo("03:33 → sets 06:16");
        }

        @Test
        @DisplayName("the 'in shadow' chip runs from u1 to u4, unlabelled, when it does not set in shadow")
        void inShadowChipWithU4Wording() {
            when(calculator.sight(eq(eclipse), eq(BAMBURGH_LAT), eq(BAMBURGH_LON))).thenReturn(
                    neverSetsSight(45, 90, "E",
                            LocalDateTime.of(2026, 8, 28, 3, 33), LocalDateTime.of(2026, 8, 28, 6, 52)));
            when(calculator.sight(eq(eclipse), eq(SCILLY_LAT), eq(SCILLY_LON))).thenReturn(invisibleSight());

            // u4 = 05:52:09 UTC → 06:52 BST, truncated.
            assertThat(factWithKey(detectOne(ECLIPSE_DAY), "in shadow").value())
                    .isEqualTo("03:33 → 06:52");
        }

        @Test
        @DisplayName("the 'seen from' chip counts visible sites over the whole enabled roster")
        void seenFromChip() {
            when(calculator.sight(eq(eclipse), eq(BAMBURGH_LAT), eq(BAMBURGH_LON))).thenReturn(
                    setsInShadowSight(8, 241, "WSW", LocalDateTime.of(2026, 8, 28, 6, 16),
                            LocalDateTime.of(2026, 8, 28, 3, 33), LocalDateTime.of(2026, 8, 28, 6, 16)));
            when(calculator.sight(eq(eclipse), eq(SCILLY_LAT), eq(SCILLY_LON))).thenReturn(invisibleSight());

            assertThat(factWithKey(detectOne(ECLIPSE_DAY), "seen from").value())
                    .isEqualTo("1 of 2 sites");
        }
    }

    @Nested
    @DisplayName("Magnitude bands — never an impossible percentage, never the wrong depth's copy")
    class MagnitudeBands {

        @BeforeEach
        void setUp() {
            stubRoster(location("Bamburgh", BAMBURGH_LAT, BAMBURGH_LON, "Northumberland"));
            stubStillAhead();
        }

        @Test
        @DisplayName("a TOTAL eclipse (2028-12-31, magnitude 1.24785) never prints a percentage — "
                + "'totally eclipsed', 'total', and no bare number in the tooltip")
        void totalEclipseNeverPrintsAPercentage() {
            LunarEclipse eclipse = eclipseOn(SUNSET_ECLIPSE_DAY);
            when(calculator.sight(eq(eclipse), eq(BAMBURGH_LAT), eq(BAMBURGH_LON))).thenReturn(
                    setsInShadowSight(20, 90, "E", LocalDateTime.of(2028, 12, 31, 20, 0),
                            LocalDateTime.of(2028, 12, 31, 15, 7), LocalDateTime.of(2028, 12, 31, 20, 0)));

            HotTopic topic = detectOne(SUNSET_ECLIPSE_DAY);

            assertThat(topic.detail()).contains("totally eclipsed").doesNotContain("%");
            assertThat(factWithKey(topic, "max").value()).startsWith("total ·").doesNotContain("%");
            assertThat(topic.description())
                    .isEqualTo("Earth's shadow covers the whole moon, and the shadowed disc turns"
                            + " copper. Earth's atmosphere bends a little sunlight into its own"
                            + " shadow and filters out the blue on the way, so a totally eclipsed"
                            + " moon glows the colour of every sunrise and sunset on Earth at once.")
                    .doesNotContain("%")
                    .doesNotContain("sliver");
        }

        @Test
        @DisplayName("a SLIGHT eclipse (2028-01-12, magnitude 0.0679) reads '7% in shadow', never "
                + "the deep-partial 'all but a sliver' copy")
        void slightEclipseReadsSevenPercent() {
            LunarEclipse eclipse = eclipseOn(SLIGHT_ECLIPSE_DAY);
            when(calculator.sight(eq(eclipse), eq(BAMBURGH_LAT), eq(BAMBURGH_LON))).thenReturn(
                    neverSetsSight(35, 90, "E",
                            LocalDateTime.of(2028, 1, 12, 3, 44), LocalDateTime.of(2028, 1, 12, 4, 41)));

            HotTopic topic = detectOne(SLIGHT_ECLIPSE_DAY);

            assertThat(topic.detail()).startsWith("7% in shadow at");
            assertThat(factWithKey(topic, "max").value()).isEqualTo("7% in shadow · moon 35° up");
            assertThat(topic.description())
                    .startsWith("Earth's shadow clips 7% of the moon's edge — a darkened bite rather"
                            + " than a copper disc.")
                    .doesNotContain("sliver")
                    .contains("7% is a fraction of the moon's diameter in shadow, not its area.");
        }

        @Test
        @DisplayName("a DEEP eclipse (2026-08-28, magnitude 0.93187) reads '93%' and the "
                + "'all but a sliver' tooltip paragraph")
        void deepEclipseReadsNinetyThreePercent() {
            LunarEclipse eclipse = eclipseOn(ECLIPSE_DAY);
            when(calculator.sight(eq(eclipse), eq(BAMBURGH_LAT), eq(BAMBURGH_LON))).thenReturn(
                    setsInShadowSight(8, 241, "WSW", LocalDateTime.of(2026, 8, 28, 6, 16),
                            LocalDateTime.of(2026, 8, 28, 3, 33), LocalDateTime.of(2026, 8, 28, 6, 16)));

            HotTopic topic = detectOne(ECLIPSE_DAY);

            assertThat(topic.detail()).startsWith("93% in shadow at");
            assertThat(topic.description())
                    .startsWith("Earth's shadow covers all but a sliver of the full moon, and the"
                            + " shadowed part turns copper.")
                    .contains("93% is a fraction of the moon's diameter in shadow, not its area.")
                    .contains("The lit sliver on the lower-left edge will still be much the"
                            + " brightest thing in the frame.");
        }
    }

    @Nested
    @DisplayName("The exposure note")
    class Note {

        @BeforeEach
        void setUp() {
            stubRoster(location("Bamburgh", BAMBURGH_LAT, BAMBURGH_LON, "Northumberland"));
            stubStillAhead();
            when(calculator.sight(eq(eclipseOn(ECLIPSE_DAY)), eq(BAMBURGH_LAT), eq(BAMBURGH_LON))).thenReturn(
                    setsInShadowSight(8, 241, "WSW", LocalDateTime.of(2026, 8, 28, 6, 16),
                            LocalDateTime.of(2026, 8, 28, 3, 33), LocalDateTime.of(2026, 8, 28, 6, 16)));
        }

        @Test
        @DisplayName("rides note, and says no filter is needed — never safetyNote")
        void ridesNoteNeverSafety() {
            HotTopic topic = detectOne(ECLIPSE_DAY);

            assertThat(topic.note())
                    .isEqualTo("No filter needed — bracket, the shadow is ~10 stops under the lit edge");
            assertThat(topic.safetyNote()).isNull();
        }
    }

    @Nested
    @DisplayName("The recurrence line")
    class Rarity {

        @BeforeEach
        void setUp() {
            stubStillAhead();
        }

        @Test
        @DisplayName("names the next catalogued UK-visible eclipse and its kind")
        void namesTheNextEntry() {
            stubRoster(location("Bamburgh", BAMBURGH_LAT, BAMBURGH_LON, "Northumberland"));
            when(calculator.sight(eq(eclipseOn(ECLIPSE_DAY)), eq(BAMBURGH_LAT), eq(BAMBURGH_LON))).thenReturn(
                    setsInShadowSight(8, 241, "WSW", LocalDateTime.of(2026, 8, 28, 6, 16),
                            LocalDateTime.of(2026, 8, 28, 3, 33), LocalDateTime.of(2026, 8, 28, 6, 16)));

            // The catalogue's real successor to 2026-08-28 is 2028-01-12, partial.
            assertThat(detectOne(ECLIPSE_DAY).rarityNote())
                    .isEqualTo("next from the UK: a partial eclipse, Wed 12 Jan 2028");
        }

        @Test
        @DisplayName("is null for the catalogue's last entry, which has nothing to point forward to")
        void nullForTheLastEntry() {
            stubRoster(location("Bamburgh", BAMBURGH_LAT, BAMBURGH_LON, "Northumberland"));
            when(calculator.sight(eq(eclipseOn(LAST_ECLIPSE_DAY)), eq(BAMBURGH_LAT), eq(BAMBURGH_LON)))
                    .thenReturn(setsInShadowSight(45, 90, "E",
                            LocalDateTime.of(2029, 12, 21, 2, 0),
                            LocalDateTime.of(2029, 12, 20, 20, 55), LocalDateTime.of(2029, 12, 21, 2, 0)));

            assertThat(detectOne(LAST_ECLIPSE_DAY).rarityNote()).isNull();
        }
    }

    @Nested
    @DisplayName("The photographic window")
    class EventType {

        @Test
        @DisplayName("a pre-noon maximum buckets as SUNRISE")
        void preNoonIsSunrise() {
            stubRoster(location("Bamburgh", BAMBURGH_LAT, BAMBURGH_LON, "Northumberland"));
            stubStillAhead();
            when(calculator.sight(eq(eclipseOn(ECLIPSE_DAY)), eq(BAMBURGH_LAT), eq(BAMBURGH_LON))).thenReturn(
                    setsInShadowSight(8, 241, "WSW", LocalDateTime.of(2026, 8, 28, 6, 16),
                            LocalDateTime.of(2026, 8, 28, 3, 33), LocalDateTime.of(2026, 8, 28, 6, 16)));

            assertThat(detectOne(ECLIPSE_DAY).eventType()).isEqualTo("SUNRISE");
        }

        @Test
        @DisplayName("a 16:52 UTC maximum buckets as SUNSET")
        void afternoonMaximumIsSunset() {
            stubRoster(location("Bamburgh", BAMBURGH_LAT, BAMBURGH_LON, "Northumberland"));
            stubStillAhead();
            when(calculator.sight(eq(eclipseOn(SUNSET_ECLIPSE_DAY)), eq(BAMBURGH_LAT), eq(BAMBURGH_LON)))
                    .thenReturn(setsInShadowSight(20, 90, "E", LocalDateTime.of(2028, 12, 31, 20, 0),
                            LocalDateTime.of(2028, 12, 31, 15, 7), LocalDateTime.of(2028, 12, 31, 20, 0)));

            HotTopic topic = detectOne(SUNSET_ECLIPSE_DAY);
            assertThat(topic.eventType()).isEqualTo("SUNSET");
            // 16:52:01 UTC — no BST on 31 December — truncated to "16:52".
            assertThat(topic.eventTime()).isEqualTo("16:52");
        }
    }

    @Nested
    @DisplayName("Choosing who speaks for the roster")
    class Representative {

        @Test
        @DisplayName("the longest visible umbral span wins, not the first alphabetically")
        void longestSpanWins() {
            LunarEclipse eclipse = eclipseOn(ECLIPSE_DAY);
            stubRoster(
                    location("Bamburgh", BAMBURGH_LAT, BAMBURGH_LON, "Northumberland"),
                    location("St Mary's", SCILLY_LAT, SCILLY_LON, "Scilly"));
            stubStillAhead();
            // Bamburgh: 1h span. St Mary's: 3h span, deeper VIEW despite sorting second alphabetically.
            when(calculator.sight(eq(eclipse), eq(BAMBURGH_LAT), eq(BAMBURGH_LON))).thenReturn(
                    setsInShadowSight(8, 241, "WSW", LocalDateTime.of(2026, 8, 28, 4, 33),
                            LocalDateTime.of(2026, 8, 28, 3, 33), LocalDateTime.of(2026, 8, 28, 4, 33)));
            when(calculator.sight(eq(eclipse), eq(SCILLY_LAT), eq(SCILLY_LON))).thenReturn(
                    neverSetsSight(20, 90, "E",
                            LocalDateTime.of(2026, 8, 28, 3, 33), LocalDateTime.of(2026, 8, 28, 6, 52)));

            HotTopic topic = detectOne(ECLIPSE_DAY);

            assertThat(factWithKey(topic, "max").dir()).isEqualTo("E");
            assertThat(topic.regions()).containsExactly("Northumberland", "Scilly");
            assertThat(topic.locationNames()).containsExactly("Bamburgh", "St Mary's");
        }

        @Test
        @DisplayName("an equal span ties to the alphabetically-first location, as the roster is already ordered")
        void tiesGoToTheFirstAlphabetically() {
            LunarEclipse eclipse = eclipseOn(ECLIPSE_DAY);
            stubRoster(
                    location("Bamburgh", BAMBURGH_LAT, BAMBURGH_LON, "Northumberland"),
                    location("St Mary's", SCILLY_LAT, SCILLY_LON, "Scilly"));
            stubStillAhead();
            when(calculator.sight(eq(eclipse), eq(BAMBURGH_LAT), eq(BAMBURGH_LON))).thenReturn(
                    setsInShadowSight(8, 241, "WSW", LocalDateTime.of(2026, 8, 28, 4, 33),
                            LocalDateTime.of(2026, 8, 28, 3, 33), LocalDateTime.of(2026, 8, 28, 4, 33)));
            when(calculator.sight(eq(eclipse), eq(SCILLY_LAT), eq(SCILLY_LON))).thenReturn(
                    setsInShadowSight(20, 90, "E", LocalDateTime.of(2026, 8, 28, 4, 33),
                            LocalDateTime.of(2026, 8, 28, 3, 33), LocalDateTime.of(2026, 8, 28, 4, 33)));

            assertThat(factWithKey(detectOne(ECLIPSE_DAY), "max").dir()).isEqualTo("WSW");
        }
    }

    @Nested
    @DisplayName("When no topic should appear")
    class NoTopic {

        @Test
        @DisplayName("no lunar eclipse in the window means no topic")
        void silentOutsideTheWindow() {
            assertThat(strategy.detect(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 4))).isEmpty();
        }

        @Test
        @DisplayName("withdraws once the umbral phase has ended, rather than pointing at a finished night")
        void withdrawsAfterU4() {
            LunarEclipse eclipse = eclipseOn(ECLIPSE_DAY);
            stubRoster(location("Bamburgh", BAMBURGH_LAT, BAMBURGH_LON, "Northumberland"));
            // Pinned to u4 specifically — not p4, not max — so a regression asking freshness about
            // the wrong contact instant fails this test rather than passing it by accident. No
            // calculator stub: freshness is now checked BEFORE the roster is reduced (see
            // WithdrawBeforeWork below), so the calculator is never reached on this path at all.
            when(freshness.isAhead(eq(eclipse.u4()))).thenReturn(false);

            assertThat(strategy.detect(ECLIPSE_DAY, ECLIPSE_DAY.plusDays(3))).isEmpty();
            verify(freshness).isAhead(eq(eclipse.u4()));
        }

        @Test
        @DisplayName("an empty roster yields nothing rather than a topic with no figures")
        void silentWithNoLocations() {
            when(locationRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of());

            assertThat(strategy.detect(ECLIPSE_DAY, ECLIPSE_DAY.plusDays(3))).isEmpty();
        }

        @Test
        @DisplayName("a roster where nothing clears the eligibility altitude yields nothing")
        void silentWhereNothingIsVisible() {
            // Freshness is checked before the roster is reduced (the withdraw-before-work ordering
            // below), so it must be stubbed ahead here or the roster loop — and this test's own
            // stub on calculator.sight() — would never run at all.
            stubStillAhead();
            stubRoster(location("Bamburgh", BAMBURGH_LAT, BAMBURGH_LON, "Northumberland"));
            when(calculator.sight(eq(eclipseOn(ECLIPSE_DAY)), eq(BAMBURGH_LAT), eq(BAMBURGH_LON)))
                    .thenReturn(invisibleSight());

            assertThat(strategy.detect(ECLIPSE_DAY, ECLIPSE_DAY.plusDays(3))).isEmpty();
        }
    }

    @Nested
    @DisplayName("Withdraw before work: a finished eclipse costs nothing")
    class WithdrawBeforeWork {

        @Test
        @DisplayName("once the umbral phase has ended, the roster is never reduced at all")
        void finishedEclipseNeverTouchesTheCalculator() {
            LunarEclipse eclipse = eclipseOn(ECLIPSE_DAY);
            stubRoster(location("Bamburgh", BAMBURGH_LAT, BAMBURGH_LON, "Northumberland"));
            when(freshness.isAhead(eq(eclipse.u4()))).thenReturn(false);

            assertThat(strategy.detect(ECLIPSE_DAY, ECLIPSE_DAY.plusDays(3))).isEmpty();

            verifyNoInteractions(calculator);
        }
    }
}
