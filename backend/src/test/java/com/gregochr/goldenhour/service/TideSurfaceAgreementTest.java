package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideExtremeEntity;
import com.gregochr.goldenhour.entity.TideExtremeType;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.TideStats;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.TideExtremeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Cross-surface agreement: the Plan tab's tide pill and the "Coming up" feed must not disagree
 * about whether a given date carries a spring tide.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>The two surfaces answer the same question by different routes — the Plan tab through
 * {@link SpringTideHotTopicStrategy} reading {@code BriefingSlot.TideInfo} off the briefing cache,
 * the feed through {@link TideSizeIndex} reading stored heights — and each has its own test class
 * that can go green while the two contradict each other. That is not hypothetical: it happened
 * twice in two days, once in each direction.
 *
 * <ul>
 *   <li>2026-08-12: the feed asked {@code LunarPhaseService.classifyTide} while the Plan tab
 *       measured water, so the Plan tab carried a tide card on the Friday and Saturday and the feed
 *       showed nothing at all for either day.</li>
 *   <li>2026-08-13: the fix moved the feed onto the water, and a later commit moved the Plan tab
 *       onto the moon — the same defect, mirrored. The Plan tab's tide pill vanished for the whole
 *       of the 12–17 August run while the feed reported it correctly.</li>
 * </ul>
 *
 * <p>Both suites passed both times. Nothing anywhere asked the two surfaces the same question.
 *
 * <h2>The fixture cannot pre-satisfy either predicate</h2>
 *
 * <p>This is the point of the class, not an implementation detail. The reason the second regression
 * was invisible is that every fixture in {@code SpringTideHotTopicStrategyTest} is built by a helper
 * parameterised by {@link LunarTideType}, with the two height booleans hard-coded as positional
 * constants inside it — so the height arm was dead input and {@code (lunar OR height)} and
 * {@code (lunar)} were behaviourally identical to the whole file.
 *
 * <p>Here, neither surface is handed a verdict. Both are handed <b>one height and one threshold</b>,
 * and {@link #aboveThreshold} applies the single comparison — strictly greater, as
 * {@code TideFactDeriver} and {@code TideSizeIndex} both make it — to derive what each surface
 * consumes. A fixture flag flipped by hand cannot make this pass, and a change to the comparison on
 * one side and not the other cannot leave it green.
 *
 * <h2>What this class does NOT pin — stated so the guarantee is not read wider than it is</h2>
 *
 * <ul>
 *   <li><b>It does not run {@code TideFactDeriver}.</b> The Plan-tab side is fed a
 *       {@code BriefingSlot.TideInfo} built here, so this pins that the two <em>detectors</em> agree
 *       given the same water, not that the two <em>derivations</em> of the flag agree.
 *       {@code TideFactDeriver}'s own comparison is pinned in its own suite; {@link #aboveThreshold}
 *       mirrors it deliberately and a divergence there would not show up here.</li>
 *   <li><b>It does not model the ±90-minute solar gate.</b>
 *       {@code BriefingSlotBuilder.calculateTideData} zeroes both height flags unless a high water
 *       lands within 90 minutes of the solar event, while {@code TideSizeIndex} applies no solar
 *       gate. So the real surfaces can disagree on a run's <b>edge</b> days, where the big water has
 *       drifted out of the light. That asymmetry is intended — see
 *       {@code SpringTideHotTopicStrategy#isSpringNotKing} — and is covered by
 *       {@code BriefingSlotBuilderTest}. What is pinned here is the failure that actually shipped:
 *       one surface going silent for a whole run while the other reports it.</li>
 *   <li><b>The third assertion (plan §7 P4) does not run {@code ComingUpConditionsBuilder}.</b> It
 *       pins that {@code TideAlmanacSource} — the producer the strip stages verbatim rather than
 *       re-detecting a run — agrees with the other two surfaces given the same height and
 *       threshold. It does not exercise the strip's own occurrence/status derivation; that is
 *       {@code ComingUpConditionsBuilderTest}'s job, covered there by its own status-precedence
 *       tests. Combined, the two suites cover detection agreement and strip-side derivation
 *       separately, matching how the production code is actually split.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class TideSurfaceAgreementTest {

    /**
     * The August 2026 new moon, 12 Aug ~17:38 UTC. The <em>date</em> is externally checkable rather
     * than taken from the implementation under test: a solar eclipse can only happen at new moon,
     * and {@code EclipseCatalog} seeds that day's total eclipse from NASA's Five Millennium Canon.
     *
     * <p>The eclipse anchors the day, <b>not</b> the minute — greatest eclipse is ~17:46 UTC and
     * London's maximum 19:13:13 BST (18:13 UTC), both later than the conjunction. Nothing in this
     * class turns on which instant is used: every assertion below depends only on 14 Aug being more
     * than one day from it, which holds across the whole range.
     */
    private static final LocalDate SYZYGY = LocalDate.of(2026, 8, 12);

    /**
     * The run's biggest water, 1.77 days after syzygy and therefore {@code REGULAR_TIDE} on the
     * lunar axis. This is the reported day: the age of the tide puts a port's peak a day or two
     * after the moon's alignment, and no lunar window one day wide can reach it.
     */
    private static final LocalDate PEAK = LocalDate.of(2026, 8, 14);

    /** A neap day well clear of the run, where both surfaces must stay silent. */
    private static final LocalDate NEAP = LocalDate.of(2026, 8, 20);

    private static final long LOCATION_ID = 7L;

    /** 125% of mean high water for this port. */
    private static final BigDecimal SPRING_THRESHOLD = new BigDecimal("4.00");

    /** This port's P95 high water. Above the spring threshold, as it usually is. */
    private static final BigDecimal P95 = new BigDecimal("4.60");

    /** Clears the spring threshold, not P95: spring-sized, not king-sized. */
    private static final BigDecimal PEAK_HIGH_WATER = new BigDecimal("4.20");

    /** Below both thresholds. */
    private static final BigDecimal NEAP_HIGH_WATER = new BigDecimal("3.10");

    @Mock
    private TideExtremeRepository tideExtremeRepository;

    @Mock
    private TideService tideService;

    @Mock
    private BriefingService briefingService;

    @Mock
    private LocationRepository locationRepository;

    @Mock
    private SolarEventFreshness freshness;

    @Mock
    private CoastalTideFactsBuilder coastalTideFactsBuilder;

    @Mock
    private TideRunBuilder tideRunBuilder;

    @Mock
    private LunarPhaseService lunarPhaseService;

    private TideSizeIndex feed;
    private SpringTideHotTopicStrategy planTab;

    @BeforeEach
    void setUp() {
        feed = new TideSizeIndex(tideExtremeRepository, tideService);
        planTab = new SpringTideHotTopicStrategy(briefingService, locationRepository,
                freshness, coastalTideFactsBuilder, tideRunBuilder, lunarPhaseService);
        // August 2026's new moon is not perigean, so every date here belongs to a SPRING run. The
        // feed makes no lunar call at all, which is the asymmetry this class exists to hold: one
        // surface asks the moon what to CALL the run, both ask the water WHICH DAYS it covers.
        lenient().when(lunarPhaseService.nearestSyzygyIsPerigean(any())).thenReturn(false);
        lenient().when(freshness.isAhead(any(LocationEntity.class), any(), any())).thenReturn(true);
    }

    @Test
    @DisplayName("a date the moon has already left but the water has not: BOTH surfaces report a "
            + "spring tide")
    void peakDayAfterSyzygy_bothSurfacesAgree() {
        // The reported regression, as a single assertion pair. On 14 Aug 2026 the lunar axis says
        // REGULAR_TIDE — 1.77 days from syzygy against a 1.0 day window — while the water at this
        // port is 4.20 m against a 4.00 m spring threshold. If either surface consults only the
        // moon, one of these two assertions fails and the app contradicts itself on screen.
        assertThat(PEAK).isAfter(SYZYGY.plusDays(1));

        stats(SPRING_THRESHOLD, P95);
        extremes(highWater(PEAK.atTime(4, 58), PEAK_HIGH_WATER));
        cacheBriefingFor(PEAK, PEAK_HIGH_WATER, LunarTideType.REGULAR_TIDE);
        stubCoastalRoster();

        TideSizeIndex.Sizes sizes = feed.measure(List.of(coastalLocation()), PEAK, PEAK);
        List<HotTopic> topics = planTab.detect(PEAK, PEAK.plusDays(3));

        assertThat(sizes.springOn(PEAK))
                .as("Coming up feed reports a spring tide on the run's peak day")
                .isTrue();
        assertThat(topics)
                .as("Plan tab reports a spring tide on the same day")
                .singleElement()
                .satisfies(t -> {
                    assertThat(t.type()).isEqualTo("SPRING_TIDE");
                    assertThat(t.date()).isEqualTo(PEAK);
                });

        // The THIRD surface (plan §7 P4): the standing-conditions strip never re-detects a run —
        // it stages whatever TideAlmanacSource already emitted (the same producer the chronology
        // reads), so pinning it here means pinning TideAlmanacSource's own agreement with the other
        // two, given the identical height and threshold. TideRunBuilder is a plain mock (its own
        // figures are covered elsewhere), stubbed only enough that a real run detected from the
        // height alone survives to a `spring-tide` AlmanacEvent, which is the fact this class exists
        // to pin — not the numeric range that ComingUpConditionsBuilderTest already covers.
        when(tideRunBuilder.build(anyList(), anyList(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(java.util.Map.of());
        TideAlmanacSource chronologySource =
                new TideAlmanacSource(lunarPhaseService, tideRunBuilder, locationRepository, feed);

        List<com.gregochr.goldenhour.model.AlmanacEvent> events = chronologySource.events(PEAK, PEAK);

        assertThat(events)
                .as("the chronology's own source — which the strip stages verbatim, never "
                        + "re-detecting a run — reports a spring-tide event on the same day")
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.type()).isEqualTo("spring-tide");
                    assertThat(e.startDate()).isEqualTo(PEAK);
                    assertThat(e.endDate()).isEqualTo(PEAK);
                });
    }

    @Test
    @DisplayName("an ordinary neap day: BOTH surfaces stay silent")
    void neapDay_bothSurfacesSilent() {
        // The converse, and it is not redundant: a fix that made the Plan tab shout on every
        // coastal day would satisfy the test above and be a worse bug than the one it replaced.
        stats(SPRING_THRESHOLD, P95);
        extremes(highWater(NEAP.atTime(5, 30), NEAP_HIGH_WATER));
        cacheBriefingFor(NEAP, NEAP_HIGH_WATER, LunarTideType.REGULAR_TIDE);

        TideSizeIndex.Sizes sizes = feed.measure(List.of(coastalLocation()), NEAP, NEAP);
        List<HotTopic> topics = planTab.detect(NEAP, NEAP.plusDays(3));

        assertThat(sizes.springOn(NEAP)).isFalse();
        assertThat(topics).isEmpty();
    }

    @Test
    @DisplayName("water exactly on the threshold: BOTH surfaces stay silent — the comparison is "
            + "strictly greater on both sides")
    void exactlyOnTheThreshold_bothSurfacesSilent() {
        // The one reading where a >= on one side and a > on the other would show, and the boundary
        // both TideFactDeriver and TideSizeIndex document as strict.
        stats(SPRING_THRESHOLD, P95);
        extremes(highWater(PEAK.atTime(4, 58), SPRING_THRESHOLD));
        cacheBriefingFor(PEAK, SPRING_THRESHOLD, LunarTideType.REGULAR_TIDE);

        assertThat(feed.measure(List.of(coastalLocation()), PEAK, PEAK).springOn(PEAK)).isFalse();
        assertThat(planTab.detect(PEAK, PEAK.plusDays(3))).isEmpty();
    }

    @Test
    @DisplayName("syzygy itself, before the water has built: the Plan tab still speaks on the "
            + "lunar arm alone")
    void syzygyBeforeTheWaterArrives_planTabStillReports() {
        // The other half of the OR, and the reason the height arm is added rather than swapped in.
        // On the day of the new moon the port's water can still be below its threshold — the lag
        // runs the other way at the start of a run — and the lunar arm is what covers it.
        cacheBriefingFor(SYZYGY, NEAP_HIGH_WATER, LunarTideType.SPRING_TIDE);
        stubCoastalRoster();

        assertThat(planTab.detect(SYZYGY, SYZYGY.plusDays(3)))
                .singleElement()
                .satisfies(t -> assertThat(t.date()).isEqualTo(SYZYGY));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * The single comparison both surfaces make. Applied here so that neither side's input is a
     * hand-set verdict — see the class javadoc for why that matters more than it looks.
     *
     * @param height    the day's high water
     * @param threshold the port's threshold, or null when unavailable
     * @return true when the water is strictly above the threshold
     */
    private static boolean aboveThreshold(BigDecimal height, BigDecimal threshold) {
        return threshold != null && height.compareTo(threshold) > 0;
    }

    /**
     * Populates the briefing cache with one coastal slot, deriving both height flags from the same
     * height and thresholds the feed is given.
     *
     * @param date        the briefing day
     * @param highWater   that day's high water
     * @param lunarType   the lunar classification for the date
     */
    private void cacheBriefingFor(LocalDate date, BigDecimal highWater, LunarTideType lunarType) {
        BriefingSlot.TideInfo tide = new BriefingSlot.TideInfo(
                "HIGH", true, null, highWater,
                aboveThreshold(highWater, P95),
                aboveThreshold(highWater, SPRING_THRESHOLD),
                lunarType, "New Moon", false);
        BriefingSlot slot = new BriefingSlot(
                "Coastal 1", null, Verdict.GO, null, tide, List.of(), null);
        BriefingRegion region = new BriefingRegion(
                "Northumberland", Verdict.GO, null, List.of(), List.of(slot),
                null, null, null, null, null, null);
        BriefingEventSummary event = new BriefingEventSummary(
                TargetType.SUNRISE, List.of(region), List.of());
        when(briefingService.getCachedDays()).thenReturn(List.of(new BriefingDay(date,
                List.of(event))));
    }

    private void stubCoastalRoster() {
        when(locationRepository.findCoastalLocations()).thenReturn(List.of(coastalLocation()));
    }

    private void stats(BigDecimal springThreshold, BigDecimal p95) {
        when(tideService.getTideStats(LOCATION_ID)).thenReturn(Optional.of(new TideStats(
                null, null, null, null, 900L, null, null, null,
                p95, 0L, null, springThreshold, null, 0L)));
    }

    private void extremes(TideExtremeEntity... rows) {
        when(tideExtremeRepository.findByLocationIdInAndTypeAndEventTimeBetweenOrderByEventTimeAsc(
                anyCollection(), eq(TideExtremeType.HIGH), any(), any()))
                .thenReturn(List.of(rows));
    }

    private static TideExtremeEntity highWater(LocalDateTime utc, BigDecimal metres) {
        TideExtremeEntity extreme = new TideExtremeEntity();
        extreme.setLocationId(LOCATION_ID);
        extreme.setEventTime(utc);
        extreme.setHeightMetres(metres);
        extreme.setType(TideExtremeType.HIGH);
        return extreme;
    }

    private static LocationEntity coastalLocation() {
        RegionEntity region = new RegionEntity();
        region.setName("Northumberland");
        return LocationEntity.builder()
                .id(LOCATION_ID)
                .name("Coastal 1")
                .lat(55.0)
                .lon(-1.5)
                .tideType(Set.of(TideType.HIGH))
                .region(region)
                .enabled(true)
                .build();
    }
}
