package com.gregochr.goldenhour.service.evaluation.visitor;

import static org.assertj.core.api.Assertions.assertThat;

import com.gregochr.goldenhour.entity.BluebellExposure;
import com.gregochr.goldenhour.entity.ForecastType;
import com.gregochr.goldenhour.entity.LocationEntity;
import com.gregochr.goldenhour.entity.LocationType;
import com.gregochr.goldenhour.model.BluebellEvaluation;
import com.gregochr.goldenhour.model.WoodlandEvaluation;

import java.util.OptionalInt;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WoodlandVisitor} — the year-round canopy scorer.
 *
 * <p>The case worth pinning hardest is the overlap with {@link BluebellVisitor}. In production
 * every canopy site is ALSO typed {@code BLUEBELL} with an exposure, so both visitors apply to
 * the same locations; what keeps them apart is that each abstains unless <em>its own</em> slice of
 * the context is populated, and the collector guarantees only one ever is.
 */
class WoodlandVisitorTest {

    private final WoodlandVisitor visitor = new WoodlandVisitor();
    private final BluebellVisitor bluebellVisitor = new BluebellVisitor();

    private static LocationEntity location(Set<LocationType> types) {
        LocationEntity loc = new LocationEntity();
        loc.setName("Houghall Woods");
        loc.setLocationType(types);
        loc.setBluebellExposure(BluebellExposure.WOODLAND);
        return loc;
    }

    /** The production shape: WOODLAND + BLUEBELL, no sky type. All 15 real woods look like this. */
    private static LocationEntity canopySite() {
        return location(Set.of(LocationType.WOODLAND, LocationType.BLUEBELL));
    }

    private static VisitorContext woodlandContext(WoodlandEvaluation woodland) {
        return new VisitorContext(null, null, null, woodland);
    }

    // ── appliesTo ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("appliesTo true for a canopy site with no sky type")
    void appliesTo_canopySite_true() {
        assertThat(visitor.appliesTo(canopySite())).isTrue();
    }

    @Test
    @DisplayName("appliesTo false for a wood that also has an open aspect")
    void appliesTo_woodWithAspect_false() {
        // A wood with a horizon still has a sky worth scoring; its rating must not be decided by
        // the canopy alone.
        assertThat(visitor.appliesTo(
                location(Set.of(LocationType.WOODLAND, LocationType.LANDSCAPE)))).isFalse();
    }

    @Test
    @DisplayName("appliesTo false for a location with no woodland type at all")
    void appliesTo_nonWoodland_false() {
        assertThat(visitor.appliesTo(location(Set.of(LocationType.LANDSCAPE)))).isFalse();
    }

    // ── evaluate ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("evaluate returns the woodland rating when one is present")
    void evaluate_withRating_returnsIt() {
        assertThat(visitor.evaluate(canopySite(),
                woodlandContext(new WoodlandEvaluation(4, "Soft even light.", null))))
                .isEqualTo(OptionalInt.of(4));
    }

    @Test
    @DisplayName("evaluate abstains when there is no woodland evaluation")
    void evaluate_noEvaluation_abstains() {
        // In bluebell season the same location is scored by the bluebell prompt, so this slice is
        // null. Abstaining is not a penalty — a zero here would drag the rating down.
        assertThat(visitor.evaluate(canopySite(), woodlandContext(null)))
                .isEqualTo(OptionalInt.empty());
    }

    @Test
    @DisplayName("evaluate abstains when the response omitted the rating")
    void evaluate_nullRating_abstains() {
        assertThat(visitor.evaluate(canopySite(),
                woodlandContext(new WoodlandEvaluation(null, "summary only", null))))
                .isEqualTo(OptionalInt.empty());
    }

    // ── the overlap that matters ─────────────────────────────────────────────

    @Test
    @DisplayName("only ONE of the two canopy visitors scores a given slot")
    void woodlandAndBluebell_neverBothScoreTheSameSlot() {
        LocationEntity loc = canopySite();
        // Both visitors APPLY to this location — that is the trap.
        assertThat(visitor.appliesTo(loc)).isTrue();
        assertThat(bluebellVisitor.appliesTo(loc)).isTrue();

        // Out of season: woodland slice populated, bluebell slice null.
        VisitorContext outOfSeason = new VisitorContext(
                null, null, null, new WoodlandEvaluation(4, "Mist.", null));
        assertThat(visitor.evaluate(loc, outOfSeason)).isEqualTo(OptionalInt.of(4));
        assertThat(bluebellVisitor.evaluate(loc, outOfSeason)).isEqualTo(OptionalInt.empty());

        // In season: bluebell slice populated, woodland slice null.
        VisitorContext inSeason = new VisitorContext(
                null, null, new BluebellEvaluation(5, "Carpet.", null), null);
        assertThat(bluebellVisitor.evaluate(loc, inSeason)).isEqualTo(OptionalInt.of(5));
        assertThat(visitor.evaluate(loc, inSeason)).isEqualTo(OptionalInt.empty());
    }

    @Test
    @DisplayName("a woodland result is recorded under WOODLAND, not BLUEBELL")
    void type_isWoodland() {
        // Provenance, not arithmetic: a woodland score filed as BLUEBELL would read correctly and
        // make the two subjects indistinguishable in forecast_score — the exact conflation V132
        // and #347 undid.
        assertThat(visitor.type()).isEqualTo(ForecastType.WOODLAND);
        assertThat(visitor.type()).isNotEqualTo(bluebellVisitor.type());
    }

    // ── summary ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("summary re-exposes the woodland prose as the component clause")
    void summary_returnsWoodlandSummary() {
        assertThat(visitor.summary(canopySite(),
                woodlandContext(new WoodlandEvaluation(4, "Mist through the trunks.", null))))
                .contains("Mist through the trunks.");
    }

    @Test
    @DisplayName("summary empty when there is no woodland evaluation")
    void summary_noEvaluation_empty() {
        assertThat(visitor.summary(canopySite(), woodlandContext(null))).isEmpty();
    }
}
