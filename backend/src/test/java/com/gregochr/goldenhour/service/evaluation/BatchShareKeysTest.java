package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.service.evaluation.BatchShareKeys.Candidate;
import com.gregochr.goldenhour.service.evaluation.BatchShareKeys.Input;
import com.gregochr.goldenhour.service.evaluation.BatchShareKeys.Keys;
import com.gregochr.goldenhour.service.evaluation.EvaluationTask.Forecast.PromptKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BatchShareKeys}.
 */
class BatchShareKeysTest {

    private static final LocalDate DATE = LocalDate.of(2031, 3, 10);
    private static final LocalDateTime TIME = LocalDateTime.of(2031, 3, 10, 7, 23, 45);
    private static final String SETTINGS = "true|false|true";

    private static String message(String name, LocalDateTime time, String tail) {
        return "Location: " + name + ". SUNRISE: " + time + " UTC.\n" + tail;
    }

    private static Input input(String name, LocalDateTime time, String tail) {
        return new Input(PromptKind.SKY, false, 1L, name, DATE, "SUNRISE", time, "model-a", 4000,
                SETTINGS, "model-a|inland", message(name, time, tail));
    }

    private static Keys keys(Input in) {
        return BatchShareKeys.keysFor(in).orElseThrow();
    }

    @Test
    @DisplayName("identical apart from the name: share strict and minute keys")
    void nameOnlyDifference_sharesBothKeys() {
        Keys a = keys(input("Bamburgh", TIME, "Cloud: Low 10%"));
        Keys b = keys(input("Dunstanburgh", TIME, "Cloud: Low 10%"));

        assertThat(a.strict()).isEqualTo(b.strict());
        assertThat(a.minute()).isEqualTo(b.minute());
        assertThat(a.strict()).hasSize(64);
    }

    @Test
    @DisplayName("event time differing by seconds: only the minute key is shared")
    void secondsDifference_sharesOnlyMinuteKey() {
        Keys a = keys(input("Bamburgh", TIME, "x"));
        Keys b = keys(input("Dunstanburgh", TIME.plusSeconds(10), "x"));

        assertThat(a.strict()).isNotEqualTo(b.strict());
        assertThat(a.minute()).isEqualTo(b.minute());
    }

    @Test
    @DisplayName("event time differing by a minute: shares neither key")
    void minuteDifference_sharesNeither() {
        Keys a = keys(input("Bamburgh", TIME, "x"));
        Keys b = keys(input("Dunstanburgh", TIME.plusMinutes(1), "x"));

        assertThat(a.strict()).isNotEqualTo(b.strict());
        assertThat(a.minute()).isNotEqualTo(b.minute());
    }

    @Test
    @DisplayName("a whole-minute time renders without seconds and still matches its minute")
    void wholeMinuteTime_matchesSecondsVariantOnMinuteKey() {
        LocalDateTime whole = LocalDateTime.of(2031, 3, 10, 7, 23);
        Keys a = keys(input("Bamburgh", whole, "x"));
        Keys b = keys(input("Dunstanburgh", TIME, "x"));

        assertThat(a.minute()).isEqualTo(b.minute());
        assertThat(a.strict()).isNotEqualTo(b.strict());
    }

    @Test
    @DisplayName("orientation, model, maxTokens, settings, prefix, date or event differences never share")
    void otherInputs_neverShare() {
        Input base = input("Bamburgh", TIME, "Location orientation: east");
        Input orientation = input("Dunstanburgh", TIME, "Location orientation: west");
        Input model = new Input(PromptKind.SKY, false, 2L, "Dunstanburgh", DATE, "SUNRISE", TIME,
                "model-b", 4000, SETTINGS, "model-a|inland",
                message("Dunstanburgh", TIME, "Location orientation: east"));
        Input tokens = new Input(PromptKind.SKY, false, 2L, "Dunstanburgh", DATE, "SUNRISE", TIME,
                "model-a", 8000, SETTINGS, "model-a|inland",
                message("Dunstanburgh", TIME, "Location orientation: east"));
        Input settings = new Input(PromptKind.SKY, false, 2L, "Dunstanburgh", DATE, "SUNRISE", TIME,
                "model-a", 4000, "false|true|false", "model-a|inland",
                message("Dunstanburgh", TIME, "Location orientation: east"));
        Input prefix = new Input(PromptKind.SKY, false, 2L, "Dunstanburgh", DATE, "SUNRISE", TIME,
                "model-a", 4000, SETTINGS, "model-a|coastal",
                message("Dunstanburgh", TIME, "Location orientation: east"));
        Input date = new Input(PromptKind.SKY, false, 2L, "Dunstanburgh", DATE.plusDays(1), "SUNRISE",
                TIME, "model-a", 4000, SETTINGS, "model-a|inland",
                message("Dunstanburgh", TIME, "Location orientation: east"));
        Input event = new Input(PromptKind.SKY, false, 2L, "Dunstanburgh", DATE, "SUNSET", TIME,
                "model-a", 4000, SETTINGS, "model-a|inland",
                message("Dunstanburgh", TIME, "Location orientation: east"));

        Keys baseKeys = keys(base);
        for (Input other : List.of(orientation, model, tokens, settings, prefix, date, event)) {
            Keys k = keys(other);
            assertThat(k.strict()).isNotEqualTo(baseKeys.strict());
            assertThat(k.minute()).isNotEqualTo(baseKeys.minute());
        }
    }

