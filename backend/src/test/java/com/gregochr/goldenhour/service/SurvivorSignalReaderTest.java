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
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SurvivorSignalReader} — the unified survivor read model.
 *
 * <p>Verifies that scores ({@code forecast_score}) and readings ({@code survivor_atmosphere}) are
 * folded by their shared key into one composite, kept in their own correctly-shaped sub-records
 * (never flattened), that single-surface keys yield the EMPTY sub-record on the absent side, and
 * that a nightly Gate 4 stability skip retracts an INVERSION/BLUEBELL component the same way it
 * retracts a {@code cached_evaluation}/{@code forecast_evaluation} rating (a Codex review of #940).
 */
@ExtendWith(MockitoExtension.class)
class SurvivorSignalReaderTest {

    private static final LocalDate FROM = LocalDate.of(2026, 6, 17);
    private static final LocalDate TO = FROM.plusDays(3);
    private static final TargetType SUNSET = TargetType.SUNSET;
    private static final String LOCATION_NAME = "Cat Bells";
    private static final Instant T0 = Instant.parse("2026-06-17T18:00:00Z");
    private static final Instant T1 = Instant.parse("2026-06-17T19:00:00Z");
    private static final Instant T2 = Instant.parse("2026-06-17T20:00:00Z");

    @Mock
    private ForecastScoreRepository forecastScoreRepository;
    @Mock
    private SurvivorAtmosphereRepository survivorAtmosphereRepository;
    @Mock
    private EvaluationViewService evaluationViewService;

    private SurvivorSignalReader reader() {
        return new SurvivorSignalReader(
                forecastScoreRepository, survivorAtmosphereRepository, evaluationViewService);
    }

    private static LocationEntity location(long id) {
        LocationEntity l = new LocationEntity();
        l.setId(id);
        l.setName(LOCATION_NAME);
        return l;
    }

    private static ForecastScoreEntity score(ForecastType type, LocationEntity loc, int value,
            String summary) {
        return score(type, loc, value, summary, T0);
    }

    private static ForecastScoreEntity score(ForecastType type, LocationEntity loc, int value,
            String summary, Instant evaluatedAt) {
        ForecastScoreEntity s = new ForecastScoreEntity();
        s.setForecastType(type);
        s.setLocation(loc);
        s.setEvaluationDate(FROM);
        s.setEventType(SUNSET);
        s.setScore(value);
        s.setSummary(summary);
        s.setEvaluatedAt(evaluatedAt);
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

    /** No stability skip recorded against any slot — the overwhelming majority of calls. */
    private void stubNoSkips() {
        when(evaluationViewService.loadStabilitySkips(FROM, TO)).thenReturn(Map.of());
    }

    /** A stability skip recorded at {@code skippedAt} against the one slot every fixture uses. */
    private void stubSkip(Instant skippedAt) {
        String key = EvaluationViewService.stabilitySkipKey(LOCATION_NAME, FROM, SUNSET);
        when(evaluationViewService.loadStabilitySkips(FROM, TO)).thenReturn(Map.of(key, skippedAt));
    }

    @Test
    @DisplayName("score and reading on the same key fold into ONE composite, both sub-records set")
    void sameKey_mergesIntoOneComposite() {
        stubNoSkips();
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
    @DisplayName("the INVERSION row's summary is exposed as the inversion band")
    void inversionRow_summaryBecomesBand() {
        stubNoSkips();
        // ForecastScoreWriter stores the NONE/MODERATE/STRONG classification in the summary
        // column; the inversion detector labels its fact line from it rather than assuming STRONG.
        LocationEntity loc = location(4L);
        stubInversion(List.of(score(ForecastType.INVERSION, loc, 10, "STRONG")));
        stubBluebell(List.of());
        stubReadings(List.of());

        SurvivorSignals s = reader().read(FROM, TO).get(0);

        assertThat(s.scores().inversion()).isEqualTo(10);
        assertThat(s.scores().inversionBand()).isEqualTo("STRONG");
    }

    @Test
    @DisplayName("an INVERSION row with no summary yields a null band, not an empty composite")
    void inversionRow_nullSummaryYieldsNullBand() {
        stubNoSkips();
        LocationEntity loc = location(5L);
        stubInversion(List.of(score(ForecastType.INVERSION, loc, 9, null)));
        stubBluebell(List.of());
        stubReadings(List.of());

        SurvivorSignals s = reader().read(FROM, TO).get(0);

        assertThat(s.scores().inversion()).isEqualTo(9);
        assertThat(s.scores().inversionBand()).isNull();
    }

    @Test
    @DisplayName("empty surfaces → empty result")
    void emptySurfaces_emptyResult() {
        stubNoSkips();
        stubInversion(List.of());
        stubBluebell(List.of());
        stubReadings(List.of());

        assertThat(reader().read(FROM, TO)).isEmpty();
    }

    @Test
    @DisplayName("readings-only key → EMPTY scores, populated readings")
    void readingsOnly_emptyScores() {
        stubNoSkips();
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
        stubNoSkips();
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
        stubNoSkips();
        LocationEntity loc = location(4L);
        stubInversion(List.of());
        stubBluebell(List.of(score(ForecastType.BLUEBELL, loc, 4, "Bright still light")));
        stubReadings(List.of());

        SurvivorSignals s = reader().read(FROM, TO).get(0);
        assertThat(s.scores().bluebell()).isEqualTo(4);
        assertThat(s.scores().bluebellSummary()).isEqualTo("Bright still light");
    }

    @Test
    @DisplayName("bluebell component written before a stability skip is retracted — dropped, not "
            + "just a null score on an otherwise-present composite (Codex review of #940)")
    void bluebellWrittenBeforeSkip_retracted() {
        LocationEntity loc = location(4L);
        stubInversion(List.of());
        stubBluebell(List.of(score(ForecastType.BLUEBELL, loc, 4, "Bright still light", T0)));
        stubReadings(List.of());
        stubSkip(T1);

        // The whole composite is gone, not merely its bluebell field — nothing else survives for
        // this slot, so there is nothing left to build a composite around.
        assertThat(reader().read(FROM, TO)).isEmpty();
    }

    @Test
    @DisplayName("bluebell component written AFTER the skip that stands against its slot is served")
    void bluebellWrittenAfterSkip_served() {
        LocationEntity loc = location(4L);
        stubInversion(List.of());
        stubBluebell(List.of(score(ForecastType.BLUEBELL, loc, 4, "Bright still light", T2)));
        stubReadings(List.of());
        stubSkip(T1);

        SurvivorSignals s = reader().read(FROM, TO).get(0);
        assertThat(s.scores().bluebell()).isEqualTo(4);
        assertThat(s.scores().bluebellSummary()).isEqualTo("Bright still light");
    }

    @Test
    @DisplayName("inversion component written before a stability skip is retracted the same way")
    void inversionWrittenBeforeSkip_retracted() {
        LocationEntity loc = location(5L);
        stubInversion(List.of(score(ForecastType.INVERSION, loc, 9, "STRONG", T0)));
        stubBluebell(List.of());
        stubReadings(List.of());
        stubSkip(T1);

        assertThat(reader().read(FROM, TO)).isEmpty();
    }

    @Test
    @DisplayName("a retracted score does not retract a live atmosphere reading on the same slot — "
            + "survivor_atmosphere is measured input, never a Claude opinion a skip can supersede")
    void retractedScore_readingsOnSameSlotSurvive() {
        LocationEntity loc = location(6L);
        stubInversion(List.of(score(ForecastType.INVERSION, loc, 9, "STRONG", T0)));
        stubBluebell(List.of());
        stubReadings(List.of(readings(loc, "60.00")));
        stubSkip(T1);

        SurvivorSignals s = reader().read(FROM, TO).get(0);
        assertThat(s.scores()).isSameAs(SurvivorSignals.Scores.EMPTY);
        assertThat(s.readings().dust()).isEqualByComparingTo("60.00");
    }

    @Test
    @DisplayName("withStabilityWindow shares ONE loadStabilitySkips call across several read() calls "
            + "for the identical window, instead of one per caller")
    void withStabilityWindow_sharesOneLoadAcrossSeveralReadCalls() {
        stubInversion(List.of());
        stubBluebell(List.of());
        stubReadings(List.of());
        stubSkip(T1);
        SurvivorSignalReader reader = reader();

        reader.withStabilityWindow(FROM, TO, () -> {
            reader.read(FROM, TO);
            reader.read(FROM, TO);
            reader.read(FROM, TO);
            return null;
        });

        org.mockito.Mockito.verify(evaluationViewService, org.mockito.Mockito.times(1))
                .loadStabilitySkips(FROM, TO);
    }

    @Test
    @DisplayName("a read() call made outside any window still loads its own skip map — unshared, "
            + "but correct — so a caller like ComingUpConditionsBuilder is unaffected")
    void readOutsideWindow_loadsOwnCopy() {
        LocationEntity loc = location(4L);
        stubInversion(List.of());
        stubBluebell(List.of(score(ForecastType.BLUEBELL, loc, 4, "Bright still light", T0)));
        stubReadings(List.of());
        stubSkip(T1);

        assertThat(reader().read(FROM, TO)).isEmpty();
        org.mockito.Mockito.verify(evaluationViewService, org.mockito.Mockito.times(1))
                .loadStabilitySkips(FROM, TO);
    }
}
