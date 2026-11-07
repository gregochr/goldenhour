package com.gregochr.goldenhour.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.gregochr.goldenhour.entity.EvaluationModel;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.comingup.ComingUpAction;
import com.gregochr.goldenhour.model.comingup.ComingUpCoincidenceLine;
import com.gregochr.goldenhour.model.comingup.ComingUpDayMark;
import com.gregochr.goldenhour.model.comingup.ComingUpDayRange;
import com.gregochr.goldenhour.model.comingup.ComingUpEntry;
import com.gregochr.goldenhour.model.comingup.ComingUpFact;
import com.gregochr.goldenhour.model.comingup.ComingUpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Tests the null-tolerant compact constructors and small derived accessors of the model records:
 * a missing collection is an empty one, and a supplied collection is a defensive immutable copy.
 */
class ModelRecordNormalisationTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 5);

    // ── comingup ─────────────────────────────────────────────────────────────

    private static ComingUpEntry entry(Map<String, String> meta, List<String> regions,
            List<ComingUpFact> facts, List<ComingUpCoincidenceLine> coincidence,
            List<ComingUpDayMark> dayMarks, List<ComingUpDayRange> dayRanges) {
        return new ComingUpEntry(DAY, DAY, AlmanacKind.ALMANAC, "SUPERMOON", "Title", "Detail", meta,
                regions, DAY, "id-1", "night-sky", "Almanac", null, null, null, facts, null, null, null,
                null, null, false, null, coincidence, null, dayMarks, dayRanges, null);
    }

    @Test
    @DisplayName("ComingUpEntry turns every absent collection into an empty one")
    void comingUpEntry_nullCollections_becomeEmpty() {
        ComingUpEntry e = entry(null, null, null, null, null, null);

        assertThat(e.meta()).isEmpty();
        assertThat(e.regions()).isEmpty();
        assertThat(e.facts()).isEmpty();
        assertThat(e.coincidence()).isEmpty();
        assertThat(e.dayMarks()).isEmpty();
        assertThat(e.dayRanges()).isEmpty();
        assertThat(e.lightRowsWithheld()).isFalse();
    }

    @Test
    @DisplayName("ComingUpEntry copies the collections it is given, so later edits to the source do not leak in")
    void comingUpEntry_suppliedCollections_areCopied() {
        Map<String, String> meta = new LinkedHashMap<>(Map.of("k", "v"));
        List<String> regions = new ArrayList<>(List.of("North"));
        ComingUpFact fact = new ComingUpFact(List.of(new ComingUpFact.Segment("text", "tone")));
        List<ComingUpFact> facts = new ArrayList<>(List.of(fact));
        ComingUpCoincidenceLine line = new ComingUpCoincidenceLine("f", "n", "l", null, null);
        List<ComingUpCoincidenceLine> coincidence = new ArrayList<>(List.of(line));
        ComingUpDayMark mark = new ComingUpDayMark(DAY, "mark", "fam");
        List<ComingUpDayMark> marks = new ArrayList<>(List.of(mark));
        ComingUpDayRange range = new ComingUpDayRange(DAY, 5.5, true, 90, false);
        List<ComingUpDayRange> ranges = new ArrayList<>(List.of(range));

        ComingUpEntry e = entry(meta, regions, facts, coincidence, marks, ranges);
        meta.put("later", "x");
        regions.add("South");
        facts.clear();
        coincidence.clear();
        marks.clear();
        ranges.clear();

        assertThat(e.meta()).containsExactly(Map.entry("k", "v"));
        assertThat(e.regions()).containsExactly("North");
        assertThat(e.facts()).containsExactly(fact);
        assertThat(e.coincidence()).containsExactly(line);
        assertThat(e.dayMarks()).containsExactly(mark);
        assertThat(e.dayRanges()).containsExactly(range);
    }

    @Test
    @DisplayName("ComingUpEntry rejects a missing identity field")
    void comingUpEntry_missingRequiredField_throws() {
        assertThatThrownBy(() -> new ComingUpEntry(null, DAY, AlmanacKind.ALMANAC, "T", "t", "d", null, null,
                DAY, "id", "fam", "tag", null, null, null, null, null, null, null, null, null, false, null,
                null, null, null, null, null))
                .isInstanceOf(NullPointerException.class).hasMessage("startDate");
        assertThatThrownBy(() -> new ComingUpEntry(DAY, null, AlmanacKind.ALMANAC, "T", "t", "d", null, null,
                DAY, "id", "fam", "tag", null, null, null, null, null, null, null, null, null, false, null,
                null, null, null, null, null))
                .isInstanceOf(NullPointerException.class).hasMessage("endDate");
        assertThatThrownBy(() -> new ComingUpEntry(DAY, DAY, AlmanacKind.ALMANAC, "T", "t", "d", null, null,
                null, "id", "fam", "tag", null, null, null, null, null, null, null, null, null, false, null,
                null, null, null, null, null))
                .isInstanceOf(NullPointerException.class).hasMessage("enteredWindow");
        assertThatThrownBy(() -> new ComingUpEntry(DAY, DAY, AlmanacKind.ALMANAC, "T", "t", "d", null, null,
                DAY, null, "fam", "tag", null, null, null, null, null, null, null, null, null, false, null,
                null, null, null, null, null))
                .isInstanceOf(NullPointerException.class).hasMessage("id");
        assertThatThrownBy(() -> new ComingUpEntry(DAY, DAY, AlmanacKind.ALMANAC, "T", "t", "d", null, null,
                DAY, "id", null, "tag", null, null, null, null, null, null, null, null, null, false, null,
                null, null, null, null, null))
                .isInstanceOf(NullPointerException.class).hasMessage("family");
        assertThatThrownBy(() -> new ComingUpEntry(DAY, DAY, AlmanacKind.ALMANAC, "T", "t", "d", null, null,
                DAY, "id", "fam", null, null, null, null, null, null, null, null, null, null, false, null,
                null, null, null, null, null))
                .isInstanceOf(NullPointerException.class).hasMessage("kindTag");
    }

    @Test
    @DisplayName("the with-er copies change one field and keep the rest")
    void comingUpEntry_withers_changeOnlyTheirField() {
        ComingUpEntry base = entry(null, null, null, null, null, null);
        ComingUpFact fact = new ComingUpFact(List.of());
        ComingUpCoincidenceLine line = new ComingUpCoincidenceLine("f", "n", "l", null, null);

        assertThat(base.withFacts(List.of(fact)).facts()).containsExactly(fact);
        assertThat(base.withCoincidence(List.of(line)).coincidence()).containsExactly(line);
        ComingUpEntry withheld = base.withLightRowsWithheld(true);
        assertThat(withheld.lightRowsWithheld()).isTrue();
        assertThat(withheld.id()).isEqualTo("id-1");
        assertThat(base.lightRowsWithheld()).isFalse();
    }

    @Test
    @DisplayName("ComingUpEntry.from() builds an interim night-sky entry with a plan action")
    void comingUpEntry_from_buildsInterimEntry() {
        AlmanacEvent event = new AlmanacEvent(DAY, DAY.plusDays(1), AlmanacKind.ALMANAC, "METEOR_SHOWER",
                "Leonids", "Peaks", Map.of("zhr", "15"), List.of("North"));

        ComingUpEntry e = ComingUpEntry.from(event, DAY.minusDays(3));

        assertThat(e.id()).isEqualTo("METEOR_SHOWER:2026-11-05:2026-11-06");
        assertThat(e.family()).isEqualTo("night-sky");
        assertThat(e.kindTag()).isEqualTo("Almanac");
        assertThat(e.interim()).isTrue();
        assertThat(e.enteredWindow()).isEqualTo(DAY.minusDays(3));
        assertThat(e.action()).isEqualTo(new ComingUpAction("See the plan for 2026-11-05 →", "plan", DAY));
        assertThat(e.meta()).containsEntry("zhr", "15");
        assertThat(e.regions()).containsExactly("North");
    }

    @Test
    @DisplayName("ComingUpResponse turns null entries into an empty list and withEntries swaps them")
    void comingUpResponse_nullEntries_becomeEmpty() {
        ComingUpResponse response = new ComingUpResponse(DAY, null, null, null);
        assertThat(response.entries()).isEmpty();

        ComingUpEntry e = entry(null, null, null, null, null, null);
        ComingUpResponse swapped = response.withEntries(List.of(e));
        assertThat(swapped.entries()).containsExactly(e);
        assertThat(swapped.builtFor()).isEqualTo(DAY);
    }

    @Test
    @DisplayName("ComingUpFact turns null segments into an empty list and defaults the kind to null")
    void comingUpFact_nullSegments_becomeEmpty() {
        assertThat(new ComingUpFact(null, ComingUpFact.Kind.EVENT_WATER).segments()).isEmpty();
        ComingUpFact plain = new ComingUpFact(List.of(new ComingUpFact.Segment("a", "b")));
        assertThat(plain.kind()).isNull();
        assertThat(plain.segments()).containsExactly(new ComingUpFact.Segment("a", "b"));
    }

    // ── aurora run records ───────────────────────────────────────────────────

    @Test
    @DisplayName("AuroraForecastRunRequest defaults null nights to empty and copies a supplied list")
    void auroraRunRequest_normalisesNights() {
        assertThat(new AuroraForecastRunRequest(null).nights()).isEmpty();
        List<LocalDate> nights = new ArrayList<>(List.of(DAY));
        AuroraForecastRunRequest request = new AuroraForecastRunRequest(nights);
        nights.clear();
        assertThat(request.nights()).containsExactly(DAY);
    }

    @Test
    @DisplayName("AuroraForecastPreview defaults null nights to empty and copies a supplied list")
    void auroraPreview_normalisesNights() {
        assertThat(new AuroraForecastPreview(null, true).nights()).isEmpty();
        AuroraForecastPreview.NightPreview night =
                new AuroraForecastPreview.NightPreview(DAY, "Tonight", 6.0, "G2", true, "Strong", 4);
        List<AuroraForecastPreview.NightPreview> nights = new ArrayList<>(List.of(night));
        AuroraForecastPreview preview = new AuroraForecastPreview(nights, false);
        nights.clear();
        assertThat(preview.nights()).containsExactly(night);
        assertThat(preview.simulated()).isFalse();
    }

    @Test
    @DisplayName("AuroraForecastRunResponse defaults null nights to empty and copies a supplied list")
    void auroraRunResponse_normalisesNights() {
        assertThat(new AuroraForecastRunResponse(null, 0, "$0").nights()).isEmpty();
        AuroraForecastRunResponse.NightResult night =
                new AuroraForecastRunResponse.NightResult(DAY, "DONE", 3, 1, 5.0, "ok");
        List<AuroraForecastRunResponse.NightResult> nights = new ArrayList<>(List.of(night));
        AuroraForecastRunResponse response = new AuroraForecastRunResponse(nights, 3, "$0.12");
        nights.clear();
        assertThat(response.nights()).containsExactly(night);
        assertThat(response.totalClaudeCalls()).isEqualTo(3);
    }

    // ── best bet ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("BestBetResult factories carry the right status and a null picks list becomes empty")
    void bestBetResult_factoriesAndNullPicks() {
        BestBet pick = mock(BestBet.class);
        List<BestBet> picks = new ArrayList<>(List.of(pick));

        BestBetResult withPicks = BestBetResult.withPicks(picks);
        picks.clear();

        assertThat(withPicks.status()).isEqualTo(BestBetStatus.SUCCESS_WITH_PICKS);
        assertThat(withPicks.picks()).containsExactly(pick);
        assertThat(BestBetResult.noPicks().status()).isEqualTo(BestBetStatus.SUCCESS_NO_PICKS);
        assertThat(BestBetResult.failed().status()).isEqualTo(BestBetStatus.FAILED);
        assertThat(BestBetResult.failed().picks()).isEmpty();
        assertThat(new BestBetResult(BestBetStatus.FAILED, null).picks()).isEmpty();
    }

    @Test
    @DisplayName("BriefingDayPeak defaults null events and regions to empty and copies supplied lists")
    void briefingDayPeak_normalisesLists() {
        BriefingDayPeak empty = new BriefingDayPeak(DisplayVerdict.MAYBE, null, null);
        assertThat(empty.events()).isEmpty();
        assertThat(empty.regions()).isEmpty();

        BriefingDayPeak.PeakRegion region =
                new BriefingDayPeak.PeakRegion("North", TargetType.SUNRISE, DisplayVerdict.WORTH_IT);
        List<TargetType> events = new ArrayList<>(List.of(TargetType.SUNRISE));
        List<BriefingDayPeak.PeakRegion> regions = new ArrayList<>(List.of(region));
        BriefingDayPeak peak = new BriefingDayPeak(DisplayVerdict.WORTH_IT, events, regions);
        events.clear();
        regions.clear();
        assertThat(peak.events()).containsExactly(TargetType.SUNRISE);
        assertThat(peak.regions()).containsExactly(region);
    }

    // ── reports ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("CalibrationReport defaults every absent list and the seen counts to empty")
    void calibrationReport_nullCollections_becomeEmpty() {
        CalibrationReport report = new CalibrationReport(DAY, DAY, 4, 2, null, null, null, null, null,
                null, null);

        assertThat(report.byDaysAhead()).isEmpty();
        assertThat(report.byModel()).isEmpty();
        assertThat(report.bucketsByBuild()).isEmpty();
        assertThat(report.bucketsByPromptHash()).isEmpty();
        assertThat(report.mistProneSeenCounts()).isEmpty();
    }

    @Test
    @DisplayName("CalibrationReport copies its lists and keeps the seen-count order it was given")
    void calibrationReport_suppliedCollections_keepOrder() {
        CalibrationBucket bucket = new CalibrationBucket("k", 1, 0.0, 0.0, 1.0, 1.0, 0, 0);
        List<CalibrationBucket> list = new ArrayList<>(List.of(bucket));
        Map<String, Integer> seen = new LinkedHashMap<>();
        seen.put("Z_LAST", 1);
        seen.put("A_FIRST", 2);

        CalibrationReport report = new CalibrationReport(DAY, DAY, 4, 2, bucket, list, list, list, list,
                bucket, seen);
        list.clear();
        seen.clear();

        assertThat(report.byDaysAhead()).containsExactly(bucket);
        assertThat(report.byModel()).containsExactly(bucket);
        assertThat(report.bucketsByBuild()).containsExactly(bucket);
        assertThat(report.bucketsByPromptHash()).containsExactly(bucket);
        assertThat(report.mistProneSeenCounts().keySet()).containsExactly("Z_LAST", "A_FIRST");
        assertThat(report.mistProne()).isEqualTo(bucket);
    }

    @Test
    @DisplayName("the 9-argument CalibrationReport constructor has no mist-prone bucket and no seen counts")
    void calibrationReport_shortConstructor_hasNoMistSection() {
        CalibrationReport report = new CalibrationReport(DAY, DAY, 4, 2, null, List.of(), List.of(), List.of(),
                List.of());

        assertThat(report.mistProne()).isNull();
        assertThat(report.mistProneSeenCounts()).isEmpty();
        assertThat(report.outcomesInRange()).isEqualTo(4);
        assertThat(report.scoredPairs()).isEqualTo(2);
    }

    @Test
    @DisplayName("CloudVerificationReport defaults every absent bucket list to empty")
    void cloudVerificationReport_nullLists_becomeEmpty() {
        CloudVerificationReport report = new CloudVerificationReport(DAY, DAY, 0L, null, null, null, null,
                null, null, null, null, null, null, null, null);

        assertThat(report.byWindSunAngle()).isEmpty();
        assertThat(report.byConeStructure()).isEmpty();
        assertThat(report.byCorridor()).isEmpty();
        assertThat(report.byTriageCut()).isEmpty();
        assertThat(report.byPromptable()).isEmpty();
    }

    @Test
    @DisplayName("CloudVerificationReport copies the bucket lists it is given")
    void cloudVerificationReport_suppliedLists_areCopied() {
        CloudVerificationBucket bucket = mock(CloudVerificationBucket.class);
        List<CloudVerificationBucket> list = new ArrayList<>(List.of(bucket));

        CloudVerificationReport report = new CloudVerificationReport(DAY, DAY, 1L, null, null, null, null,
                null, list, list, list, list, list, 0.5, 0.25);
        list.clear();

        assertThat(report.byWindSunAngle()).containsExactly(bucket);
        assertThat(report.byConeStructure()).containsExactly(bucket);
        assertThat(report.byCorridor()).containsExactly(bucket);
        assertThat(report.byTriageCut()).containsExactly(bucket);
        assertThat(report.byPromptable()).containsExactly(bucket);
        assertThat(report.vetoSeparation()).isEqualTo(0.5);
    }

    // ── small accessors ──────────────────────────────────────────────────────

    @Test
    @DisplayName("BackfillBatch.isBlank() is true only when rows were written but none had observations")
    void backfillBatch_isBlank() {
        assertThat(BackfillBatch.EMPTY.isBlank()).isFalse();
        assertThat(new BackfillBatch(3, 0).isBlank()).isTrue();
        assertThat(new BackfillBatch(3, 2).isBlank()).isFalse();
    }

    @Test
    @DisplayName("MarineWaveSample derives a sea state from wave height and none when unmeasured")
    void marineWaveSample_seaState() {
        assertThat(new MarineWaveSample(null, 1.0, 180).seaState()).isNull();
        assertThat(new MarineWaveSample(0.0, null, null).seaState()).isEqualTo(SeaState.CALM);
    }

    @Test
    @DisplayName("ExpandedHotTopicDetail.forLite() is a no-op without tide metrics and strips the counts with them")
    void expandedHotTopicDetail_forLite() {
        ExpandedHotTopicDetail noTide = new ExpandedHotTopicDetail(List.of(), null, null);
        assertThat(noTide.forLite()).isSameAs(noTide);

        ExpandedHotTopicDetail tide = new ExpandedHotTopicDetail(List.of(), null,
                new ExpandedHotTopicDetail.TideMetrics("KING", "FULL", 2, 3));
        ExpandedHotTopicDetail lite = tide.forLite();
        assertThat(lite.tideMetrics()).isEqualTo(new ExpandedHotTopicDetail.TideMetrics("KING", "FULL", null, null));
    }

    @Test
    @DisplayName("ForecastPreEvalResult copies a supplied tide-type set and keeps null as null")
    void forecastPreEvalResult_tideTypes() {
        Set<TideType> source = new java.util.HashSet<>(Set.of(TideType.HIGH));

        ForecastPreEvalResult with = result(source);
        source.add(TideType.LOW);
        assertThat(with.tideTypes()).containsExactly(TideType.HIGH);
        assertThat(result(null).tideTypes()).isNull();
    }

    private static ForecastPreEvalResult result(Set<TideType> tideTypes) {
        return new ForecastPreEvalResult(false, null, null, null, DAY, TargetType.SUNSET,
                LocalDateTime.of(2026, 11, 5, 16, 0), 250, 1, EvaluationModel.HAIKU, tideTypes, "key", null);
    }

    @Test
    @DisplayName("CalibrationPair's 16-argument constructor carries no mist potential or seen tag")
    void calibrationPair_sixteenArgConstructor() {
        CalibrationPair pair = new CalibrationPair("Bamburgh", DAY, TargetType.SUNRISE, 1,
                LocalDateTime.of(2026, 11, 4, 6, 0), EvaluationModel.HAIKU, 4, 3, 3, 2, 2, 2, true,
                "outcome-1", "build", "hash");

        assertThat(pair.mistPotential()).isNull();
        assertThat(pair.seen()).isNull();
        assertThat(pair.mistLikely()).isFalse();
        assertThat(pair.ratingError()).isEqualTo(2);
        assertThat(pair.slotHorizonKey()).isEqualTo("Bamburgh|2026-11-05|SUNRISE|1|outcome-1");
    }

    @Test
    @DisplayName("SlotSignals.Readings' 18-argument constructor reads the coastal mist columns as absent")
    void slotSignalsReadings_eighteenArgConstructor() {
        SlotSignals.Readings readings = new SlotSignals.Readings(BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO,
                "LOW", 0.0, 1500.0, 80, 0.1, 5.0, 270.0, 8.0, 7.0, true, 3.0, true, MistPotential.LIKELY,
                CoastalMistBand.LIGHT, true);

        assertThat(readings.coastalMistLowCloudPct()).isNull();
        assertThat(readings.coastalMistPrecipMm()).isNull();
        assertThat(readings.coastalMistBand()).isEqualTo(CoastalMistBand.LIGHT);
        assertThat(readings.mistPotential()).isEqualTo(MistPotential.LIKELY);

        SlotSignals signals = new SlotSignals(new LocationEntity(), DAY, TargetType.SUNRISE,
                SlotSignals.Scores.EMPTY, readings);
        assertThat(signals.effectiveInversionScore()).isEqualTo(7.0);
        assertThat(signals.effectiveMistPotential()).isEqualTo(MistPotential.LIKELY);
    }

    // ── surge curve ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("SurgeCurve.forLocation() returns the series, or null for any missing key")
    void surgeCurve_forLocation() {
        List<Double> series = List.of(0.1, 0.2);
        SurgeCurve curve = new SurgeCurve(Map.of(7L, Map.of(DAY, series)));

        assertThat(curve.forLocation(7L, DAY)).isEqualTo(series);
        assertThat(curve.forLocation(7L, DAY.plusDays(1))).isNull();
        assertThat(curve.forLocation(8L, DAY)).isNull();
        assertThat(curve.forLocation(null, DAY)).isNull();
        assertThat(curve.forLocation(7L, null)).isNull();
        assertThat(curve.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("SurgeCurve with a null map is empty, as is EMPTY")
    void surgeCurve_nullMap_isEmpty() {
        assertThat(new SurgeCurve(null).isEmpty()).isTrue();
        assertThat(SurgeCurve.EMPTY.isEmpty()).isTrue();
        assertThat(SurgeCurve.EMPTY.forLocation(1L, DAY)).isNull();
    }

    // ── solar cloud trend slot ───────────────────────────────────────────────

    @Test
    @DisplayName("SolarCloudTrend reads a clearing trajectory as not clearing when mid or high cloud is missing")
    void solarCloudTrend_isClearing_needsBothCanvasLayers() {
        var midMissing = new SolarCloudTrend(List.of(
                new SolarCloudTrend.SolarCloudSlot(3, 80, null, 70),
                new SolarCloudTrend.SolarCloudSlot(0, 10, 60, 60)));
        var highMissing = new SolarCloudTrend(List.of(
                new SolarCloudTrend.SolarCloudSlot(3, 80, 70, null),
                new SolarCloudTrend.SolarCloudSlot(0, 10, 60, 60)));
        var eventMissing = new SolarCloudTrend(List.of(
                new SolarCloudTrend.SolarCloudSlot(3, 80, 70, 70),
                new SolarCloudTrend.SolarCloudSlot(0, 10, 60, null)));
        var holds = new SolarCloudTrend(List.of(
                new SolarCloudTrend.SolarCloudSlot(3, 80, 70, 70),
                new SolarCloudTrend.SolarCloudSlot(0, 10, 60, 60)));

        assertThat(midMissing.isClearing()).isFalse();
        assertThat(highMissing.isClearing()).isFalse();
        assertThat(eventMissing.isClearing()).isFalse();
        assertThat(holds.isClearing()).isTrue();
    }

    @Test
    @DisplayName("SolarCloudTrend with null or single-slot data is neither building nor clearing")
    void solarCloudTrend_degenerateSlots() {
        assertThat(new SolarCloudTrend(null).isBuilding()).isFalse();
        assertThat(new SolarCloudTrend(null).isClearing()).isFalse();
        var single = new SolarCloudTrend(List.of(new SolarCloudTrend.SolarCloudSlot(0, 50)));
        assertThat(single.isBuilding()).isFalse();
        assertThat(single.isClearing()).isFalse();
    }

    @Test
    @DisplayName("SolarCloudTrend.average() ignores null and empty trends and returns null when none is usable")
    void solarCloudTrend_average_filtersUnusableTrends() {
        assertThat(SolarCloudTrend.average(null)).isNull();
        assertThat(SolarCloudTrend.average(new ArrayList<>(java.util.Arrays.asList((SolarCloudTrend) null))))
                .isNull();
        assertThat(SolarCloudTrend.average(List.of(new SolarCloudTrend(null), new SolarCloudTrend(List.of()))))
                .isNull();

        var only = new SolarCloudTrend(List.of(new SolarCloudTrend.SolarCloudSlot(2, 40, 30, 20)));
        assertThat(SolarCloudTrend.average(List.of(new SolarCloudTrend(null), only))).isSameAs(only);
    }

    @Test
    @DisplayName("SolarCloudTrend.average() truncates to the shortest trend and nulls a layer any slot lacks")
    void solarCloudTrend_average_truncatesAndNullsMissingCanvas() {
        var first = new SolarCloudTrend(List.of(
                new SolarCloudTrend.SolarCloudSlot(3, 60, 40, 20),
                new SolarCloudTrend.SolarCloudSlot(0, 20, 30, 10)));
        var second = new SolarCloudTrend(List.of(
                new SolarCloudTrend.SolarCloudSlot(3, 40, null, 40)));

        SolarCloudTrend averaged = SolarCloudTrend.average(List.of(first, second));

        assertThat(averaged.slots()).containsExactly(new SolarCloudTrend.SolarCloudSlot(3, 50, null, 30));
    }
}
