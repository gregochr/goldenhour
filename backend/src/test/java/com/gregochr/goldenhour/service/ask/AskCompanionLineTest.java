package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.service.ask.AskTools.CompanionKind;
import com.gregochr.goldenhour.service.ask.AskTools.CompanionPairing;
import com.gregochr.goldenhour.service.ask.AskTools.SpotInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link AskCompanionLine}: the engine-written sentence naming the lead's companion. */
class AskCompanionLineTest {

    private static final String WINDOW = "2026-10-05_sunset";
    private static final long LEAD_ID = 2L;
    private static final long COMPANION_ID = 3L;

    private static SpotInfo spot(long id, String name, int rating, String windowId) {
        return new SpotInfo(id, name, "Northumberland", windowId, rating, "PICK", null, null, null, null,
                null, null, null, null);
    }

    private static CompanionPairing pairing(CompanionKind kind, String companionName, int companionRating) {
        return new CompanionPairing(spot(LEAD_ID, "Bamburgh", 5, WINDOW),
                spot(COMPANION_ID, companionName, companionRating, WINDOW), kind);
    }

    private static List<AskPick> picks(long firstId, String windowId) {
        return List.of(new AskPick(1, firstId, "x", "Northumberland", LocalDate.of(2026, 10, 5),
                AskWindowKind.SUNSET, windowId, "why", 5, "PICK"));
    }

    private static List<AskPick> leadPicks() {
        return picks(LEAD_ID, WINDOW);
    }

    @Test
    @DisplayName("YOUR_FAVOURITE: the favourite is named with its star after the summary")
    void yourFavourite() {
        String out = AskCompanionLine.apply("Bamburgh is 5★ tonight.", leadPicks(),
                pairing(CompanionKind.YOUR_FAVOURITE, "Mary's Lighthouse", 4));

        assertThat(out).isEqualTo("Bamburgh is 5★ tonight. Mary's Lighthouse, your favourite, is 4★.");
    }

    @Test
    @DisplayName("YOUR_FAVOURITE reads correctly for a name starting The")
    void yourFavouriteWithThe() {
        assertThat(AskCompanionLine.sentence(pairing(CompanionKind.YOUR_FAVOURITE, "The Heugh", 4)))
                .isEqualTo("The Heugh, your favourite, is 4★.");
    }

    @Test
    @DisplayName("TIED: the companion also scores the same as the lead")
    void tied() {
        String out = AskCompanionLine.apply("Bamburgh is the pick.", leadPicks(),
                pairing(CompanionKind.TIED, "Amble", 4));

        assertThat(out).isEqualTo("Bamburgh is the pick. Amble also scores 4★, the same as Bamburgh.");
    }

    @Test
    @DisplayName("HIGHER_ELSEWHERE: the companion scores higher")
    void higherElsewhere() {
        String out = AskCompanionLine.apply("Your best is Mary's at 4★.", leadPicks(),
                pairing(CompanionKind.HIGHER_ELSEWHERE, "Bamburgh", 5));

        assertThat(out).isEqualTo("Your best is Mary's at 4★. Bamburgh scores higher, at 5★.");
    }

    @Test
    @DisplayName("already named with its own star in one sentence: nothing is appended")
    void alreadyNamedAddsNothing() {
        String summary = "Bamburgh is 5★; your Mary's Lighthouse is 4★ too.";

        assertThat(AskCompanionLine.apply(summary, leadPicks(),
                pairing(CompanionKind.YOUR_FAVOURITE, "Mary's Lighthouse", 4))).isEqualTo(summary);
    }

    @Test
    @DisplayName("the name match ignores case, and a star written with a space still counts")
    void nameIsCaseInsensitiveAndStarMaySitApart() {
        String summary = "Bamburgh is 5★. MARY'S LIGHTHOUSE is 4 ★.";

        assertThat(AskCompanionLine.apply(summary, leadPicks(),
                pairing(CompanionKind.YOUR_FAVOURITE, "Mary's Lighthouse", 4))).isEqualTo(summary);
    }

    @Test
    @DisplayName("named without its own star: the sentence is appended (the lead's 5★ in the sentence is not "
            + "the companion's)")
    void namedWithoutItsOwnStarIsAppended() {
        String out = AskCompanionLine.apply("Bamburgh is 5★ and Mary's Lighthouse is lower.", leadPicks(),
                pairing(CompanionKind.YOUR_FAVOURITE, "Mary's Lighthouse", 5));

        assertThat(out).endsWith(" Mary's Lighthouse, your favourite, is 5★.");
    }

    @Test
    @DisplayName("a star in the next sentence is not the name's sentence: the sentence is appended")
    void starInAnotherSentenceDoesNotCount() {
        String out = AskCompanionLine.apply("Mary's Lighthouse is your place. Bamburgh is 4★.", leadPicks(),
                pairing(CompanionKind.YOUR_FAVOURITE, "Mary's Lighthouse", 4));

        assertThat(out).endsWith(" Mary's Lighthouse, your favourite, is 4★.");
    }

    @Test
    @DisplayName("a tie named with its star but without a same-words clause is appended; with one it is named")
    void tieNeedsSameWords() {
        CompanionPairing tie = pairing(CompanionKind.TIED, "Amble", 4);

        assertThat(AskCompanionLine.apply("Bamburgh is 4★ and Amble is 4★.", leadPicks(), tie))
                .endsWith(" Amble also scores 4★, the same as Bamburgh.");
        String named = "Bamburgh is 4★ and Amble is 4★ as well.";
        assertThat(AskCompanionLine.apply(named, leadPicks(), tie)).isEqualTo(named);
    }

