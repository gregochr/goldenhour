package com.gregochr.goldenhour.service.ask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.model.BriefingRegion;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.service.DriveTimeResolver;
import com.gregochr.goldenhour.service.ask.AskAnswerValidator.BestAnchor;
import com.gregochr.goldenhour.service.ask.AskAnswerValidator.Raw;
import com.gregochr.goldenhour.service.ask.AskAnswerValidator.RawPick;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.gregochr.goldenhour.service.ask.AskFixtures.TODAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AskConversation}, the frame both engines share: how a conversation opens, in
 * which order its refusals come, and how it closes. Run against the real validator and the real
 * tools, with the validator replaced only to prove a case it would not reach.
 */
class AskConversationTest {

    private static final String SUNSET_TODAY = "2026-10-05_sunset";
    private static final AskUserContext USER = new AskUserContext(7L, UserRole.PRO_USER, true);

    private final DriveTimeResolver driveTimes = mock(DriveTimeResolver.class);
    private final AskAnswerValidator validator = new AskAnswerValidator();
    private final AskConversation.Deps deps =
            new AskConversation.Deps(validator, driveTimes, new ObjectMapper());

    private static AskSnapshot snapshot(BriefingWindow.Pick pick) {
        BriefingRegion coast = AskFixtures.region("Coast", true,
                AskFixtures.slot(1L, "Whitby", 4), AskFixtures.slot(2L, "Saltburn", 3));
        return AskFixtures.snapshotOf(AskFixtures.briefing(
                List.of(AskFixtures.sunsetDay(TODAY, pick, coast)), List.of()));
    }

    private static AskQuestion question(String text) {
        return new AskQuestion(text, text.toLowerCase(), null, AskScope.ALL, "plan");
    }

    private AskConversation open(AskQuestion question, AskSnapshot snapshot, AskUserContext user,
            AskRunOptions options) {
        AskConversation.Opening opening = AskConversation.open(question, snapshot, user, options, deps);
        assertThat(opening).isInstanceOf(AskConversation.Opening.Open.class);
        return ((AskConversation.Opening.Open) opening).conversation();
    }

    private static Raw picks(long... ids) {
        List<RawPick> picks = new ArrayList<>();
        for (long id : ids) {
            picks.add(new RawPick(id, SUNSET_TODAY, "Clear west"));
        }
        return new Raw(true, "Whitby tonight.", picks, List.of(), null);
    }

    // -- opening ----------------------------------------------------------------------------

    @Test
    @DisplayName("the options are checked first: a typed question carrying a Ready run is refused loudly, "
            + "even when the question is also blank")
    void optionsAreCheckedBeforeAnythingElse() {
        AskSnapshot snapshot = snapshot(null);

        assertThatThrownBy(() -> AskConversation.open(question("   "), snapshot, USER,
                AskRunOptions.ready(900L, null), deps))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("daily ASK run");
        assertThatThrownBy(() -> AskConversation.open(question("   "), snapshot, AskUserContext.userLess(),
                AskRunOptions.none(), deps))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ASK_READY");
    }

