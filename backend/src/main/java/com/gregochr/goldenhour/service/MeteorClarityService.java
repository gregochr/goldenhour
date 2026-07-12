package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.MeteorClarity;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Scans, once per briefing run, how many dark-sky locations are forecast clear <em>overhead</em> on
 * each meteor-shower peak night in the forecast window, so the {@link MeteorHotTopicStrategy} can add
 * a "clear at X of Y dark-sky locations" fact without making any external call itself.
 *
 * <p>Mirrors {@link NlcClarityService} — an in-memory {@link AtomicReference} cache refreshed from
 * {@code BriefingService}, failure-isolated so a fetch problem only suppresses the fact. The one
 * difference is the sampler: this uses {@link OverheadCloudSampler} (total cloud directly above each
 * site), the honest whole-sky signal for meteors, rather than the northern-horizon transect the
 * aurora/NLC counts use. It fetches <b>only on shower-peak nights</b> (see
 * {@link MeteorHotTopicStrategy#peakDatesWithin}), so most briefings do no meteor cloud fetch at all.
 */
@Component
public class MeteorClarityService {

    private static final Logger LOG = LoggerFactory.getLogger(MeteorClarityService.class);

    /**
     * Total-column cloud (%) below which a dark-sky location counts as clear overhead. Matches the
     * aurora/NLC clear threshold so "clear" means the same darkness of sky across the night topics.
     */
    static final int CLEAR_SKY_THRESHOLD = 75;

    private static final DateTimeFormatter HOUR_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final OverheadCloudSampler overheadCloudSampler;
    private final LocationRepository locationRepository;
    private final AtomicReference<MeteorClarity> cache = new AtomicReference<>(MeteorClarity.EMPTY);

    /**
     * Constructs a {@code MeteorClarityService}.
     *
     * @param overheadCloudSampler samples total-column cloud directly above each dark-sky site
     * @param locationRepository   repository for dark-sky (Bortle) location lookups
     */
    public MeteorClarityService(OverheadCloudSampler overheadCloudSampler,
            LocationRepository locationRepository) {
        this.overheadCloudSampler = overheadCloudSampler;
        this.locationRepository = locationRepository;
    }

    /**
     * Refreshes the cache for the given forecast-window nights. Fetches overhead cloud only for nights
     * that fall on a catalogued shower peak; otherwise caches {@link MeteorClarity#EMPTY} and makes no
     * external call. Each peak night is sampled at its deep-night hour (mirroring the NLC scan).
     *
     * @param nights the forecast-window nights (evening dates)
     */
    public void refresh(List<LocalDate> nights) {
        List<LocalDate> peaks = MeteorHotTopicStrategy.peakDatesWithin(nights);
        if (peaks.isEmpty()) {
            cache.set(MeteorClarity.EMPTY);
            return;
        }
        List<LocationEntity> darkSky = locationRepository.findByBortleClassIsNotNullAndEnabledTrue();
        if (darkSky.isEmpty()) {
            cache.set(MeteorClarity.EMPTY);
            return;
        }

        // One deep-night hour key per peak night (~solar midnight UTC on the following morning).
        List<String> hourKeys = peaks.stream()
                .map(p -> p.plusDays(1).atStartOfDay().format(HOUR_FORMAT))
                .toList();
        Map<LocationEntity, int[]> cloud = overheadCloudSampler.sample(darkSky, hourKeys);

        Map<LocalDate, MeteorClarity.NightClarity> byNight = new LinkedHashMap<>();
        for (int i = 0; i < peaks.size(); i++) {
            int clearCount = 0;
            for (LocationEntity loc : darkSky) {
                int[] hourly = cloud.get(loc);
                if (hourly != null && hourly[i] < CLEAR_SKY_THRESHOLD) {
                    clearCount++;
                }
            }
            byNight.put(peaks.get(i), new MeteorClarity.NightClarity(clearCount, darkSky.size()));
        }
        cache.set(new MeteorClarity(byNight));
        LOG.debug("Meteor clarity refreshed: {} peak night(s) from {} dark-sky location(s)",
                peaks.size(), darkSky.size());
    }

    /**
     * Returns the cached overhead-clarity scan (never null; {@link MeteorClarity#EMPTY} until the
     * first refresh or when no shower peak is in the window).
     *
     * @return the cached meteor clarity
     */
    public MeteorClarity getCached() {
        return cache.get();
    }
}
