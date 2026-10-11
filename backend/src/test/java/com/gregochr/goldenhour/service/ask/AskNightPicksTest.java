package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.service.ask.AskAnswerValidator.Raw;
import com.gregochr.goldenhour.service.ask.AskAnswerValidator.RawPick;
import com.gregochr.goldenhour.service.ask.AskSnapshot.AuroraState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.gregochr.goldenhour.service.ask.AskNightFixtures.TONIGHT;
import static com.gregochr.goldenhour.service.ask.AskNightFixtures.astro;
import static com.gregochr.goldenhour.service.ask.AskNightFixtures.astroNight;
import static com.gregochr.goldenhour.service.ask.AskNightFixtures.aurora;
import static com.gregochr.goldenhour.service.ask.AskNightFixtures.auroraNight;
import static com.gregochr.goldenhour.service.ask.AskNightFixtures.canopy;
import static com.gregochr.goldenhour.service.ask.AskNightFixtures.nightsOnly;
import static com.gregochr.goldenhour.service.ask.AskNightFixtures.triaged;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Night picks under the validator, the snapshot's eligibility rule, the thread re-join and the Ready
 * freshness re-check (ask-night plan §2.4): ASTRO at 4 stars, AURORA at 3 and only for an asker who may see
 * aurora, never a wood, never a triaged aurora row.
 */
class AskNightPicksTest {

    private static final String ASTRO = "2026-10-05_astro";
    private static final String AURORA = "2026-10-05_aurora";

    private final AskAnswerValidator validator = new AskAnswerValidator();

    private static AskSnapshot snapshot() {
        return nightsOnly(new AuroraState("MODERATE", 6.0),
                astroNight(TONIGHT, astro(1L, "Whitby", "Coast", 5, 3), astro(2L, "Bamburgh", "Coast", 4, 3),
                        astro(3L, "Seahouses", "Coast", 3, 4), canopy(astro(4L, "Hollin Wood", "Dales", 5, 3))),
                auroraNight(TONIGHT, aurora(1L, "Whitby", "Coast", 5, 3, 3), aurora(2L, "Bamburgh", "Coast", 4, 2, 3),
                        triaged(aurora(5L, "Kielder", "Dales", null, 4, 2)),
                        canopy(aurora(4L, "Hollin Wood", "Dales", 5, 5, 3))));
    }

    private AskAnswerValidator.Result validate(AskSnapshot snapshot, boolean canSeeAurora, RawPick... picks) {
        Set<AskEvidence.Pair> offered = new java.util.HashSet<>();
        for (RawPick pick : picks) {
            offered.add(new AskEvidence.Pair(pick.locationId(), pick.windowId()));
        }
        return validator.validate(new Raw(true, "Tonight.", List.of(picks), List.of(), null), snapshot,
                new AskEvidence(offered, Set.of(), 1), AskScope.ALL, null, null, canSeeAurora);
    }

    // -- eligibility ------------------------------------------------------------------------

    @Test
    @DisplayName("astro needs 4 stars: the template's base is 3, which is 'nothing moved it'")
    void astroBarIsFour() {
        assertThat(AskSnapshot.isNightPickEligible(AskWindowKind.ASTRO, astro(1L, "A", "Coast", 4, 3), false)).isTrue();
        assertThat(AskSnapshot.isNightPickEligible(AskWindowKind.ASTRO, astro(1L, "A", "Coast", 3, 3), false))
                .isFalse();
        assertThat(AskSnapshot.isNightPickEligible(AskWindowKind.ASTRO, astro(1L, "A", "Coast", 491, 3), false))
                .isFalse();
    }

    @Test
    @DisplayName("aurora needs 3 stars, the aurora figure, an asker who may see it, and a Claude score")
    void auroraBarIsThreeAndRoleGated() {
        AskSnapshot.NightSpot three = aurora(1L, "A", "Coast", null, 3, 3);

        assertThat(AskSnapshot.isNightPickEligible(AskWindowKind.AURORA, three, true)).isTrue();
        assertThat(AskSnapshot.isNightPickEligible(AskWindowKind.AURORA, three, false)).isFalse();
        assertThat(AskSnapshot.isNightPickEligible(AskWindowKind.AURORA, aurora(1L, "A", "Coast", null, 2, 3), true))
                .isFalse();
        assertThat(AskSnapshot.isNightPickEligible(AskWindowKind.AURORA, triaged(three), true)).isFalse();
        assertThat(AskSnapshot.isNightPickEligible(AskWindowKind.AURORA, astro(1L, "A", "Coast", 5, 3), true))
                .isFalse();
    }

