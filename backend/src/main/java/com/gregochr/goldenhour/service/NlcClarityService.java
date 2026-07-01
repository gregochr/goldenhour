package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.model.NlcNightClarity;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import com.gregochr.goldenhour.model.SeasonalWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.MonthDay;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Computes and caches which upcoming nights offer a clear dark-sky chance of seeing noctilucent
 * clouds (NLC) on the northern horizon.
 *
 * <p>Runs during the daily-briefing cycle off the hourly weather already fetched for the
 * briefing's colour locations — no additional external API call. For each in-season night in the
 * window it samples cloud cover at every dark-sky (Bortle-classified) location around solar
 * midnight and records the night as viable when at least one such location is clear. The cached
 * {@link NlcNightClarity} is read by {@link NlcHotTopicStrategy}, which must not make API calls
 * of its own.
 *
 * <p>The season gate matches {@link NlcHotTopicStrategy}: NLC is only visible from northern
 * England from late May to early August, so nights outside that window are never viable.
 */
@Component
public class NlcClarityService {

    private static final Logger LOG = LoggerFactory.getLogger(NlcClarityService.class);

    /**
     * Combined cloud percentage at or above which a location is considered too cloudy for NLC.
     * Mirrors the aurora clear-sky threshold ({@code BriefingAuroraSummaryBuilder.CLEAR_SKY_THRESHOLD})
     * so the two dark-sky night topics agree on what "clear" means.
     */
    static final int CLEAR_SKY_THRESHOLD = 75;

    /** NLC visibility season for northern England: late May to early August. */
    private static final SeasonalWindow NLC_SEASON =
            new SeasonalWindow(MonthDay.of(5, 25), MonthDay.of(8, 10), "NLC");

    /** Open-Meteo hourly timestamp format (UTC), e.g. {@code 2026-06-17T00:00}. */
    private static final DateTimeFormatter HOUR_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final AtomicReference<NlcNightClarity> cache = new AtomicReference<>();

    /**
     * Recomputes the NLC clarity cache from briefing weather.
     *
     * @param weathers per-location hourly forecasts already fetched for the briefing
     * @param nights   candidate night dates (each evening) to evaluate, typically the briefing window
     */
    public void refresh(List<BriefingSlotBuilder.LocationWeather> weathers, List<LocalDate> nights) {
        List<BriefingSlotBuilder.LocationWeather> darkSky = weathers.stream()
                .filter(w -> w.location() != null
                        && w.location().getBortleClass() != null
                        && w.forecast() != null)
                .toList();

        List<NlcNightClarity.ClearNight> clearNights = new ArrayList<>();
        for (LocalDate night : nights) {
            if (!NLC_SEASON.isActive(night)) {
                continue;
            }
            // NLC glows during deep twilight around solar midnight — the deepest part of the night
            // of `night` falls just after midnight UTC on the following calendar day.
            String hourKey = night.plusDays(1).atStartOfDay().format(HOUR_FORMAT);
            Set<String> regions = new LinkedHashSet<>();
            int clearCount = 0;
            for (BriefingSlotBuilder.LocationWeather w : darkSky) {
                Integer cloud = combinedCloudAt(w.forecast(), hourKey);
                if (cloud != null && cloud < CLEAR_SKY_THRESHOLD) {
                    clearCount++;
                    String region = regionName(w.location());
                    if (region != null) {
                        regions.add(region);
                    }
                }
            }
            if (clearCount > 0) {
                clearNights.add(new NlcNightClarity.ClearNight(
                        night, clearCount, new ArrayList<>(regions)));
            }
        }

        cache.set(new NlcNightClarity(clearNights));
        LOG.debug("NLC clarity refreshed: {} clear night(s) from {} dark-sky location(s)",
                clearNights.size(), darkSky.size());
    }

    /**
     * Returns the most recently computed NLC clarity, or {@code null} if the scan has not run
     * yet this session.
     *
     * @return the cached clarity, or null
     */
    public NlcNightClarity getCached() {
        return cache.get();
    }

    /**
     * Combined cloud cover at the given hour, taken as the worst of the low/mid/high layers —
     * any layer can hide NLC (which sit above the tropopause), so the densest layer governs.
     *
     * @return combined cloud percentage 0–100, or {@code null} if the hour is not in the forecast
     */
    private Integer combinedCloudAt(OpenMeteoForecastResponse forecast, String hourKey) {
        OpenMeteoForecastResponse.Hourly hourly = forecast.getHourly();
        if (hourly == null || hourly.getTime() == null) {
            return null;
        }
        int idx = hourly.getTime().indexOf(hourKey);
        if (idx < 0) {
            return null;
        }
        int low = valueAt(hourly.getCloudCoverLow(), idx);
        int mid = valueAt(hourly.getCloudCoverMid(), idx);
        int high = valueAt(hourly.getCloudCoverHigh(), idx);
        return Math.max(low, Math.max(mid, high));
    }

    private static int valueAt(List<Integer> values, int idx) {
        if (values == null || idx >= values.size()) {
            return 0;
        }
        Integer v = values.get(idx);
        return v != null ? v : 0;
    }

    private static String regionName(LocationEntity location) {
        RegionEntity region = location.getRegion();
        return region != null ? region.getName() : null;
    }
}
