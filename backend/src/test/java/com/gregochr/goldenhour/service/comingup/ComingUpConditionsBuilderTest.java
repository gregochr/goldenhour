package com.gregochr.goldenhour.service.comingup;

import com.gregochr.goldenhour.entity.ForecastEvaluationEntity;
import com.gregochr.goldenhour.entity.ForecastScoreEntity;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.SurvivorAtmosphereEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.AlmanacEvent;
import com.gregochr.goldenhour.model.AlmanacKind;
import com.gregochr.goldenhour.model.comingup.ComingUpAction;
import com.gregochr.goldenhour.model.comingup.ComingUpCoincidenceLine;
import com.gregochr.goldenhour.model.comingup.ComingUpCondition;
import com.gregochr.goldenhour.model.comingup.ComingUpConditionOccurrence;
import com.gregochr.goldenhour.model.comingup.ComingUpEntry;
import com.gregochr.goldenhour.repository.ForecastEvaluationRepository;
import com.gregochr.goldenhour.repository.ForecastScoreRepository;
import com.gregochr.goldenhour.repository.LocationRepository;
import com.gregochr.goldenhour.repository.SurvivorAtmosphereRepository;
import com.gregochr.goldenhour.service.SurvivorSignalReader;
import com.gregochr.goldenhour.service.TideRunBuilder;
import com.gregochr.goldenhour.service.TideService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** Unit tests for {@link ComingUpConditionsBuilder}. */
@ExtendWith(MockitoExtension.class)
class ComingUpConditionsBuilderTest {

    private static final LocalDate TODAY = LocalDate.of(2027, 9, 10);
    /** Plan's last day under {@code TODAY} (today + 3). */
    private static final LocalDate LAST_PLAN_DATE = TODAY.plusDays(3);
    private static final LocationEntity SEAHAM =
            LocationEntity.builder().id(1L).name("Seaham").lat(54.84).lon(-1.33).build();
    private static final List<LocationEntity> COASTAL_ROSTER = List.of(SEAHAM);

    @Mock
    private LocationRepository locationRepository;
    @Mock
    private TideRunBuilder tideRunBuilder;
    @Mock
    private TideRunPeakHistory tideRunPeakHistory;
    @Mock
    private TideService tideService;
    @Mock
    private ForecastEvaluationRepository forecastEvaluationRepository;
    @Mock
    private ForecastScoreRepository forecastScoreRepository;
    @Mock
    private SurvivorAtmosphereRepository survivorAtmosphereRepository;

    private ComingUpConditionsBuilder builder;

    @BeforeEach
    void setUp() {
        lenient().when(locationRepository.findCoastalLocations()).thenReturn(COASTAL_ROSTER);
        lenient().when(forecastEvaluationRepository
                .findByTargetDateBetweenAndTargetTypeIn(any(), any(), anyCollection()))
                .thenReturn(List.of());
        lenient().when(forecastScoreRepository.findComponentsByType(any(), any(), any()))
                .thenReturn(List.of());
        lenient().when(survivorAtmosphereRepository.findInDateRange(any(), any())).thenReturn(List.of());
        // No tide history/stats by default — every run scores the cold-start default unless a test
        // stubs otherwise.
        lenient().when(tideRunBuilder.peakRange(anyList(), anyList())).thenReturn(Optional.empty());

        SurvivorSignalReader survivorSignalReader =
                new SurvivorSignalReader(forecastScoreRepository, survivorAtmosphereRepository);
        builder = new ComingUpConditionsBuilder(locationRepository, tideRunBuilder, tideRunPeakHistory,
                tideService, forecastEvaluationRepository, survivorSignalReader, new ComingUpScoringProperties());
    }

    private static AlmanacEvent tideEvent(LocalDate start, LocalDate end, String type) {
        return new AlmanacEvent(start, end, AlmanacKind.ALMANAC, type, type, "detail",
                Map.of(), List.of());
    }

