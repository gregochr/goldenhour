package com.gregochr.goldenhour.service.batch;

import com.gregochr.goldenhour.service.DynamicSchedulerService;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

/**
 * Registers the {@code disposition_cleanup} job with the dynamic scheduler.
 *
 * <p>Mirrors {@code RefreshTokenCleanupService} — the actual prune logic lives
 * in {@link ForecastDispositionService#pruneStale}; this class only wires the
 * scheduler target so the V101-seeded schedule executes against it. The
 * default cron ({@code 0 30 3 * * *}, daily 03:30) is set in the V101
 * migration; admins can pause or reschedule from the Scheduler view in the
 * Manage tab.
 */
@Service
public class ForecastDispositionCleanupService {

    private final ForecastDispositionService dispositionService;
    private final DynamicSchedulerService dynamicSchedulerService;

    /**
     * Constructs the cleanup wiring.
     *
     * @param dispositionService      service exposing {@link ForecastDispositionService#pruneStale}
     * @param dynamicSchedulerService scheduler to register the cleanup target against
     */
    public ForecastDispositionCleanupService(ForecastDispositionService dispositionService,
            DynamicSchedulerService dynamicSchedulerService) {
        this.dispositionService = dispositionService;
        this.dynamicSchedulerService = dynamicSchedulerService;
    }

    /**
     * Registers the cleanup job with the dynamic scheduler so the V101-seeded
     * cron row resolves to {@link ForecastDispositionService#pruneStale}.
     */
    @PostConstruct
    void registerJob() {
        dynamicSchedulerService.registerJobTarget("disposition_cleanup",
                dispositionService::pruneStale);
    }
}
