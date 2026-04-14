package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.repository.RefreshTokenRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Periodically deletes expired refresh tokens from the database.
 *
 * <p>Registers itself with {@link DynamicSchedulerService} so the job can be
 * paused, rescheduled, or triggered from the Admin UI. The default schedule
 * (3 am daily) is seeded by the V81 Flyway migration.
 */
@Service
public class RefreshTokenCleanupService {

    private static final Logger LOG = LoggerFactory.getLogger(RefreshTokenCleanupService.class);

    private final RefreshTokenRepository refreshTokenRepository;
    private final DynamicSchedulerService dynamicSchedulerService;

    /**
     * Constructs the service.
     *
     * @param refreshTokenRepository the repository used to delete expired tokens
     * @param dynamicSchedulerService the dynamic scheduler for job registration
     */
    public RefreshTokenCleanupService(RefreshTokenRepository refreshTokenRepository,
            DynamicSchedulerService dynamicSchedulerService) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.dynamicSchedulerService = dynamicSchedulerService;
    }

    /**
     * Registers the cleanup job with the dynamic scheduler.
     */
    @PostConstruct
    void registerJob() {
        dynamicSchedulerService.registerJobTarget("refresh_token_cleanup", this::purgeExpiredTokens);
    }

    /**
     * Deletes all refresh tokens whose expiry timestamp is in the past.
     */
    void purgeExpiredTokens() {
        LocalDateTime now = LocalDateTime.now();
        refreshTokenRepository.deleteAllByExpiresAtBefore(now);
        LOG.info("Refresh token cleanup complete");
    }
}
