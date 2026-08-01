package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.CoastalParameters;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.StormSurgeBreakdown;
import com.gregochr.goldenhour.model.SurgeCurve;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Computes, once per briefing run, an hourly storm-surge curve for each parameterised coastal
 * location, so the storm-surge hot topic can draw a chart without making any external call itself.
 *
 * <p>Mirrors {@link NlcClarityService} and {@link MeteorClarityService} — an in-memory
 * {@link AtomicReference} carrier refreshed from {@code BriefingService} and failure-isolated, so a
 * problem here only suppresses the chart. See {@link SurgeCurve} for why a carrier rather than the
 * briefing payload.
 *
 * <p><b>This costs no HTTP and no DB.</b> {@link StormSurgeService#calculate} is a pure function of
 * pressure, wind speed and wind direction with no time parameter, and the hourly arrays it needs are
 * already in the {@code OpenMeteoForecastResponse} the briefing build has just fetched. Twenty-four
 * hours across the coastal roster is a few milliseconds of arithmetic.
 *
 * <p><b>Keyed by location, never by grid cell.</b> The briefing dedupes weather by grid cell and
 * fans one response out to several locations, but surge differs per location through
 * {@link CoastalParameters} — two sites in one cell with different shore bearings see different
 * wind setup from identical weather.
 *
 * <p><b>Unparameterised coastal locations are skipped, not approximated.</b> A location flagged
 * coastal but missing its fetch and shelf columns yields zero wind setup, leaving a pressure-only
 * curve that is smooth, plausible and wrong — and at the significance threshold it would still
 * clear the gate and draw. Those are logged by name so the gap is visible rather than silent.
 */
@Component
public class SurgeCurveService {

    private static final Logger LOG = LoggerFactory.getLogger(SurgeCurveService.class);

    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    private static final DateTimeFormatter HOUR_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    /**
     * Passed as the lunar tide type. It reaches only the risk classification inside
     * {@link StormSurgeService}, never {@code calculatePressureRise} or {@code calculateWindSetup},
     * so it cannot affect a single metre this class emits. Naming it here rather than threading the
     * real classification avoids an uncached three-query lookup per location, one of which
     * materialises every stored high-water extreme.
     */
    private static final String NEUTRAL_TIDE_TYPE = "REGULAR_TIDE";

    /** Metres precision retained per hour — beyond this the chart is drawing model noise. */
    private static final int METRES_SCALE = 3;

    private final StormSurgeService stormSurgeService;
    private final AtomicReference<SurgeCurve> cache = new AtomicReference<>(SurgeCurve.EMPTY);

    /**
     * Constructs a {@code SurgeCurveService}.
     *
     * @param stormSurgeService the pure surge calculator, called once per local hour
     */
    public SurgeCurveService(StormSurgeService stormSurgeService) {
        this.stormSurgeService = stormSurgeService;
    }

    /**
     * Recomputes the carrier from weather the briefing build has already fetched.
     *
     * <p>Refresh over the briefing's own date list rather than the narrower hot-topic window: serve
     * time uses the <em>request</em> day, so a briefing built on Monday and served on Tuesday asks
     * for a date one further out than the build's own horizon.
     *
     * @param locationWeathers the build's per-location weather pairings
     * @param dates            the briefing window's local dates
     */
    public void refresh(List<BriefingSlotBuilder.LocationWeather> locationWeathers,
            List<LocalDate> dates) {
        if (locationWeathers == null || locationWeathers.isEmpty()
                || dates == null || dates.isEmpty()) {
            cache.set(SurgeCurve.EMPTY);
            return;
        }

        Map<Long, Map<LocalDate, List<Double>>> byLocation = new LinkedHashMap<>();
        List<String> unparameterised = new ArrayList<>();

        for (BriefingSlotBuilder.LocationWeather pairing : locationWeathers) {
            LocationEntity location = pairing.location();
            if (location == null || location.getId() == null || !location.isCoastalTidal()) {
                continue;
            }
            if (isUnparameterised(location)) {
                unparameterised.add(location.getName());
                continue;
            }
            OpenMeteoForecastResponse.Hourly hourly = hourlyOf(pairing.forecast());
            if (hourly == null) {
                continue;
            }
            CoastalParameters params = location.toCoastalParameters();

            Map<LocalDate, List<Double>> byDate = new LinkedHashMap<>();
            for (LocalDate date : dates) {
                List<Double> series = seriesFor(hourly, date, params);
                if (series != null) {
                    byDate.put(date, series);
                }
            }
            if (!byDate.isEmpty()) {
                byLocation.put(location.getId(), byDate);
            }
        }

        cache.set(new SurgeCurve(byLocation));
        if (!unparameterised.isEmpty()) {
            // By name, because no Java write path sets these columns — they exist only in the
            // seeding migrations, so every admin-created coastal location lands here silently.
            LOG.warn("Surge curve skipped for {} coastal location(s) missing fetch/shelf "
                    + "parameters: {}", unparameterised.size(), unparameterised);
        }
        LOG.debug("Surge curve refreshed: {} location(s) over {} date(s)",
                byLocation.size(), dates.size());
    }

    /**
     * The carrier as last refreshed.
     *
     * <p>{@link SurgeCurve#EMPTY} after a restart and until the next briefing cycle, which is the
     * accepted degradation: the pill shows its fact chips rather than a chart. A curve is never
     * synthesised to fill that gap.
     *
     * @return the current curve, never null
     */
    public SurgeCurve getCached() {
        return cache.get();
    }

    /**
     * Builds one local day's 24 hourly values, or null when the day is entirely uncomputable.
     *
     * @param hourly the location's hourly weather arrays
     * @param date   the Europe/London local date
     * @param params the location's coastal parameters
     * @return 24 slots oldest first, null where an hour could not be computed; or null for no data
     */
    private List<Double> seriesFor(OpenMeteoForecastResponse.Hourly hourly, LocalDate date,
            CoastalParameters params) {
        List<String> times = hourly.getTime();
        if (times == null || times.isEmpty()) {
            return null;
        }
        LocalDateTime start = parseHour(times.get(0));
        if (start == null) {
            return null;
        }

        List<Double> series = new ArrayList<>(SurgeCurve.HOURS_PER_DAY);
        boolean any = false;
        for (int hour = 0; hour < SurgeCurve.HOURS_PER_DAY; hour++) {
            // The array is contiguous hourly UTC from a known start, so the index is arithmetic
            // rather than a scan that re-parses every element.
            LocalDateTime wantUtc = date.atTime(hour, 0).atZone(LONDON)
                    .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
            int idx = (int) Duration.between(start, wantUtc).toHours();
            Double value = surgeAt(hourly, idx, params);
            series.add(value);
            any |= value != null;
        }
        // unmodifiableList, NOT List.copyOf: a gap is a null element and List.copyOf rejects those.
        // Still value-equal, which is what the briefing's cache-equality shortcut depends on.
        return any ? Collections.unmodifiableList(series) : null;
    }

    /**
     * Surge in metres at one hourly index, or null when that hour is outside the array or missing a
     * reading. Missing is a gap, never a zero — a zero would draw as a real forecast of calm.
     *
     * @param hourly the hourly arrays
     * @param idx    the index into them
     * @param params the location's coastal parameters
     * @return surge metres, or null
     */
    private Double surgeAt(OpenMeteoForecastResponse.Hourly hourly, int idx,
            CoastalParameters params) {
        if (idx < 0) {
            return null;
        }
        // surface_pressure, matching the field the persisted per-event scalar already uses, so the
        // curve and the pill's existing "N m above normal" chip are computed from one input and
        // cannot disagree. pressure_msl is the better datum for a model whose reference is
        // 1013.25 hPa, but changing only the curve would make chart and chip visibly diverge.
        Double pressure = at(hourly.getSurfacePressure(), idx);
        Double windSpeed = at(hourly.getWindSpeed10m(), idx);
        List<Integer> directions = hourly.getWindDirection10m();
        Integer windDirection = directions != null && idx < directions.size()
                ? directions.get(idx) : null;
        if (pressure == null || windSpeed == null || windDirection == null) {
            return null;
        }
        StormSurgeBreakdown breakdown = stormSurgeService.calculate(
                pressure, windSpeed, windDirection, params, NEUTRAL_TIDE_TYPE);
        // The UNCLAMPED residual, not totalSurgeMetres(). That field is Math.max(0, pressure +
        // wind), which is right for a risk classification — there is no such thing as negative
        // risk — but wrong for a curve: it turns every below-prediction hour into exactly 0.000,
        // so an ordinary high-pressure morning draws a dead-flat run sitting on the datum rule,
        // asserting "the water tracks its prediction exactly, hour after hour", which the model
        // never said. That is the same artefact the null-gap rule exists to prevent, reached by a
        // different route. The two components are stored unclamped, so the true residual needs no
        // change to the calculator; where the surge is positive the two agree exactly, which is
        // the property the pill's chip depends on.
        return round(breakdown.pressureRiseMetres() + breakdown.windRiseMetres());
    }

    /**
     * True when a location claims coastal exposure but lacks the parameters the wind-setup term
     * needs, which would leave a pressure-only curve masquerading as a full surge forecast.
     *
     * @param location the location
     * @return true when it must be skipped
     */
    private static boolean isUnparameterised(LocationEntity location) {
        return location.getEffectiveFetchMetres() == null
                || location.getAvgShelfDepthMetres() == null
                || location.getShoreNormalBearingDegrees() == null;
    }

    private static OpenMeteoForecastResponse.Hourly hourlyOf(OpenMeteoForecastResponse forecast) {
        return forecast == null ? null : forecast.getHourly();
    }

    private static Double at(List<Double> values, int idx) {
        return values != null && idx < values.size() ? values.get(idx) : null;
    }

    private static LocalDateTime parseHour(String value) {
        try {
            return LocalDateTime.parse(value, HOUR_FORMAT);
        } catch (java.time.format.DateTimeParseException e) {
            LOG.debug("Unparseable hourly timestamp '{}' — surge curve skipped", value);
            return null;
        }
    }

    private static double round(double metres) {
        double factor = Math.pow(10, METRES_SCALE);
        return Math.round(metres * factor) / factor;
    }
}
