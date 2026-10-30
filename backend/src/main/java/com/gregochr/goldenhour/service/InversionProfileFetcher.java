package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.InversionProfile;
import com.gregochr.goldenhour.model.OpenMeteoForecastResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Fetches the v2 inversion valley profile for locations (inversion-v2-plan Phase 1b), in two modes that share
 * {@link InversionProfileEligibility} and the same request.
 *
 * <ul>
 *   <li><b>Batch prefetch</b> ({@link #prefetch}): one batched call per 20 distinct valley-floor points for a
 *       whole run, keyed by LOCATION ID. A location absent from the result has no profile; callers must not
 *       fall back to an on-demand fetch for it.</li>
 *   <li><b>On demand</b> ({@link #fetchOnDemand}): one site, for the callers that have no prefetch (JFDI,
 *       force-submit, retry). The result, including a failure, is memoised per
 *       (floor lat, floor lng, floor metres) for {@value #MEMO_TTL_MS} ms, so a site called for four dates
 *       and two events costs one request.</li>
 * </ul>
 *
 * <p>Neither mode ever throws: a profile failure means "no profile", never a failed slot.
 */
public final class InversionProfileFetcher {

    private static final Logger LOG = LoggerFactory.getLogger(InversionProfileFetcher.class);

    /** How long an on-demand result (or failure) is reused, in milliseconds. */
    static final long MEMO_TTL_MS = 5 * 60 * 1000L;

    private final OpenMeteoClient client;

    /** Overridable in unit tests; production reads the wall clock. */
    LongSupplier nowMs = System::currentTimeMillis;

    private final Map<String, MemoEntry> memo = new ConcurrentHashMap<>();

    /**
     * A memoised on-demand result.
     *
     * @param expiresAtMs when the entry stops being reused
     * @param profile     the profile, or null for a remembered failure
     */
    private record MemoEntry(long expiresAtMs, InversionProfile profile) {
    }

    /**
     * Creates a fetcher.
     *
     * @param client the Open-Meteo client carrying the batch profile fetch
     */
    public InversionProfileFetcher(OpenMeteoClient client) {
        this.client = client;
    }

    /**
     * Fetches the profile for every eligible location in one batched run.
     *
     * @param locations the run's locations (ineligible ones and duplicates are ignored)
     * @return location id to profile, holding only the locations that got one; never null
     */
    public Map<Long, InversionProfile> prefetch(Collection<LocationEntity> locations) {
        Map<Long, InversionProfile> result = new HashMap<>();
        try {
            Map<String, List<LocationEntity>> byRequest = new LinkedHashMap<>();
            for (LocationEntity location : locations) {
                if (InversionProfileEligibility.isEligible(location) && location.getId() != null) {
                    byRequest.computeIfAbsent(InversionProfileEligibility.requestKey(location),
                            k -> new ArrayList<>()).add(location);
                }
            }
            if (byRequest.isEmpty()) {
                return result;
            }
            List<List<LocationEntity>> groups = new ArrayList<>(byRequest.values());
            List<double[]> points = groups.stream().map(g -> point(g.get(0))).toList();
            long startMs = nowMs.getAsLong();
            List<OpenMeteoForecastResponse> responses = client.fetchInversionProfileBatch(points);
            int populated = 0;
            for (int i = 0; i < groups.size() && i < responses.size(); i++) {
                InversionProfile profile = InversionProfile.from(responses.get(i),
                        groups.get(i).get(0).getValleyFloorMetres());
                if (profile == null) {
                    continue;
                }
                populated++;
                for (LocationEntity location : groups.get(i)) {
                    result.put(location.getId(), profile);
                }
            }
            LOG.info("Inversion profile prefetch: {}/{} valley-floor cells populated for {} location(s) in {}ms",
                    populated, groups.size(), result.size(), nowMs.getAsLong() - startMs);
        } catch (Exception e) {
            LOG.warn("Inversion profile prefetch failed, no v2 profiles this run: {}", e.getMessage());
        }
        return result;
    }

    /**
     * Fetches the profile for one location, reusing a recent result for the same valley-floor request.
     *
     * @param location the location
     * @return the profile, or null when the location is ineligible or the fetch failed
     */
    public InversionProfile fetchOnDemand(LocationEntity location) {
        if (!InversionProfileEligibility.isEligible(location)) {
            return null;
        }
        String key = InversionProfileEligibility.requestKey(location);
        long now = nowMs.getAsLong();
        memo.values().removeIf(entry -> entry.expiresAtMs() <= now);
        MemoEntry cached = memo.get(key);
        if (cached != null) {
            return cached.profile();
        }
        InversionProfile profile = null;
        try {
            List<OpenMeteoForecastResponse> responses = client.fetchInversionProfileBatch(List.of(point(location)));
            if (!responses.isEmpty()) {
                profile = InversionProfile.from(responses.get(0), location.getValleyFloorMetres());
            }
        } catch (Exception e) {
            LOG.warn("Inversion profile fetch failed for {}, no v2 profile: {}", location.getName(),
                    e.getMessage());
        }
        memo.put(key, new MemoEntry(now + MEMO_TTL_MS, profile));
        return profile;
    }

    private static double[] point(LocationEntity location) {
        return new double[]{location.getValleyFloorLat(), location.getValleyFloorLng(),
            location.getValleyFloorMetres()};
    }
}
