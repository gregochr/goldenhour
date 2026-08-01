package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TideExtremeEntity;
import com.gregochr.goldenhour.entity.TideExtremeType;
import com.gregochr.goldenhour.model.SurgeCurve;
import com.gregochr.goldenhour.model.SurgeRunDay;
import com.gregochr.goldenhour.repository.TideExtremeRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns one location's hourly surge series into the {@link SurgeRunDay} a storm-surge pill renders.
 *
 * <p>Formatting happens here rather than on the client, as it does for the tide run: the axis is a
 * Europe/London local day, and converting on the client would put the timezone rule in two places.
 * Only the raw numeric series travels, and only because the chart's geometry needs it.
 *
 * <p><b>The verdict is the whole accessible answer.</b> The chart is decorative to a screen reader,
 * so the string this builds has to carry the peak, when it lands, and how it sits against high
 * water — the one question a surge topic exists to answer, since surge riding on top of high water
 * is the case worth going out for and the same surge at low water mostly is not.
 */
@Component
public class SurgeRunDayBuilder {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    /** Hours either side of high water within which the peak surge counts as aligned. */
    private static final long ALIGNED_WINDOW_MINUTES = 90;

    /** Minutes below which peak and high water are simply coincident rather than offset. */
    private static final long COINCIDENT_MINUTES = 15;

    /** Below this the curve is model noise rather than a finding, and no row is built. */
    private static final double SIGNIFICANCE_THRESHOLD_M = 0.05;

    /** States what the curve's zero is, because no absolute water level can be claimed. */
    static final String DATUM_NOTE = "above predicted tide";

    /** The editorial line — what a surge actually gives a photographer. */
    static final String SURGE_PHRASE = "water pushed past its predicted mark";

    /** Axis labels, one per six hours, matching the tide row's cadence. */
    private static final int AXIS_LABEL_STEP_HOURS = 6;

    private final TideExtremeRepository tideExtremeRepository;
    private final SolarService solarService;

    /**
     * Constructs a {@code SurgeRunDayBuilder}.
     *
     * @param tideExtremeRepository stored tide extrema — a DB read, never an API call, so this stays
     *                              safe to reach from a hot-topic strategy
     * @param solarService          supplies the day's sunrise and sunset
     */
    public SurgeRunDayBuilder(TideExtremeRepository tideExtremeRepository,
            SolarService solarService) {
        this.tideExtremeRepository = tideExtremeRepository;
        this.solarService = solarService;
    }

    /**
     * Builds the row, or null when no honest curve can be drawn.
     *
     * @param location the representative coastal location
     * @param date     the Europe/London local date
     * @param curve    the carrier populated on the briefing build path
     * @return the row, or null when the carrier holds nothing for this day or the peak is noise
     */
    public SurgeRunDay build(LocationEntity location, LocalDate date, SurgeCurve curve) {
        if (location == null || date == null || curve == null) {
            return null;
        }
        List<Double> series = curve.forLocation(location.getId(), date);
        if (series == null) {
            return null;
        }

        int peakHour = peakHour(series);
        if (peakHour < 0) {
            // Every hour a gap, or nothing above the threshold. A flat line is not a finding, and
            // drawing one would assert calm the model never forecast.
            return null;
        }
        double peakMetres = series.get(peakHour);

        LocalDateTime highWater = highWaterOn(location, date);
        String highWaterTime = highWater == null ? null : CLOCK.format(highWater);
        long gapMinutes = highWater == null ? Long.MAX_VALUE
                : java.time.Duration.between(date.atTime(peakHour, 0), highWater).toMinutes();
        boolean aligned = highWater != null && Math.abs(gapMinutes) <= ALIGNED_WINDOW_MINUTES;

        return new SurgeRunDay(
                location.getName(),
                series,
                axisLabels(),
                metres(peakMetres),
                CLOCK.format(date.atTime(peakHour, 0)),
                highWaterTime,
                localClock(solarService.sunriseUtc(location.getLat(), location.getLon(), date)),
                localClock(solarService.sunsetUtc(location.getLat(), location.getLon(), date)),
                verdict(peakMetres, peakHour, highWater, gapMinutes, aligned, date),
                aligned,
                DATUM_NOTE,
                SURGE_PHRASE);
    }