    private static ComingUpEntry entryFor(AlmanacEvent event, double bits,
            List<ComingUpCoincidenceLine> coincidence) {
        String id = event.type() + ":" + event.startDate() + ":" + event.endDate();
        return new ComingUpEntry(event.startDate(), event.endDate(), event.kind(), event.type(),
                event.title(), event.detail(), Map.of(), List.of(), event.startDate().minusDays(89),
                id, "coastal", "Almanac", null, null, null, List.of(), null, null,
                new ComingUpAction("See the plan for " + event.startDate() + " →", "plan", event.startDate()),
                bits, false, null, coincidence, coincidence.isEmpty() ? null : "joined");
    }

    // ── Always three conditions ─────────────────────────────────────────

    @Test
    @DisplayName("the strip always carries the three first-ship conditions, even with nothing to show")
    void alwaysThreeConditions() {
        List<ComingUpCondition> conditions = builder.build(TODAY, List.of(), List.of());

        assertThat(conditions).extracting(ComingUpCondition::type)
                .containsExactly("COASTAL_TIDES", "DUST", "VALLEY_INVERSIONS");
        assertThat(conditions).allSatisfy(c -> assertThat(c.occurrences()).isEmpty());
    }

    @Test
    @DisplayName("D11: a spring run and a king run in the same window land in the ONE Coastal-tides "
            + "row, each occurrence labelled with its own run kind — never re-derived, and never "
            + "confused with the other")
    void springAndKingRunsShareOneRowWithDistinctLabels() {
        AlmanacEvent springRun = tideEvent(TODAY.plusDays(5), TODAY.plusDays(6), "spring-tide");
        AlmanacEvent kingRun = tideEvent(TODAY.plusDays(20), TODAY.plusDays(21), "king-tide");

        List<ComingUpCondition> conditions = builder.build(TODAY, List.of(springRun, kingRun), List.of());

        assertThat(conditions).extracting(ComingUpCondition::type).containsExactly(
                "COASTAL_TIDES", "DUST", "VALLEY_INVERSIONS");
        ComingUpCondition tides = conditions.getFirst();
        assertThat(tides.occurrences()).hasSize(2);
        assertThat(tides.occurrences()).extracting(ComingUpConditionOccurrence::label)
                .containsExactlyInAnyOrder("Spring tide", "King tide");
    }

    // ── Status precedence (D11) ──────────────────────────────────────────

    @Test
    @DisplayName("promoted holds exactly when the run's own id resolves to a real chronology entry")
    void promotedIffEntryExists() {
        AlmanacEvent run = tideEvent(LAST_PLAN_DATE.plusDays(1), LAST_PLAN_DATE.plusDays(2), "spring-tide");
        ComingUpEntry entry = entryFor(run, 6.0, List.of());

        ComingUpCondition tides = builder.build(TODAY, List.of(run), List.of(entry)).getFirst();

        ComingUpConditionOccurrence occurrence = tides.occurrences().getFirst();
        assertThat(occurrence.status()).isEqualTo("promoted");
        assertThat(occurrence.entryId()).isEqualTo(entry.id());
        // The invariant test: a promoted row's entryId resolves to a real entries[] id.
        assertThat(occurrence.entryId()).isEqualTo(entry.id());
    }

    @Test
    @DisplayName("a run straddling Plan's boundary is promoted, not insidePlan — its own dates say "
            + "it belongs to the chronology")
    void straddlingRunIsPromotedNotInsidePlan() {
        AlmanacEvent straddler = tideEvent(LAST_PLAN_DATE.minusDays(1), LAST_PLAN_DATE.plusDays(1), "spring-tide");
        ComingUpEntry entry = entryFor(straddler, 6.0, List.of());

        ComingUpCondition tides = builder.build(TODAY, List.of(straddler), List.of(entry)).getFirst();

        assertThat(tides.occurrences().getFirst().status()).isEqualTo("promoted");
    }

