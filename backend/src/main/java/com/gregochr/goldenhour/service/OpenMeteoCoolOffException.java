package com.gregochr.goldenhour.service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Thrown by {@link OpenMeteoClient} instead of making a request while it is cooling off: Open-Meteo
 * has refused us (concurrent, hourly or daily limit) or has stopped answering (repeated timeouts),
 * and asking again would only deepen the refusal.
 *
 * <p>The message deliberately never contains "429": no batch loop may mistake a cool-off for a
 * minutely rate limit and apply its 61 second backoff, and no retry classifier may treat it as
 * transient. It carries a fixed reason label and the end time, never the triggering message.
 */
public class OpenMeteoCoolOffException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private static final DateTimeFormatter END_TIME =
            DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC);

    /**
     * Creates the exception.
     *
     * @param reason a short fixed label for why the client is cooling off
     * @param until  when the cool-off ends
     */
    public OpenMeteoCoolOffException(String reason, Instant until) {
        super("Open-Meteo cool-off until " + END_TIME.format(until) + " UTC: " + reason);
    }
}
