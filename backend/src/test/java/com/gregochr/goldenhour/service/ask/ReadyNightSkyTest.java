package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.service.ask.AskSnapshot.AuroraState;
import com.gregochr.goldenhour.service.ask.AskSnapshot.Night;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.gregochr.goldenhour.service.ask.AskNightFixtures.GENERATION;
import static com.gregochr.goldenhour.service.ask.AskNightFixtures.TOMORROW_NIGHT;
import static com.gregochr.goldenhour.service.ask.AskNightFixtures.TONIGHT;
import static com.gregochr.goldenhour.service.ask.AskNightFixtures.astro;
import static com.gregochr.goldenhour.service.ask.AskNightFixtures.astroNight;
import static com.gregochr.goldenhour.service.ask.AskNightFixtures.aurora;
import static com.gregochr.goldenhour.service.ask.AskNightFixtures.auroraNight;
import static com.gregochr.goldenhour.service.ask.AskNightFixtures.canopy;
import static com.gregochr.goldenhour.service.ask.AskNightFixtures.nightsOnly;
import static com.gregochr.goldenhour.service.ask.AskNightFixtures.withNights;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Ready {@code NIGHT_SKY} question (ask-night plan section 2.5, N2): when it is offered, what it may carry, and
 * every way a stored answer to it is withheld at serve time. Over the real snapshot types, the real eligibility
 * rule and the real freshness check; only the night fixtures are hand-built.
 */
class ReadyNightSkyTest {

    private static final AskScope ALL = AskScope.ALL;
    private static final String ASTRO_ID = "2026-10-05_astro";
    private static final String AURORA_ID = "2026-10-05_aurora";
    private static final LocalDateTime BUILT = LocalDateTime.of(2026, 10, 5, 5, 2, 11);
    private static final String TEXT = "Anything for the night sky tonight?";

    /** Tonight's astro night: a 5 star and a 4 star on the coast, a 3 star (not eligible) and a wood. */
    private static Night coastNight() {
        return astroNight(TONIGHT, astro(1L, "Whitby", "Coast", 5, 3), astro(2L, "Bamburgh", "Coast", 4, 3),
                astro(3L, "Seahouses", "Coast", 3, 4), canopy(astro(4L, "Hollin Wood", "Dales", 5, 3)));
    }

    private static AskSnapshot snapshot(Night... nights) {
        return nightsOnly(new AuroraState("MODERATE", 6.0), nights);
    }

    private static Optional<ReadyQuestion.Offer> offer(AskSnapshot snapshot, AskScope scope) {
        return ReadyQuestion.NIGHT_SKY.offer(snapshot, scope);
    }

    /** A night pick: the eligible figure as the rating, no verdict word (a night has none). */
    private static AskPick nightPick(int rank, long id, String name, String region, String windowId, int rating) {
        AskWindowId.Parts parts = AskWindowId.parse(windowId).orElseThrow();
        return new AskPick(rank, id, name, region, parts.date(), parts.kind(), windowId, "Clear and dark.", rating,
                null);
    }

    private static AskAnswer answer(AskPick... picks) {
        return new AskAnswer(true, "Whitby is the one.", List.of(picks), List.of(), null);
    }

    private static AskAnswer answerWith(List<AskPick> picks, AskEvent... events) {
        return new AskAnswer(true, "Whitby is the one.", picks, List.of(events), null);
    }

    private static ReadyQuestion.Offer tonightOffer() {
        return new ReadyQuestion.Offer(TEXT, List.of(ASTRO_ID), null);
    }

    // -- the catalogue entry ------------------------------------------------------------------

    @Test
    @DisplayName("NIGHT_SKY is a pick question on the Map and Plan tabs, night-bound, not anchored to a BEST BET")
    void identity() {
        assertThat(ReadyQuestion.NIGHT_SKY.picks()).isTrue();
        assertThat(ReadyQuestion.NIGHT_SKY.anchored()).isFalse();
        assertThat(ReadyQuestion.NIGHT_SKY.nightBound()).isTrue();
        assertThat(ReadyQuestion.NIGHT_SKY.tabs()).containsExactly("plan", "map");
        assertThat(ReadyQuestion.NIGHT_SKY.anchor(tonightOffer())).isNull();
        assertThat(java.util.Arrays.stream(ReadyQuestion.values()).filter(ReadyQuestion::nightBound))
                .containsExactly(ReadyQuestion.NIGHT_SKY);
    }

