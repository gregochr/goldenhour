package com.gregochr.goldenhour.service.ask;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link AskQuestionSanitiser}: strip, collapse, trim, cap. */
class AskQuestionSanitiserTest {

    private static String clean(String raw) {
        AskQuestionSanitiser.Result result = AskQuestionSanitiser.sanitise(raw);
        assertThat(result.ok()).as(String.valueOf(result.error())).isTrue();
        assertThat(result.error()).isNull();
        return result.sanitised();
    }

    @Test
    @DisplayName("an ordinary question is returned as it was")
    void ordinary() {
        assertThat(clean("Best spot this weekend?")).isEqualTo("Best spot this weekend?");
    }

    @Test
    @DisplayName("leading and trailing whitespace goes; runs of whitespace, tabs and newlines become one space")
    void whitespace() {
        assertThat(clean("  best \t\n spot \r\n tonight  ")).isEqualTo("best spot tonight");
    }

    @Test
    @DisplayName("non-breaking and other Unicode spaces are whitespace too")
    void unicodeSpaces() {
        assertThat(clean("best spot tonight　?")).isEqualTo("best spot tonight ?");
    }

    @Test
    @DisplayName("control characters vanish without leaving a space: NUL, bell, escape, DEL, the C1 range")
    void controlCharacters() {
        assertThat(clean("be\u0000st\u0007 sp\u001bot\u007f to\u0085ni\u009fght")).isEqualTo("best spot tonight");
    }

    @Test
    @DisplayName("invisible format characters vanish: zero-width space and joiner, bidi override, byte-order mark")
    void formatCharacters() {
        assertThat(clean("be​st‍ sp‮ot﻿")).isEqualTo("best spot");
    }

    @Test
    @DisplayName("an unpaired surrogate is not text and goes; a real pair (an emoji) is kept whole")
    void surrogates() {
        assertThat(clean("sunrise 🌅 at\ud800 Whitby")).isEqualTo("sunrise 🌅 at Whitby");
    }

    @Test
    @DisplayName("accented letters and typographic punctuation are kept: this is a minimum, not B4's allow-list")
    void accentsKept() {
        assertThat(clean("Café at Saltburn’s pier?")).isEqualTo("Café at Saltburn’s pier?");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\n\t \r", "\u0000", "​‌", "\u0000 ​ \u0007"})
    @DisplayName("null, empty, only whitespace, or only characters that are stripped is refused as blank")
    void blank(String raw) {
        AskQuestionSanitiser.Result result = AskQuestionSanitiser.sanitise(raw);

        assertThat(result.ok()).isFalse();
        assertThat(result.sanitised()).isNull();
        assertThat(result.error()).isEqualTo("The question must not be blank.");
    }

    @Test
    @DisplayName("the cap counts cleaned characters: 200 passes, 201 is refused, one under passes")
    void lengthBoundary() {
        assertThat(clean("a".repeat(199))).hasSize(199);
        assertThat(clean("a".repeat(200))).hasSize(200);
        AskQuestionSanitiser.Result over = AskQuestionSanitiser.sanitise("a".repeat(201));
        assertThat(over.ok()).isFalse();
        assertThat(over.error()).isEqualTo("The question must be at most 200 characters.");
    }

    @Test
    @DisplayName("what is stripped or collapsed does not count toward the cap")
    void capCountsCleanedText() {
        String padded = " ".repeat(500) + "a".repeat(200) + "\u0000".repeat(500) + "   ";

        assertThat(clean(padded)).isEqualTo("a".repeat(200));
        assertThat(clean(("a ".repeat(100)).trim())).hasSize(199);
    }

    @Test
    @DisplayName("an emoji counts as one character, not two, so the cap is in characters")
    void capCountsCodePoints() {
        assertThat(clean("🌅".repeat(200))).hasSize(400);
        assertThat(AskQuestionSanitiser.sanitise("🌅".repeat(201)).ok()).isFalse();
    }

    @Test
    @DisplayName("a very long run of padding is refused only when real text passes the cap, never on its length alone")
    void hugeInputWithShortText() {
        assertThat(clean("x" + " ".repeat(100_000))).isEqualTo("x");
    }

    // -- the typed (strict) pass ---------------------------------------------------------------

    private static AskQuestionSanitiser.Result typed(String raw) {
        return AskQuestionSanitiser.sanitiseTyped(raw);
    }

    private static String typedClean(String raw) {
        AskQuestionSanitiser.Result result = typed(raw);
        assertThat(result.ok()).as(String.valueOf(result.error())).isTrue();
        return result.sanitised();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Where's the best spot? (Tonight!)", "Whitby & Saltburn: sunrise/sunset, 5-6 stars.",
        "What’s on at Café Rouge's", "Zürich naïve façade", "Ångström fjörd", "Best spot 2 days from now"})
    @DisplayName("letters (accented too), digits, spaces and the allow-listed punctuation pass untouched")
    void typedAllowList(String question) {
        assertThat(typedClean(question)).isEqualTo(question);
    }

    @ParameterizedTest
    @ValueSource(strings = {"best​spot", "best‍spot", "best‮spot", "﻿best spot",
        "best\u0000spot", "best\u0007spot", "best\u007Fspot", "best\u0085spot", "best 🌅 spot",
        "best spot 😀", "best spot #1", "best spot @home", "best spot <b>", "best spot; drop",
        "best \"spot\"", "best_spot", "best spot +1", "best spot = 5", "best spot – ok", "x ́",
        "best spot [now]", "best spot {now}", "best spot %", "best spot \\ now", "best spot |", "best spot ~",
        "best spot £", "best spot \uD800"})
    @DisplayName("zero-width, bidi, control, emoji, symbol and combining characters are refused, never stripped")
    void typedRefuses(String question) {
        AskQuestionSanitiser.Result result = typed("Is " + question);

        assertThat(result.ok()).isFalse();
        assertThat(result.sanitised()).isNull();
        assertThat(result.normalised()).isNull();
        assertThat(result.error()).contains("letters, numbers and ordinary punctuation");
    }

