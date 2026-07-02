package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.model.NlcNightClarity;
import com.gregochr.goldenhour.model.SeasonalWindow;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.MonthDay;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Computes and caches which upcoming nights offer a clear dark-sky chance of seeing noctilucent
 * clouds (NLC) on the northern horizon.
 *
 * <p>NLC glow low on the <em>northern</em> horizon during deep twilight, so — like aurora — what
 * blocks the view is cloud toward the north, not overhead. For each in-season night in the window
 * this samples cloud along a northward transect ({@link NorthwardTransectSampler}) at every
 * dark-sky (Bortle-classified) location for the deep-night hour, and records the night as viable
 * when at least one such location has a clear northern horizon. The cached
 * {@link NlcNightClarity} is read by {@link NlcHotTopicStrategy}, which must not make API calls of
 * its own — this runs during the daily-briefing cycle instead.
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

    private final NorthwardTransectSampler transectSampler;
    private final LocationRepository locationRepository;
    private final AtomicReference<NlcNightClarity> cache = new AtomicReference<>();

    /**
     * Constructs an {@code NlcClarityService}.
     *
     * @param transectSampler    shared northward-transect cloud sampler
     * @param locationRepository repository for dark-sky (Bortle) location lookups
     */
    public NlcClarityService(NorthwardTransectSampler transectSampler,
            LocationRepository locationRepository) {
        this.transectSampler = transectSampler;
        this.locationRepository = locationRepository;
    }

    /**
     * Recomputes the NLC clarity cache by sampling the northern-horizon transect at every dark-sky
     * location for each in-season night's deep-night hour.
     *
     * @param nights candidate night dates (each evening) to evaluate, typically the briefing window
     */
    public void refresh(List<LocalDate> nights) {
        List<LocalDate> seasonNights = nights.stream().filter(NLC_SEASON::isActive).toList();
        if (seasonNights.isEmpty()) {
            cache.set(new NlcNightClarity(List.of()));
            return;
        }
        List<LocationEntity> darkSky = locationRepository.findByBortleClassIsNotNullAndEnabledTrue();
        if (darkSky.isEmpty()) {
            cache.set(new NlcNightClarity(List.of()));
            return;
        }

        // NLC glows during deep twilight around solar midnight — the deepest part of the night of
        // `night` falls just after midnight UTC on the following calendar day. One hour key per
        // in-season night, sampled together in a single transect fetch.
        List<String> hourKeys = seasonNights.stream()
                .map(n -> n.plusDays(1).atStartOfDay().format(HOUR_FORMAT))
                .toList();
        Map<LocationEntity, int[]> cloud = transectSampler.sample(
                darkSky, hourKeys, NorthwardTransectSampler.LayerCombiner.MAX_LAYER);

        List<NlcNightClarity.ClearNight> clearNights = new ArrayList<>();
        for (int night = 0; night < seasonNights.size(); night++) {
            Set<String> regions = new LinkedHashSet<>();
            int clearCount = 0;
            for (LocationEntity loc : darkSky) {
                int[] hourly = cloud.get(loc);
                if (hourly != null && hourly[night] < CLEAR_SKY_THRESHOLD) {
                    clearCount++;
                    String region = regionName(loc);
                    if (region != null) {
                        regions.add(region);
                    }
                }
            }
            if (clearCount > 0) {
                clearNights.add(new NlcNightClarity.ClearNight(
                        seasonNights.get(night), clearCount, new ArrayList<>(regions)));
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

    private static String regionName(LocationEntity location) {
        RegionEntity region = location.getRegion();
        return region != null ? region.getName() : null;
    }
}
