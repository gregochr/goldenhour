package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.RegionEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.model.BriefingEventSummary;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingSlot;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.model.HotTopicFact;
import com.gregochr.goldenhour.model.Verdict;
import com.gregochr.goldenhour.repository.LocationRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Branch tests for {@link SpringTideHotTopicStrategy}: a spring day whose sunrise and sunset have both passed is
 * dropped, and the coastal science the facts builder produces rides on the card.
 */
class SpringTideHotTopicStrategyBranchTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 4, 16);

    private BriefingService briefingService;
    private LocationRepository locationRepository;
    private SolarEventFreshness freshness;
    private CoastalTideFactsBuilder coastalTideFactsBuilder;
    private TideRunBuilder tideRunBuilder;
    private LunarPhaseService lunarPhaseService;
    private SpringTideHotTopicStrategy strategy;
    private LocationEntity craster;
    private BriefingDay springDay;

    @BeforeEach
    void setUp() {
        briefingService = mock(BriefingService.class);
        locationRepository = mock(LocationRepository.class);
        freshness = mock(SolarEventFreshness.class);
        coastalTideFactsBuilder = mock(CoastalTideFactsBuilder.class);
        tideRunBuilder = mock(TideRunBuilder.class);
        lunarPhaseService = mock(LunarPhaseService.class);
        strategy = new SpringTideHotTopicStrategy(briefingService, locationRepository, freshness,
                coastalTideFactsBuilder, tideRunBuilder, lunarPhaseService);

        RegionEntity region = new RegionEntity();
        region.setName("Northumberland");
        craster = LocationEntity.builder().id(1L).name("Craster").lat(55.47).lon(-1.59)
                .tideType(Set.of(TideType.HIGH)).region(region).enabled(true).build();
        BriefingSlot.TideInfo spring = new BriefingSlot.TideInfo("HIGH", true, null, null, false, true,
                LunarTideType.SPRING_TIDE, "Full Moon", false);
        BriefingSlot slot = new BriefingSlot("Craster", null, Verdict.GO, null, spring, List.of());
        BriefingRegion briefingRegion = new BriefingRegion("Northumberland", Verdict.GO, null, List.of(),
                List.of(slot), null, null, null, null, null, null);
        springDay = new BriefingDay(TODAY, List.of(
                new BriefingEventSummary(TargetType.SUNRISE, List.of(briefingRegion), List.of())));
        when(briefingService.getCachedDays()).thenReturn(List.of(springDay));
        when(lunarPhaseService.nearestSyzygyIsPerigean(TODAY)).thenReturn(false);
        when(locationRepository.findCoastalLocations()).thenReturn(List.of(craster));
    }

    @Test
    @DisplayName("a spring day whose sunrise and sunset have both passed gives no card")
    void detect_bothEventsPassed_noCard() {
        when(freshness.isAhead(craster, TODAY, TargetType.SUNRISE)).thenReturn(false);
        when(freshness.isAhead(craster, TODAY, TargetType.SUNSET)).thenReturn(false);
        when(tideRunBuilder.build(List.of(TODAY), List.of(craster), false)).thenReturn(Map.of());

        assertThat(strategy.detect(TODAY, TODAY)).isEmpty();
        verifyNoInteractions(coastalTideFactsBuilder);
    }

    @Test
    @DisplayName("a spring card carries the coastal science's facts and note")
    void detect_attachesCoastalScience() {
        HotTopicFact fact = HotTopicFact.metric("range", "5.2 m");
        when(freshness.isAhead(craster, TODAY, TargetType.SUNRISE)).thenReturn(true);
        when(freshness.isAhead(craster, TODAY, TargetType.SUNSET)).thenReturn(true);
        when(tideRunBuilder.build(List.of(TODAY), List.of(craster), false)).thenReturn(Map.of());
        when(coastalTideFactsBuilder.buildSpring(springDay, List.of(craster)))
                .thenReturn(new CoastalTideFactsBuilder.CoastalScience(List.of(fact), "look low"));

        List<HotTopic> topics = strategy.detect(TODAY, TODAY);

        assertThat(topics).hasSize(1);
        assertThat(topics.get(0).type()).isEqualTo("SPRING_TIDE");
        assertThat(topics.get(0).facts()).containsExactly(fact);
        assertThat(topics.get(0).note()).isEqualTo("look low");
    }
}
