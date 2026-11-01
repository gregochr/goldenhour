package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.CoastalMistBand;
import com.gregochr.goldenhour.model.GlanceLine;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.HotTopicFact;
import com.gregochr.goldenhour.model.SlotSignals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SeaMistHotTopicStrategy} (coastal-mist-plan Phase 2). Every gate is proven in isolation by
 * asserting {@code qualifies} directly on a positive row and on a row that differs ONLY in that gate, so a negative
 * case cannot pass vacuously through the freshness filter (an unstubbed mock answers "passed"). The {@code detect}
 * tests stub freshness ahead and cover the grouping, wording and the freshness drop.
 */
@ExtendWith(MockitoExtension.class)
class SeaMistHotTopicStrategyTest {

    /** 06:00 UTC on 1 Nov 2026 (GMT, so UK today is 1 Nov). */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-01T06:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 1);
    private static final LocalDate FROM = TODAY;
    private static final LocalDate TO = TODAY.plusDays(3);

    @Mock
    private SlotSignalReader slotSignalReader;

    @Mock
    private SolarEventFreshness freshness;

    private SeaMistHotTopicStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new SeaMistHotTopicStrategy(slotSignalReader, freshness, CLOCK);
    }

    private static LocationEntity location(long id, String name, double lat, double lon, Set<TideType> tides,
            boolean mistProne) {
        LocationEntity location = LocationEntity.builder().id(id).name(name).lat(lat).lon(lon).tideType(tides)
                .mistProne(mistProne).build();
        RegionEntity region = new RegionEntity();
        region.setName("Northumberland");
        location.setRegion(region);
        return location;
    }

    private static LocationEntity boulmer() {
        return location(1L, "Boulmer", 55.42, -1.59, Set.of(TideType.HIGH), false);
    }

    private static SlotSignals row(LocationEntity location, LocalDate date, TargetType event,
            CoastalMistBand band, boolean scored, Integer lowCloud, Double precipitation) {
        SlotSignals.Readings readings = new SlotSignals.Readings(
                null, null, null, null, null, null, null, null, null, null, null,
                null, false, null, false, null, band, scored, lowCloud, precipitation);
        return new SlotSignals(location, date, event, SlotSignals.Scores.EMPTY, readings);
    }

    /** A slot that passes every gate. */
    private static SlotSignals passing(LocationEntity location, LocalDate date) {
        return row(location, date, TargetType.SUNRISE, CoastalMistBand.LIGHT, true, 30, 0.0);
    }

    private List<HotTopic> detectOver(SlotSignals... rows) {
        when(slotSignalReader.read(FROM, TO)).thenReturn(List.of(rows));
        return strategy.detect(FROM, TO);
    }

    private void stubAhead(LocationEntity location, LocalDate date) {
        when(freshness.isAhead(location, date, TargetType.SUNRISE)).thenReturn(true);
    }

    @Test
    @DisplayName("a passing sunrise slot raises one SEA_MIST topic with its sunrise anchor, wording and glance")
    void passingSlotFires() {
        LocationEntity site = boulmer();
        stubAhead(site, TODAY);

        List<HotTopic> topics = detectOver(passing(site, TODAY));

        assertThat(topics).hasSize(1);
        HotTopic topic = topics.getFirst();
        assertThat(topic.type()).isEqualTo("SEA_MIST");
        assertThat(topic.label()).isEqualTo("Sea mist");
        assertThat(topic.detail()).isEqualTo("Light sea mist likely at sunrise");
        assertThat(topic.priority()).isEqualTo(3);
        assertThat(topic.date()).isEqualTo(TODAY);
        assertThat(topic.eventType()).isEqualTo("SUNRISE");
        assertThat(topic.regions()).containsExactly("Northumberland");
        assertThat(topic.locationNames()).containsExactly("Boulmer");
        HotTopicFact fact = topic.facts().getFirst();
        assertThat(fact.key()).isEqualTo("sea mist");
        assertThat(fact.value()).isEqualTo("likely at 1 site");
        assertThat(topic.glance()).isEqualTo(new GlanceLine("dawn · light mist, 1 site", "likely", null, true));
    }

    @Test
    @DisplayName("two sites on one date are one topic naming both, and tomorrow is its own topic")
    void groupsPerDate() {
        LocationEntity boulmer = boulmer();
        LocationEntity whitby = location(2L, "Whitby", 54.49, -0.61, Set.of(TideType.LOW), false);
        LocalDate tomorrow = TODAY.plusDays(1);
        stubAhead(boulmer, TODAY);
        stubAhead(whitby, TODAY);
        stubAhead(boulmer, tomorrow);

        List<HotTopic> topics = detectOver(passing(boulmer, TODAY), passing(whitby, TODAY),
                passing(boulmer, tomorrow));

        assertThat(topics).extracting(HotTopic::date).containsExactly(TODAY, tomorrow);
        assertThat(topics.getFirst().locationNames()).containsExactly("Boulmer", "Whitby");
        assertThat(topics.getFirst().facts().getFirst().value()).isEqualTo("likely at 2 sites");
    }

    /** Asserts the gate in isolation: the passing row qualifies and the row differing only in that gate does not. */
    private void assertGate(SlotSignals positive, SlotSignals negative) {
        assertThat(strategy.qualifies(positive)).as("the matching positive row qualifies").isTrue();
        assertThat(strategy.qualifies(negative)).as("the row failing only this gate does not").isFalse();
    }

    @Test
    @DisplayName("band gate: LIGHT qualifies; THICK and NONE never do, with every other gate passing")
    void bandGate() {
        LocationEntity site = boulmer();
        SlotSignals light = passing(site, TODAY);
        assertGate(light, row(site, TODAY, TargetType.SUNRISE, CoastalMistBand.THICK, true, 30, 0.0));
        assertGate(light, row(site, TODAY, TargetType.SUNRISE, CoastalMistBand.NONE, true, 30, 0.0));
    }

    @Test
    @DisplayName("scored gate: an unscored row does not qualify, whatever band it carries")
    void scoredGate() {
        LocationEntity site = boulmer();
        assertGate(passing(site, TODAY),
                row(site, TODAY, TargetType.SUNRISE, CoastalMistBand.LIGHT, false, 30, 0.0));
    }

    @Test
    @DisplayName("event gate: SUNRISE qualifies, SUNSET does not")
    void eventGate() {
        LocationEntity site = boulmer();
        assertGate(passing(site, TODAY),
                row(site, TODAY, TargetType.SUNSET, CoastalMistBand.LIGHT, true, 30, 0.0));
    }

    @Test
    @DisplayName("daysAhead gate: 1 qualifies, 2 does not")
    void daysAheadGate() {
        LocationEntity site = boulmer();
        assertGate(passing(site, TODAY.plusDays(1)), passing(site, TODAY.plusDays(2)));
    }

    @Test
    @DisplayName("daysAhead is counted on the UK civil date: 00:30 BST on 1 July makes 2 July daysAhead 1, 3 July 2")
    void daysAheadUsesUkCivilDate() {
        Clock justAfterUkMidnight = Clock.fixed(Instant.parse("2026-06-30T23:30:00Z"), ZoneOffset.UTC);
        SeaMistHotTopicStrategy bst = new SeaMistHotTopicStrategy(slotSignalReader, freshness, justAfterUkMidnight);
        LocationEntity site = boulmer();
        LocalDate july2 = LocalDate.of(2026, 7, 2);
        LocalDate july3 = LocalDate.of(2026, 7, 3);

        assertThat(bst.qualifies(passing(site, july2))).isTrue();
        assertThat(bst.qualifies(passing(site, july3))).isFalse();
        stubAhead(site, july2);
        when(slotSignalReader.read(july2, july3)).thenReturn(List.of(passing(site, july2), passing(site, july3)));
        assertThat(bst.detect(july2, july3)).extracting(HotTopic::date).containsExactly(july2);
    }

    @Test
    @DisplayName("longitude gate: -2.49 qualifies; -2.50 and -2.51 do not")
    void longitudeGate() {
        SlotSignals east = passing(location(3L, "East edge", 55.0, -2.49, Set.of(TideType.HIGH), false), TODAY);
        assertGate(east, passing(location(4L, "On the line", 55.0, -2.5, Set.of(TideType.HIGH), false), TODAY));
        assertGate(east, passing(location(5L, "West edge", 55.0, -2.51, Set.of(TideType.HIGH), false), TODAY));
    }

    @Test
    @DisplayName("latitude gate: 53.5 (the North Sea floor) qualifies; 53.49 does not, so the Channel coast is out")
    void latitudeGate() {
        SlotSignals floor = passing(location(8L, "Floor", 53.5, -1.0, Set.of(TideType.HIGH), false), TODAY);
        assertGate(floor, passing(location(9L, "Below", 53.49, -1.0, Set.of(TideType.HIGH), false), TODAY));
        assertThat(strategy.qualifies(
                passing(location(10L, "Brighton", 50.82, -0.14, Set.of(TideType.HIGH), false), TODAY))).isFalse();
    }

    @Test
    @DisplayName("coastal predicate gate: a mist_prone site and a site with no tide types do not qualify")
    void coastalPredicateGate() {
        SlotSignals coastal = passing(boulmer(), TODAY);
        assertGate(coastal, passing(location(6L, "Prone", 55.0, -1.5, Set.of(TideType.HIGH), true), TODAY));
        assertGate(coastal, passing(location(7L, "Inland", 55.0, -1.5, Set.of(), false), TODAY));
    }

    @Test
    @DisplayName("low cloud gate: 49 qualifies; 50 and a missing reading do not")
    void lowCloudGate() {
        LocationEntity site = boulmer();
        SlotSignals at49 = row(site, TODAY, TargetType.SUNRISE, CoastalMistBand.LIGHT, true, 49, 0.0);
        assertGate(at49, row(site, TODAY, TargetType.SUNRISE, CoastalMistBand.LIGHT, true, 50, 0.0));
        assertGate(at49, row(site, TODAY, TargetType.SUNRISE, CoastalMistBand.LIGHT, true, null, 0.0));
    }

    @Test
    @DisplayName("precipitation gate: 0.0 qualifies; 0.1 and a missing reading do not")
    void precipitationGate() {
        LocationEntity site = boulmer();
        SlotSignals dry = row(site, TODAY, TargetType.SUNRISE, CoastalMistBand.LIGHT, true, 30, 0.0);
        assertGate(dry, row(site, TODAY, TargetType.SUNRISE, CoastalMistBand.LIGHT, true, 30, 0.1));
        assertGate(dry, row(site, TODAY, TargetType.SUNRISE, CoastalMistBand.LIGHT, true, 30, null));
    }

    @Test
    @DisplayName("readings absent altogether (the EMPTY surface) do not qualify")
    void emptyReadingsDoNotQualify() {
        LocationEntity site = boulmer();
        SlotSignals empty = new SlotSignals(site, TODAY, TargetType.SUNRISE, SlotSignals.Scores.EMPTY,
                SlotSignals.Readings.EMPTY);

        assertGate(passing(site, TODAY), empty);
    }

    @Test
    @DisplayName("a sunrise that has already passed is dropped, even when every gate passes")
    void passedSunriseIsDropped() {
        LocationEntity site = boulmer();
        when(freshness.isAhead(site, TODAY, TargetType.SUNRISE)).thenReturn(false);

        assertThat(strategy.qualifies(passing(site, TODAY))).isTrue();
        assertThat(detectOver(passing(site, TODAY))).isEmpty();
    }
}
