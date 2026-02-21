package com.gregochr.goldenhour.service.notification;

import com.gregochr.goldenhour.config.NotificationProperties;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDate;
import java.util.Map;

/**
 * Sends a forecast evaluation as a Pushover push notification.
 *
 * <p>Silently skips sending when {@code notifications.pushover.enabled} is {@code false}.
 */
@Service
public class PushoverNotificationService {

    private static final String PUSHOVER_API_URL = "https://api.pushover.net/1/messages.json";

    private final NotificationProperties properties;
    private final WebClient webClient;

    /**
     * Constructs a {@code PushoverNotificationService}.
     *
     * @param properties notification configuration
     * @param webClient  shared WebClient for outbound HTTP
     */
    public PushoverNotificationService(NotificationProperties properties, WebClient webClient) {
        this.properties = properties;
        this.webClient = webClient;
    }

    /**
     * Sends a Pushover push notification for a forecast evaluation, if Pushover is enabled.
     *
     * @param evaluation   the colour potential evaluation to report
     * @param locationName name of the location
     * @param targetType   SUNRISE or SUNSET
     * @param date         date of the solar event
     */
    public void notify(SunsetEvaluation evaluation, String locationName,
            TargetType targetType, LocalDate date) {
        if (!properties.getPushover().isEnabled()) {
            return;
        }
        Map<String, String> body = Map.of(
                "token", properties.getPushover().getAppToken(),
                "user", properties.getPushover().getUserKey(),
                "title", buildTitle(locationName, targetType, date),
                "message", buildMessage(evaluation));

        webClient.post()
                .uri(PUSHOVER_API_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .block();
    }

    private String buildTitle(String locationName, TargetType targetType, LocalDate date) {
        return String.format("Golden Hour \u2014 %s %s %s",
                locationName, targetType.name().toLowerCase(), date);
    }

    private String buildMessage(SunsetEvaluation evaluation) {
        return String.format("Rating %d/5: %s", evaluation.rating(), evaluation.summary());
    }
}