    @Test
    @DisplayName("forced, null evalRowId and non-sky tasks are ineligible")
    void ineligibleTasks_haveNoKeys() {
        String msg = message("Bamburgh", TIME, "x");
        assertThat(BatchShareKeys.keysFor(new Input(PromptKind.SKY, true, 1L, "Bamburgh", DATE,
                "SUNRISE", TIME, "m", 1, SETTINGS, "p", msg))).isEmpty();
        assertThat(BatchShareKeys.keysFor(new Input(PromptKind.SKY, false, null, "Bamburgh", DATE,
                "SUNRISE", TIME, "m", 1, SETTINGS, "p", msg))).isEmpty();
        assertThat(BatchShareKeys.keysFor(new Input(PromptKind.WOODLAND, false, 1L, "Bamburgh",
                DATE, "SUNRISE", TIME, "m", 1, SETTINGS, "p", msg))).isEmpty();
        assertThat(BatchShareKeys.keysFor(new Input(PromptKind.BLUEBELL, false, 1L, "Bamburgh",
                DATE, "SUNRISE", TIME, "m", 1, SETTINGS, "p", msg))).isEmpty();
    }

    @Test
    @DisplayName("a message without the expected prefix gets a unique key and never shares")
    void missingPrefix_neverShares() {
        Input odd1 = new Input(PromptKind.SKY, false, 1L, "Bamburgh", DATE, "SUNRISE", TIME, "m", 1,
                SETTINGS, "p", "No prefix here");
        Input odd2 = new Input(PromptKind.SKY, false, 2L, "Dunstanburgh", DATE, "SUNRISE", TIME,
                "m", 1, SETTINGS, "p", "No prefix here");
        Input nullName = new Input(PromptKind.SKY, false, 3L, null, DATE, "SUNRISE", TIME, "m", 1,
                SETTINGS, "p", "No prefix here");
        Input nullMessage = new Input(PromptKind.SKY, false, 4L, "Bamburgh", DATE, "SUNRISE", TIME,
                "m", 1, SETTINGS, "p", null);

        List<Keys> all = List.of(keys(odd1), keys(odd2), keys(nullName), keys(nullMessage));
        assertThat(all.stream().map(Keys::strict).distinct()).hasSize(4);
        assertThat(all.get(0).strict()).isEqualTo(all.get(0).minute());
    }

    @Test
    @DisplayName("a name that is not the one the message was rendered with does not share")
    void wrongNameForPrefix_neverShares() {
        Input right = input("Bamburgh", TIME, "x");
        Input wrong = new Input(PromptKind.SKY, false, 2L, "Someone Else", DATE, "SUNRISE", TIME,
                "model-a", 4000, SETTINGS, "model-a|inland", right.userMessage());

        assertThat(keys(wrong).strict()).isNotEqualTo(keys(right).strict());
    }

    @Test
    @DisplayName("the name appearing again later in the message breaks equivalence")
    void nameRepeatedLater_breaksEquivalence() {
        Keys a = keys(input("Bamburgh", TIME, "Near Bamburgh the sea is calm"));
        Keys b = keys(input("Dunstanburgh", TIME, "Near Dunstanburgh the sea is calm"));

        assertThat(a.strict()).isNotEqualTo(b.strict());
        assertThat(a.minute()).isNotEqualTo(b.minute());
    }

    @Test
    @DisplayName("minute key falls back to strict when the event-time text is not where expected")
    void unexpectedEventSegment_minuteKeyEqualsStrictKey() {
        Input odd = new Input(PromptKind.SKY, false, 1L, "Bamburgh", DATE, "SUNRISE", TIME,
                "model-a", 4000, SETTINGS, "model-a|inland", "Location: Bamburgh. Odd text");
        Input nullTime = new Input(PromptKind.SKY, false, 1L, "Bamburgh", DATE, "SUNRISE", null,
                "model-a", 4000, SETTINGS, "model-a|inland", message("Bamburgh", TIME, "x"));

        assertThat(keys(odd).minute()).isEqualTo(keys(odd).strict());
        assertThat(keys(nullTime).minute()).isEqualTo(keys(nullTime).strict());
    }

    @Test
    @DisplayName("summarise counts calls saved and groups of two or more; ineligible tasks only count as tasks")
    void summarise_countsSavedCallsAndGroups() {
        Keys shared = keys(input("A", TIME, "x"));
        Keys sharedMinuteOnly = keys(input("B", TIME.plusSeconds(5), "x"));
        Keys other = keys(input("C", TIME, "y"));
        List<Candidate> candidates = List.of(
                new Candidate(1L, "A", shared),
                new Candidate(2L, "A2", shared),
                new Candidate(3L, "A3", shared),
                new Candidate(4L, "B", sharedMinuteOnly),
                new Candidate(5L, "C", other),
                new Candidate(6L, "forced", null));

        BatchShareKeys.Summary s = BatchShareKeys.summarise(candidates);

        assertThat(s.tasks()).isEqualTo(6);
        assertThat(s.eligible()).isEqualTo(5);
        assertThat(s.strictShareable()).isEqualTo(2);
        assertThat(s.strictGroups()).hasSize(1);
        assertThat(s.minuteShareable()).isEqualTo(3);
        assertThat(s.minuteGroups()).hasSize(1);
        assertThat(s.minuteGroups().get(0)).extracting(Candidate::locationId)
                .containsExactly(1L, 2L, 3L, 4L);
    }

    @Test
    @DisplayName("summarise of nothing is all zeros")
    void summarise_empty() {
        BatchShareKeys.Summary s = BatchShareKeys.summarise(List.of());

        assertThat(s.tasks()).isZero();
        assertThat(s.strictGroups()).isEmpty();
        assertThat(s.eligible()).isZero();
    }
}
