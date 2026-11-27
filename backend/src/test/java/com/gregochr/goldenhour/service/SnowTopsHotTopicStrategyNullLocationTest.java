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
import static org.mockito.Mockito.mock;

/**
 * Null-safety of {@link SnowTopsHotTopicStrategy}: a roster entry that is {@code null}-shaped (no id) is ignored
 * and a located place still fires. SNOW_TOPS no longer reads slot rows, so there is no null-location row to meet;
 * what can be absent is a roster place's id.
 */
class SnowTopsHotTopicStrategyNullLocationTest {

    private static final LocalDate FROM = LocalDate.of(2024, 1, 15);
    private static final LocalDate TO = FROM.plusDays(3);
    private static final LocalDateTime COMPUTED = LocalDateTime.of(2024, 1, 15, 4, 0);

    private static LocationEntity grasmere(Long id) {
        RegionEntity region = new RegionEntity();
        region.setName("The Lake District");
        LocationEntity place = new LocationEntity();
        place.setId(id);
        place.setName("Grasmere");
        place.setRegion(region);
        place.setLat(54.46);
        place.setLon(-3.02);
        return place;
    }

    private static SnowStateEntity row(String key, Double waterMm, Integer lowCloud) {
        return SnowStateEntity.builder().pointKey(key).evaluationDate(FROM).eventType(TargetType.SUNRISE)
                .snowWaterMm(waterMm).snowfall24hCm(0.0).lowCloudPct(lowCloud).computedAt(COMPUTED).build();
    }

    @Test
    @DisplayName("a roster place with no id is ignored and a located one still fires")
    void detect_placeWithoutIdIgnored() {
        SnowTopsHotTopicStrategy strategy = new SnowTopsHotTopicStrategy(mock(LocationRepository.class),
                mock(SnowStateReader.class));
        List<LocationEntity> roster = List.of(grasmere(null), grasmere(7L));
        List<SnowStateEntity> rows = List.of(row("summit:helvellyn", 40.0, null), row("loc:7", 0.0, 30));

        List<HotTopic> topics = strategy.detect(FROM, TO, HotTopicInputs.ofSnow(null, () -> roster, () -> rows));

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).regions()).containsExactly("The Lake District");
        assertThat(topics.get(0).facts()).extracting(f -> f.value()).containsExactly("Helvellyn white");
    }
}
