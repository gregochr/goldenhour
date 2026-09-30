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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SurvivorSignalReader} — the unified survivor read model.
 *
 * <p>Verifies that scores ({@code forecast_score}) and readings ({@code survivor_atmosphere}) are
 * folded by their shared key into one composite, kept in their own correctly-shaped sub-records
 * (never flattened), that single-surface keys yield the EMPTY sub-record on the absent side, and —
 * the owner's two-question rule (2026-09-29, see the class's own javadoc) — that {@link #read}
 * applies NO retraction of any kind: an INVERSION or BLUEBELL component is returned exactly as
 * stored, however long ago it was evaluated relative to anything else the pipeline has since
 * decided. This reverses part of #940 (commit c6e14cc8), which briefly made this class drop a
 * component older than its slot's latest nightly stability skip; those tests are replaced below
 * with their literal opposite rather than deleted outright, so a future regression that
 * reintroduces filtering here is caught immediately.
 */
@ExtendWith(MockitoExtension.class)
class SurvivorSignalReaderTest {

    private static final LocalDate FROM = LocalDate.of(2026, 6, 17);
    private static final LocalDate TO = FROM.plusDays(3);
    private static final TargetType SUNSET = TargetType.SUNSET;
    private static final String LOCATION_NAME = "Cat Bells";
    /** An arbitrarily old evaluation instant — under the reverted #940 extension this would have
     * been retracted by almost any stability skip recorded after it. There is no such lookup any
     * more, so every test below expects it to have no bearing on the outcome. */
    private static final Instant ANCIENT = Instant.parse("2020-01-01T00:00:00Z");

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
        l.setName(LOCATION_NAME);
        return l;
    }

    private static ForecastScoreEntity score(ForecastType type, LocationEntity loc, int value,
            String summary) {
        return score(type, loc, value, summary, Instant.parse("2026-06-17T18:00:00Z"));
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

    private static SurvivorAtmosphereEntity readingsWithInversion(LocationEntity loc,
            double inversionScore) {
        SurvivorAtmosphereEntity a = new SurvivorAtmosphereEntity();
        a.setLocation(loc);
        a.setEvaluationDate(FROM);
        a.setEventType(SUNSET);
        a.setInversionScore(inversionScore);
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
    @DisplayName("the INVERSION row's summary is exposed as the inversion band")
    void inversionRow_summaryBecomesBand() {
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
    @DisplayName("readings().inversionScore() surfaces the calculator's own score from "
            + "survivor_atmosphere — the Phase 2 read path, independent of Scores.inversion()")
    void readings_surfacesInversionScore() {
        LocationEntity loc = location(9L);
        stubInversion(List.of());
        stubBluebell(List.of());
        stubReadings(List.of(readingsWithInversion(loc, 9.0)));

        SurvivorSignals s = reader().read(FROM, TO).get(0);
        assertThat(s.readings().inversionScore()).isEqualTo(9.0);
    }

    @Test
    @DisplayName("EMPTY readings has a null inversionScore")
    void emptyReadings_inversionScoreIsNull() {
        assertThat(SurvivorSignals.Readings.EMPTY.inversionScore()).isNull();
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

    // ── Owner decision (2026-09-29): no retraction of any kind on this path ─────────────────

    @Test
    @DisplayName("a bluebell component evaluated long ago is still returned — hot topics answer "
            + "'what is happening', not 'is it worth going', so no stability skip or triage "
            + "decision recorded anywhere against this slot's RATING has any bearing here "
            + "(replaces the retraction test #940/c6e14cc8 added, which this decision reverses)")
    void bluebellComponent_returnedRegardlessOfHowStaleItsEvaluationIs() {
        LocationEntity loc = location(4L);
        stubInversion(List.of());
        stubBluebell(List.of(score(ForecastType.BLUEBELL, loc, 4, "Bright still light", ANCIENT)));
        stubReadings(List.of());

        SurvivorSignals s = reader().read(FROM, TO).get(0);
        assertThat(s.scores().bluebell()).isEqualTo(4);
        assertThat(s.scores().bluebellSummary()).isEqualTo("Bright still light");
    }

    @Test
    @DisplayName("an inversion component evaluated long ago is still returned the same way — this "
            + "class has no dependency on a disposition or stability-skip table at all any more, so "
            + "there is nothing a later SKIPPED_TRIAGED or SKIPPED_STABILITY disposition could "
            + "retract even if one existed for this slot")
    void inversionComponent_returnedRegardlessOfHowStaleItsEvaluationIs() {
        LocationEntity loc = location(5L);
        stubInversion(List.of(score(ForecastType.INVERSION, loc, 9, "STRONG", ANCIENT)));
        stubBluebell(List.of());
        stubReadings(List.of());

        SurvivorSignals s = reader().read(FROM, TO).get(0);
        assertThat(s.scores().inversion()).isEqualTo(9);
        assertThat(s.scores().inversionBand()).isEqualTo("STRONG");
    }

    @Test
    @DisplayName("survivor_atmosphere readings are returned alongside a component evaluated long "
            + "ago, exactly as they always have been — readings were never subject to any "
            + "retraction, on this path or the brief window that once applied one to scores")
    void readings_returnedAlongsideAnciently_evaluatedComponent() {
        LocationEntity loc = location(6L);
        stubInversion(List.of(score(ForecastType.INVERSION, loc, 9, "STRONG", ANCIENT)));
        stubBluebell(List.of());
        stubReadings(List.of(readings(loc, "60.00")));

        SurvivorSignals s = reader().read(FROM, TO).get(0);
        assertThat(s.scores().inversion()).isEqualTo(9);
        assertThat(s.readings().dust()).isEqualByComparingTo("60.00");
    }
}
