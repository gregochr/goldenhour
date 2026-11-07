package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.SurgeCurve;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Edge cases of {@link SurgeCurveService#refresh}: missing inputs, locations that cannot carry a curve, and
 * weather arrays that are missing, short, malformed or do not reach the requested day. A gap is always a gap,
 * never a zero.
 */
class SurgeCurveServiceEdgeTest {

    private static final LocalDate DAY = LocalDate.of(2026, 1, 20);
    private static final long ID = 1L;

    private SurgeCurveService service;

    @BeforeEach
    void setUp() {
        service = new SurgeCurveService(new StormSurgeService());
    }

    private static LocationEntity coastal() {
        return LocationEntity.builder().id(ID).name("Seaham").lat(54.84).lon(-1.33).enabled(true)
                .coastalTidal(true).shoreNormalBearingDegrees(20.0).effectiveFetchMetres(340_000.0)
                .avgShelfDepthMetres(28.0).build();
    }

    private static BriefingSlotBuilder.LocationWeather pairing(LocationEntity location,
            OpenMeteoForecastResponse forecast) {
        return new BriefingSlotBuilder.LocationWeather(location, forecast);
    }

    /** 72 contiguous hourly UTC readings from 00:00 on {@code DAY}, each list replaceable via the modifier. */
    private static OpenMeteoForecastResponse forecast(Consumer<OpenMeteoForecastResponse.Hourly> m) {
        int slots = 72;
        List<String> times = new ArrayList<>();
        for (int i = 0; i < slots; i++) {
            times.add(DAY.atStartOfDay().plusHours(i).toString().substring(0, 16));
        }
        OpenMeteoForecastResponse.Hourly hourly = new OpenMeteoForecastResponse.Hourly();
        hourly.setTime(times);
        hourly.setPressureMsl(new ArrayList<>(Collections.nCopies(slots, 975.0)));
        hourly.setWindSpeed10m(new ArrayList<>(Collections.nCopies(slots, 20.0)));
        hourly.setWindDirection10m(new ArrayList<>(Collections.nCopies(slots, 20)));
        m.accept(hourly);
        OpenMeteoForecastResponse response = new OpenMeteoForecastResponse();
        response.setHourly(hourly);
        return response;
    }

    @Test
    @DisplayName("a refresh with no weather list, no dates or empty dates leaves the carrier empty")
    void refresh_missingInputs_empty() {
        OpenMeteoForecastResponse good = forecast(h -> { });

        service.refresh(null, List.of(DAY));
        assertThat(service.getCached().isEmpty()).isTrue();
        service.refresh(List.of(pairing(coastal(), good)), null);
        assertThat(service.getCached().isEmpty()).isTrue();
        service.refresh(List.of(pairing(coastal(), good)), List.of());
        assertThat(service.getCached().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("a pairing with no location, a location with no id, or no forecast contributes nothing")
    void refresh_unusablePairings_skipped() {
        LocationEntity noId = coastal();
        noId.setId(null);
        OpenMeteoForecastResponse good = forecast(h -> { });

        service.refresh(Arrays.asList(
                pairing(null, good),
                pairing(noId, good),
                pairing(coastal(), null),
                pairing(coastal(), new OpenMeteoForecastResponse())), List.of(DAY));

        assertThat(service.getCached().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("a forecast without a time axis, or whose first stamp is unreadable, gives no curve")
    void refresh_noUsableTimeAxis_noCurve() {
        service.refresh(List.of(
                pairing(coastal(), forecast(h -> h.setTime(null)))), List.of(DAY));
        assertThat(service.getCached().forLocation(ID, DAY)).isNull();

        service.refresh(List.of(
                pairing(coastal(), forecast(h -> h.setTime(new ArrayList<>())))), List.of(DAY));
        assertThat(service.getCached().forLocation(ID, DAY)).isNull();

        service.refresh(List.of(pairing(coastal(), forecast(h -> {
            List<String> times = new ArrayList<>(h.getTime());
            times.set(0, "not-a-time");
            h.setTime(times);
        }))), List.of(DAY));
        assertThat(service.getCached().forLocation(ID, DAY)).isNull();
    }

    @Test
    @DisplayName("a missing pressure, wind-speed or wind-direction series leaves every hour a gap, so no curve")
    void refresh_missingSeries_noCurve() {
        service.refresh(List.of(pairing(coastal(), forecast(h -> h.setPressureMsl(null)))), List.of(DAY));
        assertThat(service.getCached().forLocation(ID, DAY)).isNull();

        service.refresh(List.of(pairing(coastal(), forecast(h -> h.setWindSpeed10m(null)))), List.of(DAY));
        assertThat(service.getCached().forLocation(ID, DAY)).isNull();

        service.refresh(List.of(pairing(coastal(), forecast(h -> h.setWindDirection10m(null)))), List.of(DAY));
        assertThat(service.getCached().forLocation(ID, DAY)).isNull();
    }

    @Test
    @DisplayName("series shorter than the day leave the uncovered hours as gaps; a null reading is a gap too")
    void refresh_shortSeries_leavesGaps() {
        service.refresh(List.of(pairing(coastal(), forecast(h -> {
            h.setPressureMsl(new ArrayList<>(Collections.nCopies(12, 975.0)));
            h.setWindSpeed10m(new ArrayList<>(Collections.nCopies(12, 20.0)));
            h.setWindDirection10m(new ArrayList<>(Collections.nCopies(6, 20)));
        }))), List.of(DAY));

        List<Double> series = service.getCached().forLocation(ID, DAY);

        assertThat(series).hasSize(SurgeCurve.HOURS_PER_DAY);
        assertThat(series.subList(0, 6)).doesNotContainNull();
        assertThat(series.subList(6, 24)).containsOnlyNulls();
    }

    @Test
    @DisplayName("a day the weather does not reach, or that starts before it, has no curve while a covered day does")
    void refresh_daysOutsideTheWeather_haveNoCurve() {
        LocalDate before = DAY.minusDays(1);
        LocalDate after = DAY.plusDays(10);

        service.refresh(List.of(pairing(coastal(), forecast(h -> { }))), List.of(before, DAY, after));

        assertThat(service.getCached().forLocation(ID, DAY)).hasSize(SurgeCurve.HOURS_PER_DAY);
        assertThat(service.getCached().forLocation(ID, after)).isNull();
        assertThat(service.getCached().forLocation(ID, before)).isNull();
    }

    @Test
    @DisplayName("a location whose every day is uncomputable is absent from the carrier")
    void refresh_noComputableDay_locationAbsent() {
        service.refresh(List.of(pairing(coastal(), forecast(h -> h.setPressureMsl(new ArrayList<>())))),
                List.of(DAY));

        assertThat(service.getCached().forLocation(ID, DAY)).isNull();
        assertThat(service.getCached().isEmpty()).isTrue();
    }
}