    @Test
    @DisplayName("a blank question is refused as a FAILED run before any tool exists: nothing is built, "
            + "asked or read")
    void blankQuestionIsRefusedBeforeTheToolsExist() {
        AskAnswerValidator unreachable = mock(AskAnswerValidator.class);
        AskConversation.Deps spied = new AskConversation.Deps(unreachable, driveTimes, new ObjectMapper());

        AskConversation.Opening opening = AskConversation.open(question("  \t "), snapshot(null), USER,
                null, spied);

        assertThat(opening).isInstanceOfSatisfying(AskConversation.Opening.Refused.class, refused -> {
            AskRun run = refused.run();
            assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.FAILED);
            assertThat(run.outcome().personal()).isFalse();
            assertThat(run.outcome().turns()).isZero();
            assertThat(run.outcome().answer()).isNull();
            assertThat(run.reason()).isEqualTo("the question is empty");
            assertThat(run.trace()).isEmpty();
        });
        verifyNoInteractions(unreachable, driveTimes);
    }

    @Test
    @DisplayName("a null question text is blank too")
    void nullQuestionIsBlank() {
        AskQuestion nullText = new AskQuestion(null, null, null, AskScope.ALL, "plan");

        AskConversation.Opening opening = AskConversation.open(nullText, snapshot(null), USER, null, deps);

        assertThat(opening).isInstanceOfSatisfying(AskConversation.Opening.Refused.class,
                refused -> assertThat(refused.run().reason()).isEqualTo("the question is empty"));
    }

    @Test
    @DisplayName("null options read as none, and the conversation exposes the normalised options, the "
            + "question's scope and the tools it will use")
    void nullOptionsReadAsNone() {
        AskConversation conversation = open(question("best spot"), snapshot(null), USER, null);

        assertThat(conversation.options()).isEqualTo(AskRunOptions.none());
        assertThat(conversation.anchor()).isNull();
        assertThat(conversation.scope()).isSameAs(AskScope.ALL);
        assertThat(conversation.tools().trace()).isEmpty();
        assertThat(conversation.tools().personal()).isFalse();
    }

    @Test
    @DisplayName("a Ready conversation carries its anchor")
    void readyAnchorIsExposed() {
        BestAnchor anchor = new BestAnchor(Set.of(SUNSET_TODAY));

        AskConversation conversation = open(question("best spot"), snapshot(null),
                AskUserContext.userLess(), AskRunOptions.ready(900L, anchor));

        assertThat(conversation.anchor()).isSameAs(anchor);
        assertThat(conversation.options().readyJobRunId()).isEqualTo(900L);
    }

    // -- submitting -------------------------------------------------------------------------

    @Test
    @DisplayName("a submitted answer the tools backed is an OK run: the synthetic submit_answer call ends "
            + "the trace, the caller's own trace is left alone, and the turn count is the caller's")
    void submitAcceptsWhatTheToolsReturned() {
        AskConversation conversation = open(question("best spot"), snapshot(null), USER, null);
        conversation.tools().rankSpots(new AskTools.RankSpotsArgs(null, null, null, null, null, 3));
        List<AskTools.ToolCall> trace = new ArrayList<>(conversation.tools().trace());

        AskRun run = conversation.submit(picks(1L), 2, trace);

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.OK);
        assertThat(run.outcome().turns()).isEqualTo(2);
        assertThat(run.outcome().personal()).isFalse();
        assertThat(run.reason()).isNull();
        assertThat(run.outcome().answer().picks()).extracting(AskPick::locationName).containsExactly("Whitby");
        assertThat(run.trace()).extracting(AskTools.ToolCall::tool).containsExactly("rank_spots", "submit_answer");
        assertThat(run.trace().getLast().error()).isFalse();
        assertThat(trace).as("the caller's list is not appended to").hasSize(1);
    }

    @Test
    @DisplayName("an answer the validator discards is a FAILED run with the validator's reason, after the "
            + "synthetic submit_answer call")
    void submitDiscardsWhatTheToolsDidNotReturn() {
        AskConversation conversation = open(question("best spot"), snapshot(null), USER, null);

        AskRun run = conversation.submit(picks(1L), 1, List.of());

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.FAILED);
        assertThat(run.outcome().answer()).isNull();
        assertThat(run.outcome().turns()).isEqualTo(1);
        assertThat(run.reason()).startsWith("the answer was discarded: ");
        assertThat(run.trace()).extracting(AskTools.ToolCall::tool).containsExactly("submit_answer");
    }

    @Test
    @DisplayName("an honest 'not in the forecast' is a CANT run")
    void submitUnanswerableIsCant() {
        AskConversation conversation = open(question("is the car park open"), snapshot(null), USER, null);

        AskRun run = conversation.submit(new Raw(false, "Not in the forecast.", null, null, "parking"), 1,
                List.of());

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.CANT);
        assertThat(run.outcome().answer().answerable()).isFalse();
    }

    @Test
    @DisplayName("the conversation's anchor reaches the validator: a BEST answer that does not lead with the "
            + "BEST BET window is discarded")
    void submitHoldsABestAnswerToItsAnchor() {
        BriefingWindow.Pick best = AskFixtures.pick(BriefingWindow.PickKind.BEST, "Coast", "Whitby", 1L);
        AskConversation conversation = open(question("best spot"), snapshot(best),
                AskUserContext.userLess(), AskRunOptions.ready(900L, new BestAnchor(Set.of(SUNSET_TODAY))));
        conversation.tools().rankSpots(new AskTools.RankSpotsArgs(null, null, null, null, null, 3));

        AskRun notLeading = conversation.submit(new Raw(true, "Nothing stands out.", null, null, null), 1,
                List.of());
        AskRun leading = conversation.submit(picks(1L, 2L), 1, List.of());

        assertThat(notLeading.outcome().status()).isEqualTo(AskOutcome.Status.FAILED);
        assertThat(notLeading.reason()).contains("pick 1 is not on the BEST BET window " + SUNSET_TODAY);
        assertThat(leading.outcome().status()).isEqualTo(AskOutcome.Status.OK);
    }

    @Test
    @DisplayName("the events question reaches the validator: 'any rare events' answered 'none' while the "
            + "forecast offers one is discarded")
    void submitHoldsAnEventsQuestionToWhatIsOffered() {
        AskSnapshot snapshot = AskFixtures.snapshotOf(AskFixtures.briefing(List.of(), List.of(
                AskFixtures.topic("AURORA", "Aurora tonight", "Kp 6", TODAY, List.of()))));
        AskConversation conversation = open(question("any rare events coming up?"), snapshot, USER, null);

        AskRun run = conversation.submit(new Raw(true, "Nothing is showing.", null, null, null), 1, List.of());

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.FAILED);
        assertThat(run.reason()).contains("events question answered \"none\"");
    }

    @Test
    @DisplayName("a submit_answer that could not be read ends the run as FAILED with an errored "
            + "submit_answer call on the trace")
    void rejectSubmission() {
        AskConversation conversation = open(question("best spot"), snapshot(null), USER, null);
        conversation.tools().listWindows();

        AskRun run = conversation.rejectSubmission("'summary' is missing", 2, conversation.tools().trace());

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.FAILED);
        assertThat(run.outcome().turns()).isEqualTo(2);
        assertThat(run.reason()).isEqualTo("submit_answer was malformed: 'summary' is missing");
        assertThat(run.trace()).extracting(AskTools.ToolCall::tool).containsExactly("list_windows", "submit_answer");
        assertThat(run.trace().getLast().error()).isTrue();
    }

    // -- failing ----------------------------------------------------------------------------

    @Test
    @DisplayName("fail() carries the reason, the turns and the trace it is given")
    void failCarriesWhatItIsGiven() {
        AskConversation conversation = open(question("best spot"), snapshot(null), USER, null);
        List<AskTools.ToolCall> trace = List.of(new AskTools.ToolCall("rank_spots", true, 0));

        AskRun run = conversation.fail("the deadline passed before turn 2", 1, trace);

        assertThat(run.outcome().status()).isEqualTo(AskOutcome.Status.FAILED);
        assertThat(run.outcome().turns()).isEqualTo(1);
        assertThat(run.reason()).isEqualTo("the deadline passed before turn 2");
        assertThat(run.trace()).isEqualTo(trace);
        assertThat(run.accountingUnavailable()).isFalse();
        assertThat(conversation.fail(AskRun.ACCOUNTING_UNAVAILABLE, 0, List.of()).accountingUnavailable())
                .isTrue();
    }

    @Test
    @DisplayName("every FAILED run reports whether the asker's own drive times were used, as the tools saw "
            + "it: the stub's failures now say so too")
    void everyFailureReportsThePersonalFlag() {
        AskConversation conversation = open(question("best spot within an hour"), snapshot(null), USER, null);
        when(driveTimes.getAllMinutes(7L)).thenReturn(Map.of(1L, 30));
        assertThat(conversation.fail("early", 0, List.of()).outcome().personal()).isFalse();

        conversation.tools().rankSpots(new AskTools.RankSpotsArgs(null, null, null, null, 60, 3));

        assertThat(conversation.tools().personal()).isTrue();
        assertThat(conversation.fail("later", 1, List.of()).outcome().personal()).isTrue();
        assertThat(conversation.rejectSubmission("bad", 1, List.of()).outcome().personal()).isTrue();
        assertThat(conversation.submit(picks(99L), 1, List.of()).outcome().personal())
                .as("a discarded answer").isTrue();
    }
}
