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
 * Unit tests for {@link SeaMistHotTopicStrategy} (coastal-mist-plan Phase 2). Every gate gets a test where it
 * alone fails, against a base slot that passes all of them, so a gate that was dropped or widened is caught.
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

    private static LocationEntity location(long id, String name, double lon, Set<TideType> tides, boolean mistProne) {
        LocationEntity location = LocationEntity.builder().id(id).name(name).lon(lon).tideType(tides)
                .mistProne(mistProne).build();
        RegionEntity region = new RegionEntity();
        region.setName("Northumberland");
        location.setRegion(region);
        return location;
    }

    private static LocationEntity boulmer() {
        return location(1L, "Boulmer", -1.59, Set.of(TideType.HIGH), false);
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
        LocationEntity whitby = location(2L, "Whitby", -0.61, Set.of(TideType.LOW), false);
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

    @Test
    @DisplayName("THICK and NONE never fire, even with every other gate passing")
    void thickAndNoneNeverFire() {
        LocationEntity site = boulmer();

        assertThat(detectOver(row(site, TODAY, TargetType.SUNRISE, CoastalMistBand.THICK, true, 30, 0.0)))
                .isEmpty();
        assertThat(detectOver(row(site, TODAY, TargetType.SUNRISE, CoastalMistBand.NONE, true, 30, 0.0)))
                .isEmpty();
    }

    @Test
    @DisplayName("a row the coastal check never scored does not fire, whatever band it carries")
    void unscoredRowDoesNotFire() {
        assertThat(detectOver(row(boulmer(), TODAY, TargetType.SUNRISE, CoastalMistBand.LIGHT, false, 30, 0.0)))
                .isEmpty();
    }

    @Test
    @DisplayName("a SUNSET slot does not fire")
    void sunsetDoesNotFire() {
        assertThat(detectOver(row(boulmer(), TODAY, TargetType.SUNSET, CoastalMistBand.LIGHT, true, 30, 0.0)))
                .isEmpty();
    }

    @Test
    @DisplayName("daysAhead 1 fires and daysAhead 2 does not")
    void daysAheadGate() {
        LocationEntity site = boulmer();
        LocalDate tomorrow = TODAY.plusDays(1);
        stubAhead(site, tomorrow);

        assertThat(detectOver(passing(site, tomorrow))).hasSize(1);
        assertThat(detectOver(passing(site, TODAY.plusDays(2)))).isEmpty();
    }

    @Test
    @DisplayName("daysAhead is counted on the UK civil date: 00:30 BST on 1 July is already 1 July")
    void daysAheadUsesUkCivilDate() {
        // 23:30 UTC on 30 June is 00:30 BST on 1 July, so UK today is 1 July: 2 July is daysAhead 1, 3 July is 2.
        Clock justAfterUkMidnight = Clock.fixed(Instant.parse("2026-06-30T23:30:00Z"), ZoneOffset.UTC);
        SeaMistHotTopicStrategy bst = new SeaMistHotTopicStrategy(slotSignalReader, freshness, justAfterUkMidnight);
        LocationEntity site = boulmer();
        LocalDate july2 = LocalDate.of(2026, 7, 2);
        LocalDate july3 = LocalDate.of(2026, 7, 3);
        stubAhead(site, july2);
        when(slotSignalReader.read(july2, july3)).thenReturn(List.of(passing(site, july2), passing(site, july3)));

        List<HotTopic> topics = bst.detect(july2, july3);

        assertThat(topics).extracting(HotTopic::date).containsExactly(july2);
    }

    @Test
    @DisplayName("longitude -2.49 is east coast and fires; -2.50 and -2.51 do not")
    void longitudeGate() {
        LocationEntity east = location(3L, "East edge", -2.49, Set.of(TideType.HIGH), false);
        stubAhead(east, TODAY);

        assertThat(detectOver(passing(east, TODAY))).hasSize(1);
        assertThat(detectOver(passing(location(4L, "On the line", -2.5, Set.of(TideType.HIGH), false), TODAY)))
                .isEmpty();
        assertThat(detectOver(passing(location(5L, "West edge", -2.51, Set.of(TideType.HIGH), false), TODAY)))
                .isEmpty();
    }

    @Test
    @DisplayName("a mist_prone site, and a site with no tide types, do not fire")
    void coastalPredicateGate() {
        assertThat(detectOver(passing(location(6L, "Prone", -1.5, Set.of(TideType.HIGH), true), TODAY))).isEmpty();
        assertThat(detectOver(passing(location(7L, "Inland", -1.5, Set.of(), false), TODAY))).isEmpty();
    }

    @Test
    @DisplayName("low cloud 49 fires and 50 does not; a missing reading does not")
    void lowCloudGate() {
        LocationEntity site = boulmer();
        stubAhead(site, TODAY);

        assertThat(detectOver(row(site, TODAY, TargetType.SUNRISE, CoastalMistBand.LIGHT, true, 49, 0.0)))
                .hasSize(1);
        assertThat(detectOver(row(site, TODAY, TargetType.SUNRISE, CoastalMistBand.LIGHT, true, 50, 0.0)))
                .isEmpty();
        assertThat(detectOver(row(site, TODAY, TargetType.SUNRISE, CoastalMistBand.LIGHT, true, null, 0.0)))
                .isEmpty();
    }

    @Test
    @DisplayName("any precipitation, or a missing reading, does not fire")
    void precipitationGate() {
        LocationEntity site = boulmer();

        assertThat(detectOver(row(site, TODAY, TargetType.SUNRISE, CoastalMistBand.LIGHT, true, 30, 0.1)))
                .isEmpty();
        assertThat(detectOver(row(site, TODAY, TargetType.SUNRISE, CoastalMistBand.LIGHT, true, 30, null)))
                .isEmpty();
    }

    @Test
    @DisplayName("a sunrise that has already passed is dropped, even when every gate passes")
    void passedSunriseIsDropped() {
        LocationEntity site = boulmer();
        when(freshness.isAhead(site, TODAY, TargetType.SUNRISE)).thenReturn(false);

        assertThat(detectOver(passing(site, TODAY))).isEmpty();
    }

    @Test
    @DisplayName("readings absent altogether (the EMPTY surface) do not fire")
    void emptyReadingsDoNotFire() {
        SlotSignals empty = new SlotSignals(boulmer(), TODAY, TargetType.SUNRISE, SlotSignals.Scores.EMPTY,
                SlotSignals.Readings.EMPTY);

        assertThat(detectOver(empty)).isEmpty();
    }
}
