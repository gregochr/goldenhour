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
}
