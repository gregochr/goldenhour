package com.gregochr.goldenhour.service.ask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.service.DriveTimeResolver;
import com.gregochr.goldenhour.service.ask.AskEvidence.EventFact;
import com.gregochr.goldenhour.service.ask.AskEvidence.Pair;
import com.gregochr.goldenhour.service.ask.ThreadExchange.EventRef;
import com.gregochr.goldenhour.service.ask.ThreadExchange.PickRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static com.gregochr.goldenhour.service.ask.AskFixtures.GENERATED_AT;
import static com.gregochr.goldenhour.service.ask.AskFixtures.TODAY;
import static com.gregochr.goldenhour.service.ask.AskNightFixtures.TONIGHT;
import static com.gregochr.goldenhour.service.ask.AskNightFixtures.astroNight;
import static com.gregochr.goldenhour.service.ask.AskNightFixtures.auroraNight;
import static com.gregochr.goldenhour.service.ask.AskNightFixtures.nightsOnly;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AskThreadContext} on the cases the main thread tests leave out: a missing thread, an earlier event that is
 * only on the coming-up timeline, an exchange with no question or summary, a verdict-less pick, and night picks
 * whose optional figures are absent.
 */
class AskThreadContextEdgeCasesTest {

    private static final AskUserContext PRO = new AskUserContext(7L, UserRole.PRO_USER, true);
    private static final LocalDate MOON_DAY = TODAY.plusDays(10);
    private static final String SUNSET = "2026-10-05_sunset";
    private static final String ASTRO = "2026-10-05_astro";
    private static final String AURORA = "2026-10-05_aurora";

    private final DriveTimeResolver driveTimes = mock(DriveTimeResolver.class);

    private AskThreadContext context(AskThread thread, AskSnapshot snapshot, AskUserContext user,
            AskLocationNames names) {
        AskTools tools = new AskTools(snapshot, user, AskScope.ALL, driveTimes, new ObjectMapper());
        return AskThreadContext.of(thread, snapshot, AskScope.ALL, tools, names, false, user.canSeeAurora());
    }

    private static ThreadExchange exchange(String question, String summary, List<PickRef> picks,
            List<EventRef> events) {
        return new ThreadExchange(question, summary, picks, events, GENERATED_AT, false);
    }

    @Test
    @DisplayName("a missing thread is the shared NONE, like an empty one")
    void nullThreadIsNone() {
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(List.of(), List.of()));