    @Test
    @DisplayName("a run wholly inside Plan's window with no chronology entry reads insidePlan")
    void insidePlanBoundary() {
        AlmanacEvent insidePlan = tideEvent(TODAY, LAST_PLAN_DATE, "spring-tide");

        ComingUpCondition tides = builder.build(TODAY, List.of(insidePlan), List.of()).getFirst();

        ComingUpConditionOccurrence occurrence = tides.occurrences().getFirst();
        assertThat(occurrence.status()).isEqualTo("insidePlan");
        assertThat(occurrence.entryId()).isNull();
    }

    @Test
    @DisplayName("an in-progress run with a backward-walked start (already under way, per "
            + "TideAlmanacSource.completeRun) reads insidePlan, not heldBack — it is live on the "
            + "Plan tab right now")
    void inProgressRunWithBackwardWalkedStart_isInsidePlanNotHeldBack() {
        // TideAlmanacSource's backward walk routinely produces a run whose start already lags
        // behind "today" (see the P2 phase-log row) — this is NOT a run that ended in the past.
        AlmanacEvent inProgress = tideEvent(TODAY.minusDays(1), TODAY.plusDays(1), "spring-tide");

        ComingUpCondition tides = builder.build(TODAY, List.of(inProgress), List.of()).getFirst();

        ComingUpConditionOccurrence occurrence = tides.occurrences().getFirst();
        assertThat(occurrence.status()).isEqualTo("insidePlan");
        assertThat(occurrence.entryId()).isNull();
    }

    // ── Reason tag: mandatory wherever the max rule was taken (D10), both directions ──

    /**
     * Builds the entry the way {@code ComingUpAssembler.mergeEntries} actually produces one when
     * the OTHER topic's score wins a D10 merge: the surviving entry is keyed by the WINNER's own id
     * (never the losing tide run's), and its one coincidence line names the LOSER — the tide run
     * itself, per {@link ComingUpCoincidenceLine}'s own contract. A fixture built any other way
     * (e.g. keyed by the tide's own id) cannot occur in production and was the exact defect an
     * external review caught in an earlier draft of this test.
     */
    private static ComingUpEntry winningSupermoonEntry(AlmanacEvent tideRun, double bits) {
        AlmanacEvent supermoonEvent = new AlmanacEvent(tideRun.startDate(), tideRun.endDate(),
                AlmanacKind.ALMANAC, "supermoon", "Supermoon", "detail", Map.of(), List.of());
        return entryFor(supermoonEvent, bits,
                List.of(new ComingUpCoincidenceLine("coastal", tideRun.title(), "26 Nov")));
    }

    @Test
    @DisplayName("reason names the winner when a supermoon's score overrode a tide run's own — "
            + "found by date overlap, since the surviving entry carries the SUPERMOON's id, never "
            + "the losing run's own")
    void reasonTagWhenSupermoonWonTheMerge() {
        AlmanacEvent run = tideEvent(LAST_PLAN_DATE.plusDays(1), LAST_PLAN_DATE.plusDays(1), "king-tide");
        // Own score with no stubbed tide history: rarity(14.8) + DEFAULT_MAGNITUDE_BITS(1.0) ≈ 4.87.
        ComingUpEntry supermoonEntry = winningSupermoonEntry(run, 9.0);

        ComingUpCondition tides = builder.build(TODAY, List.of(run), List.of(supermoonEntry)).getFirst();

        ComingUpConditionOccurrence occurrence = tides.occurrences().getFirst();
        assertThat(occurrence.status()).isEqualTo("promoted");
        assertThat(occurrence.entryId()).isEqualTo(supermoonEntry.id());
        assertThat(occurrence.reason()).isEqualTo("max w/ supermoon");
        assertThat(occurrence.bits()).isEqualTo(9.0);
    }

