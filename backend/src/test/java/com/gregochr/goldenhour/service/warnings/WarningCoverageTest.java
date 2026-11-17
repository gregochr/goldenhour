package com.gregochr.goldenhour.service.warnings;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.WeatherWarningVersionEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WarningCoverage}.
 */
class WarningCoverageTest {

    private static final String BOX = "{\"type\":\"MultiPolygon\",\"coordinates\":[[[[-2,54],[-1,54],[-1,55],[-2,55],"
            + "[-2,54]]]]}";
    private static final String BOX_WITH_HOLE = "{\"type\":\"MultiPolygon\",\"coordinates\":[[[[-3,53],[-1,53],"
            + "[-1,55],[-3,55],[-3,53]],[[-2.2,53.8],[-1.8,53.8],[-1.8,54.2],[-2.2,54.2],[-2.2,53.8]]]]}";

    private final WarningCoverage coverage = new WarningCoverage();

    private static LocationEntity location(long id, double lat, double lon) {
        LocationEntity l = new LocationEntity();
        l.setId(id);
        l.setLat(lat);
        l.setLon(lon);
        return l;
    }

    private static WeatherWarningVersionEntity warning(String id, String geometry) {
        WeatherWarningVersionEntity w = new WeatherWarningVersionEntity();
        w.setWarningId(id);
        w.setGeometry(geometry);
        return w;
    }

    @Test
    @DisplayName("lists the locations inside each warning and none of those outside")
    void insideAndOutside() {
        var inside = location(1, 54.5, -1.5);
        var outside = location(2, 56.0, -1.5);

        Map<String, Set<Long>> result = coverage.locationsByWarning(List.of(warning("W", BOX)),
                List.of(inside, outside));

        assertThat(result).containsOnlyKeys("W");
        assertThat(result.get("W")).containsExactly(1L);
    }

    @Test
    @DisplayName("a location in a hole is not covered; one beside the hole is")
    void holeHandled() {
        var inHole = location(1, 54.0, -2.0);
        var beside = location(2, 54.6, -1.5);

        Map<String, Set<Long>> result = coverage.locationsByWarning(List.of(warning("H", BOX_WITH_HOLE)),
                List.of(inHole, beside));

        assertThat(result.get("H")).containsExactly(2L);
    }

    @Test
    @DisplayName("longitude and latitude are not swapped: a point at lat -1.5, lon 54.5 is outside")
    void axisOrder() {
        var swapped = location(1, -1.5, 54.5);

        assertThat(coverage.locationsByWarning(List.of(warning("W", BOX)), List.of(swapped)).get("W")).isEmpty();
    }

    @Test
    @DisplayName("missing, blank or malformed geometry covers nothing and does not throw")
    void badGeometry() {
        var l = location(1, 54.5, -1.5);

        Map<String, Set<Long>> result = coverage.locationsByWarning(
                List.of(warning("NULL", null), warning("BLANK", " "), warning("BAD", "{oops"),
                        warning("POINT", "{\"type\":\"Point\",\"coordinates\":[-1.5,54.5]}")),
                List.of(l));

        assertThat(result).containsOnlyKeys("NULL", "BLANK", "BAD", "POINT");
        assertThat(result.values()).allSatisfy(ids -> assertThat(ids).isEmpty());
    }

    @Test
    @DisplayName("with no locations, every warning maps to an empty set")
    void noLocations() {
        assertThat(coverage.locationsByWarning(List.of(warning("W", BOX)), List.of()).get("W")).isEmpty();
    }
}
