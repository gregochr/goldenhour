package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Selects sentinel locations from a region for early evaluation.
 *
 * <p>Sentinels are the geographic extremes (N, S, E, W) plus the location nearest
 * to the centroid. For regions with 5 or fewer locations, all are sentinels.
 */
@Service
public class SentinelSelector {

    private static final int MAX_SENTINELS = 5;

    /**
     * Selects sentinel locations from the given list.
     *
     * @param locations the candidate locations (must not be null)
     * @return the sentinel subset (3-5 locations, or all if ≤5)
     */
    public List<LocationEntity> selectSentinels(List<LocationEntity> locations) {
        if (locations.size() <= MAX_SENTINELS) {
            return List.copyOf(locations);
        }

        LinkedHashSet<LocationEntity> sentinels = new LinkedHashSet<>();

        // Geographic extremes
        sentinels.add(locations.stream().max(Comparator.comparingDouble(LocationEntity::getLat)).orElseThrow());
        sentinels.add(locations.stream().min(Comparator.comparingDouble(LocationEntity::getLat)).orElseThrow());
        sentinels.add(locations.stream().max(Comparator.comparingDouble(LocationEntity::getLon)).orElseThrow());
        sentinels.add(locations.stream().min(Comparator.comparingDouble(LocationEntity::getLon)).orElseThrow());

        // Centroid — nearest location
        double centroidLat = locations.stream().mapToDouble(LocationEntity::getLat).average().orElse(0);
        double centroidLon = locations.stream().mapToDouble(LocationEntity::getLon).average().orElse(0);
        LocationEntity nearest = locations.stream()
                .min(Comparator.comparingDouble(loc ->
                        distanceSquared(loc.getLat(), loc.getLon(), centroidLat, centroidLon)))
                .orElseThrow();
        sentinels.add(nearest);

        return new ArrayList<>(sentinels);
    }

    private static double distanceSquared(double lat1, double lon1, double lat2, double lon2) {
        double dLat = lat1 - lat2;
        double dLon = lon1 - lon2;
        return dLat * dLat + dLon * dLon;
    }
}