    @Test
    @DisplayName("St. Mary's Lighthouse counts as already named when its own star follows the abbreviation")
    void abbreviationFullStopDoesNotEndTheSentence() {
        String summary = "Bamburgh is 5★ and your St. Mary's Lighthouse is 4★.";

        assertThat(AskCompanionLine.apply(summary, leadPicks(),
                pairing(CompanionKind.YOUR_FAVOURITE, "St. Mary's Lighthouse", 4))).isEqualTo(summary);
    }

    @Test
    @DisplayName("St. Mary's Lighthouse without its star is not named: the sentence is appended")
    void abbreviationWithoutStarIsAppended() {
        String out = AskCompanionLine.apply("Bamburgh is 5★. St. Mary's Lighthouse is fine.", leadPicks(),
                pairing(CompanionKind.YOUR_FAVOURITE, "St. Mary's Lighthouse", 4));

        assertThat(out).endsWith(" St. Mary's Lighthouse, your favourite, is 4★.");
    }

    @Test
    @DisplayName("a named sentence with no terminal punctuation still counts as named")
    void unterminatedNamedSentence() {
        String summary = "Bamburgh is 5★ and your Mary's Lighthouse is 4★";

        assertThat(AskCompanionLine.apply(summary, leadPicks(),
                pairing(CompanionKind.YOUR_FAVOURITE, "Mary's Lighthouse", 4))).isEqualTo(summary);
    }

    @Test
    @DisplayName("a rating that merely ends in the star's digit (14★) is not the companion's 4★")
    void starDigitsMustMatchWhole() {
        String out = AskCompanionLine.apply("Bamburgh is 5★. Mary's Lighthouse is 14★.", leadPicks(),
                pairing(CompanionKind.YOUR_FAVOURITE, "Mary's Lighthouse", 4));

        assertThat(out).endsWith(" Mary's Lighthouse, your favourite, is 4★.");
    }

    @Test
    @DisplayName("a summary with no terminal punctuation gets a full stop first")
    void missingTerminalPunctuation() {
        String out = AskCompanionLine.apply("Bamburgh is 5★ tonight", leadPicks(),
                pairing(CompanionKind.HIGHER_ELSEWHERE, "Amble", 5));

        assertThat(out).isEqualTo("Bamburgh is 5★ tonight. Amble scores higher, at 5★.");
    }

    @Test
    @DisplayName("the word cap: a summary the sentence would push past 60 words is left as it is")
    void wordCapLeavesTheSummaryAlone() {
        String summary = "word ".repeat(AskAnswerValidator.SUMMARY_WORDS - 2).strip() + ".";

        assertThat(AskCompanionLine.apply(summary, leadPicks(),
                pairing(CompanionKind.YOUR_FAVOURITE, "Mary's Lighthouse", 4))).isEqualTo(summary);
    }

    @Test
    @DisplayName("the code-point cap: a summary the sentence would push past 500 code points is left as it is")
    void codePointCapLeavesTheSummaryAlone() {
        String summary = "a".repeat(AskAnswerValidator.THREAD_SUMMARY_MAX_CODE_POINTS - 10) + ".";

        assertThat(AskCompanionLine.apply(summary, leadPicks(),
                pairing(CompanionKind.YOUR_FAVOURITE, "Mary's Lighthouse", 4))).isEqualTo(summary);
    }

    @Test
    @DisplayName("no pairing, no picks or a missing summary: the summary is returned as it is")
    void nothingToSay() {
        CompanionPairing p = pairing(CompanionKind.YOUR_FAVOURITE, "Mary's Lighthouse", 4);

        assertThat(AskCompanionLine.apply("Fine.", leadPicks(), null)).isEqualTo("Fine.");
        assertThat(AskCompanionLine.apply("Fine.", List.of(), p)).isEqualTo("Fine.");
        assertThat(AskCompanionLine.apply("Fine.", null, p)).isEqualTo("Fine.");
        assertThat(AskCompanionLine.apply(null, leadPicks(), p)).isNull();
        assertThat(AskCompanionLine.apply("Fine.", leadPicks(),
                new CompanionPairing(p.lead(), null, CompanionKind.TIED))).isEqualTo("Fine.");
        assertThat(AskCompanionLine.apply("Fine.", leadPicks(),
                new CompanionPairing(null, p.companion(), CompanionKind.TIED))).isEqualTo("Fine.");
        assertThat(AskCompanionLine.apply("Fine.", leadPicks(),
                new CompanionPairing(p.lead(), p.companion(), null))).isEqualTo("Fine.");
    }

    @Test
    @DisplayName("the first pick is not the lead (another place, or the lead in another window): nothing is "
            + "appended")
    void firstPickMustBeTheLead() {
        CompanionPairing p = pairing(CompanionKind.YOUR_FAVOURITE, "Mary's Lighthouse", 4);

        assertThat(AskCompanionLine.apply("Fine.", picks(99L, WINDOW), p)).isEqualTo("Fine.");
        assertThat(AskCompanionLine.apply("Fine.", picks(LEAD_ID, "2026-10-06_sunrise"), p)).isEqualTo("Fine.");
    }
}
