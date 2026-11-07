package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.SlotSignals;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A slot with no location cannot be judged against a summit, so it never fires SNOW_TOPS, while a located white row
 * beside it still does.
 */
class SnowTopsHotTopicStrategyNullLocationTest {

    private static final LocalDate FROM = LocalDate.of(2026, 1, 15);
    private static final LocalDate TO = FROM.plusDays(3);

    private static SlotSignals.Readings freezingAt(double metres) {
        return new SlotSignals.Readings(
                null, null, null, null, null, metres, null, null, null, null, null, null, false);
    }

    @Test
    @DisplayName("a null-location row is ignored and a located white-tops row still fires")
    void detect_nullLocationRowIgnored() {
        RegionEntity region = new RegionEntity();
        region.setName("The Lake District");
        LocationEntity fell = new LocationEntity();
        fell.setRegion(region);
        fell.setElevationMetres(451);
        SlotSignals orphan = new SlotSignals(null, FROM, TargetType.SUNRISE, SlotSignals.Scores.EMPTY,
                freezingAt(100.0));
        SlotSignals located = new SlotSignals(fell, FROM, TargetType.SUNRISE, SlotSignals.Scores.EMPTY,
                freezingAt(351.0));
        SlotSignalReader reader = mock(SlotSignalReader.class);
        when(reader.read(FROM, TO)).thenReturn(List.of(orphan, located));

        List<HotTopic> topics = new SnowTopsHotTopicStrategy(reader).detect(FROM, TO);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).regions()).containsExactly("The Lake District");
        assertThat(topics.get(0).facts()).extracting(f -> f.value())
                .containsExactly("~351 m", "100 m below the tops");
    }
}
