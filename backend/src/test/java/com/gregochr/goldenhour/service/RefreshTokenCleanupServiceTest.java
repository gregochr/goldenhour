package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.repository.RefreshTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import com.gregochr.goldenhour.entity.SchedulerFireStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RefreshTokenCleanupService}.
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenCleanupServiceTest {

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private DynamicSchedulerService dynamicSchedulerService;

    private RefreshTokenCleanupService service;

    @BeforeEach
    void setUp() {
        service = new RefreshTokenCleanupService(refreshTokenRepository, dynamicSchedulerService);
    }

    @Test
    @DisplayName("registerJob registers the correct job key with the dynamic scheduler")
    void registerJob_registersCorrectJobKey() {
        service.registerJob();

        verify(dynamicSchedulerService).registerJobTarget(
                eq("refresh_token_cleanup"),
                org.mockito.ArgumentMatchers.any(Runnable.class));
    }

    @Test
    @DisplayName("purgeExpiredTokens calls deleteAllByExpiresAtBefore with the current time")
    void purgeExpiredTokens_callsDeleteWithCurrentTime() {
        LocalDateTime before = LocalDateTime.now();

        service.purgeExpiredTokens();

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(refreshTokenRepository).deleteAllByExpiresAtBefore(captor.capture());
        LocalDateTime cutoff = captor.getValue();
        assertThat(cutoff).isAfterOrEqualTo(before);
        assertThat(cutoff).isBeforeOrEqualTo(LocalDateTime.now().plusSeconds(1));
    }

    @Test
    @DisplayName("purgeExpiredTokens calls the repository exactly once per invocation")
    void purgeExpiredTokens_callsRepositoryOnce() {
        service.purgeExpiredTokens();

        verify(refreshTokenRepository, org.mockito.Mockito.times(1))
                .deleteAllByExpiresAtBefore(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("purgeExpiredTokens reports SUCCEEDED with the number of tokens deleted")
    void purgeExpiredTokens_reportsCount() {
        when(refreshTokenRepository.deleteAllByExpiresAtBefore(any(LocalDateTime.class))).thenReturn(4L);

        SchedulerFireOutcome.Report report = SchedulerFireOutcome.capture(service::purgeExpiredTokens);

        assertThat(report.status()).isEqualTo(SchedulerFireStatus.SUCCEEDED);
        assertThat(report.summary()).isEqualTo("4 expired tokens deleted");
    }

    @Test
    @DisplayName("purgeExpiredTokens with nothing to delete reports SUCCEEDED, not a skip")
    void purgeExpiredTokens_zeroIsStillSucceeded() {
        when(refreshTokenRepository.deleteAllByExpiresAtBefore(any(LocalDateTime.class))).thenReturn(0L);

        SchedulerFireOutcome.Report report = SchedulerFireOutcome.capture(service::purgeExpiredTokens);

        assertThat(report.status()).isEqualTo(SchedulerFireStatus.SUCCEEDED);
        assertThat(report.summary()).isEqualTo("0 expired tokens deleted");
    }
}
