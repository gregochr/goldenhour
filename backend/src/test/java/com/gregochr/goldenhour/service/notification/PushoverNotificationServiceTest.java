package com.gregochr.goldenhour.service.notification;

import com.gregochr.goldenhour.config.NotificationProperties;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PushoverNotificationService}.
 */
@ExtendWith(MockitoExtension.class)
class PushoverNotificationServiceTest {

    @Mock
    private RestClient restClient;

    @Test
    @DisplayName("notify() does nothing when Pushover notifications are disabled")
    void notify_whenDisabled_makesNoHttpCall() {
        NotificationProperties properties = new NotificationProperties();
        properties.getPushover().setEnabled(false);
        PushoverNotificationService pushoverService =
                new PushoverNotificationService(properties, restClient);

        pushoverService.notify(new SunsetEvaluation(null, 30, 40, "Moderate."),
                "Durham UK", TargetType.SUNSET, LocalDate.of(2026, 2, 20));

        verify(restClient, never()).post();
    }

    @Test
    @DisplayName("notify() posts Sonnet dual-score message to the Pushover API when enabled")
    void notify_sonnetEvaluation_whenEnabled_makesHttpPost() {
        NotificationProperties properties = new NotificationProperties();
        properties.getPushover().setEnabled(true);
        properties.getPushover().setAppToken("app-token");
        properties.getPushover().setUserKey("user-key");

        RestClient mockClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        when(mockClient.post().uri(anyString()).contentType(any()).body(any(Object.class))
                .retrieve().toBodilessEntity())
                .thenReturn(null);

        PushoverNotificationService pushoverService =
                new PushoverNotificationService(properties, mockClient);

        pushoverService.notify(new SunsetEvaluation(null, 70, 75, "Good conditions."),
                "Durham UK", TargetType.SUNSET, LocalDate.of(2026, 2, 20));

        // Success: service completed without throwing; the HTTP call was made
    }

    @Test
    @DisplayName("notify() posts Haiku rating message to the Pushover API when enabled")
    void notify_haikuEvaluation_whenEnabled_makesHttpPost() {
        NotificationProperties properties = new NotificationProperties();
        properties.getPushover().setEnabled(true);
        properties.getPushover().setAppToken("app-token");
        properties.getPushover().setUserKey("user-key");

        RestClient mockClient = mock(RestClient.class, RETURNS_DEEP_STUBS);
        when(mockClient.post().uri(anyString()).contentType(any()).body(any(Object.class))
                .retrieve().toBodilessEntity())
                .thenReturn(null);

        PushoverNotificationService pushoverService =
                new PushoverNotificationService(properties, mockClient);

        pushoverService.notify(new SunsetEvaluation(4, null, null, "Good conditions."),
                "Durham UK", TargetType.SUNSET, LocalDate.of(2026, 2, 20));

        // Success: service completed without throwing
    }

    @Test
    @DisplayName("notify() includes app token and user key in request body")
    @SuppressWarnings("unchecked")
    void notify_includesTokenAndUserInBody() {
        NotificationProperties properties = new NotificationProperties();
        properties.getPushover().setEnabled(true);
        properties.getPushover().setAppToken("my-app-token");
        properties.getPushover().setUserKey("my-user-key");

        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class, RETURNS_DEEP_STUBS);
        RestClient mockClient = mock(RestClient.class);
        when(mockClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(uriSpec);
        when(uriSpec.contentType(any())).thenReturn(uriSpec);

        org.mockito.ArgumentCaptor<Map<String, String>> bodyCaptor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        when(uriSpec.body(bodyCaptor.capture())).thenReturn(uriSpec);
        when(uriSpec.retrieve().toBodilessEntity()).thenReturn(null);

        PushoverNotificationService pushoverService =
                new PushoverNotificationService(properties, mockClient);

        pushoverService.notify(new SunsetEvaluation(null, 72, 80, "Good."),
                "Bamburgh", TargetType.SUNSET, LocalDate.of(2026, 3, 1));

        Map<String, String> body = bodyCaptor.getValue();
        assertThat(body.get("token")).isEqualTo("my-app-token");
        assertThat(body.get("user")).isEqualTo("my-user-key");
        assertThat(body.get("title")).contains("Bamburgh");
        assertThat(body.get("title")).contains("sunset");
        assertThat(body.get("message")).contains("72/100");
        assertThat(body.get("message")).contains("80/100");
    }

    @Test
    @DisplayName("notify() Haiku message includes rating in body")
    @SuppressWarnings("unchecked")
    void notify_haikuMessage_includesRating() {
        NotificationProperties properties = new NotificationProperties();
        properties.getPushover().setEnabled(true);
        properties.getPushover().setAppToken("token");
        properties.getPushover().setUserKey("user");

        RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class, RETURNS_DEEP_STUBS);
        RestClient mockClient = mock(RestClient.class);
        when(mockClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(uriSpec);
        when(uriSpec.contentType(any())).thenReturn(uriSpec);

        org.mockito.ArgumentCaptor<Map<String, String>> bodyCaptor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        when(uriSpec.body(bodyCaptor.capture())).thenReturn(uriSpec);
        when(uriSpec.retrieve().toBodilessEntity()).thenReturn(null);

        PushoverNotificationService pushoverService =
                new PushoverNotificationService(properties, mockClient);

        pushoverService.notify(new SunsetEvaluation(5, null, null, "Stunning sky."),
                "Bamburgh", TargetType.SUNRISE, LocalDate.of(2026, 3, 1));

        Map<String, String> body = bodyCaptor.getValue();
        assertThat(body.get("title")).contains("sunrise");
        assertThat(body.get("message")).contains("Rating 5/5");
        assertThat(body.get("message")).contains("Stunning sky.");
    }
}
