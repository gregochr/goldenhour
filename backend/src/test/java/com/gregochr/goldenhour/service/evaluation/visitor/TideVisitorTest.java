package com.gregochr.goldenhour.service.evaluation.visitor;

import com.gregochr.goldenhour.entity.ForecastType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.model.TideContext;
import com.gregochr.goldenhour.model.TideSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TideVisitor} — the rule R1 tide scorer (two concentric windows,
 * derivability-gated). Each scoring tier and the abstain case is boundary-tested.
 */
class TideVisitorTest {

    private final TideVisitor visitor = new TideVisitor();

    private static final SunsetEvaluation SKY = new SunsetEvaluation(3, 55, 60, "sky");

    private static LocationEntity location(Set<TideType> tideTypes) {
        LocationEntity loc = new LocationEntity();
        loc.setName("Test");
        loc.setTideType(tideTypes);
        return loc;
    }

    private static VisitorContext context(TideContext tide) {
        return new VisitorContext(SKY, tide);
    }

    private static TideContext tide(boolean tightAligned, boolean widenedAligned,
            LunarTideType lunar) {
        TideSnapshot snapshot = new TideSnapshot(
                TideState.HIGH, null, null, null, null,
                tightAligned, null, null, lunar, null, null, null);
        return new TideContext(snapshot, widenedAligned);
    }

    // ── appliesTo: tidal property of the LOCATION ────────────────────────────

    @Test
    @DisplayName("appliesTo true for a tidal location (non-empty tideType)")
    void appliesTo_tidalLocation_true() {
        assertThat(visitor.appliesTo(location(Set.of(TideType.HIGH)))).isTrue();
    }

    @Test
    @DisplayName("appliesTo false for an inland location (empty tideType)")
    void appliesTo_inlandLocation_false() {
        assertThat(visitor.appliesTo(location(Set.of()))).isFalse();
    }

    // ── Abstain: data gap, never a penalty ───────────────────────────────────

    @Test
    @DisplayName("un-derivable tide (null context) → empty (abstain, sky alone)")
    void evaluate_nullTideContext_abstains() {
        assertThat(visitor.evaluate(location(Set.of(TideType.HIGH)), context(null)))
                .isEqualTo(OptionalInt.empty());
    }

    @Test
    @DisplayName("un-derivable tide (null snapshot inside context) → empty (abstain)")
    void evaluate_nullSnapshot_abstains() {
        TideContext emptySnapshot = new TideContext(null, false);
        assertThat(visitor.evaluate(location(Set.of(TideType.HIGH)), context(emptySnapshot)))
                .isEqualTo(OptionalInt.empty());
    }

    // ── 5★ / 4★: tight window ────────────────────────────────────────────────

    @Test
    @DisplayName("king tide aligned within tight window → 5")
    void evaluate_kingTideTightAligned_fivePoints() {
        assertThat(visitor.evaluate(location(Set.of(TideType.HIGH)),
                context(tide(true, true, LunarTideType.KING_TIDE))))
                .isEqualTo(OptionalInt.of(5));
    }

    @Test
    @DisplayName("spring tide aligned within tight window → 5")
    void evaluate_springTideTightAligned_fivePoints() {
        assertThat(visitor.evaluate(location(Set.of(TideType.HIGH)),
                context(tide(true, true, LunarTideType.SPRING_TIDE))))
                .isEqualTo(OptionalInt.of(5));
    }

    @Test
    @DisplayName("regular tide aligned within tight window → 4")
    void evaluate_regularTideTightAligned_fourPoints() {
        assertThat(visitor.evaluate(location(Set.of(TideType.HIGH)),
                context(tide(true, true, LunarTideType.REGULAR_TIDE))))
                .isEqualTo(OptionalInt.of(4));
    }