    @Test
    @DisplayName("a wood is never a night pick, and a solar kind is not a night kind")
    void woodAndSolarKind() {
        assertThat(AskSnapshot.isNightPickEligible(AskWindowKind.ASTRO, canopy(astro(1L, "W", "Dales", 5, 3)), true))
                .isFalse();
        assertThat(AskSnapshot.isNightPickEligible(AskWindowKind.SUNSET, astro(1L, "A", "Coast", 5, 3), true))
                .isFalse();
        assertThat(AskSnapshot.nightRating(AskWindowKind.SUNRISE, astro(1L, "A", "Coast", 5, 3))).isNull();
    }

    // -- the validator ----------------------------------------------------------------------

    @Test
    @DisplayName("an offered astro pick is served with the kind as its targetType, the eligible figure as its "
            + "rating, no verdict, and the facts joined from the snapshot")
    void validator_servesAnAstroPick() {
        AskAnswerValidator.Result result = validate(snapshot(), false, new RawPick(1L, ASTRO, "Clear sky, Bortle 3."));

        assertThat(result.accepted()).isTrue();
        AskPick pick = result.answer().picks().getFirst();
        assertThat(pick.targetType()).isEqualTo(AskWindowKind.ASTRO);
        assertThat(pick.windowId()).isEqualTo(ASTRO);
        assertThat(pick.locationName()).isEqualTo("Whitby");
        assertThat(pick.regionName()).isEqualTo("Coast");
        assertThat(pick.date()).isEqualTo(TONIGHT);
        assertThat(pick.ratingAtAnswer()).isEqualTo(5);
        assertThat(pick.verdictAtAnswer()).isNull();
    }

    @Test
    @DisplayName("an aurora pick is served to a PRO asker with the aurora figure as its rating")
    void validator_servesAnAuroraPickToPro() {
        AskAnswerValidator.Result result = validate(snapshot(), true, new RawPick(1L, AURORA, "Kp 6."));

        assertThat(result.answer().picks()).singleElement().satisfies(pick -> {
            assertThat(pick.targetType()).isEqualTo(AskWindowKind.AURORA);
            assertThat(pick.ratingAtAnswer()).isEqualTo(3);
        });
    }

    @Test
    @DisplayName("a forged aurora pick for a LITE asker is dropped, though the tool-evidence names it")
    void validator_dropsAnAuroraPickForLite() {
        AskAnswerValidator.Result result = validate(snapshot(), false, new RawPick(1L, AURORA, "x"));

        assertThat(result.answer().picks()).isEmpty();
    }

    @Test
    @DisplayName("a 3-star astro place, a wood, a triaged aurora row and an unknown place are dropped")
    void validator_dropsTheIneligible() {
        AskAnswerValidator.Result result = validate(snapshot(), true, new RawPick(3L, ASTRO, "3 stars"),
                new RawPick(4L, ASTRO, "wood"), new RawPick(5L, AURORA, "triaged"), new RawPick(99L, ASTRO, "gone"),
                new RawPick(2L, ASTRO, "ok"));

        assertThat(result.answer().picks()).extracting(AskPick::locationName).containsExactly("Bamburgh");
        assertThat(result.answer().picks().getFirst().rank()).isEqualTo(1);
    }

    @Test
    @DisplayName("a pick the conversation was never offered is dropped, night or not")
    void validator_needsEvidence() {
        AskAnswerValidator.Result result = validator.validate(
                new Raw(true, "Tonight.", List.of(new RawPick(1L, ASTRO, "x")), List.of(), null), snapshot(),
                new AskEvidence(Set.of(), Set.of(), 1), AskScope.ALL, null, null, true);

        assertThat(result.answer().picks()).isEmpty();
    }

