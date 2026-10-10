package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.service.ask.ThreadExchange.EventRef;
import com.gregochr.goldenhour.service.ask.ThreadExchange.PickRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AskThreadValidation} and {@link AskQuestionSanitiser#sanitiseThreadSummary}: everything a
 * client can put in a thread is checked, refused as 400 {@code INVALID} when it cannot be used, and
 * cleaned with the live question's own rules; nothing the client sent is echoed in a refusal.
 */
class AskThreadValidationTest {

    private static final LocalDateTime RUN = LocalDateTime.of(2026, 10, 10, 14, 17, 40);
    private static final int MAX = 8;

    private static ThreadExchange exchange() {
        return new ThreadExchange("anything for sunrise with tide alignment this weekend?",
                "Bamburgh and Dunstanburgh both have high water on the light on Saturday.",
                List.of(new PickRef(41L, "2026-10-11_sunrise"), new PickRef(57L, "2026-10-11_sunrise")),
                List.of(new EventRef("KING_TIDE", LocalDate.of(2026, 10, 11))), RUN, false);
    }

    private static ThreadExchange with(String question, String summary) {
        ThreadExchange base = exchange();
        return new ThreadExchange(question, summary, base.picks(), base.events(), RUN, false);
    }

    private static ThreadExchange withPicks(List<PickRef> picks) {
        return new ThreadExchange("Best spot?", "Bamburgh.", picks, List.of(), RUN, false);
    }

    private static ThreadExchange withEvents(List<EventRef> events) {
        return new ThreadExchange("Best spot?", "Bamburgh.", List.of(), events, RUN, false);
    }

    private static AskRefusal refused(List<ThreadExchange> thread) {
        return org.junit.jupiter.api.Assertions.assertThrows(AskRefusal.class,
                () -> AskThreadValidation.validate(thread, MAX));
    }

    // -- nothing sent ----------------------------------------------------------------------------

    @Test
    @DisplayName("no thread, or an empty one, is the empty thread: a fresh question")
    void noThread() {
        assertThat(AskThreadValidation.validate(null, MAX)).isSameAs(AskThread.EMPTY);
        assertThat(AskThreadValidation.validate(List.of(), MAX)).isSameAs(AskThread.EMPTY);
    }

    @Test
    @DisplayName("a good thread comes back whole, oldest first, with its text cleaned")
    void goodThread() {
        ThreadExchange second = new ThreadExchange("  Anything   closer to home?  ",
                "Nothing  closer\n  than Bamburgh.", List.of(), List.of(), RUN, true);

        AskThread thread = AskThreadValidation.validate(List.of(exchange(), second), MAX);

        assertThat(thread.size()).isEqualTo(2);
        assertThat(thread.exchanges().get(0)).isEqualTo(exchange());
        assertThat(thread.exchanges().get(1).question()).isEqualTo("Anything closer to home?");
        assertThat(thread.exchanges().get(1).summary()).isEqualTo("Nothing closer than Bamburgh.");
        assertThat(thread.exchanges().get(1).ready()).isTrue();
    }

    // -- the cap ------------------------------------------------------------------------------------

    @Test
    @DisplayName("the cap is exact: max-exchanges is accepted, one more is INVALID and the sentence names the cap")
    void capBoundary() {
        List<ThreadExchange> atCap = new ArrayList<>();
        for (int i = 0; i < MAX; i++) {
            atCap.add(exchange());
        }
        assertThat(AskThreadValidation.validate(atCap, MAX).size()).isEqualTo(MAX);

        List<ThreadExchange> over = new ArrayList<>(atCap);
        over.add(exchange());
        AskRefusal refusal = refused(over);

        assertThat(refusal.code()).isEqualTo(AskErrorCode.INVALID);
        assertThat(refusal.getMessage()).isEqualTo("The conversation can carry at most 8 earlier questions.");
    }

    @Test
    @DisplayName("a null entry in the thread is INVALID, not an NPE")
    void nullExchange() {
        List<ThreadExchange> thread = Arrays.asList(exchange(), null);

        AskRefusal refusal = refused(thread);

        assertThat(refusal.code()).isEqualTo(AskErrorCode.INVALID);
        assertThat(refusal.getMessage()).isEqualTo("The conversation held an empty entry.");
    }

    // -- each question -------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "emoji 😀 question?", "control\u0007char?", "zero​width?",
        "symbols # and $ and %?"})
    @DisplayName("an earlier question is held to the live question's rules: blank, emoji, control, format and "
            + "off-list characters are INVALID")
    void badQuestion(String question) {
        AskRefusal refusal = refused(List.of(with(question, "Fine.")));

        assertThat(refusal.code()).isEqualTo(AskErrorCode.INVALID);
        assertThat(refusal.getMessage()).isEqualTo("An earlier question in the conversation could not be used.");
    }

    @Test
    @DisplayName("a null question and a 201-character question are INVALID; 200 characters are accepted")
    void questionLength() {
        assertThat(refused(List.of(with(null, "Fine."))).code()).isEqualTo(AskErrorCode.INVALID);
        assertThat(refused(List.of(with("a".repeat(201), "Fine."))).code()).isEqualTo(AskErrorCode.INVALID);
        assertThat(AskThreadValidation.validate(List.of(with("a".repeat(200), "Fine.")), MAX).size())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a refusal never echoes the text that caused it")
    void refusalsDoNotEcho() {
        AskRefusal question = refused(List.of(with("secret 😀 words", "Fine.")));
        AskRefusal summary = refused(List.of(with("Best spot?", "hidden 😀 words")));

        assertThat(question.getMessage()).doesNotContain("secret");
        assertThat(summary.getMessage()).doesNotContain("hidden");
    }

    // -- each summary ---------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "an emoji 🌅 here", "a rain ☔ here", "control\u0007char",
        "zero​width", "a variation️ selector", "decomposed é is composed, but this is a bare "
            + "́ mark"})
    @DisplayName("a summary may not be blank or hold an emoji, a control or format character, or a combining mark")
    void badSummary(String summary) {
        AskRefusal refusal = refused(List.of(with("Best spot?", summary)));

        assertThat(refusal.code()).isEqualTo(AskErrorCode.INVALID);
        assertThat(refusal.getMessage()).isEqualTo("An earlier answer in the conversation could not be used.");
    }

    @Test
    @DisplayName("one 5,000-character word is INVALID: the word cap alone would have let it through")
    void oneHugeWord() {
        AskRefusal refusal = refused(List.of(with("Best spot?", "a".repeat(5_000))));

        assertThat(refusal.code()).isEqualTo(AskErrorCode.INVALID);
        assertThat(AskAnswerValidator.clean("a".repeat(5_000), AskAnswerValidator.SUMMARY_WORDS))
                .as("the validator's clean counts words, so it does not refuse this itself").hasSize(5_000);
    }

    @Test
    @DisplayName("the cap is 500 code points: 500 are accepted and 501 are INVALID")
    void summaryCapBoundary() {
        String at = "abcd ".repeat(99) + "abcd" + "x";
        assertThat(at.codePointCount(0, at.length())).isEqualTo(500);

        assertThat(AskQuestionSanitiser.sanitiseThreadSummary(at).ok()).isTrue();
        assertThat(AskQuestionSanitiser.sanitiseThreadSummary(at + "z").ok()).isFalse();
    }

    @Test
    @DisplayName("whitespace collapses before the cap is counted, and the raw input is bounded before any work")
    void summaryWhitespaceAndRawBound() {
        String padded = "Bamburgh." + " ".repeat(300) + "Dunstanburgh.";
        assertThat(AskQuestionSanitiser.sanitiseThreadSummary(padded).sanitised())
                .isEqualTo("Bamburgh. Dunstanburgh.");

        String huge = "a ".repeat(AskQuestionSanitiser.MAX_THREAD_SUMMARY_RAW_LENGTH);
        assertThat(AskQuestionSanitiser.sanitiseThreadSummary(huge).ok()).isFalse();
        assertThat(AskQuestionSanitiser.sanitiseThreadSummary(null).ok()).isFalse();
    }

    @Test
    @DisplayName("a summary keeps the prose punctuation a question may not: stars, dashes, ellipsis, quotes, "
            + "degrees, and a composed accent")
    void summaryKeepsProse() {
        String prose = "Bamburgh ★★★★ — high water at the light … \"worth it\" at 4°C "
                + "near Café Rouge (Café is composed too).";

        AskQuestionSanitiser.Result result = AskQuestionSanitiser.sanitiseThreadSummary(prose);

        assertThat(result.ok()).isTrue();
        assertThat(result.sanitised()).contains("★★★★", "—", "…", "Café Rouge",
                "Café is composed");
    }

    @Test
    @DisplayName("a summary then gets the validator's clean: URLs removed, brand replaced, 60 words")
    void summaryGetsValidatorClean() {
        String text = "See www.example.com or https://example.org/x for Bamburgh. "
                + "word ".repeat(80);

        String cleaned = AskQuestionSanitiser.sanitiseThreadSummary(text).sanitised();

        assertThat(cleaned).doesNotContain("example").startsWith("See or for Bamburgh.");
        assertThat(cleaned.split(" ")).hasSizeLessThanOrEqualTo(AskAnswerValidator.SUMMARY_WORDS);
    }

    @Test
    @DisplayName("a summary that is nothing but a URL is empty once cleaned, so INVALID")
    void summaryOfOnlyAUrl() {
        assertThat(AskQuestionSanitiser.sanitiseThreadSummary("https://example.com/path").ok()).isFalse();
    }

    // -- ids and times ----------------------------------------------------------------------------------

    @Test
    @DisplayName("a missing generatedAt is INVALID")
    void missingGeneratedAt() {
        ThreadExchange base = exchange();
        ThreadExchange noTime = new ThreadExchange(base.question(), base.summary(), base.picks(), base.events(),
                null, false);

        AskRefusal refusal = refused(List.of(noTime));

        assertThat(refusal.code()).isEqualTo(AskErrorCode.INVALID);
        assertThat(refusal.getMessage()).isEqualTo(
                "An earlier answer in the conversation is missing the time it was given.");
    }

    @Test
    @DisplayName("a pick needs a positive location id and a well-formed window id; anything else is INVALID")
    void badPicks() {
        String message = "An earlier answer in the conversation named a place, window or event that could "
                + "not be read.";
        for (PickRef bad : List.of(new PickRef(null, "2026-10-11_sunrise"), new PickRef(0L, "2026-10-11_sunrise"),
                new PickRef(-3L, "2026-10-11_sunrise"), new PickRef(41L, null), new PickRef(41L, ""),
                new PickRef(41L, "2026-10-11"), new PickRef(41L, "2026-10-11_noon"),
                new PickRef(41L, "2026-13-45_sunrise"), new PickRef(41L, "2026-10-11_SUNRISE"))) {
            AskRefusal refusal = refused(List.of(withPicks(List.of(bad))));
            assertThat(refusal.code()).as(String.valueOf(bad)).isEqualTo(AskErrorCode.INVALID);
            assertThat(refusal.getMessage()).isEqualTo(message);
        }
    }

    @Test
    @DisplayName("a null pick is INVALID and four picks are too many: an answer carries at most three")
    void pickCount() {
        assertThat(refused(List.of(withPicks(Arrays.asList(new PickRef(1L, "2026-10-11_sunrise"), null)))).code())
                .isEqualTo(AskErrorCode.INVALID);
        PickRef pick = new PickRef(1L, "2026-10-11_sunrise");

        assertThat(AskThreadValidation.validate(List.of(withPicks(List.of(pick, pick, pick))), MAX).size())
                .isEqualTo(1);
        assertThat(refused(List.of(withPicks(List.of(pick, pick, pick, pick)))).code())
                .isEqualTo(AskErrorCode.INVALID);
    }

    @Test
    @DisplayName("an event needs a plain type token and a date; null, odd characters and an over-long type are "
            + "INVALID")
    void badEvents() {
        LocalDate date = LocalDate.of(2026, 10, 11);
        for (EventRef bad : List.of(new EventRef(null, date), new EventRef("", date), new EventRef("KING TIDE", date),
                new EventRef("KING​TIDE", date), new EventRef("A".repeat(65), date),
                new EventRef("KING_TIDE", null))) {
            assertThat(refused(List.of(withEvents(List.of(bad)))).code()).as(String.valueOf(bad))
                    .isEqualTo(AskErrorCode.INVALID);
        }
        assertThat(refused(List.of(withEvents(Arrays.asList(new EventRef("KING_TIDE", date), null)))).code())
                .isEqualTo(AskErrorCode.INVALID);
    }

    @Test
    @DisplayName("the event type's own forms are accepted: upper case with underscores and the almanac's dash "
            + "form, up to 64 characters; ten events are the most one exchange may carry")
    void goodEvents() {
        LocalDate date = LocalDate.of(2026, 10, 11);
        List<EventRef> ten = new ArrayList<>();
        for (int i = 0; i < AskThreadValidation.MAX_EVENTS_PER_EXCHANGE; i++) {
            ten.add(new EventRef(i % 2 == 0 ? "KING_TIDE" : "lunar-eclipse", date));
        }

        assertThat(AskThreadValidation.validate(List.of(withEvents(ten)), MAX).size()).isEqualTo(1);
        assertThat(AskThreadValidation.validate(
                List.of(withEvents(List.of(new EventRef("A".repeat(64), date)))), MAX).size()).isEqualTo(1);
        ten.add(new EventRef("KING_TIDE", date));
        assertThat(refused(List.of(withEvents(ten))).code()).isEqualTo(AskErrorCode.INVALID);
    }

    // -- the value types ----------------------------------------------------------------------------------

    @Test
    @DisplayName("an exchange and a thread are immutable copies and read null lists as empty")
    void valueTypes() {
        ThreadExchange bare = new ThreadExchange("Q?", "A.", null, null, RUN, null);
        List<ThreadExchange> source = new ArrayList<>(List.of(bare));
        AskThread thread = new AskThread(source);
        source.clear();

        assertThat(bare.ready()).as("an absent flag is a typed exchange").isFalse();
        assertThat(bare.picks()).isEmpty();
        assertThat(bare.events()).isEmpty();
        assertThat(thread.size()).isEqualTo(1);
        assertThat(thread.isEmpty()).isFalse();
        assertThat(new AskThread(null).isEmpty()).isTrue();
        assertThatThrownBy(() -> thread.exchanges().add(bare)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> bare.picks().add(new PickRef(1L, "2026-10-11_sunrise")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("the reset rule reads typed exchanges only: one mismatch resets, a Ready-origin mismatch does "
            + "not, and a thread with nothing in it never does")
    void resetRule() {
        LocalDateTime live = RUN;
        LocalDateTime other = RUN.plusHours(6);
        ThreadExchange typedSame = new ThreadExchange("Q?", "A.", null, null, live, false);
        ThreadExchange typedOld = new ThreadExchange("Q?", "A.", null, null, other, false);
        ThreadExchange readyOld = new ThreadExchange("Q?", "A.", null, null, other, true);

        assertThat(new AskThread(List.of(typedSame)).staleAgainst(live)).isFalse();
        assertThat(new AskThread(List.of(typedSame, typedOld)).staleAgainst(live)).isTrue();
        assertThat(new AskThread(List.of(typedOld, typedSame)).staleAgainst(live)).isTrue();
        assertThat(new AskThread(List.of(readyOld, typedSame)).staleAgainst(live)).isFalse();
        assertThat(new AskThread(List.of(readyOld)).staleAgainst(live)).isFalse();
        assertThat(AskThread.EMPTY.staleAgainst(live)).isFalse();
    }

    @Test
    @DisplayName("run options carry the thread, default to none, and refuse a Ready conversation that has one")
    void runOptions() {
        AskThread thread = new AskThread(List.of(exchange()));

        assertThat(AskRunOptions.none().thread()).isSameAs(AskThread.EMPTY);
        assertThat(AskRunOptions.typed(thread).thread()).isSameAs(thread);
        assertThat(AskRunOptions.typed(null).thread()).isSameAs(AskThread.EMPTY);
        assertThat(AskRunOptions.ready(9L, null).thread()).isSameAs(AskThread.EMPTY);
        AskRunOptions readyWithThread = new AskRunOptions(null, 9L, thread);
        assertThatThrownBy(() -> readyWithThread.requireConsistentWith(AskUserContext.userLess()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no thread");
        assertThat(AskRunOptions.typed(thread)).isNotEqualTo(AskRunOptions.none());
    }
}