    @Test
    @DisplayName("an accent typed as letter + combining mark is composed first, so it is the same visible letter")
    void typedComposesAccents() {
        assertThat(typedClean("Café spot")).isEqualTo("Café spot");
        assertThat(typed("Café spot").normalised()).isEqualTo(typed("Café spot").normalised());
    }

    @Test
    @DisplayName("whitespace (tab, newline, no-break space) still collapses to one space and trims, as before")
    void typedCollapsesWhitespace() {
        assertThat(typedClean("  best \t\n spot  tonight \r\n")).isEqualTo("best spot tonight");
    }

    @Test
    @DisplayName("200 code points pass, 201 do not; supplementary letters count once each")
    void typedLengthBoundary() {
        assertThat(typed("a".repeat(199)).ok()).isTrue();
        assertThat(typed("a".repeat(200)).ok()).isTrue();
        assertThat(typed("a".repeat(201)).ok()).isFalse();
        assertThat(typed("a".repeat(201)).error()).contains("at most 200 characters");
        String deseret = "𐐀";
        assertThat(typed(deseret.repeat(200)).ok()).isTrue();
        assertThat(typed(deseret.repeat(201)).ok()).isFalse();
    }

    @Test
    @DisplayName("a raw input over the cap is refused before anything else, even when it would collapse short")
    void typedRawCap() {
        assertThat(typed("x" + " ".repeat(AskQuestionSanitiser.MAX_RAW_LENGTH)).ok()).isFalse();
        assertThat(typed("x" + " ".repeat(AskQuestionSanitiser.MAX_RAW_LENGTH - 1)).ok()).isTrue();
        assertThat(typed("x".repeat(5_000_000)).ok()).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n", "?", "?!", "...", "()", "'", "’ ’"})
    @DisplayName("blank input, and input with no letter or digit in it, is refused")
    void typedRefusesBlankAndWordless(String raw) {
        assertThat(typed(raw).ok()).isFalse();
        assertThat(typed(raw).error()).isNotBlank();
    }

    @Test
    @DisplayName("the lenient pass is unchanged: it still strips invisible characters the strict pass refuses")
    void lenientPassUnchanged() {
        assertThat(clean("be​st sp\u0000ot 🌅")).isEqualTo("best spot 🌅");
        assertThat(AskQuestionSanitiser.sanitise("best").normalised()).isNull();
    }

    @Test
    @DisplayName("the normalised form is lower case, punctuation-free, apostrophe-free and filler-free")
    void normalisedForm() {
        assertThat(typed("Please, could you tell me the Best Spot tonight?!").normalised())
                .isEqualTo("best spot tonight");
        assertThat(typed("What’s the best spot?").normalised()).isEqualTo(typed("what's the best spot").normalised())
                .isEqualTo("whats best spot");
        assertThat(typed("Whitby/Saltburn - sunrise (5-6)").normalised()).isEqualTo("whitby saltburn sunrise 5 6");
        assertThat(typed("BEST   spot").normalised()).isEqualTo("best spot");
    }

    @Test
    @DisplayName("normalising never merges two different questions: day, place, negation and 'my' survive")
    void normalisedKeepsMeaning() {
        assertThat(typed("best spot tonight").normalised()).isNotEqualTo(typed("best spot tomorrow").normalised());
        assertThat(typed("is there snow").normalised()).isNotEqualTo(typed("is there no snow").normalised());
        assertThat(typed("best spot near me").normalised()).isNotEqualTo(typed("best spot near my home").normalised());
        assertThat(typed("best spot at Whitby").normalised()).isNotEqualTo(typed("best spot at Bamburgh").normalised());
        assertThat(typed("will I see aurora").normalised()).contains("will").contains("see");
        java.util.List<String> kept = java.util.List.of("tonight", "tomorrow", "not", "no", "my", "near", "will",
                "should", "weekend", "sunrise", "sunset", "high", "low", "tide");
        for (String word : kept) {
            assertThat(AskQuestionSanitiser.FILLER_WORDS).as(word).doesNotContain(word);
        }
    }

    @Test
    @DisplayName("a question of nothing but filler keeps its words rather than normalising to nothing")
    void normalisedAllFiller() {
        assertThat(typed("Please, tell me!").normalised()).isEqualTo("please tell me");
        assertThat(AskQuestionSanitiser.normalise("the")).isEqualTo("the");
        assertThat(AskQuestionSanitiser.normalise("??")).isEmpty();
    }

    @Test
    @DisplayName("the filler list is one fixed set, lower case, single words only")
    void fillerList() {
        assertThat(AskQuestionSanitiser.FILLER_WORDS).isNotEmpty().allSatisfy(word -> {
            assertThat(word).isEqualTo(word.toLowerCase(java.util.Locale.ROOT));
            assertThat(word).doesNotContain(" ");
        });
    }

    @Test
    @DisplayName("lower-casing is by root locale: a Turkish default locale does not break the key")
    void normalisedRootLocale() {
        java.util.Locale original = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr"));
            assertThat(typed("BEST SPOT IN").normalised()).isEqualTo("best spot in");
        } finally {
            java.util.Locale.setDefault(original);
        }
    }
}