    @Test
    @DisplayName("an exact-id match with no overlap-based fallback needed still promotes correctly "
            + "when the tide run itself is the merge's own entry (it won, or there was no merge)")
    void promotedViaExactIdWhenTideOwnsItsEntry() {
        AlmanacEvent run = tideEvent(LAST_PLAN_DATE.plusDays(1), LAST_PLAN_DATE.plusDays(1), "king-tide");
        ComingUpEntry ownEntry = entryFor(run, 6.0, List.of());

        ComingUpCondition tides = builder.build(TODAY, List.of(run), List.of(ownEntry)).getFirst();

        ComingUpConditionOccurrence occurrence = tides.occurrences().getFirst();
        assertThat(occurrence.status()).isEqualTo("promoted");
        assertThat(occurrence.entryId()).isEqualTo(ownEntry.id());
        assertThat(occurrence.reason()).isNull();
    }

    @Test
    @DisplayName("reason still fires when THIS run's own score carried the merge (D10: mandatory "
            + "wherever the max was taken, both directions) — named from this run's own "
            + "coincidence line, which describes the loser (the other topic)")
    void reasonFiresEvenWhenThisRunsOwnScoreWon() {
        AlmanacEvent run = tideEvent(LAST_PLAN_DATE.plusDays(1), LAST_PLAN_DATE.plusDays(1), "king-tide");
        double ownBits = SurpriseScore.rarity(14.8) + SurpriseScore.DEFAULT_MAGNITUDE_BITS;
        ComingUpEntry mergedAndTideWon = entryFor(run, Math.round(ownBits * 10.0) / 10.0,
                List.of(new ComingUpCoincidenceLine("sun-moon", "Supermoon", "24-25 Nov")));

        ComingUpCondition tides = builder.build(TODAY, List.of(run), List.of(mergedAndTideWon)).getFirst();

        assertThat(tides.occurrences().getFirst().reason()).isEqualTo("max w/ supermoon");
    }

    // ── Coastal tides quant line: real distribution allowed to name percentiles ──

    @Test
    @DisplayName("with derivable ranges, the quant line reports the window's own median/p90 range")
    void coastalTidesQuantLabelReportsRealPercentiles() {
        AlmanacEvent run = tideEvent(LAST_PLAN_DATE.plusDays(1), LAST_PLAN_DATE.plusDays(1), "spring-tide");
        when(tideRunBuilder.peakRange(anyList(), anyList()))
                .thenReturn(Optional.of(new TideRunBuilder.RunPeak(SEAHAM, 4.8)));
        when(tideService.getTideStats(SEAHAM.getId())).thenReturn(Optional.empty());

        ComingUpCondition tides = builder.build(TODAY, List.of(run), List.of()).getFirst();

        assertThat(tides.quantLabel()).contains("typical run").contains("top 10% reach");
        assertThat(tides.interim()).isTrue();
    }

    @Test
    @DisplayName("with no derivable range at all, the distribution half is omitted rather than "
            + "synthesised")
    void coastalTidesQuantLabelOmitsDistributionWhenNoRangeDerivable() {
        AlmanacEvent run = tideEvent(LAST_PLAN_DATE.plusDays(1), LAST_PLAN_DATE.plusDays(1), "spring-tide");

        ComingUpCondition tides = builder.build(TODAY, List.of(run), List.of()).getFirst();

        assertThat(tides.quantLabel()).doesNotContain("typical run");
    }

