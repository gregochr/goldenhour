package com.gregochr.goldenhour.service.ask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.CoverageReason;
import com.gregochr.goldenhour.service.DriveTimeResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.gregochr.goldenhour.service.ask.ReadyFixtures.at;
import static com.gregochr.goldenhour.service.ask.ReadyFixtures.day;
import static com.gregochr.goldenhour.service.ask.ReadyFixtures.oct;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The stub reads each window's {@link AskSnapshot.Spread} straight off the snapshot (no
 * {@code list_windows} call), fills picks with game-on places only and words the asked windows from the
 * figures (ask-window-context plan §4 W1), so local development and the dry-run show the shape the real
 * engine is asked for.
 */
class StubAskEngineSpreadTest {

    private static final String SAT_SUNSET = "2026-10-10_sunset";
    private static final AskUserContext USER = new AskUserContext(7L, UserRole.PRO_USER, true);

    private final StubAskEngine engine =
            new StubAskEngine(new AskAnswerValidator(), mock(DriveTimeResolver.class), new ObjectMapper());

    private static BriefingWindow.Pick crown(String location, long id) {
        return AskFixtures.pick(BriefingWindow.PickKind.BEST, "Northumberland", location, id);
    }

    private static AskSnapshot weekend(BriefingRegion region, BriefingWindow.Pick pick) {
        return at(ReadyFixtures.FRIDAY_NOON, day(oct(10), true, true, pick, region),
                day(oct(11), true, true, null, region));
    }

    private static AskQuestion weekendQuestion(AskScope scope) {
        return new AskQuestion("Best spot this weekend?", "best spot this weekend?", null, scope, "plan");
    }

