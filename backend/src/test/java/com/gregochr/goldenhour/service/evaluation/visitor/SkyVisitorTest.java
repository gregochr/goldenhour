package com.gregochr.goldenhour.service.evaluation.visitor;

import static org.assertj.core.api.Assertions.assertThat;

import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.model.SunsetEvaluation;

import java.util.OptionalInt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link SkyVisitor} — the sole v2.13.1 visitor.
 *
 * <p>Sky always applies, and its contribution is the rating the existing pipeline already
 * produced (re-expressed, not recomputed). The tests pin both: universal applicability and
 * faithful pass-through of the rating including the nullable case.
 */
class SkyVisitorTest {

    private final SkyVisitor visitor = new SkyVisitor();

    private static LocationEntity location(String name) {
        LocationEntity loc = new LocationEntity();
        loc.setName(name);
        return loc;
    }

    private static SunsetEvaluation evalWithRating(Integer rating) {
        return new SunsetEvaluation(rating, 70, 65, "summary");
    }

    @Test
    @DisplayName("appliesTo is true for any location (sky is universal)")
    void appliesTo_alwaysTrue() {
        assertThat(visitor.appliesTo(location("Hadrians Wall"))).isTrue();
        assertThat(visitor.appliesTo(location("Saltwick Bay"))).isTrue();
    }

    @ParameterizedTest(name = "rating {0} passes through unchanged")
    @ValueSource(ints = {1, 2, 3, 4, 5})
    @DisplayName("evaluate returns the evaluation's rating across the whole 1-5 range")
    void evaluate_returnsRating(int rating) {
        assertThat(visitor.evaluate(location("X"), evalWithRating(rating)))
                .isEqualTo(OptionalInt.of(rating));
    }

    @Test
    @DisplayName("evaluate returns empty when the rating is null (preserves today's null)")
    void evaluate_nullRating_returnsEmpty() {
        assertThat(visitor.evaluate(location("X"), evalWithRating(null)))
                .isEqualTo(OptionalInt.empty());
    }
}
