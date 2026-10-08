package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.TargetType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The stored text contract of the Ready catalogue. A Ready question's text is written by
 * {@link ReadyQuestion} at precompute time, stored with the answer, compared at serve time
 * ({@link AskReadyFreshness}) and read back by {@link ReadyIntentRules#nextWindowSubjects} to match a typed
 * question to it. {@code ReadyIntentRulesTest} hard-codes the strings, so a change to the text that
 * {@link ReadyQuestion} builds would fail closed (no match) with no test failing; this class closes that gap
 * by running the two ends against each other, and pins the day words to the formatter they replaced.
 */
class ReadyTextContractTest {

    /** A Monday, so the next fourteen days run through every weekday twice and cover today and tomorrow. */
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 5);

    private static AskSnapshot.Window window(LocalDate date, TargetType type) {
        return new AskSnapshot.Window(date + "_" + type.name().toLowerCase(Locale.ROOT), date, type, null, null,
                null, null, List.of());
    }

    @Test
    @DisplayName("every day from today for two weeks and both events: the \"Best spot ...?\" text "
            + "nextWindowWords builds is read back by nextWindowSubjects as the day and the event it names")
    void nextWindowWordsRoundTrip() {
        for (int offset = 0; offset < 14; offset++) {
            LocalDate date = TODAY.plusDays(offset);
            for (TargetType type : List.of(TargetType.SUNRISE, TargetType.SUNSET)) {
                String text = "Best spot " + ReadyQuestion.nextWindowWords(window(date, type), TODAY) + "?";
                List<List<String>> subjects = ReadyIntentRules.nextWindowSubjects(text);

                assertThat(subjects).as(text).isNotEmpty();
                assertThat(subjects.getFirst()).as(text).isEqualTo(expectedPhrase(date, type));
            }
        }
    }

    /** The words a reader would type for the window, built here without ReadyQuestion's own formatter. */
    private static List<String> expectedPhrase(LocalDate date, TargetType type) {
        boolean sunrise = type == TargetType.SUNRISE;
        if (date.equals(TODAY)) {
            return sunrise ? List.of("this", "morning") : List.of("tonight");
        }
        String day = date.equals(TODAY.plusDays(1)) ? "tomorrow"
                : date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH).toLowerCase(Locale.ROOT);
        return List.of(day, sunrise ? "morning" : "evening");
    }

    @Test
    @DisplayName("the texts a Ready question is stored under, pinned: a changed word would silently stop "
            + "matching the stored rows and typed phrasings")
    void storedTextsArePinned() {
        assertThat(ReadyQuestion.nextWindowWords(window(TODAY, TargetType.SUNSET), TODAY)).isEqualTo("tonight");
        assertThat(ReadyQuestion.nextWindowWords(window(TODAY, TargetType.SUNRISE), TODAY)).isEqualTo("this morning");
        assertThat(ReadyQuestion.nextWindowWords(window(TODAY.plusDays(1), TargetType.SUNRISE), TODAY))
                .isEqualTo("tomorrow morning");
        assertThat(ReadyQuestion.nextWindowWords(window(TODAY.plusDays(1), TargetType.SUNSET), TODAY))
                .isEqualTo("tomorrow evening");
        assertThat(ReadyQuestion.nextWindowWords(window(LocalDate.of(2026, 10, 10), TargetType.SUNSET), TODAY))
                .isEqualTo("on Saturday evening");
        assertThat(ReadyQuestion.dayWords(TODAY, TODAY)).isEqualTo("today");
        assertThat(ReadyQuestion.dayWords(TODAY.plusDays(1), TODAY)).isEqualTo("tomorrow");
    }

    @Test
    @DisplayName("dayWords is \"on \" plus the English weekday name for every weekday, exactly what the formatter "
            + "it replaced produced (Locale.ENGLISH), so no stored text moves")
    void dayWordsMatchTheFormatterItReplaced() {
        for (int offset = 2; offset < 16; offset++) {
            LocalDate date = TODAY.plusDays(offset);
            String old = "on " + date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);

            assertThat(ReadyQuestion.dayWords(date, TODAY)).isEqualTo(old);
        }
        for (DayOfWeek day : DayOfWeek.values()) {
            assertThat(day.getDisplayName(TextStyle.FULL, Locale.UK))
                    .as("Locale.UK and Locale.ENGLISH spell %s alike", day)
                    .isEqualTo(day.getDisplayName(TextStyle.FULL, Locale.ENGLISH));
        }
    }
}