        assertThat(context(null, snapshot, PRO, null)).isSameAs(AskThreadContext.NONE);
    }

    @Test
    @DisplayName("an earlier event that is only on the coming-up timeline is offered live with that entry's title; "
            + "one with another date or another type is not")
    void timelineEvents() {
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(List.of(), List.of()),
                List.of(AskSnapshotBuilderTest.almanacEntry("supermoon", "Supermoon", MOON_DAY, MOON_DAY,
                        "A big moon.")));
        AskThread thread = new AskThread(List.of(exchange("q", "s", List.of(), List.of(
                new EventRef("SUPERMOON", MOON_DAY), new EventRef("supermoon", MOON_DAY.plusDays(1)),
                new EventRef("eclipse", MOON_DAY)))));

        AskThreadContext context = context(thread, snapshot, PRO, null);

        assertThat(context.fact().events()).containsExactly(new EventFact("SUPERMOON", "Supermoon", MOON_DAY, null));
        assertThat(context.exchanges().getFirst().eventLines()).containsExactly(
                "Supermoon · " + AskTools.dayWord(MOON_DAY, TODAY),
                "SUPERMOON · " + MOON_DAY.plusDays(1) + " · no longer flagged",
                "ECLIPSE · " + MOON_DAY + " · no longer flagged");
    }

    @Test
    @DisplayName("an exchange with no question and no summary is rendered with empty text, not 'null'")
    void missingTextReadsEmpty() {
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(List.of(), List.of()));

        AskThreadContext.ExchangeView view = context(new AskThread(List.of(
                exchange(null, null, List.of(), List.of()))), snapshot, PRO, null).exchanges().getFirst();

        assertThat(view.question()).isEmpty();
        assertThat(view.summary()).isEmpty();
    }

    @Test
    @DisplayName("quotes become apostrophes and runs of whitespace one space in a rendered question and summary")
    void textIsTidied() {
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(List.of(), List.of()));

        AskThreadContext.ExchangeView view = context(new AskThread(List.of(
                exchange("  Is \"Whitby\"\n good? ", "It   is.", List.of(), List.of()))), snapshot, PRO, null)
                .exchanges().getFirst();

        assertThat(view.question()).isEqualTo("Is 'Whitby' good?");
        assertThat(view.summary()).isEqualTo("It is.");
    }

    @Test
    @DisplayName("a pick whose slot carries no verdict is rendered without one")
    void pickWithoutAVerdict() {
        AskSnapshot.Slot slot = new AskSnapshot.Slot(1L, "Whitby", 4, null, null, false, null, false, null, false,
                null, null, null, null, null);
        AskSnapshot.Region region = new AskSnapshot.Region("Coast", null, 4.0, true, true, List.of(slot), null);
        AskSnapshot.Window window = new AskSnapshot.Window(SUNSET, TODAY, TargetType.SUNSET,
                LocalDateTime.of(2026, 10, 5, 18, 0), null, 4, null, List.of(region));
        AskSnapshot snapshot = new AskSnapshot(GENERATED_AT, "05:02", TODAY, List.of(window), List.of(), List.of(),
                false, null, null, null, null);
        AskThread thread = new AskThread(List.of(exchange("q", "s", List.of(new PickRef(1L, SUNSET)), List.of())));

        AskThreadContext context = context(thread, snapshot, PRO, null);

        assertThat(context.fact().picks()).containsExactly(new Pair(1L, SUNSET));
        assertThat(context.exchanges().getFirst().pickLines()).containsExactly(
                "1. Whitby (Coast) · locationId 1 · windowId " + SUNSET + " · Today sunset · 4★");
    }

    @Test
    @DisplayName("an astro pick with no Bortle class and no clear-sky line is rendered without them, and carries "
            + "the asker's drive time when they have one")
    void astroPickWithoutOptionalFigures() {
        AskSnapshot.NightSpot bare = new AskSnapshot.NightSpot(1L, "Whitby", "Coast", 54.9, -1.6, false, null, 21.4,
                5, "summary", null, "visible", "moon", "NEW", 1.0, null, null, false, null, null, null);
        AskSnapshot snapshot = nightsOnly(null, astroNight(TONIGHT, bare));
        when(driveTimes.getAllMinutes(7L)).thenReturn(Map.of(1L, 42));
        AskThread thread = new AskThread(List.of(exchange("q", "s", List.of(new PickRef(1L, ASTRO)), List.of())));

        List<String> lines = context(thread, snapshot, PRO, null).exchanges().getFirst().pickLines();

        assertThat(lines).containsExactly(
                "1. Whitby (Coast) · locationId 1 · windowId " + ASTRO
                        + " · tonight astro · 5★ · 42 min from home");
    }

    @Test
    @DisplayName("an aurora pick with no alert level, no Kp and no summary is rendered without them")
    void auroraPickWithoutOptionalFigures() {
        AskSnapshot.NightSpot bare = new AskSnapshot.NightSpot(1L, "Whitby", "Coast", 54.9, -1.6, false, 3, 21.4,
                null, null, null, null, null, null, null, 4, null, false, null, null, null);
        AskSnapshot snapshot = nightsOnly(null, auroraNight(TONIGHT, bare));
        AskThread thread = new AskThread(List.of(exchange("q", "s", List.of(new PickRef(1L, AURORA)), List.of())));

        List<String> lines = context(thread, snapshot, PRO, null).exchanges().getFirst().pickLines();

        assertThat(lines).containsExactly(
                "1. Whitby (Coast) · locationId 1 · windowId " + AURORA
                        + " · tonight aurora · 4★ · Bortle 3");
    }

    @Test
    @DisplayName("a gone place with a blank name in the repository is 'a place no longer listed'; with no "
            + "repository at all it is too")
    void blankAndMissingNames() {
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(List.of(), List.of()));
        AskThread thread = new AskThread(List.of(exchange("q", "s", List.of(new PickRef(5L, SUNSET)), List.of())));
        AskLocationNames blank = () -> Map.of(5L, new AskLocationNames.Place("  ", 55.0, -1.6));

        assertThat(context(thread, snapshot, PRO, blank).exchanges().getFirst().pickLines())
                .containsExactly("1. " + AskThreadContext.UNLISTED_PLACE + " · no longer in the forecast");
        assertThat(context(thread, snapshot, PRO, null).exchanges().getFirst().pickLines())
                .containsExactly("1. " + AskThreadContext.UNLISTED_PLACE + " · no longer in the forecast");
    }
}
