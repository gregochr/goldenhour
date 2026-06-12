package com.gregochr.goldenhour.service.evaluation.visitor;

import static org.assertj.core.api.Assertions.assertThat;

import com.gregochr.goldenhour.entity.ForecastType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LunarTideType;
import com.gregochr.goldenhour.entity.TideState;
import com.gregochr.goldenhour.entity.TideType;
import com.gregochr.goldenhour.model.SunsetEvaluation;
import com.gregochr.goldenhour.model.TideContext;
import com.gregochr.goldenhour.model.TideSnapshot;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link RatingCombiner}.
 *
 * <p>Three jobs are pinned here:
 * <ol>
 *   <li><b>v2.13.1 equivalence.</b> With the single real visitor ({@link SkyVisitor}) the
 *       combined rating equals the evaluation's own rating across the whole 1–5 range, and is
 *       {@code null} when the rating is absent — so wiring the combiner into the result handler
 *       does not change any persisted rating versus the pre-visitor pipeline. The inland and
 *       coastal cases differ only in that the coastal rating already folds in tide via the
 *       coastal prompt; the combiner treats them identically (sky-only today).</li>
 *   <li><b>Combiner mechanics</b> the next pass builds on — plain averaging, rounding, the
 *       empty case, and the applied-visitor set — exercised with lightweight stub visitors so
 *       the structure is covered before a second real visitor exists.</li>
 *   <li><b>Component exposure (Pass 2).</b> The combiner surfaces the per-visitor
 *       {@link ComponentScore}s it averaged — type, score, and authored clause — alongside the
 *       headline rating, so the dual-write can persist them. Sky-only inland exposes a single
 *       SKY component; coastal exposes SKY + TIDAL; an abstaining tide is absent from the
 *       components.</li>
 * </ol>
 */
class RatingCombinerTest {

    private static LocationEntity location(String name) {
        LocationEntity loc = new LocationEntity();
        loc.setName(name);
        return loc;
    }

    private static LocationEntity coastal(String name) {
        LocationEntity loc = location(name);
        loc.setTideType(Set.of(TideType.HIGH));
        return loc;
    }

    private static VisitorContext contextWithRating(Integer rating) {
        return new VisitorContext(new SunsetEvaluation(rating, 70, 65, "summary"), null);
    }

    private static TideContext alignedSpringTide() {
        TideSnapshot snapshot = new TideSnapshot(
                TideState.HIGH, null, null, null, null,
                true, null, null, LunarTideType.SPRING_TIDE, null, null, null);
        return new TideContext(snapshot, true);
    }

    /** Stub visitor with fixed applicability, score, and component type. */
    private static Visitor stub(boolean applies, Integer score, ForecastType type) {
        return new Visitor() {
            @Override
            public boolean appliesTo(LocationEntity loc) {
                return applies;
            }

            @Override
            public java.util.OptionalInt evaluate(LocationEntity loc, VisitorContext context) {
                return score != null ? java.util.OptionalInt.of(score)
                        : java.util.OptionalInt.empty();
            }

            @Override
            public ForecastType type() {
                return type;
            }
        };
    }

    /** Stub visitor defaulting to the SKY component type. */
    private static Visitor stub(boolean applies, Integer score) {
        return stub(applies, score, ForecastType.SKY);
    }

    // ── v2.13.1 equivalence (single real SkyVisitor) ─────────────────────────

    @ParameterizedTest(name = "inland rating {0} -> combined {0}")
    @ValueSource(ints = {1, 2, 3, 4, 5})
    @DisplayName("inland: combined rating equals the evaluation rating (sky only)")
    void inland_combinedEqualsRating(int rating) {
        RatingCombiner combiner = new RatingCombiner(List.of(new SkyVisitor()));
        assertThat(combiner.combine(location("Hadrians Wall"), contextWithRating(rating)).rating())
                .isEqualTo(rating);
    }

    @ParameterizedTest(name = "coastal rating {0} -> combined {0}")
    @ValueSource(ints = {1, 2, 3, 4, 5})
    @DisplayName("coastal: combined rating equals the (tide-aware) evaluation rating unchanged")
    void coastal_combinedEqualsRating(int rating) {
        // The coastal rating already folds in tide via CoastalPromptBuilder; the combiner does
        // not add a separate tide score in v2.13.1, so the rating passes through unchanged.
        RatingCombiner combiner = new RatingCombiner(List.of(new SkyVisitor()));
        assertThat(combiner.combine(location("Saltwick Bay"), contextWithRating(rating)).rating())
                .isEqualTo(rating);
    }

    @Test
    @DisplayName("null sky rating -> combined null + no components (preserves no-rating behaviour)")
    void nullRating_combinesToNull() {
        RatingCombiner combiner = new RatingCombiner(List.of(new SkyVisitor()));
        RatingCombiner.CombinedRating result =
                combiner.combine(location("X"), contextWithRating(null));
        assertThat(result.rating()).isNull();
        assertThat(result.components()).isEmpty();
    }

    // ── Boundary: no phantom tide on inland (reframed for v2.13.1) ────────────

