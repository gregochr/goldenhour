package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TideExtremeEntity;
import com.gregochr.goldenhour.entity.TideExtremeType;
import com.gregochr.goldenhour.model.SurgeCurve;
import com.gregochr.goldenhour.model.SurgeRunDay;
import com.gregochr.goldenhour.repository.TideExtremeRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Edge cases of {@link SurgeRunDayBuilder}: missing inputs, how far the peak sits from high water (the sentence
 * and its units), which stored extreme counts as the day's high water, and a calculator with no sunrise.
 * Dates sit in January so Europe/London is UTC.
 */
class SurgeRunDayBuilderEdgeTest {

    private static final LocalDate DAY = LocalDate.of(2026, 1, 20);
    private static final long ID = 1L;
    private static final LocalDateTime WINDOW_START = DAY.minusDays(1).atStartOfDay();
    private static final LocalDateTime WINDOW_END = DAY.plusDays(2).atStartOfDay();

    private TideExtremeRepository tideExtremeRepository;
    private SurgeRunDayBuilder builder;

    @BeforeEach
    void setUp() {
        tideExtremeRepository = mock(TideExtremeRepository.class);
        builder = new SurgeRunDayBuilder(tideExtremeRepository, new SolarService());
    }

    private static LocationEntity seaham() {
        return LocationEntity.builder().id(ID).name("Seaham").lat(54.84).lon(-1.33).build();
    }

    private static SurgeCurve curveWithPeakAt(int hour, double metres) {
        List<Double> series = new ArrayList<>(Arrays.asList(new Double[24]));
        series.set(hour, metres);
        return new SurgeCurve(Map.of(ID, Map.of(DAY, series)));
    }

    private static TideExtremeEntity extreme(TideExtremeType type, LocalDateTime time, String metres) {
        return TideExtremeEntity.builder().locationId(ID).type(type).eventTime(time)
                .heightMetres(new BigDecimal(metres)).build();
    }

    private void storedExtremes(TideExtremeEntity... rows) {
        when(tideExtremeRepository.findByLocationIdAndEventTimeBetweenOrderByEventTimeAsc(ID, WINDOW_START,
                WINDOW_END)).thenReturn(List.of(rows));
    }

    @Test
    @DisplayName("no location, no date or no carrier gives no row")
    void build_missingInputs_null() {
        SurgeCurve curve = curveWithPeakAt(14, 0.72);

        assertThat(builder.build(null, DAY, curve)).isNull();
        assertThat(builder.build(seaham(), null, curve)).isNull();
        assertThat(builder.build(seaham(), DAY, null)).isNull();
    }

    @Test
    @DisplayName("a peak under an hour before high water reads in minutes, 'before'")
    void build_peakMinutesBeforeHighWater() {
        storedExtremes(extreme(TideExtremeType.HIGH, DAY.atTime(14, 45), "5.1"));

        SurgeRunDay day = builder.build(seaham(), DAY, curveWithPeakAt(14, 0.72));

        assertThat(day.verdict()).isEqualTo("peak +0.72 m at 14:00 · 45m before high water");
        assertThat(day.aligned()).isTrue();
    }

    @Test
    @DisplayName("a peak over an hour after high water reads in hours and minutes, 'after'")
    void build_peakHoursAfterHighWater() {
        storedExtremes(extreme(TideExtremeType.HIGH, DAY.atTime(12, 40), "5.1"));

        SurgeRunDay day = builder.build(seaham(), DAY, curveWithPeakAt(14, 0.72));

        assertThat(day.verdict()).isEqualTo("peak +0.72 m at 14:00 · 1h20 after high water");
        assertThat(day.aligned()).isTrue();
    }

    @Test
    @DisplayName("a peak further than 90 minutes from high water misses it and says when high water is")
    void build_peakMissesHighWater() {
        storedExtremes(extreme(TideExtremeType.HIGH, DAY.atTime(18, 30), "5.1"));

        SurgeRunDay day = builder.build(seaham(), DAY, curveWithPeakAt(14, 0.72));

        assertThat(day.verdict()).isEqualTo("peak +0.72 m at 14:00 · high water 18:30, misses it");
        assertThat(day.aligned()).isFalse();
        assertThat(day.highWaterTime()).isEqualTo("18:30");
    }

    @Test
    @DisplayName("low waters and other days' high waters are ignored; the taller of the day's high waters wins")
    void build_choosesTheDaysTallestHighWater() {
        storedExtremes(
                extreme(TideExtremeType.LOW, DAY.atTime(14, 0), "0.5"),
                extreme(TideExtremeType.HIGH, DAY.minusDays(1).atTime(14, 0), "9.0"),
                extreme(TideExtremeType.HIGH, DAY.atTime(2, 0), "4.0"),
                extreme(TideExtremeType.HIGH, DAY.atTime(14, 5), "5.1"),
                extreme(TideExtremeType.HIGH, DAY.atTime(18, 0), "4.5"));

        SurgeRunDay day = builder.build(seaham(), DAY, curveWithPeakAt(14, 0.72));

        assertThat(day.highWaterTime()).isEqualTo("14:05");
        assertThat(day.verdict()).isEqualTo("peak +0.72 m at 14:00 · on high water");
    }

    @Test
    @DisplayName("with no stored high water the row says so and carries no high-water time")
    void build_noHighWater() {
        storedExtremes();

        SurgeRunDay day = builder.build(seaham(), DAY, curveWithPeakAt(14, 0.72));

        assertThat(day.highWaterTime()).isNull();
        assertThat(day.aligned()).isFalse();
        assertThat(day.verdict()).isEqualTo("peak +0.72 m at 14:00 · no high water derived");
    }

    @Test
    @DisplayName("a day with nothing above the significance threshold, or no series, gives no row")
    void build_noSignificantPeak_null() {
        assertThat(builder.build(seaham(), DAY, curveWithPeakAt(14, 0.05))).isNull();
        assertThat(builder.build(seaham(), DAY.plusDays(1), curveWithPeakAt(14, 0.72))).isNull();
    }

    @Test
    @DisplayName("a location at a latitude with no sunrise or sunset carries null clock times")
    void build_noSunriseOrSunset_nullClockTimes() {
        SolarService polar = mock(SolarService.class);
        SurgeRunDayBuilder polarBuilder = new SurgeRunDayBuilder(tideExtremeRepository, polar);
        storedExtremes();

        SurgeRunDay day = polarBuilder.build(seaham(), DAY, curveWithPeakAt(14, 0.72));

        assertThat(day.sunrise()).isNull();
        assertThat(day.sunset()).isNull();
    }
}
