package com.gregochr.goldenhour.service.aurora;

import com.gregochr.goldenhour.model.AuroraAlertResponse;
import com.gregochr.goldenhour.model.CurrentNight;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Answers the aurora alert for a reader who may not see the aurora status itself (the Lite banner).
 *
 * <p>Reads only the state cache and the night rule: no NOAA calls, so it is cheap. The live-alert read is
 * {@link AuroraLiveAlert}, shared with {@link AuroraNightsService}.
 */
@Service
public class AuroraAlertService {

    private final AuroraForecastRunService forecastRunService;
    private final AuroraStateCache stateCache;

    /**
     * Constructs the service.
     *
     * @param forecastRunService owns the current-night rule and its injected clock
     * @param stateCache         the live alert state
     */
    public AuroraAlertService(AuroraForecastRunService forecastRunService, AuroraStateCache stateCache) {
        this.forecastRunService = forecastRunService;
        this.stateCache = stateCache;
    }

    /**
     * Returns the alert a Lite banner may show.
     *
     * <p>The simulation is read first (inside {@link AuroraLiveAlert#read}); a simulation answers QUIET,
     * inactive, with no trigger type and not all-overcast. Otherwise the trigger and counts are read, then
     * the night once, so its date and end come from one read of the clock. A null night (a stubbed
     * service) is relayed as null fields.
     *
     * @return the role-free alert
     */
    public AuroraAlertResponse liteAlert() {
        AuroraLiveAlert alert = AuroraLiveAlert.read(stateCache);
        String triggerType = null;
        boolean allOvercast = false;
        if (!alert.simulated()) {
            triggerType = triggerTypeLabel(stateCache.getLastTriggerType());
            Integer clearLocationCount = stateCache.getClearLocationCount();
            allOvercast = stateCache.getDarkSkyLocationCount() > 0
                    && clearLocationCount != null && clearLocationCount == 0;
        }
        CurrentNight night = forecastRunService.currentNight();
        LocalDate nightDate = null;
        Instant nightEndsAt = null;
        if (night != null) {
            nightDate = night.date();
            nightEndsAt = night.endsAt();
        }
        return new AuroraAlertResponse(alert.level(), alert.active(), triggerType, allOvercast,
                nightDate, nightEndsAt);
    }

    private static String triggerTypeLabel(TriggerType trigger) {
        if (trigger == null) {
            return null;
        }
        return trigger == TriggerType.FORECAST_LOOKAHEAD ? "forecast" : "realtime";
    }
}