    /**
     * The plain-language call, stated against high water because that is what decides whether the
     * surge is worth a trip.
     *
     * @param peakMetres  the day's largest surge
     * @param peakHour    the hour it lands on
     * @param highWater   the day's high water, or null when none could be derived
     * @param gapMinutes  signed minutes from the peak to high water
     * @param aligned     whether the two effectively coincide
     * @param date        the local date
     * @return the verdict string
     */
    private static String verdict(double peakMetres, int peakHour, LocalDateTime highWater,
            long gapMinutes, boolean aligned, LocalDate date) {
        String head = metres(peakMetres) + " at " + CLOCK.format(date.atTime(peakHour, 0));
        if (highWater == null) {
            // No tide to measure against: state the surge and stop, rather than implying the
            // alignment that makes a surge dramatic.
            return head + " · no high water derived";
        }
        if (Math.abs(gapMinutes) <= COINCIDENT_MINUTES) {
            return head + " · on high water";
        }
        if (aligned) {
            return head + " · " + offset(gapMinutes) + " high water";
        }
        return head + " · high water " + CLOCK.format(highWater) + ", peak misses it";
    }

    /** {@code "1h20 before"} / {@code "45m after"} — the peak's offset from high water. */
    private static String offset(long gapMinutes) {
        long magnitude = Math.abs(gapMinutes);
        String duration = magnitude < 60
                ? magnitude + "m"
                : String.format(Locale.UK, "%dh%02d", magnitude / 60, magnitude % 60);
        // A positive gap means high water comes AFTER the peak, i.e. the peak is before it.
        return duration + (gapMinutes > 0 ? " before" : " after");
    }

    /** The hour of the largest significant surge, or -1 when the day holds nothing worth drawing. */
    private static int peakHour(List<Double> series) {
        int best = -1;
        double bestValue = SIGNIFICANCE_THRESHOLD_M;
        for (int hour = 0; hour < series.size(); hour++) {
            Double value = series.get(hour);
            if (value != null && value > bestValue) {
                bestValue = value;
                best = hour;
            }
        }
        return best;
    }

    /**
     * The day's highest high water at this location, in local time.
     *
     * @param location the location
     * @param date     the local date
     * @return the high water instant in local time, or null when none is stored for the day
     */
    private LocalDateTime highWaterOn(LocationEntity location, LocalDate date) {
        // Query a UTC window a day either side and filter to the LOCAL day, so a BST extreme near
        // midnight is attributed to the day a reader would put it on.
        List<TideExtremeEntity> extremes = tideExtremeRepository
                .findByLocationIdAndEventTimeBetweenOrderByEventTimeAsc(
                        location.getId(),
                        date.minusDays(1).atStartOfDay(),
                        date.plusDays(2).atStartOfDay());
        LocalDateTime best = null;
        java.math.BigDecimal bestHeight = null;
        for (TideExtremeEntity extreme : extremes) {
            if (extreme.getType() != TideExtremeType.HIGH) {
                continue;
            }
            LocalDateTime local = extreme.getEventTime().atOffset(ZoneOffset.UTC)
                    .atZoneSameInstant(LONDON).toLocalDateTime();
            if (!local.toLocalDate().equals(date)) {
                continue;
            }
            if (bestHeight == null || extreme.getHeightMetres().compareTo(bestHeight) > 0) {
                bestHeight = extreme.getHeightMetres();
                best = local;
            }
        }
        return best;
    }

    private static List<String> axisLabels() {
        List<String> labels = new ArrayList<>();
        for (int hour = 0; hour < SurgeCurve.HOURS_PER_DAY; hour += AXIS_LABEL_STEP_HOURS) {
            labels.add(String.format(Locale.UK, "%02d:00", hour));
        }
        return List.copyOf(labels);
    }

    private String localClock(LocalDateTime utc) {
        return utc == null ? null
                : CLOCK.format(utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(LONDON));
    }

    /** Signed metres, always with an explicit {@code +} — the value is an elevation, not a level. */
    private static String metres(double value) {
        return String.format(Locale.UK, "%+.2f m", value);
    }
}
