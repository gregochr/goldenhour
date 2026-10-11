package com.gregochr.goldenhour.service.ask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.service.DriveTimeResolver;
import com.gregochr.goldenhour.service.ask.AskSnapshot.AuroraState;
import com.gregochr.goldenhour.service.ask.ThreadExchange.PickRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.gregochr.goldenhour.service.ask.AskFixtures.GENERATED_AT;
import static com.gregochr.goldenhour.service.ask.AskNightFixtures.TONIGHT;
import static com.gregochr.goldenhour.service.ask.AskNightFixtures.TOMORROW_NIGHT;
import static com.gregochr.goldenhour.service.ask.AskNightFixtures.astro;
import static com.gregochr.goldenhour.service.ask.AskNightFixtures.astroNight;
import static com.gregochr.goldenhour.service.ask.AskNightFixtures.aurora;
import static com.gregochr.goldenhour.service.ask.AskNightFixtures.auroraNight;
import static com.gregochr.goldenhour.service.ask.AskNightFixtures.nightsOnly;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * A thread's night picks (ask-night plan §2.2, the thread): re-joined live and offered only while eligible,
 * an aurora pick only to an asker who may see aurora, and a night answer's summary never rendered verbatim.
 */
class AskThreadContextNightTest {

    private static final String ASTRO = "2026-10-05_astro";
    private static final String AURORA = "2026-10-05_aurora";
    private static final String TOMORROW_ASTRO = "2026-10-06_astro";
    private static final String VERSION = "n1;2026-10-05=1";
    private static final AskUserContext PRO = new AskUserContext(7L, UserRole.PRO_USER, true);

    private final DriveTimeResolver driveTimes = mock(DriveTimeResolver.class);
    private final AskLocationNames names = () -> java.util.Map.of(
            1L, new AskLocationNames.Place("Whitby", 54.5, -0.6),
            9L, new AskLocationNames.Place("Gone Place", 55.0, -1.6));

    private static AskSnapshot snapshot() {
        return nightsOnly(new AuroraState("MODERATE", 6.0),
                astroNight(TONIGHT, astro(1L, "Whitby", "Coast", 5, 3), astro(2L, "Bamburgh", "Coast", 3, 3)),
                auroraNight(TONIGHT, aurora(1L, "Whitby", "Coast", 5, 4, 3)),
                astroNight(TOMORROW_NIGHT, astro(1L, "Whitby", "Coast", 4, 3)));
    }

    private static ThreadExchange night(String summary, String version, PickRef... picks) {
        return new ThreadExchange("clear skies tonight?", summary, List.of(picks), List.of(), GENERATED_AT, false,
                version);
    }

    private AskThreadContext context(AskSnapshot snapshot, AskScope scope, boolean canSeeAurora,
            ThreadExchange... exchanges) {
        AskUserContext user = canSeeAurora ? PRO : new AskUserContext(8L, UserRole.LITE_USER, false);
        AskTools tools = new AskTools(snapshot, user, scope, driveTimes, new ObjectMapper());
        return AskThreadContext.of(new AskThread(List.of(exchanges)), snapshot, scope, tools, names, false,
                canSeeAurora);
    }

    @Test
    @DisplayName("an eligible earlier astro pick is offered, and rendered live with its ids, the figure, the "
            + "Bortle class and the clear-sky line")
    void offersAndRendersAnAstroPick() {
        AskThreadContext context = context(snapshot(), AskScope.ALL, true,
                night("Whitby is clear, Kp 6.", VERSION, new PickRef(1L, ASTRO)));

        assertThat(context.fact().picks()).containsExactly(new AskEvidence.Pair(1L, ASTRO));
        assertThat(context.exchanges().getFirst().pickLines()).containsExactly(
                "1. Whitby (Coast) · locationId 1 · windowId 2026-10-05_astro · tonight astro · 5★ · Bortle 3"
                        + " · Clear skies");
    }

