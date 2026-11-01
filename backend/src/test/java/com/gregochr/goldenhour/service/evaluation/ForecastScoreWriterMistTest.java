package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.entity.ForecastScoreEntity;
import com.gregochr.goldenhour.entity.ForecastType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.MistPotential;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.repository.ForecastScoreRepository;
import com.gregochr.goldenhour.service.evaluation.visitor.ComponentScore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the {@code MIST} component of the Pass 2 dual-write (valley-mist-plan M2): Claude's band is
 * upserted only when recognised AND the location is flagged, with the band on the summary and the
 * band's midpoint as the integer score.
 */
@ExtendWith(MockitoExtension.class)
class ForecastScoreWriterMistTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 22);
    private static final TargetType SUNRISE = TargetType.SUNRISE;
    private static final long LOCATION_ID = 11L;

    @Mock
    private ForecastScoreRepository repository;

    private ForecastScoreWriter writer() {
        return new ForecastScoreWriter(repository,
                Clock.fixed(Instant.parse("2026-10-22T05:00:00Z"), ZoneOffset.UTC), true);
    }

    private static LocationEntity location(boolean mistProne) {
        LocationEntity location = new LocationEntity();
        location.setId(LOCATION_ID);
        location.setName("River Brathay");
        location.setMistProne(mistProne);
        return location;
    }

    private static SunsetEvaluation eval(String mistPotential) {
        return new SunsetEvaluation(4, 20, 70, "Thin mist over the river.", null, null,
                null, mistPotential);
    }

    private void stubAllAbsent() {
        when(repository.findComponent(any(), eq(LOCATION_ID), eq(DATE), eq(SUNRISE)))
                .thenReturn(Optional.empty());
    }

    private List<ForecastScoreEntity> write(LocationEntity location, SunsetEvaluation eval) {
        writer().write(location, DATE, SUNRISE, eval,
                List.of(new ComponentScore(ForecastType.SKY, 4, "Mist and a low sun")), 900L);
        ArgumentCaptor<ForecastScoreEntity> captor = ArgumentCaptor.forClass(ForecastScoreEntity.class);
        verify(repository, atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    private static Optional<ForecastScoreEntity> mistRow(List<ForecastScoreEntity> saved) {
        return saved.stream().filter(r -> r.getForecastType() == ForecastType.MIST).findFirst();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(MistPotential.class)
    @DisplayName("a recognised band on a flagged site writes a MIST row (band on the summary, midpoint as score)")
    void flaggedSite_writesMistRow(MistPotential band) {
        stubAllAbsent();

        ForecastScoreEntity row = mistRow(write(location(true), eval(band.name()))).orElseThrow();

        assertThat(row.getSummary()).isEqualTo(band.name());
        assertThat(row.getScore()).isEqualTo(band.echoScore());
        assertThat(row.getPipelineRunId()).isEqualTo(900L);
    }

    @Test
    @DisplayName("the band midpoints are inside the calculator's 0-10 scale and ordered")
    void echoScores_areOrderedAndInScale() {
        assertThat(MistPotential.NONE.echoScore()).isLessThan(MistPotential.POSSIBLE.echoScore());
        assertThat(MistPotential.POSSIBLE.echoScore()).isLessThan(MistPotential.LIKELY.echoScore());
        assertThat(MistPotential.LIKELY.echoScore()).isLessThan(MistPotential.FOGBOUND.echoScore());
        assertThat(MistPotential.FOGBOUND.echoScore()).isEqualTo(ForecastType.MIST.getScaleMax());
        // LIKELY sits above the calculator's LIKELY floor (6), POSSIBLE inside [4, 6), NONE below 4.
        assertThat(MistPotential.LIKELY.echoScore()).isGreaterThanOrEqualTo(6);
        assertThat(MistPotential.POSSIBLE.echoScore()).isBetween(4, 5);
        assertThat(MistPotential.NONE.echoScore()).isLessThan(4);
    }

    @Test
    @DisplayName("no band from Claude writes no MIST row")
    void noBand_writesNoMistRow() {
        stubAllAbsent();

        assertThat(mistRow(write(location(true), eval(null)))).isEmpty();
    }

    @Test
    @DisplayName("an unrecognised stored name writes no MIST row")
    void unrecognisedBand_writesNoMistRow() {
        stubAllAbsent();

        assertThat(mistRow(write(location(true), eval("Moderate")))).isEmpty();
    }

    @Test
    @DisplayName("a stray band on an unflagged site writes no MIST row")
    void unflaggedSite_writesNoMistRow() {
        stubAllAbsent();

        assertThat(mistRow(write(location(false), eval("LIKELY")))).isEmpty();
    }
}
