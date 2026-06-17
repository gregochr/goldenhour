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
 * Unit tests for {@link NlcHotTopicStrategy}.
 */
@ExtendWith(MockitoExtension.class)
class NlcHotTopicStrategyTest {

    private static final LocalDate IN_SEASON = LocalDate.of(2026, 6, 17);
    private static final LocalDate BEFORE_SEASON = LocalDate.of(2026, 5, 24);
    private static final LocalDate AFTER_SEASON = LocalDate.of(2026, 8, 11);

    @Mock
    private LocationRepository locationRepository;

    private NlcHotTopicStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new NlcHotTopicStrategy(locationRepository);
    }

    @Test
    @DisplayName("in NLC season fires with priority 8 and dark-sky regions")
    void detect_inSeason_fires() {
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue())
                .thenReturn(darkSkyLocation());

        List<HotTopic> topics = strategy.detect(IN_SEASON, IN_SEASON);

        assertThat(topics).hasSize(1);
        HotTopic topic = topics.get(0);
        assertThat(topic.type()).isEqualTo("NLC");
        assertThat(topic.priority()).isEqualTo(8);
        assertThat(topic.date()).isEqualTo(IN_SEASON);
        assertThat(topic.regions()).containsExactly("Northumberland");
    }

    @Test
    @DisplayName("season start boundary: 05-25 fires, 05-24 does not")
    void detect_seasonStartBoundary() {
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue())
                .thenReturn(darkSkyLocation());
        LocalDate seasonStart = LocalDate.of(2026, 5, 25);

        assertThat(strategy.detect(seasonStart, seasonStart)).hasSize(1);
        assertThat(strategy.detect(BEFORE_SEASON, BEFORE_SEASON)).isEmpty();
    }

    @Test
    @DisplayName("season end boundary: 08-10 fires, 08-11 does not")
    void detect_seasonEndBoundary() {
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue())
                .thenReturn(darkSkyLocation());
        LocalDate seasonEnd = LocalDate.of(2026, 8, 10);

        assertThat(strategy.detect(seasonEnd, seasonEnd)).hasSize(1);
        assertThat(strategy.detect(AFTER_SEASON, AFTER_SEASON)).isEmpty();
    }

    @Test
    @DisplayName("window edge: fires on earliest in-season day when from is out of season")
    void detect_windowStraddlesSeasonStart_firesOnFirstInSeasonDay() {
        when(locationRepository.findByBortleClassIsNotNullAndEnabledTrue())
                .thenReturn(darkSkyLocation());
        LocalDate from = LocalDate.of(2026, 5, 23);
        LocalDate to = LocalDate.of(2026, 5, 26);

        List<HotTopic> topics = strategy.detect(from, to);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).date()).isEqualTo(LocalDate.of(2026, 5, 25));
    }

    private List<LocationEntity> darkSkyLocation() {
        RegionEntity region = new RegionEntity();
        region.setName("Northumberland");
        return List.of(LocationEntity.builder()
                .name("Kielder").bortleClass(3).region(region).build());
    }
}
