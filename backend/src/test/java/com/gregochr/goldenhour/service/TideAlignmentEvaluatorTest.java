package com.gregochr.goldenhour.service;

import com.gregochr.goldenhour.TestAtmosphericData;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.AtmosphericData;
import com.gregochr.goldenhour.model.TideSnapshot;
import com.gregochr.goldenhour.model.TriageRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TideAlignmentEvaluator}.
 */
class TideAlignmentEvaluatorTest {

    private TideAlignmentEvaluator evaluator;

    // Sunset golden/blue hour window: [event-1h, civilDusk]
    private static final LocalDateTime EVENT_TIME = LocalDateTime.of(2026, 6, 21, 20, 47);
    private static final LocalDateTime WINDOW_START = EVENT_TIME.minusHours(1);   // 19:47
    private static final LocalDateTime WINDOW_END   = EVENT_TIME.plusMinutes(45); // 21:32

    @BeforeEach
    void setUp() {
        evaluator = new TideAlignmentEvaluator();
    }

    // -------------------------------------------------------------------------
    // Fail-open cases
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Returns empty when tide snapshot is null (fail open)")
    void evaluate_noTideSnapshot_failOpen() {
        AtmosphericData data = TestAtmosphericData.defaults(); // tide = null
        Optional<com.gregochr.goldenhour.model.TriageResult> result =
                evaluator.evaluate(data, Set.of(TideType.HIGH), WINDOW_START, WINDOW_END);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Returns empty when tideTypes is null (fail open)")
    void evaluate_nullTideTypes_failOpen() {
        AtmosphericData data = dataWithHighTideAt(WINDOW_START.plusMinutes(30));
        Optional<com.gregochr.goldenhour.model.TriageResult> result =
                evaluator.evaluate(data, null, WINDOW_START, WINDOW_END);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Returns empty when tideTypes is empty (fail open)")
    void evaluate_emptyTideTypes_failOpen() {
        AtmosphericData data = dataWithHighTideAt(WINDOW_START.plusMinutes(30));
        Optional<com.gregochr.goldenhour.model.TriageResult> result =
                evaluator.evaluate(data, Set.of(), WINDOW_START, WINDOW_END);
        assertThat(result).isEmpty();
    }

    // -------------------------------------------------------------------------
    // HIGH tide alignment
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("HIGH preference passes when nearestHighTideTime is within window")
    void evaluate_highPreference_tideWithinWindow_passes() {
        LocalDateTime highWithinWindow = WINDOW_START.plusMinutes(30);
        AtmosphericData data = dataWithHighTideAt(highWithinWindow);
        Optional<com.gregochr.goldenhour.model.TriageResult> result =
                evaluator.evaluate(data, Set.of(TideType.HIGH), WINDOW_START, WINDOW_END);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("HIGH preference passes when nearestHighTideTime is exactly at window start")
    void evaluate_highPreference_tideAtWindowStart_passes() {
        AtmosphericData data = dataWithHighTideAt(WINDOW_START);
        Optional<com.gregochr.goldenhour.model.TriageResult> result =
                evaluator.evaluate(data, Set.of(TideType.HIGH), WINDOW_START, WINDOW_END);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("HIGH preference passes when nearestHighTideTime is exactly at window end")
    void evaluate_highPreference_tideAtWindowEnd_passes() {
        AtmosphericData data = dataWithHighTideAt(WINDOW_END);
        Optional<com.gregochr.goldenhour.model.TriageResult> result =
                evaluator.evaluate(data, Set.of(TideType.HIGH), WINDOW_START, WINDOW_END);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("HIGH preference triaged when nearestHighTideTime is before window")
    void evaluate_highPreference_tideBeforeWindow_triaged() {
        AtmosphericData data = dataWithHighTideAt(WINDOW_START.minusMinutes(1));
        Optional<com.gregochr.goldenhour.model.TriageResult> result =
                evaluator.evaluate(data, Set.of(TideType.HIGH), WINDOW_START, WINDOW_END);
        assertThat(result).isPresent();
        assertThat(result.get().rule()).isEqualTo(TriageRule.TIDE_MISALIGNED);
    }

    @Test
    @DisplayName("HIGH preference triaged when nearestHighTideTime is after window")
    void evaluate_highPreference_tideAfterWindow_triaged() {
        AtmosphericData data = dataWithHighTideAt(WINDOW_END.plusMinutes(1));
        Optional<com.gregochr.goldenhour.model.TriageResult> result =
                evaluator.evaluate(data, Set.of(TideType.HIGH), WINDOW_START, WINDOW_END);
        assertThat(result).isPresent();
        assertThat(result.get().rule()).isEqualTo(TriageRule.TIDE_MISALIGNED);
    }

    @Test
    @DisplayName("HIGH preference triaged when nearestHighTideTime is null")
    void evaluate_highPreference_noNearestHigh_triaged() {
        AtmosphericData data = dataWithNoNearestTides(TideState.LOW);
        Optional<com.gregochr.goldenhour.model.TriageResult> result =
                evaluator.evaluate(data, Set.of(TideType.HIGH), WINDOW_START, WINDOW_END);
        assertThat(result).isPresent();
        assertThat(result.get().rule()).isEqualTo(TriageRule.TIDE_MISALIGNED);
    }

    // -------------------------------------------------------------------------
    // LOW tide alignment
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("LOW preference passes when nearestLowTideTime is within window")
    void evaluate_lowPreference_tideWithinWindow_passes() {
        LocalDateTime lowWithinWindow = WINDOW_START.plusMinutes(45);
        AtmosphericData data = dataWithLowTideAt(lowWithinWindow);
        Optional<com.gregochr.goldenhour.model.TriageResult> result =
                evaluator.evaluate(data, Set.of(TideType.LOW), WINDOW_START, WINDOW_END);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("LOW preference triaged when nearestLowTideTime is outside window")
    void evaluate_lowPreference_tideOutsideWindow_triaged() {
        AtmosphericData data = dataWithLowTideAt(WINDOW_START.minusHours(3));
        Optional<com.gregochr.goldenhour.model.TriageResult> result =
                evaluator.evaluate(data, Set.of(TideType.LOW), WINDOW_START, WINDOW_END);
        assertThat(result).isPresent();
        assertThat(result.get().rule()).isEqualTo(TriageRule.TIDE_MISALIGNED);
    }

    // -------------------------------------------------------------------------
    // MID tide alignment
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("MID preference passes when tideState is MID")
    void evaluate_midPreference_tideStateMid_passes() {
        AtmosphericData data = dataWithNoNearestTides(TideState.MID);
        Optional<com.gregochr.goldenhour.model.TriageResult> result =
                evaluator.evaluate(data, Set.of(TideType.MID), WINDOW_START, WINDOW_END);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("MID preference triaged when tideState is HIGH")
    void evaluate_midPreference_tideStateHigh_triaged() {
        AtmosphericData data = dataWithNoNearestTides(TideState.HIGH);
        Optional<com.gregochr.goldenhour.model.TriageResult> result =
                evaluator.evaluate(data, Set.of(TideType.MID), WINDOW_START, WINDOW_END);
        assertThat(result).isPresent();
        assertThat(result.get().rule()).isEqualTo(TriageRule.TIDE_MISALIGNED);
    }

    // -------------------------------------------------------------------------
    // Multiple preferences (any-match)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Passes when one of multiple preferences is aligned (high within window, low outside)")
    void evaluate_multiplePreferences_oneAligned_passes() {
        LocalDateTime highWithinWindow = WINDOW_START.plusMinutes(20);
        AtmosphericData data = dataWithHighAndLowTides(highWithinWindow,
                WINDOW_START.minusHours(2));
        Optional<com.gregochr.goldenhour.model.TriageResult> result =
                evaluator.evaluate(data, Set.of(TideType.HIGH, TideType.LOW),
                        WINDOW_START, WINDOW_END);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Triaged when no preference is aligned (high and low both outside window)")
    void evaluate_multiplePreferences_noneAligned_triaged() {
        AtmosphericData data = dataWithHighAndLowTides(
                WINDOW_START.minusHours(3),
                WINDOW_START.minusHours(6));
        Optional<com.gregochr.goldenhour.model.TriageResult> result =
                evaluator.evaluate(data, Set.of(TideType.HIGH, TideType.LOW),
                        WINDOW_START, WINDOW_END);
        assertThat(result).isPresent();
        assertThat(result.get().rule()).isEqualTo(TriageRule.TIDE_MISALIGNED);
    }

    @Test
    @DisplayName("Triage result contains human-readable reason")
    void evaluate_triaged_reasonContainsExpectedText() {
        AtmosphericData data = dataWithHighTideAt(WINDOW_START.minusHours(3));
        Optional<com.gregochr.goldenhour.model.TriageResult> result =
                evaluator.evaluate(data, Set.of(TideType.HIGH), WINDOW_START, WINDOW_END);
        assertThat(result).isPresent();
        assertThat(result.get().reason()).containsIgnoringCase("tide");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private AtmosphericData dataWithHighTideAt(LocalDateTime highTime) {
        return TestAtmosphericData.builder()
                .tide(new TideSnapshot(TideState.MID, null, null, null, null,
                        false, highTime, null, null, null, null, null))
                .build();
    }

    private AtmosphericData dataWithLowTideAt(LocalDateTime lowTime) {
        return TestAtmosphericData.builder()
                .tide(new TideSnapshot(TideState.MID, null, null, null, null,
                        false, null, lowTime, null, null, null, null))
                .build();
    }

    private AtmosphericData dataWithNoNearestTides(TideState state) {
        return TestAtmosphericData.builder()
                .tide(new TideSnapshot(state, null, null, null, null,
                        false, null, null, null, null, null, null))
                .build();
    }

    private AtmosphericData dataWithHighAndLowTides(LocalDateTime highTime,
            LocalDateTime lowTime) {
        return TestAtmosphericData.builder()
                .tide(new TideSnapshot(TideState.MID, null, null, null, null,
                        false, highTime, lowTime, null, null, null, null))
                .build();
    }
}
