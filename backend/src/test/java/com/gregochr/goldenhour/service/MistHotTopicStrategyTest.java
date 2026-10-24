package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.GlanceLine;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.HotTopicFact;
import com.gregochr.goldenhour.model.MistPotential;
import com.gregochr.goldenhour.model.SlotSignals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MistHotTopicStrategy} (valley-mist-plan M1b).
 *
 * <p>The strategy reads the STORED BAND ({@link SlotSignals.Readings#mistPotential()}), fires on
 * {@link MistPotential#LIKELY} and on nothing else, and counts both events. Fixtures set the scored
 * flag, the score and the band independently so a test can prove the band — not the score — is what
 * gates the topic.
 */
@ExtendWith(MockitoExtension.class)
class MistHotTopicStrategyTest {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 21);
    private static final LocalDate TO = FROM.plusDays(3);

    @Mock
    private SlotSignalReader slotSignalReader;

    @Mock
    private SolarEventFreshness freshness;

    private MistHotTopicStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new MistHotTopicStrategy(slotSignalReader, freshness);
        // Every event is ahead unless a test says otherwise. Without this a mutant that widened
        // the band would still pass the "does not fire" tests, because the unstubbed mock answers
        // "passed" and drops the row for the wrong reason.
        lenient().when(freshness.isAhead(any(LocationEntity.class), any(LocalDate.class),
                any(TargetType.class))).thenReturn(true);
    }

    /** Keeps the given date's event ahead of the freshness cutoff. */
    private void stubAhead(LocalDate date, TargetType event) {
        when(freshness.isAhead(any(LocationEntity.class), eq(date), eq(event))).thenReturn(true);
    }

    private static LocationEntity location(long id, String regionName) {
        LocationEntity location = new LocationEntity();
        location.setId(id);
        if (regionName != null) {
            RegionEntity region = new RegionEntity();
            region.setName(regionName);
            location.setRegion(region);
        }
        return location;
    }

    private static SlotSignals row(LocationEntity location, LocalDate date, TargetType event,
            boolean scored, Double score, MistPotential band) {
        SlotSignals.Readings readings = new SlotSignals.Readings(
                null, null, null, null, null, null, null, null, null, null, null,
                null, false, score, scored, band);
        return new SlotSignals(location, date, event, SlotSignals.Scores.EMPTY, readings);
    }

    private static SlotSignals likely(LocationEntity location, LocalDate date, TargetType event) {
        return row(location, date, event, true, 7.0, MistPotential.LIKELY);
    }

    @Test
    @DisplayName("a LIKELY sunrise row fires one SUNRISE-anchored topic at priority 2 with the cue")
    void detect_likelySunrise_fires() {
        when(slotSignalReader.read(FROM, TO)).thenReturn(
                List.of(likely(location(1, "The Lake District"), FROM, TargetType.SUNRISE)));
        stubAhead(FROM, TargetType.SUNRISE);

        List<HotTopic> topics = strategy.detect(FROM, TO);

        assertThat(topics).hasSize(1);
        HotTopic topic = topics.getFirst();
        assertThat(topic.type()).isEqualTo("MIST");
        assertThat(topic.label()).isEqualTo("Valley mist");
        assertThat(topic.priority()).isEqualTo(2);
        assertThat(topic.date()).isEqualTo(FROM);
        assertThat(topic.eventType()).isEqualTo("SUNRISE");
        assertThat(topic.regions()).containsExactly("The Lake District");
        HotTopicFact fact = topic.facts().getFirst();
        assertThat(fact.key()).isEqualTo("valley mist");
        assertThat(fact.value()).isEqualTo("likely at 1 site");
        assertThat(topic.note()).isEqualTo("get there before the sun — it burns off within the hour");
        assertThat(topic.glance())
                .isEqualTo(new GlanceLine("dawn · likely, 1 site", "likely", null, true));
    }

    @Test
    @DisplayName("a LIKELY sunset row fires a SUNSET-anchored topic with the evening cue")
    void detect_likelySunset_fires() {
        when(slotSignalReader.read(FROM, TO)).thenReturn(
                List.of(likely(location(1, "The Lake District"), FROM, TargetType.SUNSET)));
        stubAhead(FROM, TargetType.SUNSET);

        List<HotTopic> topics = strategy.detect(FROM, TO);

        assertThat(topics).hasSize(1);
        assertThat(topics.getFirst().eventType()).isEqualTo("SUNSET");
        assertThat(topics.getFirst().note()).contains("blue hour");
        assertThat(topics.getFirst().glance())
                .isEqualTo(new GlanceLine("dusk · likely, 1 site", "likely", null, true));
    }

    @Test
    @DisplayName("⚠️ FOGBOUND never fires, even with the highest score: the white frame is not mist")
    void detect_fogbound_doesNotFire() {
        when(slotSignalReader.read(FROM, TO)).thenReturn(List.of(
                row(location(1, "The Lake District"), FROM, TargetType.SUNRISE, true, 9.0,
                        MistPotential.FOGBOUND)));

        assertThat(strategy.detect(FROM, TO)).isEmpty();
    }

    @Test
    @DisplayName("POSSIBLE and NONE never fire")
    void detect_possibleAndNone_doNotFire() {
        when(slotSignalReader.read(FROM, TO)).thenReturn(List.of(
                row(location(1, "The Lake District"), FROM, TargetType.SUNRISE, true, 5.0,
                        MistPotential.POSSIBLE),
                row(location(2, "The Lake District"), FROM, TargetType.SUNSET, true, 1.0,
                        MistPotential.NONE)));

        assertThat(strategy.detect(FROM, TO)).isEmpty();
    }

    @Test
    @DisplayName("an unflagged site (scored, null score, null band) and a pre-column row (unscored) "
            + "contribute nothing, even if a stale band were somehow present on the latter")
    void detect_unscoredOrUnflagged_doNotFire() {
        when(slotSignalReader.read(FROM, TO)).thenReturn(List.of(
                row(location(1, "The Lake District"), FROM, TargetType.SUNRISE, true, null, null),
                row(location(2, "The Lake District"), FROM, TargetType.SUNRISE, false, 8.0,
                        MistPotential.LIKELY)));

        assertThat(strategy.detect(FROM, TO)).isEmpty();
    }

    @Test
    @DisplayName("the BAND gates, not the score: a LIKELY band with a score below the LIKELY cut "
            + "still fires, a high score under another band does not")
    void detect_bandNotScore() {
        when(slotSignalReader.read(FROM, TO)).thenReturn(List.of(
                row(location(1, "The Lake District"), FROM, TargetType.SUNRISE, true, 2.0,
                        MistPotential.LIKELY),
                row(location(2, "The Peak District"), FROM, TargetType.SUNRISE, true, 9.5,
                        MistPotential.POSSIBLE)));
        stubAhead(FROM, TargetType.SUNRISE);

        List<HotTopic> topics = strategy.detect(FROM, TO);

        assertThat(topics).hasSize(1);
        assertThat(topics.getFirst().regions()).containsExactly("The Lake District");
    }

    @Test
    @DisplayName("two LIKELY slots in one region are one topic, naming the region once and counting "
            + "both sites")
    void detect_twoSlotsOneRegion_oneTopic() {
        when(slotSignalReader.read(FROM, TO)).thenReturn(List.of(
                likely(location(1, "The Lake District"), FROM, TargetType.SUNRISE),
                likely(location(2, "The Lake District"), FROM, TargetType.SUNRISE)));
        stubAhead(FROM, TargetType.SUNRISE);

        List<HotTopic> topics = strategy.detect(FROM, TO);

        assertThat(topics).hasSize(1);
        assertThat(topics.getFirst().regions()).containsExactly("The Lake District");
        assertThat(topics.getFirst().facts().getFirst().value()).isEqualTo("likely at 2 sites");
        assertThat(topics.getFirst().glance().value()).isEqualTo("dawn · likely, 2 sites");
    }

    @Test
    @DisplayName("regions group per date: two regions on one morning share a topic, a second morning "
            + "is its own")
    void detect_regionsAndDates() {
        LocalDate next = FROM.plusDays(1);
        when(slotSignalReader.read(FROM, TO)).thenReturn(List.of(
                likely(location(1, "The Lake District"), FROM, TargetType.SUNRISE),
                likely(location(2, "Durham"), FROM, TargetType.SUNRISE),
                likely(location(3, "Durham"), next, TargetType.SUNRISE)));
        stubAhead(FROM, TargetType.SUNRISE);
        stubAhead(next, TargetType.SUNRISE);

        List<HotTopic> topics = strategy.detect(FROM, TO);

        assertThat(topics).hasSize(2);
        assertThat(topics.get(0).date()).isEqualTo(FROM);
        assertThat(topics.get(0).regions()).containsExactlyInAnyOrder("The Lake District", "Durham");
        assertThat(topics.get(1).date()).isEqualTo(next);
        assertThat(topics.get(1).regions()).containsExactly("Durham");
    }

    @Test
    @DisplayName("one date with LIKELY at both events emits a sunrise topic and a sunset topic")
    void detect_bothEventsOneDay_twoTopics() {
        when(slotSignalReader.read(FROM, TO)).thenReturn(List.of(
                likely(location(1, "The Lake District"), FROM, TargetType.SUNRISE),
                likely(location(1, "The Lake District"), FROM, TargetType.SUNSET)));
        stubAhead(FROM, TargetType.SUNRISE);
        stubAhead(FROM, TargetType.SUNSET);

        List<HotTopic> topics = strategy.detect(FROM, TO);

        assertThat(topics).extracting(HotTopic::eventType)
                .containsExactlyInAnyOrder("SUNRISE", "SUNSET");
    }

    @Test
    @DisplayName("an event that has already passed is dropped by the freshness filter")
    void detect_passedEvent_dropped() {
        when(slotSignalReader.read(FROM, TO)).thenReturn(
                List.of(likely(location(1, "The Lake District"), FROM, TargetType.SUNRISE)));
        when(freshness.isAhead(any(LocationEntity.class), eq(FROM), eq(TargetType.SUNRISE)))
                .thenReturn(false);

        assertThat(strategy.detect(FROM, TO)).isEmpty();
    }

    @Test
    @DisplayName("no rows does not fire")
    void detect_noRows_empty() {
        when(slotSignalReader.read(FROM, TO)).thenReturn(List.of());

        assertThat(strategy.detect(FROM, TO)).isEmpty();
    }

    // ── Claude's echo as the fallback (valley-mist-plan M2) ──────────────

    /** A row with the readings given and Claude's echo band on the score half. */
    private static SlotSignals withEcho(LocationEntity location, LocalDate date, TargetType event,
            boolean scored, MistPotential readingBand, String echoBand) {
        SlotSignals.Readings readings = new SlotSignals.Readings(
                null, null, null, null, null, null, null, null, null, null, null,
                null, false, readingBand == null ? null : 7.0, scored, readingBand);
        return new SlotSignals(location, date, event,
                new SlotSignals.Scores(null, null, null, null, echoBand), readings);
    }

    @Test
    @DisplayName("an unscored (pre-V185) row with a LIKELY Claude echo fires: the echo stands in")
    void detect_unscoredRowWithLikelyEcho_fires() {
        when(slotSignalReader.read(FROM, TO)).thenReturn(List.of(
                withEcho(location(1, "The Lake District"), FROM, TargetType.SUNRISE, false, null, "LIKELY")));
        stubAhead(FROM, TargetType.SUNRISE);

        assertThat(strategy.detect(FROM, TO)).hasSize(1);
    }

    @Test
    @DisplayName("a scored row with a NULL band and a LIKELY echo does not fire: the calculator's null wins")
    void detect_scoredNullBandWithLikelyEcho_doesNotFire() {
        when(slotSignalReader.read(FROM, TO)).thenReturn(List.of(
                withEcho(location(1, "The Lake District"), FROM, TargetType.SUNRISE, true, null, "LIKELY")));

        assertThat(strategy.detect(FROM, TO)).isEmpty();
    }

    @Test
    @DisplayName("a scored POSSIBLE row beats a LIKELY echo: the echo never promotes the calculator's band")
    void detect_scoredPossibleWithLikelyEcho_doesNotFire() {
        when(slotSignalReader.read(FROM, TO)).thenReturn(List.of(
                withEcho(location(1, "The Lake District"), FROM, TargetType.SUNRISE, true,
                        MistPotential.POSSIBLE, "LIKELY")));

        assertThat(strategy.detect(FROM, TO)).isEmpty();
    }

    @Test
    @DisplayName("an unscored row with a FOGBOUND echo does not fire")
    void detect_unscoredRowWithFogboundEcho_doesNotFire() {
        when(slotSignalReader.read(FROM, TO)).thenReturn(List.of(
                withEcho(location(1, "The Lake District"), FROM, TargetType.SUNRISE, false, null, "FOGBOUND")));

        assertThat(strategy.detect(FROM, TO)).isEmpty();
    }
}