    @Test
    @DisplayName("a single tide run's scoring failure degrades that occurrence rather than 500ing "
            + "the whole feed — and does not prevent the dust/inversion conditions from building")
    void tideRunScoringFailureDegradesRatherThanThrows() {
        AlmanacEvent run = tideEvent(LAST_PLAN_DATE.plusDays(1), LAST_PLAN_DATE.plusDays(1), "spring-tide");
        when(tideRunBuilder.peakRange(anyList(), anyList())).thenThrow(new RuntimeException("db down"));

        List<ComingUpCondition> conditions = builder.build(TODAY, List.of(run), List.of());

        ComingUpCondition tides = conditions.getFirst();
        assertThat(tides.occurrences()).hasSize(1);
        assertThat(tides.interim()).isTrue();
        assertThat(tides.occurrences().getFirst().valueLabel()).isNull();
        // The other two conditions still built normally — one run's failure is isolated.
        assertThat(conditions).extracting(ComingUpCondition::type)
                .containsExactly("COASTAL_TIDES", "DUST", "VALLEY_INVERSIONS");
    }

    // ── Dust: evidentiary bar ────────────────────────────────────────────

    private static ForecastEvaluationEntity dustRow(LocalDate date, double aod) {
        return ForecastEvaluationEntity.builder()
                .targetDate(date)
                .targetType(TargetType.SUNRISE)
                .aerosolOpticalDepth(BigDecimal.valueOf(aod))
                .build();
    }

    @Test
    @DisplayName("below the evidentiary bar, dust rarity uses the config fallback and the cadence "
            + "clause is omitted")
    void dustBelowEvidentiaryBarUsesFallback() {
        // One isolated arrival — below the 5-arrival bar.
        when(forecastEvaluationRepository.findByTargetDateBetweenAndTargetTypeIn(any(), any(), anyCollection()))
                .thenReturn(List.of(dustRow(TODAY.minusDays(10), 0.4)));

        ComingUpCondition dust = builder.build(TODAY, List.of(), List.of()).get(1);

        double expectedFallback = SurpriseScore.rarity(new ComingUpScoringProperties().getRecurrent()
                .getDust().getFallbackMeanGapDays());
        assertThat(dust.quantLabel()).startsWith(rarityWord(expectedFallback) + " (" + fmt1(expectedFallback) + ")");
        assertThat(dust.rateLabel()).doesNotContain("about");
        assertThat(dust.interim()).isTrue();
    }

    @Test
    @DisplayName("at or above the evidentiary bar, dust rarity is the observed trailing-window rate")
    void dustAtEvidentiaryBarUsesObservedRate() {
        List<ForecastEvaluationEntity> rows = List.of(
                dustRow(TODAY.minusDays(50), 0.4), dustRow(TODAY.minusDays(40), 0.4),
                dustRow(TODAY.minusDays(30), 0.4), dustRow(TODAY.minusDays(20), 0.4),
                dustRow(TODAY.minusDays(10), 0.4));
        when(forecastEvaluationRepository.findByTargetDateBetweenAndTargetTypeIn(any(), any(), anyCollection()))
                .thenReturn(rows);

        ComingUpCondition dust = builder.build(TODAY, List.of(), List.of()).get(1);

        double expectedObserved = SurpriseScore.rarity(60.0 / 5);
        assertThat(dust.quantLabel()).startsWith(rarityWord(expectedObserved) + " (" + fmt1(expectedObserved) + ")");
        assertThat(dust.rateLabel()).contains("about");
    }

    @Test
    @DisplayName("dust's quant line never claims a distribution — always the config threshold phrase")
    void dustQuantLabelNeverClaimsMedianOrP90() {
        ComingUpCondition dust = builder.build(TODAY, List.of(), List.of()).get(1);

        assertThat(dust.quantLabel()).doesNotContain("median").doesNotContain("p90");
        assertThat(dust.quantLabel()).contains("counts as a plume above a haze reading of")
                .contains("(early threshold)");
    }

