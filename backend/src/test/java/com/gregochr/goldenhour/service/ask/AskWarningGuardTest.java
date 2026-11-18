package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.service.ask.AskAnswerValidator.Raw;
import com.gregochr.goldenhour.service.ask.AskAnswerValidator.RawEvent;
import com.gregochr.goldenhour.service.ask.AskAnswerValidator.RawPick;
import com.gregochr.goldenhour.service.ask.AskAnswerValidator.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static com.gregochr.goldenhour.service.ask.AskFixtures.TODAY;
import static com.gregochr.goldenhour.service.ask.AskWarningFixtures.served;
import static com.gregochr.goldenhour.service.ask.AskWarningFixtures.servedStale;
import static com.gregochr.goldenhour.service.ask.AskWarningFixtures.twoRegions;
import static com.gregochr.goldenhour.service.ask.AskWarningFixtures.warning;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The typed guard for Met Office warnings (weather-warnings-plan.md section 14, W6): an answer may say a warning is
 * in force only when a tool showed the model that warning, never at a level it was not shown, and never with the
 * wording softened; an unsupported claim is removed, and the server attaches the facts and the attribution.
 */
class AskWarningGuardTest {

    private static final String WINDOW = "2026-10-05_sunset";
    private static final AskSnapshot.Warning AMBER_SNOW = warning("W1", "AMBER", List.of("SNOW"), 1L);

    private final AskAnswerValidator validator = new AskAnswerValidator();

    private static AskSnapshot snapshot(AskSnapshot.Warnings warnings) {
        return twoRegions().withWarnings(warnings);
    }

    private static AskEvidence shown(String... ids) {
        return new AskEvidence(Set.of(new AskEvidence.Pair(1L, WINDOW)), Set.of(), 1, AskEvidence.ThreadFact.NONE,
                Set.of(), Set.of(ids));
    }

    private static AskEvidence nothingShown() {
        return shown();
    }

    private Result validate(String summary, AskSnapshot snapshot, AskEvidence evidence) {
        return validator.validate(new Raw(true, summary, List.of(new RawPick(1L, WINDOW, "Clear west")), List.of(),
                null), snapshot, evidence, AskScope.ALL, null, null, false);
    }

    // -- the guard is off with the feature --------------------------------------------------

    @Test
    @DisplayName("with the warnings feature off nothing is stripped, flagged or attached: the answer is what it was")
    void featureOff_isANoOp() {
        String claim = "There is an Amber warning of snow, from the Met Office. Whitby looks good.";

        Result result = validate(claim, snapshot(AskSnapshot.Warnings.NONE), nothingShown());

        assertThat(result.accepted()).isTrue();
        assertThat(result.answer().summary()).isEqualTo(claim);
        assertThat(result.answer().warnings()).isNull();
    }

    // -- an answer never says or implies a warning that is not in the facts ------------------

    @Test
    @DisplayName("no warning shown: the sentence that says one is in force is removed, the rest of the answer stands")
    void noWarningShown_claimIsRemoved() {
        Result result = validate("Whitby looks good tonight. An Amber warning of snow is in force.",
                snapshot(served(AMBER_SNOW)), nothingShown());

        assertThat(result.answer().summary()).isEqualTo("Whitby looks good tonight.");
        assertThat(result.answer().warnings()).isNull();
        assertThat(result.answer().picks()).hasSize(1);
    }

    @Test
    @DisplayName("no warning shown and nothing else to say: our own can't-answer, never a 'no warnings' all-clear")
    void onlyAClaim_becomesTheCantAnswer() {
        Result result = validate("A yellow weather warning is in place for Hadrian's Wall.",
                snapshot(served(AMBER_SNOW)), nothingShown());

        assertThat(result.accepted()).isTrue();
        assertThat(result.answer().answerable()).isFalse();
        assertThat(result.answer().summary()).isEqualTo(AskWarningGuard.UNKNOWN_SUMMARY);
        assertThat(result.answer().missing()).isEqualTo(AskWarningGuard.UNKNOWN_MISSING);
        assertThat(result.answer().picks()).isEmpty();
        assertThat(AskWarningGuard.UNKNOWN_SUMMARY).doesNotContainIgnoringCase("no warning")
                .doesNotContainIgnoringCase("all clear");
    }

