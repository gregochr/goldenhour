package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.MarineWaveEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.HotTopicFact;
import com.gregochr.goldenhour.model.SurvivorSignals;
import com.gregochr.goldenhour.repository.MarineWaveRepository;
import com.gregochr.goldenhour.repository.TideExtremeRepository;
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

    @Mock
    private TideExtremeRepository tideExtremeRepository;

    /**
     * Built with an UNREFRESHED surge carrier, so it reports EMPTY and no curve is attached — the
     * post-restart state these fact assertions are all written against. The curve's own behaviour
     * is pinned in {@link SurgeRunDayBuilderTest}.
     */
    private StormSurgeFactsBuilder builder() {
        return new StormSurgeFactsBuilder(marineWaveRepository,
                new SurgeCurveService(new StormSurgeService()),
                new SurgeRunDayBuilder(tideExtremeRepository, new SolarService()));
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
    // ── the curve/chip disagreement guard ─────────────────────────────────────

    /** A builder whose carrier holds a curve for location 1 on DATE, with the given values. */
    private StormSurgeFactsBuilder builderWithCurve(java.util.List<Double> series) {
        return builderWithCurve(series, null);
    }

    /** As above, plus the FOLLOWING day's series — the window the chip may have been sampled in. */
    private StormSurgeFactsBuilder builderWithCurve(java.util.List<Double> series,
            java.util.List<Double> nextDay) {
        java.util.Map<java.time.LocalDate, java.util.List<Double>> byDate =
                new java.util.LinkedHashMap<>();
        byDate.put(DATE, series);
        if (nextDay != null) {
            byDate.put(DATE.plusDays(1), nextDay);
        }
        SurgeCurveService carrier = new SurgeCurveService(new StormSurgeService()) {
            @Override
            public com.gregochr.goldenhour.model.SurgeCurve getCached() {
                return new com.gregochr.goldenhour.model.SurgeCurve(java.util.Map.of(1L, byDate));
            }
        };
        return new StormSurgeFactsBuilder(marineWaveRepository, carrier,
                new SurgeRunDayBuilder(tideExtremeRepository, new SolarService()));
    }

    private static java.util.List<Double> flatSeries(double metres) {
        return java.util.Collections.nCopies(24, metres);
    }

    @Test
    @DisplayName("a curve consistent with the chip is attached")
    void curveAgreeingWithChip_isAttached() {
        HotTopic result = builderWithCurve(flatSeries(0.62))
                .attach(baseTopic(), List.of(surgeRow(1L, 0.60, 17.0, 247.0)));

        assertThat(result.surgeRun()).isNotNull();
        assertThat(result.facts()).isNotEmpty();
    }

    @Test
    @DisplayName("a chip OUTSIDE the curve's whole-day envelope suppresses the chart, keeping chips")
    void curveContradictingChip_isSuppressed() {
        // The pill must never show a chart and a number that disagree. Whatever hour the chip
        // sampled, a value nowhere inside the day's range means the carrier is stale — a curve
        // from an earlier build, or another location.
        HotTopic result = builderWithCurve(flatSeries(0.10))
                .attach(baseTopic(), List.of(surgeRow(1L, 0.95, 17.0, 247.0)));

        assertThat(result.surgeRun()).isNull();
        // The facts survive: suppressing the chart must not cost the pill its numbers.
        assertThat(factWithKey(result, "surge").value()).isEqualTo("1.0 m above normal");
    }

    @Test
    @DisplayName("the guard tolerates the hour the chip and the curve sample independently")
    void curveWithinTolerance_isAttached() {
        // The chip is taken at the next high tide after its solar event, which need not be any
        // hour this day's curve peaks at. A near-miss must NOT suppress a good chart.
        HotTopic result = builderWithCurve(flatSeries(0.50))
                .attach(baseTopic(), List.of(surgeRow(1L, 0.60, 17.0, 247.0)));

        assertThat(result.surgeRun()).isNotNull();
    }

    @Test
    @DisplayName("no chip means nothing to contradict, so the curve stands")
    void noChip_curveStillAttached() {
        HotTopic result = builderWithCurve(flatSeries(0.40))
                .attach(baseTopic(), List.of(surgeRow(1L, null, 17.0, 247.0)));

        assertThat(result.surgeRun()).isNotNull();
    }
    /** A day that climbs from calm to a late peak — the overnight-building case. */
    private static java.util.List<Double> risingSeries(double from, double to) {
        java.util.List<Double> out = new java.util.ArrayList<>();
        for (int h = 0; h < 24; h++) {
            out.add(from + (to - from) * (h / 23.0));
        }
        return out;
    }

    @Test
    @DisplayName("a chip sampled on the FOLLOWING day no longer deletes a correct chart")
    void chipSampledNextDay_curveSurvives() {
        // The guard's own javadoc concedes the chip can be taken after midnight. A surge building
        // overnight then produces a chip legitimately larger than today's maximum, and a
        // day-D-only envelope vetoed the chart on exactly the biggest nights — made worse by
        // attach() picking the representative by MAX surge, which selects for that very row.
        HotTopic result = builderWithCurve(risingSeries(0.05, 0.40), risingSeries(0.45, 0.95))
                .attach(baseTopic(), List.of(surgeRow(1L, 0.90, 17.0, 247.0)));

        assertThat(result.surgeRun()).isNotNull();
    }

    @Test
    @DisplayName("a chip outside BOTH days is still suppressed — the guard has not been defanged")
    void chipOutsideBothDays_isSuppressed() {
        HotTopic result = builderWithCurve(risingSeries(0.05, 0.40), risingSeries(0.10, 0.45))
                .attach(baseTopic(), List.of(surgeRow(1L, 2.50, 17.0, 247.0)));

        assertThat(result.surgeRun()).isNull();
    }

    @Test
    @DisplayName("the surge chip stands down when a curve is attached — one number, not two")
    void curveAttached_surgeChipDropped() {
        // The pill was printing the curve's day peak AND the next-high-tide sample, at different
        // precisions, in words that gave no clue they were different instants.
        HotTopic result = builderWithCurve(flatSeries(0.62))
                .attach(baseTopic(), List.of(surgeRow(1L, 0.60, 17.0, 247.0)));

        assertThat(result.surgeRun()).isNotNull();
        assertThat(result.facts()).extracting(HotTopicFact::key).doesNotContain("surge");
    }

    @Test
    @DisplayName("with the chart suppressed the surge chip returns — it is then the only magnitude")
    void curveSuppressed_surgeChipKept() {
        HotTopic result = builderWithCurve(flatSeries(0.10))
                .attach(baseTopic(), List.of(surgeRow(1L, 0.95, 17.0, 247.0)));

        assertThat(result.surgeRun()).isNull();
        assertThat(factWithKey(result, "surge").value()).isEqualTo("1.0 m above normal");
    }
}
