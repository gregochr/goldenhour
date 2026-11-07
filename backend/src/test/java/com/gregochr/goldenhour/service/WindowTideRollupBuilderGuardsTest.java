package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.BriefingDay;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.MarineWaveRepository;
import com.gregochr.goldenhour.repository.TideExtremeRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The input guards of {@link WindowTideRollupBuilder#build}: a briefing with no dated days, a roster of coastal
 * locations without ids, and one with no stored extremes all give no rollup, and read nothing further.
 */
class WindowTideRollupBuilderGuardsTest {

    private LocationRepository locationRepository;
    private TideExtremeRepository tideExtremeRepository;
    private WindowTideRollupBuilder builder;

    @BeforeEach
    void setUp() {
        locationRepository = mock(LocationRepository.class);
        tideExtremeRepository = mock(TideExtremeRepository.class);
        builder = new WindowTideRollupBuilder(locationRepository, tideExtremeRepository,
                mock(MarineWaveRepository.class), mock(TideService.class), mock(SolarService.class),
                mock(TideFactDeriver.class), "");
    }

    @Test
    @DisplayName("a briefing whose days have no date gives no rollup and reads no locations")
    void build_undatedDays_empty() {
        assertThat(builder.build(List.of(new BriefingDay(null, List.of())))).isEmpty();
        verifyNoInteractions(locationRepository);
    }

    @Test
    @DisplayName("coastal locations without ids cannot be queried, so there is no rollup")
    void build_coastalWithoutIds_empty() {
        when(locationRepository.findCoastalLocations()).thenReturn(
                List.of(LocationEntity.builder().name("Unsaved").lat(54.5).lon(-0.6).build()));

        List<BriefingDay> days = List.of(new BriefingDay(LocalDate.of(2026, 1, 27), List.of()));

        assertThat(builder.build(days)).isEmpty();
        verifyNoInteractions(tideExtremeRepository);
    }
}
