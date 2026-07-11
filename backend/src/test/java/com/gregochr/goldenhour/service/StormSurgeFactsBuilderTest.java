package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.MarineWaveEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.HotTopicFact;
import com.gregochr.goldenhour.model.SurvivorSignals;
import com.gregochr.goldenhour.repository.MarineWaveRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StormSurgeFactsBuilder} — the enriched storm-surge fact line. Wave height
 * leads when present (the drama signal), the surge offset and onshore wind follow, and the strongest
 * surge in the day is chosen as the representative.
 */
@ExtendWith(MockitoExtension.class)
class StormSurgeFactsBuilderTest {

    private static final LocalDate DATE = LocalDate.of(2026, 6, 17);

    @Mock
    private MarineWaveRepository marineWaveRepository;

    private StormSurgeFactsBuilder builder;

    private StormSurgeFactsBuilder builder() {
        return new StormSurgeFactsBuilder(marineWaveRepository);
    }

    private static LocationEntity location(long id) {
        LocationEntity loc = new LocationEntity();
        loc.setId(id);
        loc.setName("Coast-" + id);
        return loc;
    }

    private static SurvivorSignals surgeRow(long locationId, Double surgeM, Double windMs,
            Double windDir) {
        SurvivorSignals.Readings readings = new SurvivorSignals.Readings(
                null, null, null, "HIGH", null, null, null, surgeM, windMs, windDir, null);
        return new SurvivorSignals(location(locationId), DATE, TargetType.SUNSET,
                SurvivorSignals.Scores.EMPTY, readings);
    }

    private static HotTopic baseTopic() {
        return new HotTopic("STORM_SURGE", "Storm surge", "High surge risk", DATE, 1, null,
                List.of(), "desc", null);
    }

    private static HotTopicFact factWithKey(HotTopic topic, String key) {
        return topic.facts().stream().filter(f -> key.equals(f.key())).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("wave height leads with its sea-state band; surge and wind follow")
    void wavesLeadWhenPresent() {
        builder = builder();
        MarineWaveEntity wave = new MarineWaveEntity();
        wave.setSignificantWaveHeightMetres(4.2);
        when(marineWaveRepository.findByLocation_IdAndEvaluationDateAndEventType(
                eq(1L), eq(DATE), eq(TargetType.SUNSET))).thenReturn(Optional.of(wave));

        HotTopic result = builder.attach(baseTopic(), List.of(surgeRow(1L, 0.6, 17.0, 247.0)));

        HotTopicFact waves = factWithKey(result, "waves");
        assertThat(waves.value()).isEqualTo("4.2 m · very rough");
        assertThat(waves.emphasis()).isTrue();

        HotTopicFact surge = factWithKey(result, "surge");
        assertThat(surge.value()).isEqualTo("0.6 m above normal");
        assertThat(surge.emphasis()).isFalse();

        HotTopicFact wind = factWithKey(result, "wind");
        assertThat(wind.value()).isEqualTo("WSW 38 mph");
        assertThat(wind.optional()).isTrue();

        assertThat(result.note()).isEqualTo("shoot long from safe high ground");
    }

    @Test
    @DisplayName("with no marine sample, the surge offset becomes the emphasised headline")
    void surgeIsHeadlineWhenNoWaves() {
        builder = builder();
        when(marineWaveRepository.findByLocation_IdAndEvaluationDateAndEventType(
                eq(1L), eq(DATE), eq(TargetType.SUNSET))).thenReturn(Optional.empty());

        HotTopic result = builder.attach(baseTopic(), List.of(surgeRow(1L, 0.6, 17.0, 247.0)));

        assertThat(result.facts()).noneMatch(f -> "waves".equals(f.key()));
        HotTopicFact surge = factWithKey(result, "surge");
        assertThat(surge.value()).isEqualTo("0.6 m above normal");
        assertThat(surge.emphasis()).isTrue();
    }

    @Test
    @DisplayName("the strongest surge in the day is chosen as the representative")
    void picksStrongestSurge() {
        builder = builder();
        when(marineWaveRepository.findByLocation_IdAndEvaluationDateAndEventType(
                eq(2L), eq(DATE), eq(TargetType.SUNSET))).thenReturn(Optional.empty());

        HotTopic result = builder.attach(baseTopic(), List.of(
                surgeRow(1L, 0.3, 12.0, 200.0),
                surgeRow(2L, 0.8, 20.0, 247.0)));

        // Location 2 has the bigger surge, so its 0.8 m and its marine lookup win.
        assertThat(factWithKey(result, "surge").value()).isEqualTo("0.8 m above normal");
    }

    @Test
    @DisplayName("wind chip omits the compass when direction is unknown")
    void windWithoutDirection() {
        builder = builder();
        when(marineWaveRepository.findByLocation_IdAndEvaluationDateAndEventType(
                eq(1L), eq(DATE), eq(TargetType.SUNSET))).thenReturn(Optional.empty());

        HotTopic result = builder.attach(baseTopic(), List.of(surgeRow(1L, 0.6, 17.0, null)));

        assertThat(factWithKey(result, "wind").value()).isEqualTo("38 mph");
    }

    @Test
    @DisplayName("no facts and no science when the day carries no surge numerics at all")
    void noScienceWhenNoData() {
        builder = builder();
        when(marineWaveRepository.findByLocation_IdAndEvaluationDateAndEventType(
                eq(1L), eq(DATE), eq(TargetType.SUNSET))).thenReturn(Optional.empty());

        HotTopic result = builder.attach(baseTopic(), List.of(surgeRow(1L, null, null, null)));

        assertThat(result.facts()).isNull();
        assertThat(result.note()).isNull();
    }
}