    @Test
    @DisplayName("exactly one visitor (SkyVisitor) applies to an inland location today")
    void inland_exactlyOneVisitorApplies() {
        // v2.13.1 has only SkyVisitor; a TideVisitor joins in v2.13.2 and will NOT apply to
        // inland locations. Asserting the applied set is a singleton SkyVisitor documents that
        // an inland rating is computed from sky alone — never dragged by a phantom tide score.
        RatingCombiner combiner = new RatingCombiner(List.of(new SkyVisitor()));
        List<Visitor> applied = combiner.appliedVisitors(location("Hadrians Wall"));
        assertThat(applied).hasSize(1);
        assertThat(applied.get(0)).isInstanceOf(SkyVisitor.class);
    }

    // ── Combiner mechanics (stub visitors) ───────────────────────────────────

    @Test
    @DisplayName("plain average of two applied visitors (5 and 1 -> 3), no weakest-link")
    void average_twoVisitors_noWeakestLink() {
        RatingCombiner combiner = new RatingCombiner(
                List.of(stub(true, 5, ForecastType.SKY), stub(true, 1, ForecastType.TIDAL)));
        assertThat(combiner.combine(location("X"), contextWithRating(5)).rating()).isEqualTo(3);
    }

    @Test
    @DisplayName("average rounds half up (4 and 5 -> 4.5 -> 5)")
    void average_roundsHalfUp() {
        RatingCombiner combiner = new RatingCombiner(
                List.of(stub(true, 4, ForecastType.SKY), stub(true, 5, ForecastType.TIDAL)));
        assertThat(combiner.combine(location("X"), contextWithRating(4)).rating()).isEqualTo(5);
    }

    @Test
    @DisplayName("non-applicable visitors are excluded from the average")
    void average_excludesNonApplicable() {
        // Applicable 4, plus a non-applicable 1 that must NOT pull the mean down.
        RatingCombiner combiner = new RatingCombiner(
                List.of(stub(true, 4, ForecastType.SKY), stub(false, 1, ForecastType.TIDAL)));
        assertThat(combiner.combine(location("X"), contextWithRating(4)).rating()).isEqualTo(4);
    }

    @Test
    @DisplayName("visitors returning empty are excluded from the average")
    void average_excludesEmptyScores() {
        RatingCombiner combiner = new RatingCombiner(
                List.of(stub(true, 4, ForecastType.SKY), stub(true, null, ForecastType.TIDAL)));
        assertThat(combiner.combine(location("X"), contextWithRating(4)).rating()).isEqualTo(4);
    }

    @Test
    @DisplayName("empty case: no applicable visitor -> null, never a nonsense value")
    void emptyCase_noApplicableVisitor_returnsNull() {
        RatingCombiner combiner = new RatingCombiner(List.of(stub(false, 5)));
        assertThat(combiner.combine(location("X"), contextWithRating(5)).rating()).isNull();
    }

    // ── Component exposure (Pass 2) ──────────────────────────────────────────

    @Test
    @DisplayName("inland exposes a single SKY component carrying the sky score and Claude prose")
    void components_inland_skyOnly() {
        RatingCombiner combiner = new RatingCombiner(List.of(new SkyVisitor(), new TideVisitor()));
        VisitorContext context =
                new VisitorContext(new SunsetEvaluation(3, 70, 65, "Broken cloud over the fells"),
                        null);

        List<ComponentScore> components =
                combiner.combine(location("Keswick"), context).components();

        assertThat(components).singleElement().satisfies(component -> {
            assertThat(component.type()).isEqualTo(ForecastType.SKY);
            assertThat(component.score()).isEqualTo(3);
            assertThat(component.summary()).isEqualTo("Broken cloud over the fells");
        });
    }

    @Test
    @DisplayName("coastal exposes SKY + TIDAL components; rating is their half-up average")
    void components_coastal_skyAndTide() {
        RatingCombiner combiner = new RatingCombiner(List.of(new SkyVisitor(), new TideVisitor()));
        VisitorContext context = new VisitorContext(
                new SunsetEvaluation(3, 72, 68, "Clearing western sky"), alignedSpringTide());

        RatingCombiner.CombinedRating result =
                combiner.combine(coastal("Berwick-Upon-Tweed"), context);

        // Sky 3 + aligned spring tide 5 → avg 4.
        assertThat(result.rating()).isEqualTo(4);
        assertThat(result.components())
                .extracting(ComponentScore::type)
                .containsExactly(ForecastType.SKY, ForecastType.TIDAL);
        assertThat(result.components())
                .filteredOn(c -> c.type() == ForecastType.TIDAL)
                .singleElement()
                .satisfies(tidal -> {
                    assertThat(tidal.score()).isEqualTo(5);
                    assertThat(tidal.summary()).contains("Spring tide");
                });
    }

    @Test
    @DisplayName("coastal with abstaining tide (data gap) exposes SKY only — no TIDAL component")
    void components_coastal_tideAbstains() {
        RatingCombiner combiner = new RatingCombiner(List.of(new SkyVisitor(), new TideVisitor()));
        // Coastal location, but the tide could not be derived (null context) → TideVisitor abstains.
        VisitorContext context =
                new VisitorContext(new SunsetEvaluation(4, 60, 55, "Settled high pressure"), null);

        RatingCombiner.CombinedRating result =
                combiner.combine(coastal("St Marys Lighthouse"), context);

        assertThat(result.rating()).isEqualTo(4);
        assertThat(result.components())
                .extracting(ComponentScore::type)
                .containsExactly(ForecastType.SKY);
    }
}
