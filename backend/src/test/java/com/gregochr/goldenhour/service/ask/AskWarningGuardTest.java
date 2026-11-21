package com.gregochr.goldenhour.service.ask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.UserRole;
import com.gregochr.goldenhour.service.DriveTimeResolver;
import com.gregochr.goldenhour.service.ask.AskAnswerValidator.Raw;
import com.gregochr.goldenhour.service.ask.AskAnswerValidator.RawEvent;
import com.gregochr.goldenhour.service.ask.AskAnswerValidator.RawPick;
import com.gregochr.goldenhour.service.ask.AskAnswerValidator.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static com.gregochr.goldenhour.service.ask.AskFixtures.TODAY;
import static com.gregochr.goldenhour.service.ask.AskWarningFixtures.served;
import static com.gregochr.goldenhour.service.ask.AskWarningFixtures.servedStale;
import static com.gregochr.goldenhour.service.ask.AskWarningFixtures.twoRegions;
import static com.gregochr.goldenhour.service.ask.AskWarningFixtures.warning;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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

    private Result validateNoPicks(String summary, AskSnapshot snapshot, AskEvidence evidence) {
        return validator.validate(new Raw(true, summary, List.of(), List.of(), null), snapshot, evidence,
                AskScope.ALL, null, null, false);
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
        Result result = validateNoPicks("A yellow weather warning is in place for Hadrian's Wall.",
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
        Result result = validateNoPicks("An Amber warning of snow is in force.", snapshot(served(AMBER_SNOW)),
                shown("W-invented"));

        assertThat(result.answer().answerable()).isFalse();
        assertThat(result.answer().warnings()).isNull();
    }

    @Test
    @DisplayName("a pick's reason and the missing phrase that claim a warning the facts lack are dropped")
    void shortPhrases_areDroppedWhole() {
        Raw raw = new Raw(true, "Whitby looks good.", List.of(new RawPick(1L, WINDOW, "Amber warning of snow")),
                List.of(new RawEvent("KING_TIDE", TODAY, "Met Office warning issued")), "weather warning data");
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
                "weather warning data");

        Result result = validator.validate(raw, snapshot(served(AMBER_SNOW)), nothingShown(), AskScope.ALL, null,
                null, false);

        assertThat(result.answer().missing()).isNull();
    }

    @Test
    @DisplayName("a summary that was only a warning claim keeps the picks under a neutral line, not the can't-answer")
    void onlyAClaimWithPicks_keepsThePicks() {
        Result result = validate("Whitby is your best bet tonight, though the Met Office has a yellow warning for "
                + "wind.", snapshot(served(AMBER_SNOW)), nothingShown());

        assertThat(result.answer().answerable()).isTrue();
        assertThat(result.answer().summary()).isEqualTo(AskWarningGuard.NEUTRAL_SUMMARY);
        assertThat(result.answer().picks()).hasSize(1);
        assertThat(result.answer().warnings()).isNull();
    }

    @ParameterizedTest(name = "stripped with none shown: {0}")
    @ValueSource(strings = {
        "The Met Office has a red snow warning for Keswick.",
        "An amber snow warning covers Keswick from 06:00 Thursday.",
        "A storm warning is out for the coast.",
        "Expect a yellow alert for wind.",
        "A snow warning is in force.",
        "The Met Office warned of heavy snow.",
        "There are no warnings for the Lake District tomorrow.",
        "No weather warning covers Bamburgh.",
        "The Met Office warning was lifted this morning.",
        "The area is clear of any warnings.",
    })
    void phrasings_areStrippedWhenNothingWasShown(String claim) {
        Result result = validate("Whitby looks good. " + claim, snapshot(served(AMBER_SNOW)), nothingShown());

        assertThat(result.answer().summary()).isEqualTo("Whitby looks good.");
    }

    @ParameterizedTest(name = "absence claim never kept: {0}")
    @ValueSource(strings = {
        "There aren't any weather warnings for Whitby.",
        "There aren’t any weather warnings for Whitby.",
        "There are no active Met Office weather warnings for Whitby.",
        "There isn't a warning for Whitby.",
        "There isn’t a weather warning in force for Whitby.",
        "The Met Office doesn't have any warnings out for the coast.",
        "The Met Office hasn’t issued a warning for Whitby.",
        "There's no warning for Whitby tomorrow.",
        "There’s no weather warning in force.",
        "Whitby is not under any current weather warning.",
        "Nothing is in force for Whitby, no alerts at all.",
        "Nothing in force for the coast.",
        "Whitby is free of warnings.",
        "Whitby is free from any weather warnings.",
        "It is all clear of warnings for Whitby.",
        "No warnings are in place.",
        "The Amber warning has been lifted.",
    })
    void absenceClaims_areNeverKept_shownOrNot(String claim) {
        Result none = validate("Whitby looks good. " + claim, snapshot(served(AMBER_SNOW)), nothingShown());
        Result shownWarning = validate("Whitby looks good. " + claim, snapshot(served(AMBER_SNOW)), shown("W1"));

        assertThat(none.answer().summary()).isEqualTo("Whitby looks good.");
        assertThat(shownWarning.answer().summary()).isEqualTo("Whitby looks good.");
        assertThat(shownWarning.answer().warnings()).isNull();
    }

    /** One realistic sentence and whether it survives with no warning shown and with the Amber one shown. */
    private static Arguments row(String sentence, boolean keptNone,
            boolean keptShown) {
        return Arguments.of(sentence, keptNone, keptShown);
    }

    static Stream<Arguments> adversarialTable() {
        return Stream.of(
                // benign photography and eclipse safety: always kept
                row("Bring a tripod, the light at Bamburgh is soft.", true, true),
                row("Expect a red sky and amber light over the harbour.", true, true),
                row("Stay alert on the wet rocks.", true, true),
                row("A word of warning: the rocks are slippery.", true, true),
                row("Park away from the warning sign at the slipway.", true, true),
                row("The tide can turn without warning.", true, true),
                row("Red sky at night, shepherd\u2019s warning.", true, true),
                row("The Met Office models agree on clear skies.", true, true),
                row("The aurora alert level is low tonight.", true, true),
                row("Use a certified solar filter on the lens, not only over your eye.", true, true),
                row("The safety note warns you to use a certified solar filter on the lens.", true, true),
                row("That is the safety warning for the eclipse.", true, true),
                row("Don't look at the sun without a safety warning filter.", true, true),
                row("Keep an eye on the tide.", true, true),
                row("Eclipse glasses are essential; keep an eye on the sky.", true, true),
                row("Heavy snow is likely on the tops.", true, true),
                row("There is no warning sign, just a gate.", true, true),
                // true mentions: kept only with a shown warning
                row("The Met Office has issued an Amber warning of snow.", false, true),
                row("There is a warning in force.", false, true),
                row("An amber snow warning covers the tops.", false, true),
                row("Keep an eye on the warning for snow.", false, true),
                row("A weather warning is in force, so keep an eye on conditions.", false, true),
                row("The Met Office has a warning, bring a filter.", false, true),
                row("Whitby is under a Met Office warning.", false, true),
                row("The Met Office warned of snow.", false, true),
                row("Be aware of the warning.", false, true),
                // invented level: never kept
                row("A red warning of snow is in force.", false, false),
                row("The Met Office has a yellow alert for wind.", false, false),
                row("The Met Office has a yellow warning, bring a filter.", false, false),
                row("An amber warning of snow, and a red sky later.", false, false),
                // absence and lifted: never kept
                row("There are no weather warnings to affect the eclipse.", false, false),
                row("There aren't any warnings for Whitby.", false, false),
                row("No warnings are in place.", false, false),
                row("The Met Office hasn't issued a warning.", false, false),
                row("Nothing in force for the coast.", false, false),
                row("Whitby is free of warnings.", false, false),
                row("The warning was lifted this morning.", false, false),
                row("Skies are all clear, no weather warning.", false, false),
                // documented limits: a warn/alert verb or a hazard note is a claim
                row("Alert drivers to the ice.", false, true),
                row("Warning: wet rocks and strong wind.", false, true),
                row("I would warn you the car park fills early.", false, true));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("adversarialTable")
    void adversarialTable_noWarningShown_andAmberShown(String sentence, boolean keptNone, boolean keptShown) {
        Result none = validate("Whitby looks good. " + sentence, snapshot(served(AMBER_SNOW)), nothingShown());
        Result shownWarning = validate("Whitby looks good. " + sentence, snapshot(served(AMBER_SNOW)),
                shown("W1"));

        assertThat(none.answer().summary()).isEqualTo(keptNone ? "Whitby looks good. " + sentence
                : "Whitby looks good.");
        assertThat(shownWarning.answer().summary()).isEqualTo(keptShown ? "Whitby looks good. " + sentence
                : "Whitby looks good.");
    }

    @ParameterizedTest(name = "[{index}] sanitise and attach agree: {0}")
    @MethodSource("adversarialTable")
    void sanitiseAndAttachAgree_overTheWholeTable(String sentence, boolean keptNone, boolean keptShown) {
        Result none = validate("Whitby looks good. " + sentence, snapshot(served(AMBER_SNOW)), nothingShown());
        Result shownWarning = validate("Whitby looks good. " + sentence, snapshot(served(AMBER_SNOW)),
                shown("W1"));

        // With nothing shown there is never a card. With Amber shown, a card exactly when a retained sentence is a
        // supported warning claim: kept in the shown state but not in the none state.
        assertThat(none.answer().warnings()).isNull();
        assertThat(shownWarning.answer().warnings() != null).isEqualTo(keptShown && !keptNone);
    }

    @Test
    @DisplayName("the colour score's name is not a warning level: 'Red Sky 72' beside a shown Amber warning is kept")
    void theRedSkyScoreNameIsNotAWarningLevel() {
        String sentence = "The Met Office has an Amber warning of snow; Red Sky 72, Golden Hour 60.";

        Result result = validate(sentence, snapshot(served(AMBER_SNOW)), shown("W1"));

        assertThat(result.answer().summary()).isEqualTo(sentence);
        assertThat(result.answer().warnings()).isNotNull();
    }

    @Test
    @DisplayName("a real Red warning is still caught when the same sentence also carries a Red Sky score")
    void aRealRedWarningNextToTheScoreIsStillRemoved() {
        Result result = validate("Whitby looks good. The Met Office has a Red warning of snow; Red Sky 72.",
                snapshot(served(AMBER_SNOW)), shown("W1"));

        assertThat(result.answer().summary()).isEqualTo("Whitby looks good.");
    }

    @Test
    @DisplayName("the card follows the retained claim, not the whole summary: an unrelated 'red sky' does not hide it")
    void aColourSentenceDoesNotHideASupportedClaim() {
        Result result = validate("The Met Office has an Amber warning of snow. A red sky is possible at sunset.",
                snapshot(served(AMBER_SNOW)), shown("W1"));

        assertThat(result.answer().summary())
                .isEqualTo("The Met Office has an Amber warning of snow. A red sky is possible at sunset.");
        assertThat(result.answer().warnings()).isNotNull();
    }

    @Test
    @DisplayName("a warning claim in the second sentence attaches the card")
    void aClaimInTheSecondSentence_attaches() {
        Result result = validate("Whitby looks good tonight. A red sky is likely. The Met Office has an Amber "
                + "warning of snow.", snapshot(served(AMBER_SNOW)), shown("W1"));

        assertThat(result.answer().warnings()).isNotNull();
    }

    @Test
    @DisplayName("a supported claim beside a stripped invented one keeps the card and drops only the invented claim")
    void supportedBesideInvented_keepsTheCard() {
        Result result = validate("The Met Office has an Amber warning of snow. A red warning of wind is in force. "
                + "Whitby looks good.", snapshot(served(AMBER_SNOW)), shown("W1"));

        assertThat(result.answer().summary())
                .isEqualTo("The Met Office has an Amber warning of snow. Whitby looks good.");
        assertThat(result.answer().warnings()).isNotNull();
    }

    @Test
    @DisplayName("a summary with only colour-sky sentences carries no card, though a warning was shown")
    void onlyColourSentences_noCard() {
        Result result = validate("A red sky and amber light are likely. Whitby looks good.",
                snapshot(served(AMBER_SNOW)), shown("W1"));

        assertThat(result.answer().warnings()).isNull();
    }

    @Test
    @DisplayName("a multi-sentence reason is judged sentence by sentence: a supported claim attaches, an invented "
            + "or absence one drops the whole reason")
    void multiSentenceReasons() {
        Raw supported = new Raw(true, "Whitby looks good.", List.of(new RawPick(1L, WINDOW,
                "Clear west. The Met Office has an Amber warning of snow.")), List.of(), null);
        Raw invented = new Raw(true, "Whitby looks good.", List.of(new RawPick(1L, WINDOW,
                "Clear west. No weather warning covers it.")), List.of(), null);

        Result ok = validator.validate(supported, snapshot(served(AMBER_SNOW)), shown("W1"), AskScope.ALL, null, null,
                false);
        Result dropped = validator.validate(invented, snapshot(served(AMBER_SNOW)), shown("W1"), AskScope.ALL, null,
                null, false);

        assertThat(ok.answer().warnings()).isNotNull();
        assertThat(ok.answer().picks().getFirst().why()).contains("Amber warning of snow");
        assertThat(dropped.answer().warnings()).isNull();
        assertThat(dropped.answer().picks().getFirst().why()).isEmpty();
    }

    @Test
    @DisplayName("a retained claim in the missing phrase of a can't-answer attaches the card too")
    void missingPhraseClaim_attaches() {
        Raw raw = new Raw(false, "PhotoCast has no snow depth.", List.of(), List.of(),
                "snow depth beyond the Met Office Amber warning of snow");

        Result result = validator.validate(raw, snapshot(served(AMBER_SNOW)), shown("W1"), AskScope.ALL, null, null,
                false);

        assertThat(result.answer().warnings()).isNotNull();
    }

    @ParameterizedTest(name = "any warning claim needs a shown warning: {0}")
    @ValueSource(strings = {
        "The Met Office has issued a warning for Whitby.",
        "Whitby is under a Met Office warning.",
        "There is a warning in force.",
        "A weather warning has been issued.",
        "Be aware of the warning.",
        "The Met Office warned of snow.",
        "An early warning of snow has gone out.",
        "Warning: heavy snow expected on the tops.",
        "The Met Office alerts say snow is likely.",
    })
    void anyWarnClaim_isKeptOnlyWithAShownWarning(String claim) {
        Result none = validate("Whitby looks good. " + claim, snapshot(served(AMBER_SNOW)), nothingShown());
        Result shownWarning = validate("Whitby looks good. " + claim, snapshot(served(AMBER_SNOW)), shown("W1"));

        assertThat(none.answer().summary()).isEqualTo("Whitby looks good.");
        assertThat(none.answer().warnings()).isNull();
        assertThat(shownWarning.answer().summary()).isEqualTo("Whitby looks good. " + claim);
        assertThat(shownWarning.answer().warnings()).isNotNull();
    }

    @ParameterizedTest(name = "benign use left alone, shown or not: {0}")
    @ValueSource(strings = {
        "A word of warning: the rocks are slippery.",
        "Park away from the warning sign.",
        "There is a warning label on the gate.",
        "Wear a solar filter, that is the safety warning.",
        "The tide can turn without warning.",
        "Stay alert near the water.",
        "Red sky at night, shepherd’s warning.",
        "The aurora alert level is low tonight.",
        "The note warns you to use a certified solar filter on the lens.",
    })
    void benignUses_areLeftAloneShownOrNot(String text) {
        Result none = validate(text, snapshot(served(AMBER_SNOW)), nothingShown());
        Result shownWarning = validate(text, snapshot(served(AMBER_SNOW)), shown("W1"));

        assertThat(none.answer().summary()).isEqualTo(text);
        assertThat(shownWarning.answer().summary()).isEqualTo(text);
        assertThat(shownWarning.answer().warnings()).isNull();
    }

    @Test
    @DisplayName("a level in an eclipse sentence is a weather claim, not the safety note")
    void aLevelBesideTheEclipse_isStillAClaim() {
        Result result = validate("Whitby looks good. An Amber warning of wind may affect the eclipse.",
                snapshot(served(AMBER_SNOW)), nothingShown());

        assertThat(result.answer().summary()).isEqualTo("Whitby looks good.");
    }

    @ParameterizedTest(name = "ordinary sentence left alone: {0}")
    @ValueSource(strings = {
        "Never look at the sun without a safety warning filter.",
        "Don't look at the sun without a safety warning filter.",
        "Don’t park by the warning sign.",
        "Skies aren't all that clear, but the red sky is lovely.",
        "It isn't far to the amber light of the harbour.",
    })
    void ordinarySentences_areLeftAlone(String text) {
        Result result = validate(text, snapshot(served(AMBER_SNOW)), shown("W1"));

        assertThat(result.answer().summary()).isEqualTo(text);
    }

    @Test
    @DisplayName("a claim at a level that was not shown is removed even when the sentence also names the Met Office")
    void aDifferentLevelInAMetOfficeSentence_isRemoved() {
        Result result = validate("Whitby looks good. The Met Office has a red snow warning for Keswick.",
                snapshot(served(AMBER_SNOW)), shown("W1"));

        assertThat(result.answer().summary()).isEqualTo("Whitby looks good.");
        assertThat(result.answer().warnings()).isNull();
    }

    @Test
    @DisplayName("an absence claim is never kept, even beside a shown warning, and attaches no card")
    void absenceClaims_areNeverKept() {
        Result result = validate("Whitby looks good. No weather warning covers Bamburgh.",
                snapshot(served(AMBER_SNOW)), shown("W1"));

        assertThat(result.answer().summary()).isEqualTo("Whitby looks good.");
        assertThat(result.answer().warnings()).isNull();
    }

    @Test
    @DisplayName("a level-and-type phrasing of a shown warning counts as a mention and attaches the card")
    void levelAndTypePhrasing_attaches() {
        Result result = validate("Whitby looks good. The amber snow warning applies from Monday.",
                snapshot(served(AMBER_SNOW)), shown("W1"));

        assertThat(result.answer().summary()).contains("amber snow warning");
        assertThat(result.answer().warnings()).isNotNull();
    }

    @ParameterizedTest(name = "left alone: {0}")
    @ValueSource(strings = {
        "Amber light and a red sky at Whitby.",
        "Red sky at night, shepherd's warning.",
        "A word of warning: be in place by 07:10.",
        "The Met Office models agree on clear skies.",
        "Park in the warning zone sign area.",
    })
    void lookAlikes_areLeftAlone(String text) {
        Result result = validate(text, snapshot(served(AMBER_SNOW)), nothingShown());

        assertThat(result.answer().summary()).isEqualTo(text);
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
    @DisplayName("an unanswerable submission that quotes a shown warning carries the facts and attribution too")
    void cantAnswerQuotingAShownWarning_attaches() {
        Raw raw = new Raw(false, "The Met Office has an Amber warning of snow, but PhotoCast has no snow depth.",
                List.of(), List.of(), "snow depth");

        Result result = validator.validate(raw, snapshot(servedStale(AMBER_SNOW)), shown("W1"), AskScope.ALL, null,
                null, false);

        assertThat(result.answer().answerable()).isFalse();
        assertThat(result.answer().warnings()).isNotNull();
        assertThat(result.answer().warnings().stale()).isTrue();
        assertThat(result.answer().warnings().attribution()).isEqualTo(AskWarningFixtures.ATTRIBUTION);
    }

    @Test
    @DisplayName("an unanswerable submission with no warning mention carries none")
    void plainCantAnswer_carriesNone() {
        Raw raw = new Raw(false, "PhotoCast has no snow depth.", List.of(), List.of(), "snow depth");

        Result result = validator.validate(raw, snapshot(served(AMBER_SNOW)), shown("W1"), AskScope.ALL, null, null,
                false);

        assertThat(result.answer().warnings()).isNull();
    }

    @Test
    @DisplayName("the real link: a conversation whose tool showed a warning ends uncacheable and carries the card")
    void aConversationShownAWarning_isUncacheableAndCarriesTheCard() {
        AskConversation.Deps deps = new AskConversation.Deps(validator, mock(DriveTimeResolver.class),
                new ObjectMapper(), () -> Map.of(1L, new AskLocationNames.Place("Whitby", 54.4, -0.6)));
        AskConversation.Opening opening = AskConversation.open(
                new AskQuestion("Will the snow be heavy?", "will the snow be heavy", null, AskScope.ALL, "plan"),
                snapshot(served(AMBER_SNOW)), new AskUserContext(7L, UserRole.PRO_USER, true), null, deps);
        AskConversation conversation = ((AskConversation.Opening.Open) opening).conversation();
        conversation.tools().listWindows();

        AskRun run = conversation.submit(new Raw(true, "The Met Office has an Amber warning of snow.",
                List.of(new RawPick(1L, WINDOW, "Clear")), List.of(), null), 1, conversation.tools().trace());

        assertThat(run.outcome().uncacheable()).isTrue();
        assertThat(run.outcome().answer().warnings()).isNotNull();
    }

    @Test
    @DisplayName("the attached facts carry when the feed was last read, so a client can order them against its list")
    void attachedFactsCarryTheFeedCheckTime() {
        AskSnapshot.Warnings withTime = new AskSnapshot.Warnings(true, false, List.of(AMBER_SNOW),
                AskWarningFixtures.URL, AskWarningFixtures.ATTRIBUTION, Instant.parse("2026-10-05T11:55:00Z"));

        Result timed = validate("An Amber warning of snow is in force.", snapshot(withTime), shown("W1"));
        Result untimed = validate("An Amber warning of snow is in force.", snapshot(served(AMBER_SNOW)),
                shown("W1"));

        assertThat(timed.answer().warnings().checkedAt()).isEqualTo("2026-10-05T11:55:00Z");
        assertThat(untimed.answer().warnings().checkedAt()).isNull();
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