    @Test
    @DisplayName("the question text is pinned: a changed word would silently stop matching every stored row and "
            + "every typed phrasing")
    void textIsPinned() {
        assertThat(ReadyQuestion.NIGHT_SKY_TEXT).isEqualTo("Anything for the night sky tonight?");
    }

    // -- availability -------------------------------------------------------------------------

    @Test
    @DisplayName("offered when tonight's ASTRO night holds a 4 star spot in scope, about that night alone")
    void offeredWithAnEligibleSpot() {
        assertThat(offer(snapshot(coastNight()), ALL)).hasValueSatisfying(o -> {
            assertThat(o.text()).isEqualTo(TEXT);
            assertThat(o.windowIds()).containsExactly(ASTRO_ID);
            assertThat(o.contextWindowId()).isNull();
        });
    }

    @Test
    @DisplayName("the bar is 4 stars, not 3: a night whose best spot is the template's base 3 is not offered")
    void threeStarsIsNotEnough() {
        AskSnapshot threes = snapshot(astroNight(TONIGHT, astro(1L, "Whitby", "Coast", 3, 3),
                astro(2L, "Bamburgh", "Coast", 3, 3)));
        AskSnapshot four = snapshot(astroNight(TONIGHT, astro(1L, "Whitby", "Coast", 3, 3),
                astro(2L, "Bamburgh", "Coast", 4, 3)));

        assertThat(offer(threes, ALL)).isEmpty();
        assertThat(offer(four, ALL)).isPresent();
    }

    @Test
    @DisplayName("not offered when the snapshot carries no nights at all")
    void noNights() {
        assertThat(offer(snapshot(), ALL)).isEmpty();
        assertThat(offer(AskFixtures.snapshotOf(AskFixtures.briefing(List.of(), List.of())), ALL)).isEmpty();
    }

    @Test
    @DisplayName("not offered when only an AURORA night exists: rows are not the question, an astro one is, "
            + "and a Ready answer never carries an aurora figure")
    void auroraOnlyNightIsNotOffered() {
        AskSnapshot onlyAurora = snapshot(auroraNight(TONIGHT, aurora(1L, "Whitby", "Coast", null, 5, 3)));

        assertThat(offer(onlyAurora, ALL)).isEmpty();
    }

    @Test
    @DisplayName("not offered when a place is 5 stars for aurora but only 3 for astro: the astro night decides")
    void auroraFiguresDoNotMakeItAvailable() {
        AskSnapshot snapshot = snapshot(astroNight(TONIGHT, astro(1L, "Whitby", "Coast", 3, 3)),
                auroraNight(TONIGHT, aurora(1L, "Whitby", "Coast", 3, 5, 3)));

        assertThat(offer(snapshot, ALL)).isEmpty();
    }

    @Test
    @DisplayName("not offered when only TOMORROW night holds the eligible spot: the question is about tonight")
    void tomorrowNightIsNotTonight() {
        AskSnapshot snapshot = snapshot(astroNight(TOMORROW_NIGHT, astro(1L, "Whitby", "Coast", 5, 3)));

        assertThat(offer(snapshot, ALL)).isEmpty();
    }

    @Test
    @DisplayName("not offered when every eligible spot is a wood: a wood's star means the opposite of a sky star")
    void woodsDoNotCount() {
        AskSnapshot snapshot = snapshot(astroNight(TONIGHT, canopy(astro(4L, "Hollin Wood", "Dales", 5, 3))));

        assertThat(offer(snapshot, ALL)).isEmpty();
    }

