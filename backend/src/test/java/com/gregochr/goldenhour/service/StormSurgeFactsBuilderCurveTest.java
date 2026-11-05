package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.MarineWaveEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.HotTopicFact;
import com.gregochr.goldenhour.model.SlotSignals;
import com.gregochr.goldenhour.model.SurgeCurve;
import com.gregochr.goldenhour.model.SurgeRunDay;
import com.gregochr.goldenhour.repository.MarineWaveRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The surge-curve decisions of {@link StormSurgeFactsBuilder}: when the day's curve is attached or suppressed
 * against the persisted chip, what that does to the chips beside it, and the empty and null edges.
 */
class StormSurgeFactsBuilderCurveTest {

    private static final LocalDate DATE = LocalDate.of(2026, 6, 17);
    private static final long LOCATION_ID = 1L;

    private MarineWaveRepository marineWaveRepository;
    private SurgeCurveService surgeCurveService;
    private SurgeRunDayBuilder surgeRunDayBuilder;
    private StormSurgeFactsBuilder builder;
    private LocationEntity location;
    private SurgeCurve curve;

    @BeforeEach
    void setUp() {
        marineWaveRepository = mock(MarineWaveRepository.class);
        surgeCurveService = mock(SurgeCurveService.class);
        surgeRunDayBuilder = mock(SurgeRunDayBuilder.class);
        builder = new StormSurgeFactsBuilder(marineWaveRepository, surgeCurveService, surgeRunDayBuilder);
        location = new LocationEntity();
        location.setId(LOCATION_ID);
        location.setName("Coast-1");
        curve = new SurgeCurve(Map.of());
        when(surgeCurveService.getCached()).thenReturn(curve);
    }

    private SlotSignals row(Double surgeM) {
        SlotSignals.Readings readings = new SlotSignals.Readings(
                null, null, null, "HIGH", null, null, null, surgeM, 10.0, 90.0, null, null, false);
        return new SlotSignals(location, DATE, TargetType.SUNSET, SlotSignals.Scores.EMPTY, readings);
    }

    private static HotTopic baseTopic() {
        return new HotTopic("STORM_SURGE", "Storm surge", "High surge risk", DATE, 1, null, List.of(), "desc",
                null);
    }

    private static SurgeRunDay run(List<Double> surge) {
        return new SurgeRunDay("Coast-1", surge, List.of(), "+0.7 m", "14:00", null, "05:00", "21:00", "verdict",
                false, "note", "phrase");
    }

    private void noWaves() {
        when(marineWaveRepository.findByLocation_IdAndEvaluationDateAndEventType(LOCATION_ID, DATE,
                TargetType.SUNSET)).thenReturn(Optional.empty());
    }

    private void waves(double hs) {
        MarineWaveEntity wave = new MarineWaveEntity();
        wave.setSignificantWaveHeightMetres(hs);
        when(marineWaveRepository.findByLocation_IdAndEvaluationDateAndEventType(LOCATION_ID, DATE,
                TargetType.SUNSET)).thenReturn(Optional.of(wave));
    }

