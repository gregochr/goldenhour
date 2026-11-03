package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.SolarEvent;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Builds {@link SolarOffsetTrigger}s against the configured reference point.
 *
 * <p>The scheduler, the catch-up policy and the admin preview all obtain their trigger here, so the
 * three can never be timed against different points or calculators.
 */
@Component
public class SolarTriggerFactory {

    private final SolarService solarService;
    private final SolarReferencePoint referencePoint;

    /**
     * Constructs the factory.
     *
     * @param solarService   the sunrise and sunset calculator
     * @param referencePoint the point the schedules are timed against
     */
    public SolarTriggerFactory(SolarService solarService, SolarReferencePoint referencePoint) {
        this.solarService = solarService;
        this.referencePoint = referencePoint;
    }

    /**
     * Creates a trigger.
     *
     * @param jobKey        the job key, for logs
     * @param event         the solar event
     * @param offsetMinutes minutes before the event, 0..720
     * @param storedCron    the job's stored cron, the fallback
     * @return the trigger
     * @throws IllegalArgumentException if the event is null or the offset is out of range
     */
    public SolarOffsetTrigger create(String jobKey, SolarEvent event, int offsetMinutes,
            String storedCron) {
        double lat = referencePoint.lat();
        double lon = referencePoint.lon();
        return new SolarOffsetTrigger(jobKey, event, offsetMinutes, storedCron,
                (solarEvent, date) -> solarEvent == SolarEvent.SUNRISE
                        ? solarService.sunriseUtc(lat, lon, date)
                        : solarService.sunsetUtc(lat, lon, date));
    }

    /**
     * The instant of a solar event on a UK civil date at the reference point, by the same calculator the
     * triggers use. The trigger's own sane-fire-range rules are deliberately not applied.
     *
     * @param event the event
     * @param date  the UK civil date
     * @return the event instant, or null when it cannot be computed or does not occur (the calculator's midnight
     *         sentinel)
     */
    public Instant eventInstant(SolarEvent event, LocalDate date) {
        Instant at = create("solar-event", event, 0, null).fireFor(date).eventInstant();
        return at == null || at.equals(date.atStartOfDay(ZoneOffset.UTC).toInstant()) ? null : at;
    }

    /**
     * The reference point the triggers are timed against.
     *
     * @return the reference point
     */
    public SolarReferencePoint referencePoint() {
        return referencePoint;
    }
}