    @Test
    @DisplayName("a stay-home weekend with two exceptions: framed as one, the exceptions named, only 4★ picked, "
            + "and no list_windows call")
    void stayHomeWeekendWithExceptions() {
        AskSnapshot snapshot = weekend(AskFixtures.bandedRegion("Northumberland", 1L, 0, 56, 122, 2, 0), null);

        AskRun run = engine.run(weekendQuestion(AskScope.ALL), snapshot, USER, AskRunOptions.none());

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.OK);
        // Summed over the four windows asked about: 4 x 180 rated places, 4 x 2 at 4★.
        assertThat(run.outcome().answer().summary()).startsWith("Not a window to go out for across every region: "
                + "of 720 rated places, 8 are 4★ or better. The exceptions are").doesNotContain("Broadly");
        assertThat(run.outcome().answer().picks()).isNotEmpty()
                .allSatisfy(p -> assertThat(p.ratingAtAnswer()).as("never a 3★ filler").isEqualTo(4));
        assertThat(run.trace()).extracting(AskTools.ToolCall::tool).doesNotContain("list_windows");
    }

    @Test
    @DisplayName("nothing game on anywhere the question covers: the stub names that and picks nothing, "
            + "never filling with a 3★")
    void nothingGameOn() {
        AskSnapshot snapshot = weekend(AskFixtures.bandedRegion("Northumberland", 1L, 0, 56, 122, 0, 0), null);

        AskRun run = engine.run(weekendQuestion(AskScope.ALL), snapshot, USER, AskRunOptions.none());

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.OK);
        assertThat(run.outcome().answer().picks()).isEmpty();
        assertThat(run.outcome().answer().summary()).startsWith("Nothing worth going out for across every region")
                .contains("none is 4★ or better");
        assertThat(run.trace()).extracting(AskTools.ToolCall::tool).doesNotContain("list_windows");
    }

    @Test
    @DisplayName("a window every region of which was stood down is called poor everywhere; a scope names itself")
    void everyRegionStoodDown() {
        AskSnapshot snapshot = weekend(AskFixtures.blankedRegion("Northumberland", CoverageReason.STOOD_DOWN), null);

        AskRun everywhere = engine.run(weekendQuestion(AskScope.ALL), snapshot, USER, AskRunOptions.none());
        AskRun scoped = engine.run(weekendQuestion(TestScopes.of("Northumberland")), snapshot, USER,
                AskRunOptions.none());

        assertThat(everywhere.outcome().answer().summary()).contains("Every region is stood down")
                .contains("poor across every region");
        assertThat(scoped.outcome().answer().summary()).contains("poor in Northumberland");
    }

    @Test
    @DisplayName("a broadly game-on weekend is framed as one and picks the game-on places")
    void broadlyGameOn() {
        AskSnapshot snapshot = weekend(AskFixtures.bandedRegion("Northumberland", 1L, 0, 5, 40, 110, 25), null);

        AskRun run = engine.run(weekendQuestion(AskScope.ALL), snapshot, USER, AskRunOptions.none());

        assertThat(run.outcome().answer().summary()).startsWith("Broadly game on across every region");
        assertThat(run.outcome().answer().picks()).isNotEmpty()
                .allSatisfy(p -> assertThat(p.ratingAtAnswer()).isGreaterThanOrEqualTo(4));
    }

    @Test
    @DisplayName("Ready BEST_WEEKEND with nothing game on: the stub gives the honest no-pick answer the validator "
            + "now admits, though the forecast has a BEST BET, and carries the covered windows' spreads")
    void readyStayHomeIsAnAdmittedNoPick() {
        AskSnapshot snapshot = weekend(AskFixtures.bandedRegion("Northumberland", 1L, 0, 56, 122, 0, 0),
                crown("Northumberland 3-0", 57L));
        ReadyQuestion.Offer offer = ReadyQuestion.BEST_WEEKEND.offer(snapshot, AskScope.ALL).orElseThrow();

        AskRun run = engine.run(AskQuestion.of(offer.text(), null, AskScope.ALL, "plan"), snapshot,
                AskUserContext.userLess(), AskRunOptions.ready(900L, ReadyQuestion.BEST_WEEKEND, offer));

        assertThat(run.outcome().status()).as(String.valueOf(run.reason())).isEqualTo(AskOutcome.Status.OK);
        assertThat(run.outcome().answer().picks()).isEmpty();
        assertThat(run.outcome().answer().windows()).extracting(AskAnswer.WindowSpread::windowId)
                .containsExactlyElementsOf(offer.coveredWindowIds());
        assertThat(ReadyRelevance.violation(ReadyQuestion.BEST_WEEKEND, run.outcome().answer(), offer, snapshot,
                AskScope.ALL)).isEmpty();
    }

    @Test
    @DisplayName("Ready BEST_WEEKEND with a game-on place: pick 1 leads on the BEST BET window as it always did, "
            + "and the covered windows are carried")
    void readyWithGameOnStaysAnchored() {
        AskSnapshot snapshot = weekend(AskFixtures.bandedRegion("Northumberland", 1L, 0, 56, 122, 2, 0),
                crown("Northumberland 3-0", 179L));
        ReadyQuestion.Offer offer = ReadyQuestion.BEST_WEEKEND.offer(snapshot, AskScope.ALL).orElseThrow();

        AskRun run = engine.run(AskQuestion.of(offer.text(), null, AskScope.ALL, "plan"), snapshot,
                AskUserContext.userLess(), AskRunOptions.ready(900L, ReadyQuestion.BEST_WEEKEND, offer));

        assertThat(run.outcome().status()).as(String.valueOf(run.reason())).isEqualTo(AskOutcome.Status.OK);
        assertThat(run.outcome().answer().picks().getFirst().windowId()).isEqualTo(SAT_SUNSET);
        assertThat(run.outcome().answer().windows()).extracting(AskAnswer.WindowSpread::windowId)
                .containsAll(offer.coveredWindowIds());
    }

    @Test
    @DisplayName("a window with one stood-down region and the rest thin is NOT 'every region stood down': missing "
            + "evidence is never read as poor weather")
    void partialStandDownIsNotEveryRegion() {
        AskSnapshot snapshot = at(ReadyFixtures.FRIDAY_NOON,
                day(oct(10), true, true, null, AskFixtures.blankedRegion("Northumberland", CoverageReason.STOOD_DOWN),
                        AskFixtures.bandedRegion("Hills", false, 1L, 0, 0, 0, 3, 0)));

        AskRun run = engine.run(new AskQuestion("Best spot on Saturday?", "best spot on saturday?", null,
                AskScope.ALL, "plan"), snapshot, USER, AskRunOptions.none());

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.OK);
        assertThat(run.outcome().answer().summary()).doesNotContain("Every region is stood down")
                .contains("not scored yet");
    }
}