    private static HotTopicFact fact(HotTopic topic, String key) {
        return topic.facts().stream().filter(f -> key.equals(f.key())).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("no rows for the day leaves the topic untouched")
    void attach_noRows_unchanged() {
        HotTopic topic = baseTopic();

        assertThat(builder.attach(topic, List.of())).isSameAs(topic);
    }

    @Test
    @DisplayName("an agreeing curve with no waves replaces the surge chip, and wind becomes the lead fact")
    void attach_curveWithoutWaves_windLeads() {
        SurgeRunDay run = run(List.of(0.1, 0.6, 0.9));
        SlotSignals slot = row(0.6);
        noWaves();
        when(surgeRunDayBuilder.build(location, DATE, curve)).thenReturn(run);

        HotTopic result = builder.attach(baseTopic(), List.of(slot));

        assertThat(result.surgeRun()).isSameAs(run);
        assertThat(result.facts()).extracting(HotTopicFact::key).containsExactly("wind");
        assertThat(fact(result, "wind").optional()).isFalse();
    }

    @Test
    @DisplayName("an agreeing curve with waves keeps the waves chip and demotes wind to optional")
    void attach_curveWithWaves_windOptional() {
        SurgeRunDay run = run(List.of(0.1, 0.6, 0.9));
        SlotSignals slot = row(0.6);
        waves(2.4);
        when(surgeRunDayBuilder.build(location, DATE, curve)).thenReturn(run);

        HotTopic result = builder.attach(baseTopic(), List.of(slot));

        assertThat(result.surgeRun()).isSameAs(run);
        assertThat(result.facts()).extracting(HotTopicFact::key).containsExactly("waves", "wind");
        assertThat(fact(result, "wind").optional()).isTrue();
    }

    @Test
    @DisplayName("a chip above the curve's envelope suppresses the chart and keeps the surge chip")
    void attach_chipAboveEnvelope_curveSuppressed() {
        SlotSignals slot = row(1.2);
        noWaves();
        when(surgeRunDayBuilder.build(location, DATE, curve)).thenReturn(run(List.of(0.1, 0.6, 0.9)));

        HotTopic result = builder.attach(baseTopic(), List.of(slot));

        assertThat(result.surgeRun()).isNull();
        assertThat(fact(result, "surge").value()).isEqualTo("1.2 m above normal at high water");
    }

    @Test
    @DisplayName("a chip below the curve's envelope suppresses the chart too")
    void attach_chipBelowEnvelope_curveSuppressed() {
        SlotSignals slot = row(0.2);
        noWaves();
        when(surgeRunDayBuilder.build(location, DATE, curve)).thenReturn(run(List.of(0.6, 0.9)));

        HotTopic result = builder.attach(baseTopic(), List.of(slot));

        assertThat(result.surgeRun()).isNull();
        assertThat(fact(result, "surge").value()).isEqualTo("0.2 m above normal at high water");
    }

    @Test
    @DisplayName("a chip within tolerance of the envelope edge still agrees")
    void attach_chipWithinTolerance_curveKept() {
        SlotSignals slot = row(1.0);
        noWaves();
        SurgeRunDay run = run(List.of(0.6, 0.9));
        when(surgeRunDayBuilder.build(location, DATE, curve)).thenReturn(run);

        assertThat(builder.attach(baseTopic(), List.of(slot)).surgeRun()).isSameAs(run);
    }

    @Test
    @DisplayName("tomorrow's series widens the envelope, so an overnight surge peak still agrees")
    void attach_chipInTomorrowsRange_curveKept() {
        SurgeCurve withTomorrow = new SurgeCurve(Map.of(LOCATION_ID,
                Map.of(DATE.plusDays(1), List.of(0.4, 1.4))));
        when(surgeCurveService.getCached()).thenReturn(withTomorrow);
        SurgeRunDay run = run(List.of(0.1, 0.6));
        SlotSignals slot = row(1.3);
        noWaves();
        when(surgeRunDayBuilder.build(location, DATE, withTomorrow)).thenReturn(run);

        assertThat(builder.attach(baseTopic(), List.of(slot)).surgeRun()).isSameAs(run);
    }

    @Test
    @DisplayName("a curve whose hours are all gaps, or has no series, cannot contradict the chip")
    void attach_curveWithNoValues_curveKept() {
        SlotSignals slot = row(0.6);
        noWaves();
        SurgeRunDay allGaps = run(new ArrayList<>(Arrays.asList(null, null)));
        when(surgeRunDayBuilder.build(location, DATE, curve)).thenReturn(allGaps);
        assertThat(builder.attach(baseTopic(), List.of(slot)).surgeRun()).isSameAs(allGaps);

        SurgeRunDay noSeries = run(null);
        when(surgeRunDayBuilder.build(location, DATE, curve)).thenReturn(noSeries);
        assertThat(builder.attach(baseTopic(), List.of(slot)).surgeRun()).isSameAs(noSeries);
    }

    @Test
    @DisplayName("a row with no surge reading has no chip to contradict; the curve stands and no surge chip shows")
    void attach_noSurgeReading_curveKept() {
        SlotSignals slot = row(null);
        noWaves();
        SurgeRunDay run = run(List.of(0.1, 0.6));
        when(surgeRunDayBuilder.build(location, DATE, curve)).thenReturn(run);

        HotTopic result = builder.attach(baseTopic(), List.of(slot));

        assertThat(result.surgeRun()).isSameAs(run);
        assertThat(result.facts()).extracting(HotTopicFact::key).containsExactly("wind");
    }

    @Test
    @DisplayName("an unavailable carrier is tolerated: the day's own series alone decides")
    void attach_nullCarrier_daySeriesDecides() {
        when(surgeCurveService.getCached()).thenReturn(null);
        SlotSignals slot = row(0.6);
        noWaves();
        SurgeRunDay run = run(List.of(0.1, 0.9));
        when(surgeRunDayBuilder.build(location, DATE, null)).thenReturn(run);

        HotTopic result = builder.attach(baseTopic(), List.of(slot));

        assertThat(result.surgeRun()).isSameAs(run);
    }
}
