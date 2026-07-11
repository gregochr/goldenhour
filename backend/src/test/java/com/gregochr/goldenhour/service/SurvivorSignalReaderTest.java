package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.ForecastScoreEntity;
import com.gregochr.goldenhour.entity.ForecastType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.SurvivorAtmosphereEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.SurvivorSignals;
import com.gregochr.goldenhour.repository.ForecastScoreRepository;
import com.gregochr.goldenhour.repository.SurvivorAtmosphereRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SurvivorSignalReader} — the unified survivor read model.
 *
 * <p>Verifies that scores ({@code forecast_score}) and readings ({@code survivor_atmosphere}) are
 * folded by their shared key into one composite, kept in their own correctly-shaped sub-records
 * (never flattened), and that single-surface keys yield the EMPTY sub-record on the absent side.
 */
@ExtendWith(MockitoExtension.class)
class SurvivorSignalReaderTest {

    private static final LocalDate FROM = LocalDate.of(2026, 6, 17);
    private static final LocalDate TO = FROM.plusDays(3);
    private static final TargetType SUNSET = TargetType.SUNSET;

    @Mock
    private ForecastScoreRepository forecastScoreRepository;
    @Mock
    private SurvivorAtmosphereRepository survivorAtmosphereRepository;

    private SurvivorSignalReader reader() {
        return new SurvivorSignalReader(forecastScoreRepository, survivorAtmosphereRepository);
    }

    private static LocationEntity location(long id) {
        LocationEntity l = new LocationEntity();
        l.setId(id);
        l.setName("Cat Bells");
        return l;
    }

    private static ForecastScoreEntity score(ForecastType type, LocationEntity loc, int value,
            String summary) {
        ForecastScoreEntity s = new ForecastScoreEntity();
        s.setForecastType(type);
        s.setLocation(loc);
        s.setEvaluationDate(FROM);
        s.setEventType(SUNSET);
        s.setScore(value);
        s.setSummary(summary);
        return s;
    }

    private static SurvivorAtmosphereEntity readings(LocationEntity loc, String dust) {
        SurvivorAtmosphereEntity a = new SurvivorAtmosphereEntity();
        a.setLocation(loc);
        a.setEvaluationDate(FROM);
        a.setEventType(SUNSET);
        a.setDust(new BigDecimal(dust));
        a.setTemperatureCelsius(-1.5);
        return a;
    }

    private void stubInversion(List<ForecastScoreEntity> rows) {
        when(forecastScoreRepository.findComponentsByType(
                ForecastType.INVERSION.getId(), FROM, TO)).thenReturn(rows);
    }

    private void stubBluebell(List<ForecastScoreEntity> rows) {
        when(forecastScoreRepository.findComponentsByType(
                ForecastType.BLUEBELL.getId(), FROM, TO)).thenReturn(rows);
    }

    private void stubReadings(List<SurvivorAtmosphereEntity> rows) {
        when(survivorAtmosphereRepository.findInDateRange(FROM, TO)).thenReturn(rows);
    }

    @Test
    @DisplayName("score and reading on the same key fold into ONE composite, both sub-records set")
    void sameKey_mergesIntoOneComposite() {
        LocationEntity loc = location(1L);
        stubInversion(List.of(score(ForecastType.INVERSION, loc, 9, "STRONG")));
        stubBluebell(List.of());
        stubReadings(List.of(readings(loc, "60.00")));

        List<SurvivorSignals> result = reader().read(FROM, TO);

        assertThat(result).hasSize(1);
        SurvivorSignals s = result.get(0);
        assertThat(s.location().getId()).isEqualTo(1L);
        assertThat(s.date()).isEqualTo(FROM);
        assertThat(s.eventType()).isEqualTo(SUNSET);
        assertThat(s.scores().inversion()).isEqualTo(9);
        assertThat(s.readings().dust()).isEqualByComparingTo("60.00");
        assertThat(s.readings().temperatureCelsius()).isEqualTo(-1.5);
    }

    @Test
    @DisplayName("empty surfaces → empty result")
    void emptySurfaces_emptyResult() {
        stubInversion(List.of());
        stubBluebell(List.of());
        stubReadings(List.of());

        assertThat(reader().read(FROM, TO)).isEmpty();
    }

    @Test
    @DisplayName("readings-only key → EMPTY scores, populated readings")
    void readingsOnly_emptyScores() {
        LocationEntity loc = location(2L);
        stubInversion(List.of());
        stubBluebell(List.of());
        stubReadings(List.of(readings(loc, "55.00")));

        SurvivorSignals s = reader().read(FROM, TO).get(0);
        assertThat(s.scores()).isSameAs(SurvivorSignals.Scores.EMPTY);
        assertThat(s.readings().dust()).isEqualByComparingTo("55.00");
    }

    @Test
    @DisplayName("scores-only key → populated scores, EMPTY readings")
    void scoresOnly_emptyReadings() {
        LocationEntity loc = location(3L);
        stubInversion(List.of(score(ForecastType.INVERSION, loc, 7, "MODERATE")));
        stubBluebell(List.of());
        stubReadings(List.of());

        SurvivorSignals s = reader().read(FROM, TO).get(0);
        assertThat(s.scores().inversion()).isEqualTo(7);
        assertThat(s.readings()).isSameAs(SurvivorSignals.Readings.EMPTY);
    }

    @Test
    @DisplayName("bluebell score carries score and summary into the composite")
    void bluebell_carriesScoreAndSummary() {
        LocationEntity loc = location(4L);
        stubInversion(List.of());
        stubBluebell(List.of(score(ForecastType.BLUEBELL, loc, 4, "Bright still light")));
        stubReadings(List.of());

        SurvivorSignals s = reader().read(FROM, TO).get(0);
        assertThat(s.scores().bluebell()).isEqualTo(4);
        assertThat(s.scores().bluebellSummary()).isEqualTo("Bright still light");
    }
}
