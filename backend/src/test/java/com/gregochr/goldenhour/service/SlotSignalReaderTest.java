package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.entity.ForecastScoreEntity;
import com.gregochr.goldenhour.entity.ForecastType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.SlotAtmosphereEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.CoastalMistBand;
import com.gregochr.goldenhour.model.MistPotential;
import com.gregochr.goldenhour.model.SlotSignals;
import com.gregochr.goldenhour.repository.ForecastScoreRepository;
import com.gregochr.goldenhour.repository.SlotAtmosphereRepository;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SlotSignalReader} — the unified slot read model.
 *
 * <p>Verifies that scores ({@code forecast_score}) and readings ({@code slot_atmosphere}) are
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
class SlotSignalReaderTest {

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
    private SlotAtmosphereRepository slotAtmosphereRepository;

    /**
     * The reader also asks for the MIST echo rows (V186); a test that does not care leaves them
     * empty, and strict stubs would otherwise reject the unstubbed MIST call as an argument mismatch.
     */
    @BeforeEach
    void noMistEchoByDefault() {
        lenient().when(forecastScoreRepository.findComponentsByType(
                eq(ForecastType.MIST.getId()), any(), any())).thenReturn(List.of());
    }

    private SlotSignalReader reader() {
        return new SlotSignalReader(forecastScoreRepository, slotAtmosphereRepository);
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

    private static SlotAtmosphereEntity readings(LocationEntity loc, String dust) {
        SlotAtmosphereEntity a = new SlotAtmosphereEntity();
        a.setLocation(loc);
        a.setEvaluationDate(FROM);
        a.setEventType(SUNSET);
        a.setDust(new BigDecimal(dust));
        a.setTemperatureCelsius(-1.5);
        return a;
    }

    private static SlotAtmosphereEntity readingsWithInversion(LocationEntity loc,
            double inversionScore) {
        SlotAtmosphereEntity a = new SlotAtmosphereEntity();
        a.setLocation(loc);
        a.setEvaluationDate(FROM);
        a.setEventType(SUNSET);
        a.setInversionScore(inversionScore);
        a.setInversionScored(true);
        return a;
    }

    /** A FRESH row (V158 round 4: {@code inversion_scored = true}) whose calculator legitimately
     * found nothing to report — the reading itself stays null. */
    private static SlotAtmosphereEntity readingsWithScoredNullInversion(LocationEntity loc) {
        SlotAtmosphereEntity a = new SlotAtmosphereEntity();
        a.setLocation(loc);
        a.setEvaluationDate(FROM);
        a.setEventType(SUNSET);
        a.setInversionScored(true);
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

    private void stubReadings(List<SlotAtmosphereEntity> rows) {
        when(slotAtmosphereRepository.findInDateRange(FROM, TO)).thenReturn(rows);
    }

    @Test
    @DisplayName("score and reading on the same key fold into ONE composite, both sub-records set")
    void sameKey_mergesIntoOneComposite() {
        LocationEntity loc = location(1L);
        stubInversion(List.of(score(ForecastType.INVERSION, loc, 9, "STRONG")));
        stubBluebell(List.of());
        stubReadings(List.of(readings(loc, "60.00")));

        List<SlotSignals> result = reader().read(FROM, TO);

        assertThat(result).hasSize(1);
        SlotSignals s = result.get(0);
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

        SlotSignals s = reader().read(FROM, TO).get(0);

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

        SlotSignals s = reader().read(FROM, TO).get(0);

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

        SlotSignals s = reader().read(FROM, TO).get(0);
        assertThat(s.scores()).isSameAs(SlotSignals.Scores.EMPTY);
        assertThat(s.readings().dust()).isEqualByComparingTo("55.00");
    }

    @Test
    @DisplayName("readings().inversionScore() surfaces the calculator's own score from "
            + "slot_atmosphere — the Phase 2 read path, independent of Scores.inversion()")
    void readings_surfacesInversionScore() {
        LocationEntity loc = location(9L);
        stubInversion(List.of());
        stubBluebell(List.of());
        stubReadings(List.of(readingsWithInversion(loc, 9.0)));

        SlotSignals s = reader().read(FROM, TO).get(0);
        assertThat(s.readings().inversionScore()).isEqualTo(9.0);
    }

    @Test
    @DisplayName("V185: the reader maps mistScore and mistScored off the entity, carried together")
    void readings_mapsMistScoreAndFlag() {
        LocationEntity loc = location(20L);
        SlotAtmosphereEntity a = new SlotAtmosphereEntity();
        a.setLocation(loc);
        a.setEvaluationDate(FROM);
        a.setEventType(SUNSET);
        a.setMistScore(6.5);
        a.setMistScored(true);
        stubInversion(List.of());
        stubBluebell(List.of());
        stubReadings(List.of(a));

        SlotSignals s = reader().read(FROM, TO).get(0);

        assertThat(s.readings().mistScore()).isEqualTo(6.5);
        assertThat(s.readings().mistScored()).isTrue();
    }

    @Test
    @DisplayName("V185: a fresh authoritative null (mistScored true, no score) and a pre-column row "
            + "(mistScored false) stay distinguishable through the reader")
    void readings_freshNullAndPreColumnRowAreDistinct() {
        LocationEntity fresh = location(21L);
        SlotAtmosphereEntity freshRow = new SlotAtmosphereEntity();
        freshRow.setLocation(fresh);
        freshRow.setEvaluationDate(FROM);
        freshRow.setEventType(SUNSET);
        freshRow.setMistScored(true);
        LocationEntity old = location(22L);
        SlotAtmosphereEntity oldRow = readings(old, "1.00");
        stubInversion(List.of());
        stubBluebell(List.of());
        stubReadings(List.of(freshRow, oldRow));

        List<SlotSignals> result = reader().read(FROM, TO);

        SlotSignals freshSignals = result.stream()
                .filter(x -> x.location().getId() == 21L).findFirst().orElseThrow();
        SlotSignals oldSignals = result.stream()
                .filter(x -> x.location().getId() == 22L).findFirst().orElseThrow();
        assertThat(freshSignals.readings().mistScore()).isNull();
        assertThat(freshSignals.readings().mistScored()).isTrue();
        assertThat(oldSignals.readings().mistScore()).isNull();
        assertThat(oldSignals.readings().mistScored()).isFalse();
    }

    @Test
    @DisplayName("V185: the reader maps the stored band name onto Readings.mistPotential")
    void readings_mapsMistPotential() {
        LocationEntity loc = location(23L);
        SlotAtmosphereEntity a = new SlotAtmosphereEntity();
        a.setLocation(loc);
        a.setEvaluationDate(FROM);
        a.setEventType(SUNSET);
        a.setMistScore(8.0);
        a.setMistScored(true);
        a.setMistPotential("FOGBOUND");
        stubInversion(List.of());
        stubBluebell(List.of());
        stubReadings(List.of(a));

        SlotSignals s = reader().read(FROM, TO).get(0);

        assertThat(s.readings().mistPotential()).isEqualTo(MistPotential.FOGBOUND);
    }

    @Test
    @DisplayName("V210: the reader maps the coastal mist band and flag off the entity, carried together")
    void readings_mapsCoastalMistBandAndFlag() {
        LocationEntity loc = location(25L);
        SlotAtmosphereEntity a = new SlotAtmosphereEntity();
        a.setLocation(loc);
        a.setEvaluationDate(FROM);
        a.setEventType(SUNSET);
        a.setCoastalMistBand("LIGHT");
        a.setCoastalMistScored(true);
        stubInversion(List.of());
        stubBluebell(List.of());
        stubReadings(List.of(a));

        SlotSignals s = reader().read(FROM, TO).get(0);

        assertThat(s.readings().coastalMistBand()).isEqualTo(CoastalMistBand.LIGHT);
        assertThat(s.readings().coastalMistScored()).isTrue();
    }

    @Test
    @DisplayName("V211: the reader maps the coastal low cloud and precipitation readings; a pre-column row is null")
    void readings_mapsCoastalLowCloudAndPrecipitation() {
        SlotAtmosphereEntity a = new SlotAtmosphereEntity();
        a.setLocation(location(26L));
        a.setEvaluationDate(FROM);
        a.setEventType(SUNSET);
        a.setCoastalMistLowCloudPct(35);
        a.setCoastalMistPrecipMm(0.2);
        SlotAtmosphereEntity old = new SlotAtmosphereEntity();
        old.setLocation(location(27L));
        old.setEvaluationDate(FROM);
        old.setEventType(SUNSET);
        stubInversion(List.of());
        stubBluebell(List.of());
        stubReadings(List.of(a, old));

        List<SlotSignals> signals = reader().read(FROM, TO);

        SlotSignals mapped = signals.stream().filter(s -> s.location().getId() == 26L).findFirst().orElseThrow();
        SlotSignals pre = signals.stream().filter(s -> s.location().getId() == 27L).findFirst().orElseThrow();
        assertThat(mapped.readings().coastalMistLowCloudPct()).isEqualTo(35);
        assertThat(mapped.readings().coastalMistPrecipMm()).isEqualTo(0.2);
        assertThat(pre.readings().coastalMistLowCloudPct()).isNull();
        assertThat(pre.readings().coastalMistPrecipMm()).isNull();
        assertThat(SlotSignals.Readings.EMPTY.coastalMistLowCloudPct()).isNull();
        assertThat(SlotSignals.Readings.EMPTY.coastalMistPrecipMm()).isNull();
    }

    @Test
    @DisplayName("V210: a pre-column row and an unknown stored name both read as no band, never throwing")
    void readings_coastalMistAbsentAndUnknownNameReadAsNoBand() {
        LocationEntity unknown = location(26L);
        SlotAtmosphereEntity unknownRow = new SlotAtmosphereEntity();
        unknownRow.setLocation(unknown);
        unknownRow.setEvaluationDate(FROM);
        unknownRow.setEventType(SUNSET);
        unknownRow.setCoastalMistBand("HEAVY");
        unknownRow.setCoastalMistScored(true);
        LocationEntity old = location(27L);
        SlotAtmosphereEntity oldRow = readings(old, "1.00");
        stubInversion(List.of());
        stubBluebell(List.of());
        stubReadings(List.of(unknownRow, oldRow));

        List<SlotSignals> result = reader().read(FROM, TO);

        SlotSignals unknownSignals = result.stream()
                .filter(x -> x.location().getId() == 26L).findFirst().orElseThrow();
        SlotSignals oldSignals = result.stream()
                .filter(x -> x.location().getId() == 27L).findFirst().orElseThrow();
        assertThat(unknownSignals.readings().coastalMistBand()).isNull();
        assertThat(oldSignals.readings().coastalMistBand()).isNull();
        assertThat(oldSignals.readings().coastalMistScored()).isFalse();
        assertThat(SlotSignals.Readings.EMPTY.coastalMistBand()).isNull();
        assertThat(SlotSignals.Readings.EMPTY.coastalMistScored()).isFalse();
    }

    @Test
    @DisplayName("V186: a forecast_score MIST row's summary is read as Claude's mist band beside the readings")
    void reader_foldsTheMistEchoOntoTheSlotKey() {
        LocationEntity loc = location(24L);
        stubInversion(List.of());
        stubBluebell(List.of());
        when(forecastScoreRepository.findComponentsByType(ForecastType.MIST.getId(), FROM, TO))
                .thenReturn(List.of(score(ForecastType.MIST, loc, 8, "LIKELY")));
        SlotAtmosphereEntity a = new SlotAtmosphereEntity();
        a.setLocation(loc);
        a.setEvaluationDate(FROM);
        a.setEventType(SUNSET);
        a.setMistScore(3.0);
        a.setMistScored(true);
        a.setMistPotential("NONE");
        stubReadings(List.of(a));

        SlotSignals s = reader().read(FROM, TO).get(0);

        assertThat(s.scores().mistBand()).isEqualTo("LIKELY");
        assertThat(s.readings().mistPotential()).isEqualTo(MistPotential.NONE);
        assertThat(s.effectiveMistPotential())
                .as("the calculator's fresh band wins over the echo")
                .isEqualTo(MistPotential.NONE);
    }

    @Test
    @DisplayName("V186: a key with only a MIST echo (no reading) still produces a composite carrying it")
    void reader_mistEchoAloneMakesAComposite() {
        LocationEntity loc = location(25L);
        stubInversion(List.of());
        stubBluebell(List.of());
        when(forecastScoreRepository.findComponentsByType(ForecastType.MIST.getId(), FROM, TO))
                .thenReturn(List.of(score(ForecastType.MIST, loc, 8, "LIKELY")));
        stubReadings(List.of());

        SlotSignals s = reader().read(FROM, TO).get(0);

        assertThat(s.readings()).isSameAs(SlotSignals.Readings.EMPTY);
        assertThat(s.effectiveMistPotential()).isEqualTo(MistPotential.LIKELY);
    }

    @Test
    @DisplayName("V185: the stored band name parses null-safely: null, every known name, and garbage")
    void parsePotential_isNullSafe() {
        assertThat(SlotSignals.Readings.parsePotential(null)).isNull();
        assertThat(SlotSignals.Readings.parsePotential("")).isNull();
        assertThat(SlotSignals.Readings.parsePotential("not-a-band")).isNull();
        assertThat(SlotSignals.Readings.parsePotential("likely")).isNull();
        for (MistPotential p : MistPotential.values()) {
            assertThat(SlotSignals.Readings.parsePotential(p.name())).isEqualTo(p);
        }
    }

    @Test
    @DisplayName("V185: an unknown stored band name reads as null through the reader, never throws")
    void readings_unknownMistPotentialReadsNull() {
        LocationEntity loc = location(24L);
        SlotAtmosphereEntity a = new SlotAtmosphereEntity();
        a.setLocation(loc);
        a.setEvaluationDate(FROM);
        a.setEventType(SUNSET);
        a.setMistScore(8.0);
        a.setMistScored(true);
        a.setMistPotential("GARBAGE");
        stubInversion(List.of());
        stubBluebell(List.of());
        stubReadings(List.of(a));

        SlotSignals s = reader().read(FROM, TO).get(0);

        assertThat(s.readings().mistScore()).isEqualTo(8.0);
        assertThat(s.readings().mistPotential()).isNull();
    }

    @Test
    @DisplayName("EMPTY readings carries no mist reading and is not scored")
    void emptyReadings_mistIsAbsent() {
        assertThat(SlotSignals.Readings.EMPTY.mistScore()).isNull();
        assertThat(SlotSignals.Readings.EMPTY.mistScored()).isFalse();
        assertThat(SlotSignals.Readings.EMPTY.mistPotential()).isNull();
    }

    @Test
    @DisplayName("EMPTY readings has a null inversionScore")
    void emptyReadings_inversionScoreIsNull() {
        assertThat(SlotSignals.Readings.EMPTY.inversionScore()).isNull();
    }

    @Test
    @DisplayName("V158 round 4: the reader maps inversionScored=true off the entity's own flag, "
            + "carried alongside a real reading")
    void readings_mapsInversionScoredTrue_withReading() {
        LocationEntity loc = location(10L);
        stubInversion(List.of());
        stubBluebell(List.of());
        stubReadings(List.of(readingsWithInversion(loc, 9.0)));

        SlotSignals s = reader().read(FROM, TO).get(0);
        assertThat(s.readings().inversionScore()).isEqualTo(9.0);
        assertThat(s.readings().inversionScored()).isTrue();
    }

    @Test
    @DisplayName("V158 round 4: the reader also maps inversionScored=true when the reading itself "
            + "is null — a fresh, authoritative null, not an absent row")
    void readings_mapsInversionScoredTrue_withNullReading() {
        LocationEntity loc = location(11L);
        stubInversion(List.of());
        stubBluebell(List.of());
        stubReadings(List.of(readingsWithScoredNullInversion(loc)));

        SlotSignals s = reader().read(FROM, TO).get(0);
        assertThat(s.readings().inversionScore()).isNull();
        assertThat(s.readings().inversionScored()).isTrue();
    }

    @Test
    @DisplayName("V158 round 4: EMPTY readings defaults inversionScored to false")
    void emptyReadings_inversionScoredIsFalse() {
        assertThat(SlotSignals.Readings.EMPTY.inversionScored()).isFalse();
    }

    @Test
    @DisplayName("scores-only key → populated scores, EMPTY readings")
    void scoresOnly_emptyReadings() {
        LocationEntity loc = location(3L);
        stubInversion(List.of(score(ForecastType.INVERSION, loc, 7, "MODERATE")));
        stubBluebell(List.of());
        stubReadings(List.of());

        SlotSignals s = reader().read(FROM, TO).get(0);
        assertThat(s.scores().inversion()).isEqualTo(7);
        assertThat(s.readings()).isSameAs(SlotSignals.Readings.EMPTY);
    }

    @Test
    @DisplayName("bluebell score carries score and summary into the composite")
    void bluebell_carriesScoreAndSummary() {
        LocationEntity loc = location(4L);
        stubInversion(List.of());
        stubBluebell(List.of(score(ForecastType.BLUEBELL, loc, 4, "Bright still light")));
        stubReadings(List.of());

        SlotSignals s = reader().read(FROM, TO).get(0);
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

        SlotSignals s = reader().read(FROM, TO).get(0);
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

        SlotSignals s = reader().read(FROM, TO).get(0);
        assertThat(s.scores().inversion()).isEqualTo(9);
        assertThat(s.scores().inversionBand()).isEqualTo("STRONG");
    }

    @Test
    @DisplayName("slot_atmosphere readings are returned alongside a component evaluated long "
            + "ago, exactly as they always have been — readings were never subject to any "
            + "retraction, on this path or the brief window that once applied one to scores")
    void readings_returnedAlongsideAnciently_evaluatedComponent() {
        LocationEntity loc = location(6L);
        stubInversion(List.of(score(ForecastType.INVERSION, loc, 9, "STRONG", ANCIENT)));
        stubBluebell(List.of());
        stubReadings(List.of(readings(loc, "60.00")));

        SlotSignals s = reader().read(FROM, TO).get(0);
        assertThat(s.scores().inversion()).isEqualTo(9);
        assertThat(s.readings().dust()).isEqualByComparingTo("60.00");
    }
}
