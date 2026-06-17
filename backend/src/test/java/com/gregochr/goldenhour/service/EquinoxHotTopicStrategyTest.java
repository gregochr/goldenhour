package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.repository.LocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EquinoxHotTopicStrategy}.
 */
@ExtendWith(MockitoExtension.class)
class EquinoxHotTopicStrategyTest {

    private static final LocalDate SPRING_EQUINOX = LocalDate.of(2026, 3, 20);
    private static final LocalDate FAR_FROM_EQUINOX = LocalDate.of(2026, 6, 17);
    private static final double LAT = 54.5;
    private static final double LON = -0.6;

    @Mock
    private SolarService solarService;

    @Mock
    private LocationRepository locationRepository;

    private EquinoxHotTopicStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new EquinoxHotTopicStrategy(solarService, locationRepository);
    }

    @Test
    @DisplayName("near equinox with due-east sunrise fires with priority 6")
    void detect_nearEquinoxAligned_fires() {
        when(locationRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(enabledLocation());
        when(solarService.sunriseAzimuthDeg(LAT, LON, SPRING_EQUINOX)).thenReturn(90);
        when(solarService.sunsetAzimuthDeg(LAT, LON, SPRING_EQUINOX)).thenReturn(270);

        List<HotTopic> topics = strategy.detect(SPRING_EQUINOX, SPRING_EQUINOX);

        assertThat(topics).hasSize(1);
        HotTopic topic = topics.get(0);
        assertThat(topic.type()).isEqualTo("EQUINOX");
        assertThat(topic.priority()).isEqualTo(6);
        assertThat(topic.regions()).containsExactly("Tyne and Wear");
    }

    @Test
    @DisplayName("sunrise azimuth exactly 3 degrees off due east still fires (boundary)")
    void detect_azimuthAtTolerance_fires() {
        when(locationRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(enabledLocation());
        when(solarService.sunriseAzimuthDeg(LAT, LON, SPRING_EQUINOX)).thenReturn(93);
        when(solarService.sunsetAzimuthDeg(LAT, LON, SPRING_EQUINOX)).thenReturn(280);

        assertThat(strategy.detect(SPRING_EQUINOX, SPRING_EQUINOX)).hasSize(1);
    }

    @Test
    @DisplayName("azimuth beyond tolerance on both events does not fire (boundary)")
    void detect_azimuthBeyondTolerance_doesNotFire() {
        when(locationRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(enabledLocation());
        when(solarService.sunriseAzimuthDeg(LAT, LON, SPRING_EQUINOX)).thenReturn(94);
        when(solarService.sunsetAzimuthDeg(LAT, LON, SPRING_EQUINOX)).thenReturn(274);

        assertThat(strategy.detect(SPRING_EQUINOX, SPRING_EQUINOX)).isEmpty();
    }

    @Test
    @DisplayName("date far from any equinox does not fire")
    void detect_farFromEquinox_doesNotFire() {
        when(locationRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(enabledLocation());

        assertThat(strategy.detect(FAR_FROM_EQUINOX, FAR_FROM_EQUINOX)).isEmpty();
    }

    @Test
    @DisplayName("no enabled locations does not fire")
    void detect_noLocations_doesNotFire() {
        when(locationRepository.findAllByEnabledTrueOrderByNameAsc()).thenReturn(List.of());

        assertThat(strategy.detect(SPRING_EQUINOX, SPRING_EQUINOX)).isEmpty();
    }

    @Test
    @DisplayName("isNearEquinox: ±3 days of an equinox is true, ±4 is false")
    void isNearEquinox_boundary() {
        assertThat(EquinoxHotTopicStrategy.isNearEquinox(LocalDate.of(2026, 3, 23))).isTrue();
        assertThat(EquinoxHotTopicStrategy.isNearEquinox(LocalDate.of(2026, 3, 24))).isFalse();
        assertThat(EquinoxHotTopicStrategy.isNearEquinox(LocalDate.of(2026, 9, 22))).isTrue();
        assertThat(EquinoxHotTopicStrategy.isNearEquinox(LocalDate.of(2026, 9, 26))).isFalse();
    }

    private List<LocationEntity> enabledLocation() {
        RegionEntity region = new RegionEntity();
        region.setName("Tyne and Wear");
        return List.of(LocationEntity.builder()
                .name("Tyne Valley").lat(LAT).lon(LON).region(region).build());
    }
}
