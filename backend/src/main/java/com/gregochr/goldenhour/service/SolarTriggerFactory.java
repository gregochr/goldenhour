package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.SolarEvent;
import org.springframework.stereotype.Component;

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
     * The reference point the triggers are timed against.
     *
     * @return the reference point
     */
    public SolarReferencePoint referencePoint() {
        return referencePoint;
    }
}