    @Test
    @DisplayName("a feed-failure snapshot (enabled, stale, empty) still strips a claim: failure is never 'none'")
    void staleEmptyFeed_stillStripsClaims() {
        Result result = validate("Whitby is good. The Met Office has an amber warning of snow.",
                snapshot(servedStale()), nothingShown());

        assertThat(result.answer().summary()).isEqualTo("Whitby is good.");
    }

    @Test
    @DisplayName("a level the model was not shown is removed: Amber was shown, so 'Red warning' is an upgrade")
    void aLevelNotShown_isRemoved() {
        Result result = validate("A Red warning of snow is in force. Whitby looks good.",
                snapshot(served(AMBER_SNOW)), shown("W1"));

        assertThat(result.answer().summary()).isEqualTo("Whitby looks good.");
        assertThat(result.answer().warnings()).isNull();
    }

    @Test
    @DisplayName("a warning id the model invented is not an id the server will attach: only shown ids count")
    void anInventedId_isNeverAttached() {
        Result result = validate("An Amber warning of snow is in force.", snapshot(served(AMBER_SNOW)),
                shown("W-invented"));

        assertThat(result.answer().answerable()).isFalse();
        assertThat(result.answer().warnings()).isNull();
    }

    @Test
    @DisplayName("a pick's reason and the missing phrase that claim a warning the facts lack are dropped")
    void shortPhrases_areDroppedWhole() {
        Raw raw = new Raw(true, "Whitby looks good.", List.of(new RawPick(1L, WINDOW, "Amber warning of snow")),
                List.of(new RawEvent("KING_TIDE", TODAY, "Met Office warning issued")), "Met Office warning data");
        AskEvidence evidence = new AskEvidence(Set.of(new AskEvidence.Pair(1L, WINDOW)),
                Set.of(new AskEvidence.EventFact("KING_TIDE", "King tide", TODAY)), 2);

        Result result = validator.validate(raw, snapshot(served(AMBER_SNOW)), evidence, AskScope.ALL, null, null,
                false);

        assertThat(result.answer().picks().getFirst().why()).isEmpty();
        assertThat(result.answer().events().getFirst().why()).isEmpty();
        assertThat(result.answer().warnings()).isNull();
    }

    @Test
    @DisplayName("an unanswerable submission's 'missing' phrase is held to the same rule")
    void missingPhrase_isHeld() {
        Raw raw = new Raw(false, "PhotoCast has no forecast for that.", List.of(), List.of(),
                "Met Office warning data");

        Result result = validator.validate(raw, snapshot(served(AMBER_SNOW)), nothingShown(), AskScope.ALL, null,
                null, false);

        assertThat(result.answer().missing()).isNull();
    }

    @Test
    @DisplayName("words that merely look alike are left alone: red sky, a warning sign, a safety warning")
    void lookAlikes_areNotClaims() {
        String text = "A red sky is likely. There is a warning sign at the car park. "
                + "Never look at the sun without a safety warning filter.";

        Result result = validate(text, snapshot(served(AMBER_SNOW)), nothingShown());

        assertThat(result.answer().summary()).isEqualTo(text);
    }

    // -- an answer that mentions a shown warning carries the server's facts and attribution ---

