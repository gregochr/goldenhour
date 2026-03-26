package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.client.NoaaSwpcClient;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.model.AuroraForecastScore;
import com.gregochr.goldenhour.model.AuroraLocationSlot;
import com.gregochr.goldenhour.model.AuroraRegionSummary;
import com.gregochr.goldenhour.model.AuroraTonightSummary;
import com.gregochr.goldenhour.model.AuroraTomorrowSummary;
import com.gregochr.goldenhour.model.KpForecast;
import com.gregochr.goldenhour.service.aurora.AuroraStateCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds aurora summary sections for the daily briefing.
 *
 * <p>Tonight's summary comes from the in-memory {@link AuroraStateCache} FSM.
 * Tomorrow's summary comes from a NOAA SWPC 3-day Kp forecast lookup.
 */
@Component
public class BriefingAuroraSummaryBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(BriefingAuroraSummaryBuilder.class);

    private final AuroraStateCache auroraStateCache;
    private final NoaaSwpcClient noaaSwpcClient;

    /**
     * Constructs a {@code BriefingAuroraSummaryBuilder}.
     *
     * @param auroraStateCache aurora FSM cache for tonight's active-alert data
     * @param noaaSwpcClient   NOAA SWPC client for tomorrow's Kp forecast
     */
    public BriefingAuroraSummaryBuilder(AuroraStateCache auroraStateCache,
            NoaaSwpcClient noaaSwpcClient) {
        this.auroraStateCache = auroraStateCache;
        this.noaaSwpcClient = noaaSwpcClient;
    }

    /**
     * Builds tonight's aurora summary from the active aurora state cache.
     * Returns {@code null} when the state machine is idle (no active alert).
     *
     * @return tonight's aurora summary, or null
     */
    public AuroraTonightSummary buildAuroraTonight() {
        if (!auroraStateCache.isActive()) {
            return null;
        }
        AlertLevel alertLevel = auroraStateCache.getCurrentLevel();
        Double kp = auroraStateCache.getLastTriggerKp();
        List<AuroraForecastScore> scores = auroraStateCache.getCachedScores();

        // Group locations by region
        Map<String, List<AuroraLocationSlot>> regionSlots = new LinkedHashMap<>();
        for (AuroraForecastScore score : scores) {
            String regionName = score.location().getRegion() != null
                    ? score.location().getRegion().getName()
                    : score.location().getName();
            boolean clear = score.cloudPercent() < 75;
            AuroraLocationSlot slot = new AuroraLocationSlot(
                    score.location().getName(),
                    score.location().getBortleClass(),
                    clear,
                    score.cloudPercent());
            regionSlots.computeIfAbsent(regionName, k -> new ArrayList<>()).add(slot);
        }

        List<AuroraRegionSummary> regions = regionSlots.entrySet().stream()
                .map(e -> new AuroraRegionSummary(e.getKey(), e.getValue()))
                .collect(Collectors.toList());

        int clearCount = scores.stream()
                .filter(s -> s.cloudPercent() < 75)
                .mapToInt(s -> 1)
                .sum();

        return new AuroraTonightSummary(alertLevel, kp, clearCount, regions);
    }

    /**
     * Builds tomorrow night's aurora forecast summary from NOAA's 3-day Kp forecast.
     * Looks at windows 20–48 hours in the future to approximate tomorrow's dark window.
     * Returns {@code null} if the forecast cannot be fetched.
     *
     * @return tomorrow's aurora forecast summary, or null
     */
    public AuroraTomorrowSummary buildAuroraTomorrow() {
        try {
            List<KpForecast> forecast = noaaSwpcClient.fetchKpForecast();
            ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
            ZonedDateTime windowStart = now.plusHours(20);
            ZonedDateTime windowEnd = now.plusHours(48);

            double peakKp = forecast.stream()
                    .filter(f -> f.from().isBefore(windowEnd) && f.to().isAfter(windowStart))
                    .mapToDouble(KpForecast::kp)
                    .max()
                    .orElse(0.0);

            String label;
            if (peakKp >= 6) {
                label = "Potentially strong";
            } else if (peakKp >= 4) {
                label = "Worth watching";
            } else {
                label = "Quiet";
            }

            return new AuroraTomorrowSummary(peakKp, label);
        } catch (Exception e) {
            LOG.debug("Could not fetch tomorrow Kp forecast for briefing: {}", e.getMessage());
            return null;
        }
    }
}
