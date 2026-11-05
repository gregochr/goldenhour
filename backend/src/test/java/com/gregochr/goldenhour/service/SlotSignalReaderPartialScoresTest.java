package com.gregochr.goldenhour.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.gregochr.goldenhour.entity.ForecastScoreEntity;
import com.gregochr.goldenhour.entity.ForecastType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.SlotSignals;
import com.gregochr.goldenhour.repository.ForecastScoreRepository;
import com.gregochr.goldenhour.repository.SlotAtmosphereRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * A component row that carries only its text (a null numeric score) still counts as a score on its slot, so the
 * slot is not collapsed to {@link SlotSignals.Scores#EMPTY}.
 */
@ExtendWith(MockitoExtension.class)
class SlotSignalReaderPartialScoresTest {

    private static final LocalDate FROM = LocalDate.of(2026, 6, 17);
    private static final LocalDate TO = FROM.plusDays(3);

    @Mock
    private ForecastScoreRepository forecastScoreRepository;
    @Mock
    private SlotAtmosphereRepository slotAtmosphereRepository;

    private static LocationEntity location(long id) {
        LocationEntity l = new LocationEntity();
        l.setId(id);
        l.setName("Loc " + id);
        return l;
    }

    private static ForecastScoreEntity row(ForecastType type, LocationEntity loc, Integer score, String summary) {
        ForecastScoreEntity s = new ForecastScoreEntity();
        s.setForecastType(type);
        s.setLocation(loc);
        s.setEvaluationDate(FROM);
        s.setEventType(TargetType.SUNRISE);
        s.setScore(score);
        s.setSummary(summary);
        return s;
    }

    private SlotSignals only(List<SlotSignals> signals, LocationEntity loc) {
        return signals.stream().filter(s -> s.location() == loc).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("text-only inversion, bluebell and mist rows each keep their text; a slot with none stays empty")
    void read_textOnlyRows_keepTheirText() {
        LocationEntity inversion = location(1L);
        LocationEntity bluebell = location(2L);
        LocationEntity mist = location(3L);
        LocationEntity none = location(4L);
        when(forecastScoreRepository.findComponentsByType(ForecastType.INVERSION.getId(), FROM, TO))
                .thenReturn(List.of(row(ForecastType.INVERSION, inversion, null, "MODERATE")));
        when(forecastScoreRepository.findComponentsByType(ForecastType.BLUEBELL.getId(), FROM, TO))
                .thenReturn(List.of(row(ForecastType.BLUEBELL, bluebell, null, "Carpet in bloom")));
        when(forecastScoreRepository.findComponentsByType(ForecastType.MIST.getId(), FROM, TO))
                .thenReturn(List.of(row(ForecastType.MIST, mist, null, "DENSE"),
                        row(ForecastType.MIST, none, null, null)));
        when(slotAtmosphereRepository.findInDateRange(FROM, TO)).thenReturn(List.of());

        List<SlotSignals> signals = new SlotSignalReader(forecastScoreRepository, slotAtmosphereRepository)
                .read(FROM, TO);

        assertThat(signals).hasSize(4);
        SlotSignals.Scores inversionScores = only(signals, inversion).scores();
        assertThat(inversionScores.inversion()).isNull();
        assertThat(inversionScores.inversionBand()).isEqualTo("MODERATE");
        SlotSignals.Scores bluebellScores = only(signals, bluebell).scores();
        assertThat(bluebellScores.bluebell()).isNull();
        assertThat(bluebellScores.bluebellSummary()).isEqualTo("Carpet in bloom");
        assertThat(only(signals, mist).scores().mistBand()).isEqualTo("DENSE");
        assertThat(only(signals, none).scores()).isSameAs(SlotSignals.Scores.EMPTY);
    }
}