    @Test
    @DisplayName("a dust-enhanced row with a null AOD (elevated surface dust alone, aerosol fetch "
            + "degraded) does not abort the trailing-window read — every other row still counts")
    void dustToleratesNullAodOnAnEnhancedRow() {
        ForecastEvaluationEntity nullAodButDusty = ForecastEvaluationEntity.builder()
                .targetDate(TODAY.minusDays(40))
                .targetType(TargetType.SUNRISE)
                .dust(BigDecimal.valueOf(60))
                .build();
        List<ForecastEvaluationEntity> rows = List.of(
                nullAodButDusty,
                dustRow(TODAY.minusDays(30), 0.4), dustRow(TODAY.minusDays(20), 0.4),
                dustRow(TODAY.minusDays(10), 0.4), dustRow(TODAY.minusDays(5), 0.4));
        when(forecastEvaluationRepository.findByTargetDateBetweenAndTargetTypeIn(any(), any(), anyCollection()))
                .thenReturn(rows);

        ComingUpCondition dust = builder.build(TODAY, List.of(), List.of()).get(1);

        // All 5 arrivals counted (clears the evidentiary bar) — the null-AOD row is neither lost
        // nor does it abort processing of the rows around it.
        double expectedObserved = SurpriseScore.rarity(60.0 / 5);
        assertThat(dust.quantLabel()).startsWith(rarityWord(expectedObserved) + " (" + fmt1(expectedObserved) + ")");
    }

    @Test
    @DisplayName("a failed trailing-window read reports 'unavailable', never a false 'none in the "
            + "last N days' claim — the two are indistinguishable in the data alone, and "
            + "AlmanacService caches the built response for the whole civil day (Codex finding)")
    void dustTrailingWindowReadFailure_reportsUnavailableNotFalseAbsence() {
        when(forecastEvaluationRepository.findByTargetDateBetweenAndTargetTypeIn(any(), any(), anyCollection()))
                .thenThrow(new RuntimeException("db down"));

        ComingUpCondition dust = builder.build(TODAY, List.of(), List.of()).get(1);

        assertThat(dust.rateLabel()).isEqualTo("history unavailable right now");
        assertThat(dust.rateLabel()).doesNotContain("none in the last");
    }

    // ── Peak gate (D5) ───────────────────────────────────────────────────

    @Test
    @DisplayName("a candidate outside a light window never becomes the peak, even scoring higher "
            + "than a same-window SUNRISE candidate")
    void peakGateExcludesNonLightWindowCandidate() {
        assertThat(builder.passesPeakGate(TargetType.HOURLY)).isFalse();
        assertThat(builder.passesPeakGate(TargetType.SUNRISE)).isTrue();
        assertThat(builder.passesPeakGate(TargetType.SUNSET)).isTrue();
    }

    @Test
    @DisplayName("the gate's configured minutes bound is genuinely read — a non-positive value "
            + "closes the gate even for SUNRISE/SUNSET")
    void peakGateReadsConfiguredMinutesBound() {
        ComingUpScoringProperties zeroWindow = new ComingUpScoringProperties();
        zeroWindow.setPeakLightWindowMinutes(0);
        ComingUpConditionsBuilder zeroWindowBuilder = new ComingUpConditionsBuilder(locationRepository,
                tideRunBuilder, tideRunPeakHistory, tideService, forecastEvaluationRepository,
                new SurvivorSignalReader(forecastScoreRepository, survivorAtmosphereRepository), zeroWindow);

        assertThat(zeroWindowBuilder.passesPeakGate(TargetType.SUNRISE)).isFalse();
    }

    private static SurvivorAtmosphereEntity survivorAtmosphere(LocationEntity location, LocalDate date,
            TargetType eventType, double aod) {
        SurvivorAtmosphereEntity entity = new SurvivorAtmosphereEntity();
        entity.setLocation(location);
        entity.setEvaluationDate(date);
        entity.setEventType(eventType);
        entity.setAerosolOpticalDepth(BigDecimal.valueOf(aod));
        return entity;
    }

