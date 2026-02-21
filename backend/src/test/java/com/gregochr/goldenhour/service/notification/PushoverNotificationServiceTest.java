package com.gregochr.goldenhour.service.notification;

import com.gregochr.goldenhour.config.NotificationProperties;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDate;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link PushoverNotificationService}.
 */
@ExtendWith(MockitoExtension.class)
class PushoverNotificationServiceTest {

    @Mock
    private WebClient webClient;

    @Mock
    private NotificationProperties properties;

    @InjectMocks
    private PushoverNotificationService pushoverService;

    @Test
    @DisplayName("notify() does nothing when Pushover notifications are disabled")
    void notify_whenDisabled_makesNoHttpCall() {
        NotificationProperties.Pushover pushover = new NotificationProperties.Pushover();
        pushover.setEnabled(false);
        org.mockito.Mockito.when(properties.getPushover()).thenReturn(pushover);

        pushoverService.notify(new SunsetEvaluation(3, "Moderate."),
                "Durham UK", TargetType.SUNSET, LocalDate.of(2026, 2, 20));

        verify(webClient, never()).post();
    }
}
