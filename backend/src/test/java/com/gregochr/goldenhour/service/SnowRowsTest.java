package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.SnowStateEntity;
import com.gregochr.goldenhour.entity.TargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link SnowRows}: the SUNRISE index, the lying rule and the place key. */
class SnowRowsTest {

    private static final LocalDate DAY = LocalDate.of(2024, 1, 15);

    private static SnowStateEntity row(String key, TargetType event, Double waterMm) {
        return SnowStateEntity.builder().pointKey(key).evaluationDate(DAY).eventType(event).snowWaterMm(waterMm)
                .build();
    }

    @Test
    @DisplayName("only SUNRISE rows are indexed, by point and date, and the first of a duplicate wins")
    void sunriseByPointDay_indexesSunriseOnly() {
        SnowStateEntity first = row("loc:1", TargetType.SUNRISE, 5.0);
        SnowStateEntity duplicate = row("loc:1", TargetType.SUNRISE, 9.0);
        SnowStateEntity sunset = row("loc:2", TargetType.SUNSET, 5.0);

        Map<SnowRows.PointDay, SnowStateEntity> index = SnowRows.sunriseByPointDay(List.of(first, duplicate, sunset));

        assertThat(index).containsOnlyKeys(new SnowRows.PointDay("loc:1", DAY));
        assertThat(index.get(new SnowRows.PointDay("loc:1", DAY))).isSameAs(first);
    }

    @Test
    @DisplayName("lying is 3.0 mm or more; a null row or null snow water is not lying")
    void isLying_boundaryAndNulls() {
        assertThat(SnowRows.isLying(row("loc:1", TargetType.SUNRISE, 3.0))).isTrue();
        assertThat(SnowRows.isLying(row("loc:1", TargetType.SUNRISE, 2.99))).isFalse();
        assertThat(SnowRows.isLying(row("loc:1", TargetType.SUNRISE, null))).isFalse();
        assertThat(SnowRows.isLying(null)).isFalse();
    }

    @Test
    @DisplayName("a place's key is loc:<id>; a null place or a place with no id has none")
    void keyOf_handlesMissingPlaceAndId() {
        LocationEntity place = new LocationEntity();
        place.setId(7L);

        assertThat(SnowRows.keyOf(place)).isEqualTo("loc:7");
        assertThat(SnowRows.keyOf(new LocationEntity())).isNull();
        assertThat(SnowRows.keyOf(null)).isNull();
    }
}
