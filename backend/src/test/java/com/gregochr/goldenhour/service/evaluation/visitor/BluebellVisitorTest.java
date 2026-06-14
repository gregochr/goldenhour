package com.gregochr.goldenhour.service.evaluation.visitor;

import static org.assertj.core.api.Assertions.assertThat;

import com.gregochr.goldenhour.entity.BluebellExposure;
import com.gregochr.goldenhour.entity.ForecastType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.model.BluebellEvaluation;
import com.gregochr.goldenhour.model.SunsetEvaluation;

import java.util.OptionalInt;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BluebellVisitor} — the Pass 3 bluebell scorer. Applicability is a
 * location property (bluebell site with an exposure); the season gate is the data-gap abstain in
 * {@link BluebellVisitor#evaluate} (no bluebell evaluation present → abstain, never a penalty).
 */
class BluebellVisitorTest {

    private final BluebellVisitor visitor = new BluebellVisitor();

    private static final SunsetEvaluation SKY = new SunsetEvaluation(3, 55, 60, "sky");

    private static LocationEntity location(Set<LocationType> types, BluebellExposure exposure) {
        LocationEntity loc = new LocationEntity();
        loc.setName("Test");
        loc.setLocationType(types);
        loc.setBluebellExposure(exposure);
        return loc;
    }

    private static VisitorContext context(BluebellEvaluation bluebell) {
        return new VisitorContext(SKY, null, bluebell);
    }

    // ── appliesTo: bluebell site with an exposure ────────────────────────────

    @Test
    @DisplayName("appliesTo true for a bluebell site with an exposure set")
    void appliesTo_bluebellSiteWithExposure_true() {
        assertThat(visitor.appliesTo(
                location(Set.of(LocationType.LANDSCAPE, LocationType.BLUEBELL),
                        BluebellExposure.WOODLAND))).isTrue();
    }

    @Test
    @DisplayName("appliesTo false for a non-bluebell location")
    void appliesTo_nonBluebell_false() {
        assertThat(visitor.appliesTo(
                location(Set.of(LocationType.LANDSCAPE), BluebellExposure.WOODLAND))).isFalse();
    }

    @Test
    @DisplayName("appliesTo false for a bluebell-typed location with no exposure set")
    void appliesTo_bluebellNoExposure_false() {
        assertThat(visitor.appliesTo(
                location(Set.of(LocationType.BLUEBELL), null))).isFalse();
    }

    // ── evaluate: season gate via the data-gap abstain ───────────────────────

    @Test
    @DisplayName("null bluebell evaluation (out of season) → abstain")
    void evaluate_nullBluebell_abstains() {
        assertThat(visitor.evaluate(
                location(Set.of(LocationType.BLUEBELL), BluebellExposure.WOODLAND), context(null)))
                .isEqualTo(OptionalInt.empty());
    }

    @Test
    @DisplayName("bluebell evaluation with a null rating → abstain")
    void evaluate_nullRating_abstains() {
        assertThat(visitor.evaluate(
                location(Set.of(LocationType.BLUEBELL), BluebellExposure.WOODLAND),
                context(new BluebellEvaluation(null, "no rating", null))))
                .isEqualTo(OptionalInt.empty());
    }

    @Test
    @DisplayName("evaluate returns the bluebell rating across the 1-5 range")
    void evaluate_returnsRating() {
        for (int rating = 1; rating <= 5; rating++) {
            assertThat(visitor.evaluate(
                    location(Set.of(LocationType.BLUEBELL), BluebellExposure.OPEN_FELL),
                    context(new BluebellEvaluation(rating, "summary", null))))
                    .isEqualTo(OptionalInt.of(rating));
        }
    }

    // ── type + summary ───────────────────────────────────────────────────────

    @Test
    @DisplayName("type is BLUEBELL")
    void type_isBluebell() {
        assertThat(visitor.type()).isEqualTo(ForecastType.BLUEBELL);
    }

    @Test
    @DisplayName("summary re-exposes the bluebell prose for the component row")
    void summary_returnsBluebellProse() {
        assertThat(visitor.summary(
                location(Set.of(LocationType.BLUEBELL), BluebellExposure.WOODLAND),
                context(new BluebellEvaluation(5, "Mist and low sun if they're in flower.", null))))
                .contains("Mist and low sun if they're in flower.");
    }
}