    @Test
    @DisplayName("a night and a sunrise at the same place are two windows; a fresh answer still takes one place once")
    void validator_distinctLocations() {
        AskAnswerValidator.Result result = validate(snapshot(), true, new RawPick(1L, ASTRO, "sky"),
                new RawPick(1L, AURORA, "lights"), new RawPick(2L, ASTRO, "sky"));

        assertThat(result.answer().picks()).extracting(AskPick::locationName).containsExactly("Whitby", "Bamburgh");
    }

    @Test
    @DisplayName("a night pick outside the question's scope is dropped")
    void validator_scope() {
        AskAnswerValidator.Result result = validator.validate(
                new Raw(true, "Tonight.", List.of(new RawPick(1L, ASTRO, "x")), List.of(), null), snapshot(),
                new AskEvidence(Set.of(new AskEvidence.Pair(1L, ASTRO)), Set.of(), 1), TestScopes.of("Dales"), null,
                null, true);

        assertThat(result.answer().picks()).isEmpty();
    }

    // -- resolution and the freshness re-check ------------------------------------------------

    @Test
    @DisplayName("resolvePick reads the live night figure under the matching rule, and a solar id is unchanged")
    void resolvePick() {
        AskSnapshot snapshot = snapshot();

        assertThat(snapshot.resolvePick(ASTRO, 1L, false)).get().satisfies(pick -> {
            assertThat(pick.kind()).isEqualTo(AskWindowKind.ASTRO);
            assertThat(pick.rating()).isEqualTo(5);
            assertThat(pick.verdict()).isNull();
        });
        assertThat(snapshot.resolvePick(AURORA, 1L, false)).isEmpty();
        assertThat(snapshot.resolvePick(AURORA, 1L, true)).isPresent();
        assertThat(snapshot.resolvePick("2026-10-05_sunset", 1L, true)).isEmpty();
        assertThat(snapshot.hasWindow(ASTRO)).isTrue();
        assertThat(snapshot.hasWindow("2026-10-09_astro")).isFalse();
        assertThat(snapshot.nightSlotAt(ASTRO, 3L)).isPresent();
        assertThat(snapshot.nightCandidate(ASTRO, 3L, true)).isEmpty();
    }

    private static AskAnswer storedAnswer(String windowId, long locationId, int rating) {
        return new AskAnswer(true, "Tonight.", List.of(new AskPick(1, locationId, "Whitby", "Coast", TONIGHT,
                windowId.endsWith("astro") ? AskWindowKind.ASTRO : AskWindowKind.AURORA, windowId, "Clear.",
                rating, null)), List.of(), null);
    }

    @Test
    @DisplayName("the freshness re-check withholds a night answer whose star moved, whose place is no longer "
            + "eligible or whose night has gone; a fresh one is re-decorated")
    void freshness_nightPicks() {
        AskSnapshot snapshot = snapshot();

        assertThat(AskReadyFreshness.recheck(storedAnswer(ASTRO, 1L, 5), snapshot, AskScope.ALL, false).fresh())
                .isTrue();
        assertThat(AskReadyFreshness.recheck(storedAnswer(ASTRO, 1L, 4), snapshot, AskScope.ALL, false).reason())
                .contains("rating changed");
        assertThat(AskReadyFreshness.recheck(storedAnswer(ASTRO, 3L, 3), snapshot, AskScope.ALL, false).reason())
                .contains("no longer pick-eligible");
        assertThat(AskReadyFreshness.recheck(storedAnswer("2026-10-04_astro", 1L, 5), snapshot, AskScope.ALL,
                false).reason()).contains("window has passed");
    }

    @Test
    @DisplayName("an aurora pick is re-served only to an asker who may see aurora: never in a Ready answer")
    void freshness_auroraNeedsAccess() {
        AskSnapshot snapshot = snapshot();

        assertThat(AskReadyFreshness.recheck(storedAnswer(AURORA, 1L, 3), snapshot, AskScope.ALL, true).fresh())
                .isTrue();
        assertThat(AskReadyFreshness.recheck(storedAnswer(AURORA, 1L, 3), snapshot, AskScope.ALL, false).reason())
                .contains("no longer pick-eligible");
    }
}