    @Test
    @DisplayName("a mention of a shown warning attaches the server's own facts, the attribution and the link")
    void aShownWarning_isAttached() {
        Result result = validate("The Met Office has an Amber warning of snow. Whitby looks good.",
                snapshot(served(AMBER_SNOW)), shown("W1"));

        AskAnswer.WarningsCard card = result.answer().warnings();
        assertThat(card).isNotNull();
        assertThat(card.stale()).isFalse();
        assertThat(card.attribution()).isEqualTo(AskWarningFixtures.ATTRIBUTION);
        assertThat(card.url()).isEqualTo(AskWarningFixtures.URL);
        assertThat(card.items()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo("W1");
            assertThat(item.level()).isEqualTo("AMBER");
            assertThat(item.weatherTypes()).containsExactly("SNOW");
            assertThat(item.phrase()).isEqualTo("Amber warning of snow");
            assertThat(item.headline()).isEqualTo("Snow, heavy at times");
            assertThat(item.validFrom()).isEqualTo("2026-10-05T06:00:00Z");
            assertThat(item.validTo()).isEqualTo("2026-10-06T18:00:00Z");
            assertThat(item.updated()).isFalse();
        });
        assertThat(result.answer().summary()).contains("Amber warning of snow");
    }

    @Test
    @DisplayName("a stale feed's attached facts say so, and an update keeps its Updated flag")
    void staleAndUpdated_areCarried() {
        AskSnapshot.Warning updated = new AskSnapshot.Warning("W1", "3.0", "YELLOW", List.of("WIND"), null, null,
                null, List.of(), true, Set.of(1L));

        Result result = validate("A yellow warning of wind is in force.", snapshot(servedStale(updated)),
                shown("W1"));

        assertThat(result.answer().warnings().stale()).isTrue();
        assertThat(result.answer().warnings().items().getFirst().updated()).isTrue();
        assertThat(result.answer().warnings().items().getFirst().validFrom()).isNull();
    }

    @Test
    @DisplayName("a mention in a pick's reason or an event's reason attaches the facts too")
    void aMentionInAReason_attaches() {
        LocalDate date = TODAY;
        Raw raw = new Raw(true, "Whitby looks good.", List.of(new RawPick(1L, WINDOW, "Amber warning of snow")),
                List.of(), null);
        Raw rawEvent = new Raw(true, "A king tide is due.", List.of(), List.of(new RawEvent("KING_TIDE", date,
                "Met Office warning of wind")), null);
        AskEvidence withEvent = new AskEvidence(Set.of(), Set.of(new AskEvidence.EventFact("KING_TIDE", "King tide",
                date)), 1, AskEvidence.ThreadFact.NONE, Set.of(), Set.of("W1"));

        Result pick = validator.validate(raw, snapshot(served(AMBER_SNOW)), shown("W1"), AskScope.ALL, null, null,
                false);
        Result event = validator.validate(rawEvent, snapshot(served(AMBER_SNOW)), withEvent, AskScope.ALL, null,
                null, false);

        assertThat(pick.answer().warnings()).isNotNull();
        assertThat(event.answer().warnings()).isNotNull();
    }

    @Test
    @DisplayName("an answer that does not mention a warning carries none, though one was shown: nothing changes")
    void noMention_attachesNothing() {
        Result result = validate("Whitby looks good tonight.", snapshot(served(AMBER_SNOW)), shown("W1"));

        assertThat(result.answer().warnings()).isNull();
        assertThat(result.answer().summary()).isEqualTo("Whitby looks good tonight.");
    }

    @Test
    @DisplayName("a shown id whose warning has since left the snapshot attaches nothing")
    void aWithdrawnWarning_attachesNothing() {
        Result result = validate("An Amber warning of snow is in force.", snapshot(served()), shown("W1"));

        assertThat(result.answer().warnings()).isNull();
    }

    @Test
    @DisplayName("a mention with a warning shown but its level unknown is judged on the generic wording alone")
    void aShownWarningWithoutALevel_supportsGenericMentions() {
        AskSnapshot.Warning noLevel = new AskSnapshot.Warning("W1", "1.0", null, List.of("SNOW"), null, null, null,
                List.of(), false, Set.of(1L));

        Result result = validate("The Met Office has a warning of snow in force.", snapshot(served(noLevel)),
                shown("W1"));

        assertThat(result.answer().warnings()).isNotNull();
        assertThat(result.answer().warnings().items().getFirst().phrase()).isEqualTo("Weather warning of snow");
    }

    @Test
    @DisplayName("an answer's JSON omits warnings when there are none: the wire shape before W6")
    void warningsFieldIsOmittedFromJsonWhenNull() throws Exception {
        AskAnswer plain = new AskAnswer(true, "Whitby.", List.of(), List.of(), null);

        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(plain);

        assertThat(json).doesNotContain("warnings");
    }
}
