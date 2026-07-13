package com.gregochr.goldenhour.service.notification;

import com.gregochr.goldenhour.config.NotificationProperties;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests for {@link PushoverNotificationService}.
 *
 * <p>Uses {@link MockRestServiceServer} bound to the injected {@link RestClient} so the outbound
 * request URL, method, and JSON body are asserted at the HTTP boundary.
 */
class PushoverNotificationServiceTest {

    private static final String PUSHOVER_URL = "https://api.pushover.net/1/messages.json";

    private RestClient restClient;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        restClient = builder.build();
    }

    private NotificationProperties enabledProperties(String appToken, String userKey) {
        NotificationProperties properties = new NotificationProperties();
        properties.getPushover().setEnabled(true);
        properties.getPushover().setAppToken(appToken);
        properties.getPushover().setUserKey(userKey);
        return properties;
    }

    @Test
    @DisplayName("notify() does nothing when Pushover notifications are disabled")
    void notify_whenDisabled_makesNoHttpCall() {
        NotificationProperties properties = new NotificationProperties();
        properties.getPushover().setEnabled(false);
        PushoverNotificationService pushoverService =
                new PushoverNotificationService(properties, restClient);

        pushoverService.notify(new SunsetEvaluation(null, 30, 40, "Moderate."),
                "Durham UK", TargetType.SUNSET, LocalDate.of(2026, 2, 20));

        // No request expected; MockRestServiceServer fails if any call is made.
        server.verify();
    }

    @Test
    @DisplayName("notify() posts Sonnet dual-score message to the Pushover API when enabled")
    void notify_sonnetEvaluation_whenEnabled_makesHttpPost() {
        server.expect(requestTo(PUSHOVER_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess());

        PushoverNotificationService pushoverService =
                new PushoverNotificationService(enabledProperties("app-token", "user-key"), restClient);

        pushoverService.notify(new SunsetEvaluation(null, 70, 75, "Good conditions."),
                "Durham UK", TargetType.SUNSET, LocalDate.of(2026, 2, 20));

        server.verify();
    }

    @Test
    @DisplayName("notify() posts Haiku rating message to the Pushover API when enabled")
    void notify_haikuEvaluation_whenEnabled_makesHttpPost() {
        server.expect(requestTo(PUSHOVER_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess());

        PushoverNotificationService pushoverService =
                new PushoverNotificationService(enabledProperties("app-token", "user-key"), restClient);

        pushoverService.notify(new SunsetEvaluation(4, null, null, "Good conditions."),
                "Durham UK", TargetType.SUNSET, LocalDate.of(2026, 2, 20));

        server.verify();
    }

    @Test
    @DisplayName("notify() includes app token and user key in request body")
    void notify_includesTokenAndUserInBody() {
        server.expect(requestTo(PUSHOVER_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.token").value("my-app-token"))
                .andExpect(jsonPath("$.user").value("my-user-key"))
                .andExpect(jsonPath("$.title", containsString("Bamburgh")))
                .andExpect(jsonPath("$.title", containsString("sunset")))
                .andExpect(jsonPath("$.message", containsString("72/100")))
                .andExpect(jsonPath("$.message", containsString("80/100")))
                .andRespond(withSuccess());

        PushoverNotificationService pushoverService =
                new PushoverNotificationService(enabledProperties("my-app-token", "my-user-key"), restClient);

        pushoverService.notify(new SunsetEvaluation(null, 72, 80, "Good."),
                "Bamburgh", TargetType.SUNSET, LocalDate.of(2026, 3, 1));

        server.verify();
    }

    @Test
    @DisplayName("notify() Haiku message includes rating in body")
    void notify_haikuMessage_includesRating() {
        server.expect(requestTo(PUSHOVER_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.title", containsString("sunrise")))
                .andExpect(jsonPath("$.message", containsString("Rating 5/5")))
                .andExpect(jsonPath("$.message", containsString("Stunning sky.")))
                .andRespond(withSuccess());

        PushoverNotificationService pushoverService =
                new PushoverNotificationService(enabledProperties("token", "user"), restClient);

        pushoverService.notify(new SunsetEvaluation(5, null, null, "Stunning sky."),
                "Bamburgh", TargetType.SUNRISE, LocalDate.of(2026, 3, 1));

        server.verify();
    }
}