    @Test
    @DisplayName("a night answer's summary is never rendered verbatim; an ordinary exchange's still is")
    void nightSummaryIsWithheld() {
        AskThreadContext context = context(snapshot(), AskScope.ALL, true,
                night("Alert active, Kp 6, skies clear.", VERSION, new PickRef(1L, ASTRO)),
                new ThreadExchange("best sunset?", "Whitby.", List.of(), List.of(), GENERATED_AT, false));

        assertThat(context.exchanges().getFirst().summary()).isEqualTo(AskThreadContext.NIGHT_SUMMARY_WITHHELD)
                .doesNotContain("Kp");
        assertThat(context.exchanges().get(1).summary()).isEqualTo("Whitby.");
    }

    @Test
    @DisplayName("an earlier pick whose stars have fallen below the bar, whose place has left the night, or "
            + "that is outside the follow-up's scope is dropped and says which")
    void dropsAndExplains() {
        AskThreadContext context = context(snapshot(), TestScopes.of("Coast"), true,
                night("x", VERSION, new PickRef(2L, ASTRO), new PickRef(9L, ASTRO), new PickRef(1L, TOMORROW_ASTRO)));
        AskThreadContext outOfScope = context(snapshot(), TestScopes.of("Dales"), true,
                night("x", VERSION, new PickRef(1L, ASTRO)));

        assertThat(context.fact().picks()).containsExactly(new AskEvidence.Pair(1L, TOMORROW_ASTRO));
        assertThat(context.fact().droppedPicks()).containsExactlyInAnyOrder(new AskEvidence.Pair(2L, ASTRO),
                new AskEvidence.Pair(9L, ASTRO));
        assertThat(context.exchanges().getFirst().pickLines()).satisfiesExactly(
                line -> assertThat(line).isEqualTo("1. Bamburgh · no longer rated well enough to pick"),
                line -> assertThat(line).isEqualTo("2. Gone Place · no longer in the forecast"),
                line -> assertThat(line).contains("tomorrow night astro"));
        assertThat(outOfScope.exchanges().getFirst().pickLines().getFirst())
                .isEqualTo("1. Whitby · outside the area you are now asking about");
    }

    @Test
    @DisplayName("an earlier aurora pick is offered to an asker who may see aurora and is gone for one who "
            + "may not, whatever a forged reference says")
    void auroraPickNeedsAccess() {
        ThreadExchange exchange = night("x", VERSION, new PickRef(1L, AURORA));

        AskThreadContext pro = context(snapshot(), AskScope.ALL, true, exchange);
        AskThreadContext lite = context(snapshot(), AskScope.ALL, false, exchange);

        assertThat(pro.fact().picks()).containsExactly(new AskEvidence.Pair(1L, AURORA));
        assertThat(pro.exchanges().getFirst().pickLines().getFirst()).contains("tonight aurora").contains("4★");
        assertThat(lite.fact().picks()).isEmpty();
        assertThat(lite.fact().droppedPicks()).containsExactly(new AskEvidence.Pair(1L, AURORA));
        assertThat(lite.exchanges().getFirst().pickLines().getFirst())
                .isEqualTo("1. Whitby · no longer in the forecast");
    }

    @Test
    @DisplayName("nightStaleAgainst: an exchange with no night version is never stale by it; a live one is not; "
            + "any non-live one, typed or Ready, is")
    void nightStaleAgainst() {
        AskSnapshot snapshot = snapshot();
        long generation = AskNightFixtures.GENERATION.toEpochMilli();
        ThreadExchange plain = new ThreadExchange("q", "s", List.of(), List.of(), GENERATED_AT, false);
        ThreadExchange live = night("s", "n1;2026-10-05=" + generation);
        ThreadExchange old = night("s", "n1;2026-10-05=1");
        ThreadExchange readyOld = new ThreadExchange("q", "s", List.of(), List.of(), GENERATED_AT, true,
                "n1;2026-10-05=1");

        assertThat(new AskThread(List.of(plain, live)).nightStaleAgainst(snapshot)).isFalse();
        assertThat(new AskThread(List.of(plain, old)).nightStaleAgainst(snapshot)).isTrue();
        assertThat(new AskThread(List.of(live, readyOld)).nightStaleAgainst(snapshot)).isTrue();
        assertThat(AskThread.EMPTY.nightStaleAgainst(snapshot)).isFalse();
    }
}
