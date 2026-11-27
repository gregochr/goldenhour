package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.SnowStateEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;

/**
 * Feeds the snow detectors the values the S2 fixture replay computed for the 2026-11-24 evidence
 * ({@code PointSnowpackFixtureReplayTest}, recorded in {@code snow-topics-truth-plan.md} §3.1), so the
 * production false positive (Rydal Water, Grasmere, Durham and Tynemouth called "fresh snow" off a 9 km grid
 * cell) is pinned shut at the detector level, not only in the snowpack arithmetic.
 */
class SnowTopicsReplayTest {

    private static final LocalDate DAY_25 = LocalDate.of(2026, 11, 25);
    private static final LocalDate DAY_28 = LocalDate.of(2026, 11, 28);
    private static final LocalDateTime COMPUTED = LocalDateTime.of(2026, 11, 25, 4, 0);

    private static final LocationEntity RYDAL = place(1, "Rydal Water", "The Lake District", 54.4476, -2.9987);
    private static final LocationEntity GRASMERE = place(2, "Grasmere", "The Lake District", 54.4590, -3.0236);
    private static final LocationEntity DURHAM = place(3, "Durham", "Northumberland", 54.7761, -1.5733);
    private static final LocationEntity TYNEMOUTH = place(4, "Tynemouth", "Northumberland", 55.0170, -1.4230);
    private static final LocationEntity WINDY_GYLE = place(5, "Windy Gyle", "Northumberland", 55.4350, -2.2050);

    private static LocationEntity place(long id, String name, String region, double lat, double lon) {
        RegionEntity regionEntity = new RegionEntity();
        regionEntity.setName(region);
        LocationEntity location = new LocationEntity();
        location.setId(id);
        location.setName(name);
        location.setRegion(regionEntity);
        location.setLat(lat);
        location.setLon(lon);
        return location;
    }

    private static SnowStateEntity row(String key, LocalDate date, double waterMm, Integer lowCloud) {
        return SnowStateEntity.builder().pointKey(key).evaluationDate(date).eventType(TargetType.SUNRISE)
                .snowWaterMm(waterMm).snowfall24hCm(0.0).lowCloudPct(lowCloud).computedAt(COMPUTED).build();
    }

    private static List<SnowStateEntity> replayRows() {
        return List.of(
                // 25 Nov: every valley place at 0 mm; the Lakes summits white (S2 replay table).
                row("loc:1", DAY_25, 0.0, 30), row("loc:2", DAY_25, 0.0, 30), row("loc:3", DAY_25, 0.0, 30),
                row("loc:4", DAY_25, 0.0, 30), row("loc:5", DAY_25, 2.9, 30),
                row("summit:scafell-pike", DAY_25, 40.0, null), row("summit:helvellyn", DAY_25, 37.1, null),
                // 28 Nov: Windy Gyle's own pack reaches 5.0 mm; the valleys are still bare.
                row("loc:1", DAY_28, 0.0, 30), row("loc:2", DAY_28, 0.0, 30), row("loc:3", DAY_28, 0.0, 30),
                row("loc:4", DAY_28, 0.0, 30), row("loc:5", DAY_28, 5.0, 30),
                row("summit:scafell-pike", DAY_28, 35.9, null), row("summit:helvellyn", DAY_28, 37.8, null));
    }

    private static HotTopicInputs inputs() {
        List<LocationEntity> roster = List.of(DURHAM, GRASMERE, RYDAL, TYNEMOUTH, WINDY_GYLE);
        List<SnowStateEntity> rows = replayRows();
        return HotTopicInputs.ofSnow(List::of, () -> roster, () -> rows);
    }

    @Test
    @DisplayName("replay: Rydal, Grasmere, Durham and Tynemouth are never SNOW_FRESH, on any replayed day")
    void valleyPlacesNeverFreshSnow() {
        SnowFreshHotTopicStrategy fresh = new SnowFreshHotTopicStrategy(null, mock(LocationRepository.class),
                mock(SnowStateReader.class));

        List<HotTopic> topics = fresh.detect(DAY_25, DAY_28, inputs());

        assertThat(topics).extracting(HotTopic::date, HotTopic::locationNames)
                .containsExactly(tuple(DAY_28, List.of("Windy Gyle")));
    }

    @Test
    @DisplayName("replay: the Lakes valleys are green-below SNOW_TOPS under white Lakes summits, not the coast")
    void lakesValleysSeeWhiteSummits() {
        SnowTopsHotTopicStrategy tops = new SnowTopsHotTopicStrategy(mock(LocationRepository.class),
                mock(SnowStateReader.class));

        List<HotTopic> topics = tops.detect(DAY_25, DAY_28, inputs());

        assertThat(topics).extracting(HotTopic::date, HotTopic::detail, HotTopic::locationNames)
                .containsExactly(
                        tuple(DAY_25, "Tops white above green valleys", List.of("Grasmere", "Rydal Water")),
                        tuple(DAY_28, "Tops white above green valleys", List.of("Grasmere", "Rydal Water")));
        assertThat(topics.get(0).regions()).containsExactly("The Lake District");
    }
}