    @Test
    @DisplayName("a region scope is part of the test: a night only another region can answer is not this region's")
    void scopeNarrowsAvailability() {
        AskSnapshot snapshot = snapshot(astroNight(TONIGHT, astro(1L, "Whitby", "Coast", 5, 3),
                astro(3L, "Seahouses", "Dales", 3, 4)));

        assertThat(offer(snapshot, TestScopes.of("Coast"))).isPresent();
        assertThat(offer(snapshot, TestScopes.of("Dales"))).isEmpty();
        assertThat(offer(snapshot, TestScopes.of("coast"))).isPresent();
        assertThat(offer(snapshot, ALL)).isPresent();
    }

    // -- the version --------------------------------------------------------------------------

    @Test
    @DisplayName("the version is the AskNightVersion of the night the offer names: its date and astro generation, "
            + "no aurora segment, the very string a typed answer would carry for a user-less reader")
    void versionIsTheNightVersionWithoutAurora() {
        AskSnapshot snapshot = snapshot(coastNight());

        Optional<String> version = ReadyQuestion.NIGHT_SKY.nightVersion(snapshot, tonightOffer());

        assertThat(version).contains("n1;2026-10-05=" + GENERATION.toEpochMilli());
        assertThat(version.orElseThrow()).doesNotContain(";a=");
        assertThat(version).isEqualTo(AskNightVersion.of(snapshot, List.of(TONIGHT), false));
        assertThat(AskNightVersion.isLive(version.orElseThrow(), snapshot, true)).isTrue();
    }

    @Test
    @DisplayName("a question that is not night-bound, and an offer naming no window, have no version")
    void noVersionOtherwise() {
        AskSnapshot snapshot = snapshot(coastNight());

        assertThat(ReadyQuestion.BEST_NEXT.nightVersion(snapshot,
                new ReadyQuestion.Offer("Best spot tonight?", List.of(ASTRO_ID), null))).isEmpty();
        assertThat(ReadyQuestion.NIGHT_SKY.nightVersion(snapshot,
                new ReadyQuestion.Offer(TEXT, List.of(), null))).isEmpty();
        assertThat(ReadyQuestion.NIGHT_SKY.nightVersion(snapshot,
                new ReadyQuestion.Offer(TEXT, List.of("not-a-window"), null))).isEmpty();
    }

    // -- relevance ----------------------------------------------------------------------------

    private static boolean admits(AskPick pick, ReadyQuestion.Offer offer, AskSnapshot snapshot, AskScope scope) {
        return ReadyRelevance.admitsPick(ReadyQuestion.NIGHT_SKY, pick, offer, snapshot, scope);
    }

    @Test
    @DisplayName("admits a pick at an eligible ASTRO spot on the offered night, in scope")
    void admitsAnAstroPick() {
        AskSnapshot snapshot = snapshot(coastNight());

        assertThat(admits(nightPick(1, 1L, "Whitby", "Coast", ASTRO_ID, 5), tonightOffer(), snapshot, ALL)).isTrue();
        assertThat(admits(nightPick(1, 2L, "Bamburgh", "Coast", ASTRO_ID, 4), tonightOffer(), snapshot,
                TestScopes.of("Coast"))).isTrue();
    }

    @Test
    @DisplayName("refuses a pick that is not eligible live: a 3 star, a wood, an unknown place, another region")
    void refusesIneligiblePicks() {
        AskSnapshot snapshot = snapshot(coastNight());

        assertThat(admits(nightPick(1, 3L, "Seahouses", "Coast", ASTRO_ID, 3), tonightOffer(), snapshot, ALL))
                .isFalse();
        assertThat(admits(nightPick(1, 4L, "Hollin Wood", "Dales", ASTRO_ID, 5), tonightOffer(), snapshot, ALL))
                .isFalse();
        assertThat(admits(nightPick(1, 99L, "Nowhere", "Coast", ASTRO_ID, 5), tonightOffer(), snapshot, ALL))
                .isFalse();
        assertThat(admits(nightPick(1, 1L, "Whitby", "Coast", ASTRO_ID, 5), tonightOffer(), snapshot,
                TestScopes.of("Dales"))).isFalse();
    }

