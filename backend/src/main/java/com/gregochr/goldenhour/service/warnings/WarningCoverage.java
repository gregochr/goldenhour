package com.gregochr.goldenhour.service.warnings;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.WeatherWarningVersionEntity;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which locations each warning covers: a point-in-MultiPolygon test of every given location's coordinates.
 *
 * <p>Pure and computed per call, never cached: the location roster changes (enable, disable, add) and a few active
 * warnings against a few hundred locations is cheap, so there is no key to get wrong. Geometry that is missing or
 * malformed covers nothing; it never throws.
 */
@Component
public class WarningCoverage {

    /**
     * The ids of the locations inside each warning's polygon.
     *
     * @param warnings  the warning versions to test
     * @param locations the locations to test (the caller passes the enabled ones)
     * @return location ids per warning id, in the warnings' order; a warning that covers nothing maps to an empty set
     */
    public Map<String, Set<Long>> locationsByWarning(Collection<WeatherWarningVersionEntity> warnings,
            Collection<LocationEntity> locations) {
        Map<String, Set<Long>> out = new LinkedHashMap<>();
        for (WeatherWarningVersionEntity warning : warnings) {
            List<List<double[][]>> polygons = WarningGeometry.parse(warning.getGeometry());
            Set<Long> covered = new LinkedHashSet<>();
            if (!polygons.isEmpty()) {
                for (LocationEntity location : locations) {
                    if (WarningGeometry.contains(polygons, location.getLon(), location.getLat())) {
                        covered.add(location.getId());
                    }
                }
            }
            out.put(warning.getWarningId(), covered);
        }
        return out;
    }
}