    @Test
    @DisplayName("tight aligned with null lunar classification → 4 (not king/spring)")
    void evaluate_tightAlignedNullLunar_fourPoints() {
        assertThat(visitor.evaluate(location(Set.of(TideType.HIGH)),
                context(tide(true, true, null))))
                .isEqualTo(OptionalInt.of(4));
    }

    // ── 3★: widened window (just inside the +60 boundary) ────────────────────

    @Test
    @DisplayName("aligned only within widened window (not tight) → 3 — just inside the +60 edge")
    void evaluate_widenedAlignedNotTight_threePoints() {
        // tightAligned=false, widenedAligned=true: outside the tight window but inside tight+60.
        assertThat(visitor.evaluate(location(Set.of(TideType.HIGH)),
                context(tide(false, true, LunarTideType.SPRING_TIDE))))
                .isEqualTo(OptionalInt.of(3));
    }

    @Test
    @DisplayName("widened-window 3★ ignores king/spring (the boost is a tight-window reward only)")
    void evaluate_widenedAlignedKingTide_stillThreePoints() {
        assertThat(visitor.evaluate(location(Set.of(TideType.HIGH)),
                context(tide(false, true, LunarTideType.KING_TIDE))))
                .isEqualTo(OptionalInt.of(3));
    }

    // ── 1★: outside even the widened window (just outside the +60 boundary) ──

    @Test
    @DisplayName("outside even the widened window → 1 — just outside the +60 edge")
    void evaluate_outsideWidenedWindow_onePoint() {
        // tightAligned=false, widenedAligned=false: misaligned beyond tight+60.
        assertThat(visitor.evaluate(location(Set.of(TideType.HIGH)),
                context(tide(false, false, LunarTideType.SPRING_TIDE))))
                .isEqualTo(OptionalInt.of(1));
    }

    // ── type + summary clause (Pass 2 component exposure) ────────────────────

    @Test
    @DisplayName("type is TIDAL — the component its score is recorded under")
    void type_isTidal() {
        assertThat(visitor.type()).isEqualTo(ForecastType.TIDAL);
    }

    @Test
    @DisplayName("data gap → summary abstains (empty), exactly as the score does")
    void summary_dataGap_abstains() {
        assertThat(visitor.summary(location(Set.of(TideType.HIGH)), context(null))).isEmpty();
    }

    @Test
    @DisplayName("king-tide aligned summary names the king tide (score 5 state)")
    void summary_kingTideAligned_namesKing() {
        assertThat(visitor.summary(location(Set.of(TideType.HIGH)),
                context(tide(true, true, LunarTideType.KING_TIDE))))
                .get().asString().contains("King tide");
    }

    @Test
    @DisplayName("spring-tide aligned summary names the spring tide (score 5 state)")
    void summary_springTideAligned_namesSpring() {
        assertThat(visitor.summary(location(Set.of(TideType.HIGH)),
                context(tide(true, true, LunarTideType.SPRING_TIDE))))
                .get().asString().contains("Spring tide");
    }

    @Test
    @DisplayName("regular aligned summary reads as a well-timed tide (score 4 state)")
    void summary_regularAligned_alignsWell() {
        assertThat(visitor.summary(location(Set.of(TideType.HIGH)),
                context(tide(true, true, LunarTideType.REGULAR_TIDE))))
                .get().asString().contains("aligns well");
    }

    @Test
    @DisplayName("widened-window summary reads as workable but not ideal (score 3 state)")
    void summary_widenedAligned_workable() {
        assertThat(visitor.summary(location(Set.of(TideType.HIGH)),
                context(tide(false, true, LunarTideType.REGULAR_TIDE))))
                .get().asString().contains("workable but not ideal");
    }

    @Test
    @DisplayName("misaligned summary reads as the tide working against the slot (score 1 state)")
    void summary_misaligned_worksAgainst() {
        assertThat(visitor.summary(location(Set.of(TideType.HIGH)),
                context(tide(false, false, LunarTideType.REGULAR_TIDE))))
                .get().asString().contains("works against this slot");
    }
}