    @Test
    @DisplayName("refuses an AURORA pick however good, and even if the offer itself were forged to name the aurora "
            + "window: a shared answer carries no aurora figure")
    void refusesAnAuroraPickEvenOnAForgedOffer() {
        AskSnapshot snapshot = snapshot(coastNight(),
                auroraNight(TONIGHT, aurora(1L, "Whitby", "Coast", 5, 5, 3)));
        AskPick onAurora = nightPick(1, 1L, "Whitby", "Coast", AURORA_ID, 5);
        ReadyQuestion.Offer forged = new ReadyQuestion.Offer(TEXT, List.of(ASTRO_ID, AURORA_ID), null);

        assertThat(admits(onAurora, tonightOffer(), snapshot, ALL)).isFalse();
        assertThat(admits(onAurora, forged, snapshot, ALL)).isFalse();
    }

    @Test
    @DisplayName("refuses a pick on a night the offer does not name, and a solar pick")
    void refusesAnotherNightAndASolarPick() {
        AskSnapshot snapshot = snapshot(coastNight(), astroNight(TOMORROW_NIGHT, astro(1L, "Whitby", "Coast", 5, 3)));

        assertThat(admits(nightPick(1, 1L, "Whitby", "Coast", "2026-10-06_astro", 5), tonightOffer(), snapshot, ALL))
                .isFalse();
        assertThat(admits(ReadyFixtures.pick(1, 1L, "Whitby", "Coast", "2026-10-05_sunset", 5,
                com.gregochr.goldenhour.model.DisplayVerdict.WORTH_IT), tonightOffer(), snapshot, ALL)).isFalse();
    }

    @Test
    @DisplayName("a pick whose targetType says AURORA is refused even when it names the astro window id")
    void refusesAMislabelledKind() {
        AskSnapshot snapshot = snapshot(coastNight());
        AskPick lying = new AskPick(1, 1L, "Whitby", "Coast", TONIGHT, AskWindowKind.AURORA, ASTRO_ID, "x", 5, null);

        assertThat(admits(lying, tonightOffer(), snapshot, ALL)).isFalse();
    }

    @Test
    @DisplayName("the only event it admits is the AURORA hot topic, in any spelling; every other type is dropped")
    void admitsOnlyTheAuroraTopic() {
        assertThat(ReadyRelevance.admitsEvent(ReadyQuestion.NIGHT_SKY, "AURORA")).isTrue();
        assertThat(ReadyRelevance.admitsEvent(ReadyQuestion.NIGHT_SKY, "aurora")).isTrue();
        assertThat(ReadyRelevance.admitsEvent(ReadyQuestion.NIGHT_SKY, "SNOW_TOPS")).isFalse();
        assertThat(ReadyRelevance.admitsEvent(ReadyQuestion.NIGHT_SKY, "ECLIPSE")).isFalse();
        assertThat(ReadyRelevance.admitsEvent(ReadyQuestion.NIGHT_SKY, "METEOR")).isFalse();
        assertThat(ReadyRelevance.admitsEvent(ReadyQuestion.NIGHT_SKY, null)).isFalse();
    }

    @Test
    @DisplayName("an answer is reduced to its picks and its aurora card; a warned event it would drop discards it")
    void relevantPartKeepsPicksAndTheAuroraCard() {
        AskEvent aurora = new AskEvent("AURORA", "Aurora tonight", TONIGHT, "Kp 6.", null);
        AskEvent meteor = new AskEvent("METEOR", "Meteors", TONIGHT, "Peak.", null);
        AskEvent eclipse = new AskEvent("ECLIPSE", "Eclipse", TONIGHT, "Filter.", "Use a filter");
        AskPick pick = nightPick(1, 1L, "Whitby", "Coast", ASTRO_ID, 5);

        AskAnswer reduced = ReadyRelevance.relevantPart(ReadyQuestion.NIGHT_SKY,
                answerWith(List.of(pick), aurora, meteor));

        assertThat(reduced.picks()).containsExactly(pick);
        assertThat(reduced.events()).containsExactly(aurora);
        assertThat(ReadyRelevance.dropsWarning(ReadyQuestion.NIGHT_SKY, answerWith(List.of(pick), eclipse))).isTrue();
        assertThat(ReadyRelevance.dropsWarning(ReadyQuestion.NIGHT_SKY, answerWith(List.of(pick), aurora)))
                .isFalse();
    }

