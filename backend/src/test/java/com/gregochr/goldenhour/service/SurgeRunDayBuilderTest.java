package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TideExtremeEntity;
import com.gregochr.goldenhour.entity.TideExtremeType;
import com.gregochr.goldenhour.model.SurgeCurve;
import com.gregochr.goldenhour.model.SurgeRunDay;
import com.gregochr.goldenhour.repository.TideExtremeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SurgeRunDayBuilder}.
 *
 * <p>The chart is {@code aria-hidden}, so {@link SurgeRunDay#verdict()} is the entire accessible
 * answer — which is why the assertions here read as the sentences a photographer hears rather than
 * as the numbers behind them. The other thing pinned is refusal: a day with nothing above the
 * significance threshold, or no derivable high water, must decline to claim more than it knows.
 *
 * <p>Dates sit in <b>January</b>, where Europe/London is UTC, so a stored UTC extreme and its local
 * clock time coincide and each assertion reads unambiguously.
 */
@ExtendWith(MockitoExtension.class)
class SurgeRunDayBuilderTest {

    private static final LocalDate DAY = LocalDate.of(2026, 1, 20);
    private static final long ID = 1L;

    @Mock
    private TideExtremeRepository tideExtremeRepository;

    private SurgeRunDayBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new SurgeRunDayBuilder(tideExtremeRepository, new SolarService());
    }

    // ── the verdict, which is the whole accessible answer ─────────────────────

    @Test
    @DisplayName("a peak ON high water says so — the case worth going out for")
    void build_peakOnHighWater_readsAsCoincident() {
        givenHighWaterAt(14, 5);

        SurgeRunDay day = builder.build(seaham(), DAY, curveWithPeakAt(14, 0.72));

        assertThat(day.verdict()).isEqualTo("+0.72 m at 14:00 · on high water");
        assertThat(day.aligned()).isTrue();
        assertThat(day.peak()).isEqualTo("+0.72 m");
        assertThat(day.peakTime()).isEqualTo("14:00");
        assertThat(day.highWaterTime()).isEqualTo("14:05");
    }

    @Test
    @DisplayName("a peak near high water states the offset and its direction")
    void build_peakNearHighWater_statesOffset() {
        givenHighWaterAt(15, 20);

        SurgeRunDay day = builder.build(seaham(), DAY, curveWithPeakAt(14, 0.60));

        assertThat(day.verdict()).isEqualTo("+0.60 m at 14:00 · 1h20 before high water");
        assertThat(day.aligned()).isTrue();
    }

    @Test
    @DisplayName("a peak that misses high water says it misses, rather than implying drama")
    void build_peakFarFromHighWater_saysItMisses() {
        // The whole point of a surge topic is surge riding ON high water. The same surge at low
        // water is mostly not worth the trip, and the verdict must not let that read as alignment.
        givenHighWaterAt(21, 0);

        SurgeRunDay day = builder.build(seaham(), DAY, curveWithPeakAt(9, 0.55));

        assertThat(day.verdict()).isEqualTo("+0.55 m at 09:00 · high water 21:00, peak misses it");
        assertThat(day.aligned()).isFalse();
    }

    @Test
    @DisplayName("with no derivable high water it states the surge and stops")
    void build_noHighWater_claimsNoAlignment() {
        // Stating an offset against a tide we do not have would be the most confident-sounding
        // sentence on the card and entirely unfounded.
        when(tideExtremeRepository.findByLocationIdAndEventTimeBetweenOrderByEventTimeAsc(
                anyLong(), any(), any())).thenReturn(List.of());

        SurgeRunDay day = builder.build(seaham(), DAY, curveWithPeakAt(14, 0.72));

        assertThat(day.verdict()).isEqualTo("+0.72 m at 14:00 · no high water derived");
        assertThat(day.aligned()).isFalse();
        assertThat(day.highWaterTime()).isNull();
    }

    // ── refusal ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a day below the significance threshold draws nothing rather than a flat line")
    void build_belowThreshold_returnsNull() {
        // A flat line is not a finding. Drawing one would assert calm the model never forecast.
        assertThat(builder.build(seaham(), DAY, curveWithPeakAt(14, 0.02))).isNull();
    }

    @Test
    @DisplayName("a day absent from the carrier yields no row — the post-restart state")
    void build_carrierEmpty_returnsNull() {
        assertThat(builder.build(seaham(), DAY, SurgeCurve.EMPTY)).isNull();
    }

    @Test
    @DisplayName("an all-gap day yields no row rather than a curve drawn through nothing")
    void build_allHoursMissing_returnsNull() {
        List<Double> allNull = new ArrayList<>(java.util.Collections.nCopies(24, null));
        SurgeCurve curve = new SurgeCurve(Map.of(ID, Map.of(DAY, allNull)));

        assertThat(builder.build(seaham(), DAY, curve)).isNull();
    }

    // ── the payload the chart needs ───────────────────────────────────────────

    @Test
    @DisplayName("the row states its datum, because no absolute water level can be claimed")
    void build_carriesDatumNote() {
        givenHighWaterAt(14, 0);

        SurgeRunDay day = builder.build(seaham(), DAY, curveWithPeakAt(14, 0.72));

        // Surge is a residual on top of the predicted tide, and the tide datum itself is
        // undocumented — so the row says what its zero is rather than letting a reader assume.
        assertThat(day.datumNote()).isEqualTo(SurgeRunDayBuilder.DATUM_NOTE);
        assertThat(day.locationName()).isEqualTo("Seaham");
        assertThat(day.surgeMetres()).hasSize(SurgeCurve.HOURS_PER_DAY);
        assertThat(day.sunrise()).matches("\\d{2}:\\d{2}");
        assertThat(day.sunset()).matches("\\d{2}:\\d{2}");
        assertThat(day.axisLabels()).containsExactly("00:00", "06:00", "12:00", "18:00");
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private void givenHighWaterAt(int hour, int minute) {
        TideExtremeEntity high = new TideExtremeEntity();
        high.setType(TideExtremeType.HIGH);
        high.setEventTime(DAY.atTime(hour, minute));
        high.setHeightMetres(new BigDecimal("5.100"));
        lenient().when(tideExtremeRepository
                        .findByLocationIdAndEventTimeBetweenOrderByEventTimeAsc(anyLong(), any(), any()))
                .thenReturn(List.of(high));
    }

    private static SurgeCurve curveWithPeakAt(int hour, double metres) {
        Double[] series = new Double[SurgeCurve.HOURS_PER_DAY];
        Arrays.fill(series, 0.01);
        series[hour] = metres;
        return new SurgeCurve(Map.of(ID, Map.of(DAY, Arrays.asList(series))));
    }

    private static LocationEntity seaham() {
        return LocationEntity.builder()
                .id(ID).name("Seaham").lat(54.84).lon(-1.33).enabled(true).coastalTidal(true)
                .build();
    }
}
