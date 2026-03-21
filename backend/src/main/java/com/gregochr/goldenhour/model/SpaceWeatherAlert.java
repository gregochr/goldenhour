package com.gregochr.goldenhour.model;

import java.time.ZonedDateTime;

/**
 * A NOAA SWPC space weather watch, warning, or alert message.
 *
 * <p>Message types:
 * <ul>
 *   <li>{@code "W"} — Watch (24–72 hour advance notice)</li>
 *   <li>{@code "A"} — Alert (threshold crossed now)</li>
 *   <li>{@code "S"} — Summary (post-event summary)</li>
 * </ul>
 *
 * @param messageType     W, A, or S
 * @param messageId       unique NOAA message identifier
 * @param issueDatetime   when this message was issued (UTC)
 * @param message         full text of the alert as issued by NOAA SWPC
 */
public record SpaceWeatherAlert(
        String messageType,
        String messageId,
        ZonedDateTime issueDatetime,
        String message) {}