    @Test
    @DisplayName("the violation test: an answer with no pick is refused (the offer promised one), one with an astro "
            + "pick passes, with or without the aurora card, and an aurora pick is a violation")
    void violationRules() {
        AskSnapshot snapshot = snapshot(coastNight(), auroraNight(TONIGHT, aurora(1L, "Whitby", "Coast", 5, 5, 3)));
        AskPick good = nightPick(1, 1L, "Whitby", "Coast", ASTRO_ID, 5);
        AskEvent aurora = new AskEvent("AURORA", "Aurora tonight", TONIGHT, "Kp 6.", null);

        assertThat(ReadyRelevance.violation(ReadyQuestion.NIGHT_SKY, answer(), tonightOffer(), snapshot, ALL))
                .hasValue("a pick question with no pick");
        assertThat(ReadyRelevance.violation(ReadyQuestion.NIGHT_SKY, answer(good), tonightOffer(), snapshot, ALL))
                .isEmpty();
        assertThat(ReadyRelevance.violation(ReadyQuestion.NIGHT_SKY, answerWith(List.of(good), aurora),
                tonightOffer(), snapshot, ALL)).isEmpty();
        assertThat(ReadyRelevance.violation(ReadyQuestion.NIGHT_SKY,
                answer(nightPick(1, 1L, "Whitby", "Coast", AURORA_ID, 5)), tonightOffer(), snapshot, ALL))
                .hasValueSatisfying(why -> assertThat(why).contains("NIGHT_SKY"));
        assertThat(ReadyRelevance.violation(ReadyQuestion.NIGHT_SKY,
                answerWith(List.of(good), new AskEvent("METEOR", "Meteors", TONIGHT, "Peak.", null)),
                tonightOffer(), snapshot, ALL)).hasValueSatisfying(why -> assertThat(why).contains("METEOR"));
    }

    // -- freshness ----------------------------------------------------------------------------

    private static AskReadyStore.Stored stored(AskSnapshot writtenAgainst, AskAnswer answer) {
        String version = ReadyQuestion.NIGHT_SKY.nightVersion(writtenAgainst, tonightOffer()).orElseThrow();
        return new AskReadyStore.Stored("ALL", "NIGHT_SKY", TEXT, List.of(ASTRO_ID), BUILT, answer, version);
    }

    private static AskReadyFreshness.Verdict check(AskReadyStore.Stored stored, AskSnapshot live) {
        return AskReadyFreshness.check(ReadyQuestion.NIGHT_SKY, stored, live, ALL);
    }

    /** The fixtures' coast night, re-stamped as the output of a later astro run. */
    private static Night coastNightGeneratedAt(Instant generation) {
        return new Night(ASTRO_ID, TONIGHT, AskWindowKind.ASTRO, AskNightFixtures.DUSK, AskNightFixtures.DAWN,
                generation, coastNight().spots());
    }

    @Test
    @DisplayName("fresh when the night, its generation and every pick agree; re-decorated from live data, with no "
            + "verdict word because a night has none")
    void freshAndReDecorated() {
        AskSnapshot written = snapshot(coastNight());
        AskReadyStore.Stored stored = stored(written, answer(nightPick(1, 1L, "Old Name", "Coast", ASTRO_ID, 5),
                nightPick(2, 2L, "Bamburgh", "Coast", ASTRO_ID, 4)));

        AskReadyFreshness.Verdict verdict = check(stored, written);

        assertThat(verdict.fresh()).isTrue();
        assertThat(verdict.answer().picks()).satisfiesExactly(
                first -> {
                    assertThat(first.locationName()).isEqualTo("Whitby");
                    assertThat(first.regionName()).isEqualTo("Coast");
                    assertThat(first.targetType()).isEqualTo(AskWindowKind.ASTRO);
                    assertThat(first.date()).isEqualTo(TONIGHT);
                    assertThat(first.verdictAtAnswer()).isNull();
                    assertThat(first.ratingAtAnswer()).isEqualTo(5);
                    assertThat(first.why()).isEqualTo("Clear and dark.");
                },
                second -> assertThat(second.locationName()).isEqualTo("Bamburgh"));
    }

