package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Shared "is this solar event still ahead of now?" test for hot-topic strategies.
 *
 * <p>A phenomenon tied to a sunrise or sunset is only worth surfacing while that event is still in
 * the future — once it has passed, the photographer can no longer get into position, so a pill
 * pointing at it is noise (the failure that made cloud-inversion topics useless). This helper
 * centralises the clock-based freshness check that {@link InversionHotTopicStrategy} pioneered so
 * every solar-event-tied strategy excludes expired events the same way.
 *
 * <p>Freshness is decided purely by comparing the event's UTC time to {@link #now()}: a future
 * date is ahead, today-before-the-event is ahead, and today-after-the-event or any past date is
 * not. A null event time (or location we cannot place) is treated as ahead — unknown should never
 * suppress a topic.
 */
@Component
public class SolarEventFreshness {

    private final SolarService solarService;
    private final Clock clock;

    /**
     * Constructs a {@code SolarEventFreshness}.
     *
     * @param solarService solar calculator for per-location sunrise/sunset times
     * @param clock        UTC clock used to decide whether an event has passed
     */
    public SolarEventFreshness(SolarService solarService, Clock clock) {
        this.solarService = solarService;
        this.clock = clock;
    }

    /**
     * The current instant as a UTC {@link LocalDateTime}, for callers that need to compare several
     * event times against a single "now".
     *
     * @return the current UTC date-time
     */
    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /**
     * Whether the given UTC solar-event time is still ahead of now. A null time is treated as
     * ahead (unknown must not suppress a topic).
     *
     * @param eventTimeUtc the UTC time of the sunrise/sunset, or null
     * @return true when the event has not yet passed
     */
    public boolean isAhead(LocalDateTime eventTimeUtc) {
        return eventTimeUtc == null || now().isBefore(eventTimeUtc);
    }

    /**
     * Whether the sunrise/sunset for the given location, date and event type is still ahead of now.
     *
     * @param location  the location whose horizon the event is timed for
     * @param date      the date of the event
     * @param eventType SUNRISE or SUNSET
     * @return true when the event has not yet passed
     */
    public boolean isAhead(LocationEntity location, LocalDate date, TargetType eventType) {
        return isAhead(eventTime(location, date, eventType));
    }

    /**
     * The UTC time of the given solar event, or {@code null} when it cannot be computed (no
     * location or event type).
     *
     * @param location  the location whose horizon the event is timed for
     * @param date      the date of the event
     * @param eventType SUNRISE or SUNSET
     * @return the UTC event time, or null
     */
    public LocalDateTime eventTime(LocationEntity location, LocalDate date, TargetType eventType) {
        if (location == null || eventType == null) {
            return null;
        }
        return eventType == TargetType.SUNSET
                ? solarService.sunsetUtc(location.getLat(), location.getLon(), date)
                : solarService.sunriseUtc(location.getLat(), location.getLon(), date);
    }
}
