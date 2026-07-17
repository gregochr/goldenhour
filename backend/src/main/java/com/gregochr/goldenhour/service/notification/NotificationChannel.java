package com.gregochr.goldenhour.service.notification;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.SunsetEvaluation;

import java.time.LocalDate;

/**
 * A destination a forecast evaluation can be announced to (email, Pushover, macOS toast).
 *
 * <p>Each implementation self-gates on its own {@code notifications.*.enabled} flag and
 * returns silently when disabled or unconfigured — callers dispatch unconditionally and do
 * not ask whether a channel is on.
 *
 * @see NotificationDispatcher
 */
public interface NotificationChannel {

    /**
     * Announces a forecast evaluation on this channel, or returns silently if the channel is
     * disabled.
     *
     * @param evaluation   the colour potential evaluation to report
     * @param locationName name of the location
     * @param targetType   SUNRISE or SUNSET
     * @param date         date of the solar event
     */
    void notify(SunsetEvaluation evaluation, String locationName,
            TargetType targetType, LocalDate date);
}