    @Test
    @DisplayName("withheld when the astro job has re-run the night: a new generation, even when every star is the "
            + "same (the cloud explanation moved without moving the integer)")
    void withheldWhenTheGenerationDiffers() {
        AskSnapshot written = snapshot(coastNight());
        AskReadyStore.Stored stored = stored(written, answer(nightPick(1, 1L, "Whitby", "Coast", ASTRO_ID, 5)));
        AskSnapshot rerun = snapshot(coastNightGeneratedAt(GENERATION.plusSeconds(3600)));

        AskReadyFreshness.Verdict verdict = check(stored, rerun);

        assertThat(verdict.fresh()).isFalse();
        assertThat(verdict.answer()).isNull();
        assertThat(verdict.reason()).contains("re-run");
    }

    @Test
    @DisplayName("a generation one millisecond different is a different generation")
    void generationBoundary() {
        AskSnapshot written = snapshot(coastNight());
        AskReadyStore.Stored stored = stored(written, answer(nightPick(1, 1L, "Whitby", "Coast", ASTRO_ID, 5)));

        assertThat(check(stored, snapshot(coastNightGeneratedAt(GENERATION))).fresh()).isTrue();
        assertThat(check(stored, snapshot(coastNightGeneratedAt(GENERATION.plusMillis(1)))).fresh()).isFalse();
    }

    @Test
    @DisplayName("withheld when the answer carries no night version: a row that cannot be shown to be current is "
            + "not served")
    void withheldWithoutAVersion() {
        AskSnapshot written = snapshot(coastNight());
        AskReadyStore.Stored legacy = new AskReadyStore.Stored("ALL", "NIGHT_SKY", TEXT, List.of(ASTRO_ID), BUILT,
                answer(nightPick(1, 1L, "Whitby", "Coast", ASTRO_ID, 5)));

        AskReadyFreshness.Verdict verdict = check(legacy, written);

        assertThat(verdict.fresh()).isFalse();
        assertThat(verdict.reason()).contains("no night version");
    }

    @Test
    @DisplayName("withheld when a pick's live astro star differs from the stored one, up or down")
    void withheldWhenAStarMoves() {
        AskReadyStore.Stored stored = stored(snapshot(coastNight()),
                answer(nightPick(1, 2L, "Bamburgh", "Coast", ASTRO_ID, 4)));
        AskSnapshot better = snapshot(astroNight(TONIGHT, astro(2L, "Bamburgh", "Coast", 5, 3)));

        AskReadyFreshness.Verdict verdict = check(stored, better);

        assertThat(verdict.fresh()).isFalse();
        assertThat(verdict.reason()).contains("pick 1's rating changed");
    }

    @Test
    @DisplayName("withheld when a pick is no longer night-eligible: dropped to the base 3, now a wood, or gone")
    void withheldWhenAPickStopsBeingEligible() {
        AskReadyStore.Stored stored = stored(snapshot(coastNight()),
                answer(nightPick(1, 2L, "Bamburgh", "Coast", ASTRO_ID, 4)));

        // Whitby at 5 stars keeps the question offered, so it is the pick check that withholds.
        assertThat(check(stored, snapshot(astroNight(TONIGHT, astro(2L, "Bamburgh", "Coast", 3, 3),
                astro(1L, "Whitby", "Coast", 5, 3)))).reason()).contains("no longer pick-eligible");
        assertThat(check(stored, snapshot(astroNight(TONIGHT, canopy(astro(2L, "Bamburgh", "Coast", 4, 3)),
                astro(1L, "Whitby", "Coast", 5, 3)))).reason()).contains("no longer pick-eligible");
        assertThat(check(stored, snapshot(astroNight(TONIGHT, astro(1L, "Whitby", "Coast", 5, 3)))).reason())
                .contains("no longer pick-eligible");
    }

