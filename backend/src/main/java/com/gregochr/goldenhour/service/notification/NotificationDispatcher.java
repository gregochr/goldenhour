package com.gregochr.goldenhour.service.notification;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * Announces a forecast evaluation on every registered {@link NotificationChannel}.
 *
 * <p>Spring injects every channel bean, so adding a channel means adding one class — no
 * call-site edits. Notifications are strictly best-effort: the forecast is already saved by
 * the time this runs, so a channel failure is logged and never propagates to the caller.
 *
 * <p>Each channel is isolated: one channel throwing does not stop the others. The previous
 * hand-rolled dispatch wrapped all three calls in a single try/catch, so an email failure
 * silently suppressed the Pushover and toast sends.
 */
@Service
public class NotificationDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final List<NotificationChannel> channels;

    /**
     * Constructs a {@code NotificationDispatcher}.
     *
     * @param channels every notification channel on the classpath, injected by Spring
     */
    public NotificationDispatcher(List<NotificationChannel> channels) {
        this.channels = channels;
    }

    /**
     * Announces the evaluation on every channel, isolating per-channel failures.
     *
     * @param evaluation   the colour potential evaluation to report
     * @param locationName name of the location
     * @param targetType   SUNRISE or SUNSET
     * @param date         date of the solar event
     */
    public void dispatch(SunsetEvaluation evaluation, String locationName,
            TargetType targetType, LocalDate date) {
        for (NotificationChannel channel : channels) {
            try {
                channel.notify(evaluation, locationName, targetType, date);
            } catch (Exception e) {
                LOG.warn("Notification channel {} failed for {} {} {} "
                                + "— forecast was saved successfully: {}",
                        channel.getClass().getSimpleName(), locationName, targetType, date,
                        e.getMessage());
            }
        }
    }
}
