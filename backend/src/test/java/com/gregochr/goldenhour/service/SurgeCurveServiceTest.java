package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.StormSurgeBreakdown;
import com.gregochr.goldenhour.model.SurgeCurve;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SurgeCurveService} — the build-path carrier behind the storm-surge chart.
 *
 * <p>What is pinned here is mostly <em>honesty</em> rather than arithmetic: that a computed hour
 * equals what the existing per-event calculation would produce for the same inputs (so the chart
 * and the pill's chip cannot disagree), that a missing hour stays a gap rather than becoming a
 * zero, and that a coastal location lacking its fetch parameters is skipped rather than drawn as a
 * smooth, plausible, pressure-only curve.
 *
 * <p>Dates sit in <b>January</b>, where Europe/London is UTC, so a UTC hourly array and the local
 * day line up and each index assertion reads unambiguously. BST is exercised separately, because
 * that is exactly where the off-by-one lives.
 */
class SurgeCurveServiceTest {

    private static final LocalDate WINTER_DAY = LocalDate.of(2026, 1, 20);
    private static final LocalDate SUMMER_DAY = LocalDate.of(2026, 7, 20);

    private static final long ID_COASTAL = 1L;

    /** A deep low with a strong onshore wind, so the surge is comfortably significant. */
    private static final double STORM_PRESSURE_HPA = 975.0;
    private static final double STORM_WIND_MS = 20.0;
    private static final int ONSHORE_DIRECTION_DEG = 20;

    /**
     * Roughly what reducing to a clifftop cell's elevation takes off the reading — the reason
     * surface_pressure was the wrong field: it looks like a standing low that no weather caused.
     */
    private static final double ELEVATION_REDUCTION_HPA = 6.0;

    private StormSurgeService stormSurgeService;
    private SurgeCurveService service;

    @BeforeEach
    void setUp() {
        stormSurgeService = new StormSurgeService();
        service = new SurgeCurveService(stormSurgeService);
    }

    // ── the carrier's empty states ────────────────────────────────────────────

    @Test
    @DisplayName("before any refresh the carrier is EMPTY — the post-restart state, not a zero curve")
    void beforeRefresh_isEmpty() {
        // The pill must fall back to its fact chips after a restart. A synthesised flat curve would
        // be a forecast of calm that nobody made.
        assertThat(service.getCached().isEmpty()).isTrue();
        assertThat(service.getCached().forLocation(ID_COASTAL, WINTER_DAY)).isNull();
    }

    @Test
    @DisplayName("a refresh with no weather clears the carrier rather than keeping stale values")
    void refresh_withNoWeather_clearsCarrier() {
        service.refresh(List.of(pairing(coastal(), hourly(WINTER_DAY))), List.of(WINTER_DAY));
        assertThat(service.getCached().isEmpty()).isFalse();

        service.refresh(List.of(), List.of(WINTER_DAY));

        assertThat(service.getCached().isEmpty()).isTrue();
    }

    // ── the series ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a parameterised coastal location gets 24 hourly values for the local day")
    void refresh_coastalLocation_produces24HourlyValues() {
        service.refresh(List.of(pairing(coastal(), hourly(WINTER_DAY))), List.of(WINTER_DAY));

        List<Double> series = service.getCached().forLocation(ID_COASTAL, WINTER_DAY);

        assertThat(series).hasSize(SurgeCurve.HOURS_PER_DAY);
        assertThat(series).doesNotContainNull();
    }

    @Test
    @DisplayName("an hour on the curve equals the pure calculation for the same inputs")
    void refresh_valueAtHour_matchesStormSurgeServiceDirectly() {
        // THE honesty rule: the chart and the pill's "N m above normal" chip must be incapable of
        // disagreeing, and the way that is guaranteed is that both run the same pure function over
        // the same input field. If this ever fails, the two surfaces have diverged.
        LocationEntity location = coastal();
        service.refresh(List.of(pairing(location, hourly(WINTER_DAY))), List.of(WINTER_DAY));

        StormSurgeBreakdown direct = stormSurgeService.calculate(
                STORM_PRESSURE_HPA, STORM_WIND_MS, ONSHORE_DIRECTION_DEG,
                location.toCoastalParameters(), "REGULAR_TIDE");

        List<Double> series = service.getCached().forLocation(ID_COASTAL, WINTER_DAY);
        assertThat(series.get(12)).isEqualTo(round(direct.totalSurgeMetres()));
    }

    // ── the gates ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a non-coastal location is absent entirely — surge is not a thing inland")
    void refresh_nonCoastalLocation_isAbsent() {
        LocationEntity inland = coastal();
        inland.setCoastalTidal(false);

        service.refresh(List.of(pairing(inland, hourly(WINTER_DAY))), List.of(WINTER_DAY));

        assertThat(service.getCached().forLocation(ID_COASTAL, WINTER_DAY)).isNull();
    }

    @Test
    @DisplayName("a coastal location missing its fetch parameters is SKIPPED, not approximated")
    void refresh_unparameterisedCoastal_isSkipped() {
        // Without fetch/shelf/bearing the wind-setup term is zero, leaving a pressure-only curve
        // that is smooth, plausible and wrong — and still clears the significance gate. No Java
        // write path sets these columns, so every admin-created coastal location lands here.
        LocationEntity unparameterised = coastal();
        unparameterised.setEffectiveFetchMetres(null);

        service.refresh(List.of(pairing(unparameterised, hourly(WINTER_DAY))), List.of(WINTER_DAY));

        assertThat(service.getCached().forLocation(ID_COASTAL, WINTER_DAY)).isNull();
    }

    @Test
    @DisplayName("a missing hourly reading stays a GAP — a null, never a zero")
    void refresh_missingReading_yieldsNullNotZero() {
        // A zero would draw as a forecast of calm water at that hour. The path must break instead.
        OpenMeteoForecastResponse forecast = hourly(WINTER_DAY);
        forecast.getHourly().getWindSpeed10m().set(3, null);

        service.refresh(List.of(pairing(coastal(), forecast)), List.of(WINTER_DAY));

        List<Double> series = service.getCached().forLocation(ID_COASTAL, WINTER_DAY);
        assertThat(series.get(3)).isNull();
        assertThat(series.get(4)).isNotNull();
    }

    @Test
    @DisplayName("a date outside the fetched array is omitted rather than zero-filled")
    void refresh_dateBeyondArray_isOmitted() {
        service.refresh(List.of(pairing(coastal(), hourly(WINTER_DAY))),
                List.of(WINTER_DAY, WINTER_DAY.plusDays(9)));

        assertThat(service.getCached().forLocation(ID_COASTAL, WINTER_DAY)).isNotNull();
        assertThat(service.getCached().forLocation(ID_COASTAL, WINTER_DAY.plusDays(9))).isNull();
    }

    @Test
    @DisplayName("in BST the local day is offset from the UTC array, and hour 0 is a gap")
    void refresh_britishSummerTime_firstLocalHourIsAGap() {
        // The array starts at 00:00 UTC with no past days requested, so 00:00–01:00 BST is the
        // previous UTC day and genuinely absent. It must read as missing, not as the value an
        // hour later — which is what a nearest-index scan would have returned.
        service.refresh(List.of(pairing(coastal(), hourly(SUMMER_DAY))), List.of(SUMMER_DAY));

        List<Double> series = service.getCached().forLocation(ID_COASTAL, SUMMER_DAY);
        assertThat(series).hasSize(SurgeCurve.HOURS_PER_DAY);
        assertThat(series.get(0)).isNull();
        assertThat(series.get(1)).isNotNull();
    }

    // ── the record-equality trap ──────────────────────────────────────────────

    @Test
    @DisplayName("equal inputs produce EQUAL series, so the briefing cache shortcut still holds")
    void refresh_seriesAreValueEqual() {
        // A double[] component would compare by identity, making the briefing's cache-equality
        // check always false and rebuilding the entire response on every single request.
        service.refresh(List.of(pairing(coastal(), hourly(WINTER_DAY))), List.of(WINTER_DAY));
        List<Double> first = service.getCached().forLocation(ID_COASTAL, WINTER_DAY);

        service.refresh(List.of(pairing(coastal(), hourly(WINTER_DAY))), List.of(WINTER_DAY));
        List<Double> second = service.getCached().forLocation(ID_COASTAL, WINTER_DAY);

        assertThat(first).isNotSameAs(second).isEqualTo(second);
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private static double round(double metres) {
        return Math.round(metres * 1000.0) / 1000.0;
    }

    /** A fully parameterised North Sea coastal location, shore facing roughly north-east. */
    private static LocationEntity coastal() {
        return LocationEntity.builder()
                .id(ID_COASTAL).name("Seaham").lat(54.84).lon(-1.33).enabled(true)
                .coastalTidal(true)
                .shoreNormalBearingDegrees(20.0)
                .effectiveFetchMetres(340_000.0)
                .avgShelfDepthMetres(28.0)
                .build();
    }

    private static BriefingSlotBuilder.LocationWeather pairing(LocationEntity location,
            OpenMeteoForecastResponse forecast) {
        return new BriefingSlotBuilder.LocationWeather(location, forecast);
    }

    /** Three days of contiguous hourly UTC readings, starting 00:00 UTC on {@code from}. */
    private static OpenMeteoForecastResponse hourly(LocalDate from) {
        int slots = 24 * 3;
        List<String> times = new ArrayList<>(slots);
        List<Double> pressure = new ArrayList<>(slots);
        List<Double> wind = new ArrayList<>(slots);
        List<Integer> direction = new ArrayList<>(slots);
        for (int i = 0; i < slots; i++) {
            times.add(from.atStartOfDay().plusHours(i).toString().substring(0, 16));
            pressure.add(STORM_PRESSURE_HPA);
            wind.add(STORM_WIND_MS);
            direction.add(ONSHORE_DIRECTION_DEG);
        }
        OpenMeteoForecastResponse.Hourly h = new OpenMeteoForecastResponse.Hourly();
        h.setTime(times);
        h.setPressureMsl(pressure);
        // surface_pressure is deliberately set to a DIFFERENT value — what an elevated coastal
        // grid cell would report. Any regression back to that field produces a visibly wrong
        // surge here rather than an identical-looking pass.
        h.setSurfacePressure(new ArrayList<>(java.util.Collections.nCopies(
                pressure.size(), STORM_PRESSURE_HPA - ELEVATION_REDUCTION_HPA)));
        h.setWindSpeed10m(wind);
        h.setWindDirection10m(direction);
        OpenMeteoForecastResponse response = new OpenMeteoForecastResponse();
        response.setHourly(h);
        return response;
    }
    @Test
    @DisplayName("a below-prediction hour plots NEGATIVE, not a flat zero on the datum line")
    void refresh_highPressureOffshoreWind_plotsBelowPrediction() {
        // totalSurgeMetres() is Math.max(0, ...) — right for a risk band, wrong for a curve. Left
        // clamped, an ordinary high-pressure morning drew a dead-flat run sitting exactly on the
        // zero rule, asserting the water tracks its prediction hour after hour. The model never
        // said that; it said the water is BELOW prediction.
        OpenMeteoForecastResponse calm = hourly(WINTER_DAY);
        java.util.Collections.fill(calm.getHourly().getPressureMsl(), 1035.0);
        java.util.Collections.fill(calm.getHourly().getWindSpeed10m(), 2.0);

        service.refresh(List.of(pairing(coastal(), calm)), List.of(WINTER_DAY));

        List<Double> series = service.getCached().forLocation(ID_COASTAL, WINTER_DAY);
        assertThat(series.get(12)).isLessThan(0.0);
    }

    @Test
    @DisplayName("where the surge is positive the curve still equals the chip's own calculation")
    void refresh_positiveSurge_stillMatchesTotalSurgeMetres() {
        // The unclamped residual and totalSurgeMetres() coincide above zero, which is the property
        // the pill's chip depends on — plotting the true residual must not break that agreement.
        LocationEntity location = coastal();
        service.refresh(List.of(pairing(location, hourly(WINTER_DAY))), List.of(WINTER_DAY));

        StormSurgeBreakdown direct = stormSurgeService.calculate(
                STORM_PRESSURE_HPA, STORM_WIND_MS, ONSHORE_DIRECTION_DEG,
                location.toCoastalParameters(), "REGULAR_TIDE");

        assertThat(direct.totalSurgeMetres()).isGreaterThan(0.0);
        assertThat(service.getCached().forLocation(ID_COASTAL, WINTER_DAY).get(12))
                .isEqualTo(round(direct.totalSurgeMetres()));
    }
    @Test
    @DisplayName("the curve reads pressure_msl, not the elevation-reduced surface_pressure")
    void refresh_usesMeanSeaLevelPressure() {
        // The inverse-barometer term measures departure from 1013.25 hPa, a MEAN-SEA-LEVEL
        // reference. surface_pressure is reduced to the grid cell's own elevation, so an elevated
        // coastal cell reads permanently low and the model turns that into a standing surge no
        // weather caused — right at the significance threshold, so it can carry a calm day over
        // the gate on its own.
        LocationEntity location = coastal();
        service.refresh(List.of(pairing(location, hourly(WINTER_DAY))), List.of(WINTER_DAY));

        StormSurgeBreakdown fromMsl = stormSurgeService.calculate(
                STORM_PRESSURE_HPA, STORM_WIND_MS, ONSHORE_DIRECTION_DEG,
                location.toCoastalParameters(), "REGULAR_TIDE");
        StormSurgeBreakdown fromSurface = stormSurgeService.calculate(
                STORM_PRESSURE_HPA - ELEVATION_REDUCTION_HPA, STORM_WIND_MS, ONSHORE_DIRECTION_DEG,
                location.toCoastalParameters(), "REGULAR_TIDE");

        double plotted = service.getCached().forLocation(ID_COASTAL, WINTER_DAY).get(12);
        assertThat(plotted).isEqualTo(round(fromMsl.totalSurgeMetres()));
        assertThat(plotted).isNotEqualTo(round(fromSurface.totalSurgeMetres()));
    }
}
