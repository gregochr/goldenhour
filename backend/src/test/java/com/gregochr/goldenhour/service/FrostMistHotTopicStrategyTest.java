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
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FrostMistHotTopicStrategy} (frost-mist-plan §3.5).
 */
@ExtendWith(MockitoExtension.class)
class FrostMistHotTopicStrategyTest {

    private static final LocalDate FROM = LocalDate.of(2026, 1, 12);
    private static final LocalDate TO = FROM.plusDays(3);

    @Mock
    private SlotSignalReader slotSignalReader;

    @Mock
    private SolarEventFreshness freshness;

    private FrostMistHotTopicStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new FrostMistHotTopicStrategy(slotSignalReader, freshness);
    }

    private static LocationEntity place(long id, String name, String regionName) {
        LocationEntity location = new LocationEntity();
        location.setId(id);
        location.setName(name);
        RegionEntity region = new RegionEntity();
        region.setName(regionName);
        location.setRegion(region);
        return location;
    }

    private static SlotSignals row(LocationEntity place, LocalDate date, TargetType event, MistPotential band,
            Double temperature) {
        SlotSignals.Readings readings = new SlotSignals.Readings(
                null, null, null, null, null, null, null, null, null, null, temperature,
                null, false, 7.0, true, band);
        return new SlotSignals(place, date, event, SlotSignals.Scores.EMPTY, readings);
    }

    private static SlotSignals frosty(LocationEntity place, LocalDate date, double temperature) {
        return row(place, date, TargetType.SUNRISE, MistPotential.LIKELY, temperature);
    }

    private void ahead(LocationEntity place, LocalDate date) {
        when(freshness.isAhead(place, date, TargetType.SUNRISE)).thenReturn(true);
    }

    @Test
    @DisplayName("a LIKELY sunrise at -0.1 °C fires one SUNRISE topic with the planned copy, facts and glance")
    void detect_justBelowFreezing_fires() {
        LocationEntity tarn = place(1, "Tarn Hows", "The Lake District");
        when(slotSignalReader.read(FROM, TO)).thenReturn(List.of(frosty(tarn, FROM, -0.1)));
        ahead(tarn, FROM);

        List<HotTopic> topics = strategy.detect(FROM, TO);

        assertThat(topics).hasSize(1);
        HotTopic topic = topics.getFirst();
        assertThat(topic.type()).isEqualTo("FROST_MIST");
        assertThat(topic.label()).isEqualTo("Frost & mist");
        assertThat(topic.detail()).isEqualTo("Hoar frost and valley mist at first light");
        assertThat(topic.priority()).isEqualTo(1);
        assertThat(topic.date()).isEqualTo(FROM);
        assertThat(topic.eventType()).isEqualTo("SUNRISE");
        assertThat(topic.eventTime()).isNull();
        assertThat(topic.regions()).containsExactly("The Lake District");
        assertThat(topic.locationNames()).containsExactly("Tarn Hows");
        assertThat(topic.description()).startsWith("On a cold, still, misty night, hoar frost can feather")
                .endsWith("so first light is the window.");
        assertThat(topic.facts()).containsExactly(
                new HotTopicFact("air", "-1 °C · frost likely", null, false, false),
                HotTopicFact.metric("valley mist", "likely at 1 site"));
        assertThat(topic.note()).isEqualTo("densest before dawn — frost on every branch");
        assertThat(topic.glance()).isEqualTo(new GlanceLine("dawn · -1 °C, 1 site", null, true));
    }

    @Test
    @DisplayName("exactly 0.0 °C does not fire")
    void detect_exactlyFreezing_doesNotFire() {
        LocationEntity tarn = place(1, "Tarn Hows", "The Lake District");
        when(slotSignalReader.read(FROM, TO)).thenReturn(List.of(frosty(tarn, FROM, 0.0)));

        assertThat(strategy.detect(FROM, TO)).isEmpty();
    }

    @Test
    @DisplayName("a null temperature never fires")
    void detect_nullTemperature_doesNotFire() {
        LocationEntity tarn = place(1, "Tarn Hows", "The Lake District");
        when(slotSignalReader.read(FROM, TO)).thenReturn(List.of(
                row(tarn, FROM, TargetType.SUNRISE, MistPotential.LIKELY, null)));

        assertThat(strategy.detect(FROM, TO)).isEmpty();
    }

    @Test
    @DisplayName("POSSIBLE, FOGBOUND, NONE and an absent band never fire, however cold")
    void detect_otherBands_doNotFire() {
        LocationEntity tarn = place(1, "Tarn Hows", "The Lake District");
        when(slotSignalReader.read(FROM, TO)).thenReturn(List.of(
                row(tarn, FROM, TargetType.SUNRISE, MistPotential.POSSIBLE, -5.0),
                row(tarn, FROM, TargetType.SUNRISE, MistPotential.FOGBOUND, -5.0),
                row(tarn, FROM, TargetType.SUNRISE, MistPotential.NONE, -5.0),
                row(tarn, FROM, TargetType.SUNRISE, null, -5.0)));

        assertThat(strategy.detect(FROM, TO)).isEmpty();
    }

    @Test
    @DisplayName("a SUNSET row never fires")
    void detect_sunset_doesNotFire() {
        LocationEntity tarn = place(1, "Tarn Hows", "The Lake District");
        when(slotSignalReader.read(FROM, TO)).thenReturn(List.of(
                row(tarn, FROM, TargetType.SUNSET, MistPotential.LIKELY, -5.0)));

        assertThat(strategy.detect(FROM, TO)).isEmpty();
    }

    @Test
    @DisplayName("a sunrise already past is dropped by the freshness filter")
    void detect_passedSunrise_dropped() {
        LocationEntity tarn = place(1, "Tarn Hows", "The Lake District");
        when(slotSignalReader.read(FROM, TO)).thenReturn(List.of(frosty(tarn, FROM, -2.0)));
        when(freshness.isAhead(tarn, FROM, TargetType.SUNRISE)).thenReturn(false);

        assertThat(strategy.detect(FROM, TO)).isEmpty();
    }

    @Test
    @DisplayName("per day: exact ringed places and only their regions; a mild misty place is not named")
    void detect_perDay_exactLocationNamesAndRegions() {
        LocationEntity tarn = place(1, "Tarn Hows", "The Lake District");
        LocationEntity crags = place(2, "Hardcastle Crags", "The Pennines");
        LocationEntity mild = place(3, "Bamburgh", "Northumberland");
        when(slotSignalReader.read(FROM, TO)).thenReturn(List.of(
                frosty(tarn, FROM, -2.0), frosty(crags, FROM, -4.0),
                row(mild, FROM, TargetType.SUNRISE, MistPotential.LIKELY, 3.0)));
        ahead(tarn, FROM);
        ahead(crags, FROM);

        List<HotTopic> topics = strategy.detect(FROM, TO);

        assertThat(topics).hasSize(1);
        assertThat(topics.getFirst().locationNames()).containsExactly("Tarn Hows", "Hardcastle Crags");
        assertThat(topics.getFirst().regions()).containsExactly("The Lake District", "The Pennines");
    }

    @Test
    @DisplayName("facts use the day's coldest place and the site count, and a multi-day spell is one topic a day")
    void detect_multiDay_coldestAndSiteCountPerDay() {
        LocationEntity tarn = place(1, "Tarn Hows", "The Lake District");
        LocationEntity crags = place(2, "Hardcastle Crags", "The Pennines");
        LocalDate next = FROM.plusDays(1);
        when(slotSignalReader.read(FROM, TO)).thenReturn(List.of(
                frosty(tarn, FROM, -2.0), frosty(crags, FROM, -4.4), frosty(tarn, next, -0.4)));
        ahead(tarn, FROM);
        ahead(crags, FROM);
        ahead(tarn, next);

        List<HotTopic> topics = strategy.detect(FROM, TO);

        assertThat(topics).extracting(HotTopic::date).containsExactly(FROM, next);
        assertThat(topics.get(0).facts()).containsExactly(
                new HotTopicFact("air", "-4 °C · frost likely", null, false, false),
                HotTopicFact.metric("valley mist", "likely at 2 sites"));
        assertThat(topics.get(0).glance().value()).isEqualTo("dawn · -4 °C, 2 sites");
        assertThat(topics.get(1).facts().getFirst().value()).isEqualTo("-1 °C · frost likely");
        assertThat(topics.get(1).glance().value()).isEqualTo("dawn · -1 °C, 1 site");
    }

    @Test
    @DisplayName("a reading in [-0.5, 0) is shown as -1 °C, never 0")
    void detect_roundingGuard() {
        LocationEntity tarn = place(1, "Tarn Hows", "The Lake District");
        when(slotSignalReader.read(FROM, TO)).thenReturn(List.of(frosty(tarn, FROM, -0.5)));
        ahead(tarn, FROM);

        assertThat(strategy.detect(FROM, TO).getFirst().facts().getFirst().value())
                .isEqualTo("-1 °C · frost likely");
    }

    @Test
    @DisplayName("a place with snow lying still fires: there is no snow exclusion")
    void detect_snowyPlace_stillFires() {
        LocationEntity fell = place(4, "Great Gable", "The Lake District");
        SlotSignals.Readings readings = new SlotSignals.Readings(
                null, null, null, null, 0.3, null, null, null, null, null, -3.0,
                null, false, 7.0, true, MistPotential.LIKELY);
        when(slotSignalReader.read(FROM, TO)).thenReturn(List.of(
                new SlotSignals(fell, FROM, TargetType.SUNRISE, SlotSignals.Scores.EMPTY, readings)));
        ahead(fell, FROM);

        assertThat(strategy.detect(FROM, TO)).hasSize(1);
    }

    @Test
    @DisplayName("isFrostMist: a row with no readings is not frost-and-mist")
    void isFrostMist_noReadings_false() {
        SlotSignals bare = new SlotSignals(place(1, "Tarn Hows", "The Lake District"), FROM,
                TargetType.SUNRISE, SlotSignals.Scores.EMPTY, null);

        assertThat(FrostMistHotTopicStrategy.isFrostMist(bare)).isFalse();
    }
}