    @Test
    @DisplayName("withheld when the night has passed: the poller's tonight has moved on and the old night is gone")
    void withheldWhenTheNightHasPassed() {
        AskReadyStore.Stored stored = stored(snapshot(coastNight()),
                answer(nightPick(1, 1L, "Whitby", "Coast", ASTRO_ID, 5)));
        LocalDate next = TONIGHT.plusDays(1);
        AskSnapshot nextNight = AskNightFixtures.withNights(
                AskFixtures.snapshotOf(AskFixtures.briefing(List.of(), List.of())), null,
                astroNight(next, astro(1L, "Whitby", "Coast", 5, 3)));
        // withNights fixes tonight at TONIGHT; move it on as the poller does at nautical dawn.
        AskSnapshot dawned = new AskSnapshot(nextNight.generatedAt(), nextNight.runLabel(), nextNight.today(),
                nextNight.windows(), nextNight.hotTopics(), nextNight.comingUp(), false,
                new AskSnapshot.Nights(next, nextNight.nights().nights(), null));

        AskReadyFreshness.Verdict verdict = check(stored, dawned);

        assertThat(verdict.fresh()).isFalse();
        assertThat(verdict.reason()).contains("every window");
    }

    @Test
    @DisplayName("withheld when the question is no longer offered because tonight holds no eligible spot")
    void withheldWhenNoLongerOffered() {
        AskReadyStore.Stored stored = stored(snapshot(coastNight()),
                answer(nightPick(1, 1L, "Whitby", "Coast", ASTRO_ID, 5)));
        AskSnapshot none = snapshot(astroNight(TONIGHT, astro(1L, "Whitby", "Coast", 2, 3)));

        assertThat(check(stored, none).reason()).contains("no longer offered");
    }

    @Test
    @DisplayName("withheld when the row was written for another night than the one the question is now about")
    void withheldForAnotherNight() {
        AskReadyStore.Stored forTomorrow = new AskReadyStore.Stored("ALL", "NIGHT_SKY", TEXT,
                List.of("2026-10-06_astro"), BUILT, answer(nightPick(1, 1L, "Whitby", "Coast", "2026-10-06_astro", 5)),
                "n1;2026-10-06=" + GENERATION.toEpochMilli());
        AskSnapshot live = snapshot(coastNight(), astroNight(TOMORROW_NIGHT, astro(1L, "Whitby", "Coast", 5, 3)));

        AskReadyFreshness.Verdict verdict = check(forTomorrow, live);

        assertThat(verdict.fresh()).isFalse();
        assertThat(verdict.reason()).contains("another night");
    }

    @Test
    @DisplayName("withheld when a forged row carries an AURORA pick: a shared answer is re-checked as a reader who "
            + "may not see aurora")
    void withheldWhenARowCarriesAnAuroraPick() {
        AskSnapshot live = snapshot(coastNight(), auroraNight(TONIGHT, aurora(1L, "Whitby", "Coast", 5, 5, 3)));
        AskReadyStore.Stored forged = stored(live, answer(nightPick(1, 1L, "Whitby", "Coast", AURORA_ID, 5)));

        AskReadyFreshness.Verdict verdict = check(forged, live);

        assertThat(verdict.fresh()).isFalse();
    }

    @Test
    @DisplayName("the aurora hot topic is re-found live by its type and date; it goes stale when the topic goes, "
            + "and the live label is what is served")
    void auroraCardIsCheckedLive() {
        AskEvent card = new AskEvent("AURORA", "old label", TONIGHT, "Kp 6.", null);
        AskSnapshot base = AskFixtures.snapshotOf(AskFixtures.briefing(List.of(),
                List.of(AskFixtures.topic("AURORA", "Aurora tonight", "Kp 6", TONIGHT, List.of()))));
        AskSnapshot live = withNights(base, null, coastNight());
        AskReadyStore.Stored stored = stored(live, answerWith(List.of(nightPick(1, 1L, "Whitby", "Coast", ASTRO_ID, 5)),
                card));

        AskReadyFreshness.Verdict fresh = check(stored, live);

        assertThat(fresh.fresh()).isTrue();
        assertThat(fresh.answer().events()).singleElement().satisfies(e -> {
            assertThat(e.type()).isEqualTo("AURORA");
            assertThat(e.label()).isEqualTo("Aurora tonight");
        });
        AskSnapshot gone = snapshot(coastNight());
        assertThat(check(stored, gone).reason()).contains("AURORA").contains("no longer live");
    }
}