    @Test
    @DisplayName("end-to-end: a higher-AOD HOURLY reading never becomes the dust peak — the gate "
            + "excludes it and a lower-AOD SUNRISE candidate wins instead")
    void peakGateExcludesHourlyEndToEnd() {
        LocationEntity dusty = LocationEntity.builder().id(2L).name("Dusty Point").lat(1.0).lon(1.0).build();
        // Only the dust condition's forward peak reads through SurvivorSignalReader without a
        // second, redundant type filter (unlike inversion's, which also hardcodes SUNRISE) — so
        // this is the one path where passesPeakGate is the sole thing standing between an HOURLY
        // wildlife-comfort reading and a fabricated dust peak.
        when(survivorAtmosphereRepository.findInDateRange(TODAY, LAST_PLAN_DATE)).thenReturn(List.of(
                survivorAtmosphere(dusty, TODAY.plusDays(1), TargetType.HOURLY, 0.9),
                survivorAtmosphere(dusty, TODAY.plusDays(1), TargetType.SUNRISE, 0.55)));

        ComingUpCondition dust = builder.build(TODAY, List.of(), List.of()).get(1);

        assertThat(dust.peak()).isNotNull();
        assertThat(dust.peak().valueLabel()).isEqualTo("AOD 0.55");
    }

    // ── Inversion: rarity never upgrades ─────────────────────────────────

    private static ForecastScoreEntity inversionRow(LocationEntity location, LocalDate date, int score) {
        ForecastScoreEntity entity = new ForecastScoreEntity();
        entity.setLocation(location);
        entity.setEvaluationDate(date);
        entity.setEventType(TargetType.SUNRISE);
        entity.setScore(score);
        return entity;
    }

    @Test
    @DisplayName("inversion rarity stays on the config fallback no matter how many strong mornings "
            + "the (survivor-biased) window shows")
    void inversionRarityNeverUpgrades() {
        LocationEntity fell = LocationEntity.builder().id(3L).name("Fell").lat(1.0).lon(1.0).build();
        List<ForecastScoreEntity> rows = List.of(
                inversionRow(fell, TODAY.minusDays(50), 9), inversionRow(fell, TODAY.minusDays(40), 9),
                inversionRow(fell, TODAY.minusDays(30), 9), inversionRow(fell, TODAY.minusDays(20), 9),
                inversionRow(fell, TODAY.minusDays(10), 9), inversionRow(fell, TODAY.minusDays(5), 9));
        // Answers by actual date range rather than a blanket any(): the builder makes two calls
        // (the trailing 60-day historical read, and the forward T+0..T+3 peak read), and a
        // date-blind stub would let a historical row leak into the forward peak.
        when(forecastScoreRepository.findComponentsByType(any(), any(), any())).thenAnswer(invocation -> {
            LocalDate from = invocation.getArgument(1);
            LocalDate to = invocation.getArgument(2);
            return rows.stream()
                    .filter(r -> !r.getEvaluationDate().isBefore(from) && !r.getEvaluationDate().isAfter(to))
                    .toList();
        });

        ComingUpCondition inversion = builder.build(TODAY, List.of(), List.of()).get(2);

        double expectedFallback = SurpriseScore.rarity(new ComingUpScoringProperties().getRecurrent()
                .getInversion().getFallbackMeanGapDays());
        assertThat(inversion.quantLabel())
                .startsWith(rarityWord(expectedFallback) + " (" + fmt1(expectedFallback) + ")");
        assertThat(inversion.occurrences()).hasSize(6);
        assertThat(inversion.interim()).isTrue();
    }

    private static String fmt1(double value) {
        return String.format(java.util.Locale.UK, "%.1f", value);
    }

    /** Mirrors {@code ComingUpConditionsBuilder}'s private word bucketing for assertion purposes. */
    private static String rarityWord(double bits) {
        if (bits < 2.0) {
            return "common";
        }
        if (bits < 4.0) {
            return "occasional";
        }
        if (bits < 6.0) {
            return "uncommon";
        }
        if (bits < 8.0) {
            return "rare";
        }
        return "very rare";
    }
}
