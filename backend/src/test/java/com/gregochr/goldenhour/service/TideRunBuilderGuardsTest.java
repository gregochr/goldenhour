package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.repository.MarineWaveRepository;
import com.gregochr.goldenhour.repository.TideExtremeRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The input guards of {@link TideRunBuilder}: nothing is read from the database for a question that cannot be
 * answered (no dates, no roster, a roster without ids, no representative).
 */
class TideRunBuilderGuardsTest {

    private static final LocalDate DAY = LocalDate.of(2026, 1, 27);

    private TideExtremeRepository tideExtremeRepository;
    private TideRunBuilder builder;

    @BeforeEach
    void setUp() {
        tideExtremeRepository = mock(TideExtremeRepository.class);
        builder = new TideRunBuilder(tideExtremeRepository, mock(MarineWaveRepository.class),
                mock(TideService.class), mock(SolarService.class), "");
    }

    private static LocationEntity coastal(Long id) {
        return LocationEntity.builder().id(id).name("Whitby").lat(54.49).lon(-0.61).build();
    }

    @Test
    @DisplayName("a run cannot be built from no dates, no roster, or a roster without ids")
    void buildWithEventWaters_unanswerable_empty() {
        List<LocationEntity> roster = List.of(coastal(1L));

        assertThat(builder.buildWithEventWaters(null, roster, true).days()).isEmpty();
        assertThat(builder.buildWithEventWaters(List.of(), roster, true).days()).isEmpty();
        assertThat(builder.buildWithEventWaters(List.of(DAY), null, true).days()).isEmpty();
        assertThat(builder.buildWithEventWaters(List.of(DAY), List.of(), true).days()).isEmpty();
        assertThat(builder.buildWithEventWaters(List.of(DAY), List.of(coastal(null)), true).eventWaters()).isEmpty();
        verifyNoInteractions(tideExtremeRepository);
    }

    @Test
    @DisplayName("a run with no stored extremes for the roster has no days")
    void buildWithEventWaters_noStoredExtremes_empty() {
        assertThat(builder.build(List.of(DAY), List.of(coastal(1L)), false)).isEmpty();
    }

    @Test
    @DisplayName("the peak range is unknown without dates, a roster, stored extremes or a representative")
    void peakRange_unanswerable_empty() {
        List<LocationEntity> roster = List.of(coastal(1L));

        assertThat(builder.peakRange(null, roster)).isEmpty();
        assertThat(builder.peakRange(List.of(), roster)).isEmpty();
        assertThat(builder.peakRange(List.of(DAY), null)).isEmpty();
        assertThat(builder.peakRange(List.of(DAY), List.of())).isEmpty();
        assertThat(builder.peakRange(List.of(DAY), List.of(coastal(null)))).isEmpty();
        assertThat(builder.peakRange(List.of(DAY), roster)).isEmpty();
    }

    @Test
    @DisplayName("the peak range at a named location is unknown without that location or any dates")
    void peakRangeAt_unanswerable_empty() {
        assertThat(builder.peakRangeAt(null, List.of(DAY))).isEmpty();
        assertThat(builder.peakRangeAt(coastal(1L), null)).isEmpty();
        assertThat(builder.peakRangeAt(coastal(1L), List.of())).isEmpty();
        assertThat(builder.peakRangeAt(coastal(1L), List.of(DAY))).isEmpty();
    }
}
