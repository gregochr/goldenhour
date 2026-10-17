package com.gregochr.goldenhour.service.ask;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The can't-answer pre-filter against a should / should-not table: the owner's phrases refuse as whole
 * words, and the bundle mock's false positives ("open horizon", "national park", "Park Rash") do not.
 */
class PhraseAskPreFilterTest {

    private final PhraseAskPreFilter filter = new PhraseAskPreFilter();

    private static AskQuestion asked(String text) {
        AskQuestionSanitiser.Result cleaned = AskQuestionSanitiser.sanitiseTyped(text);
        return new AskQuestion(cleaned.sanitised(), cleaned.normalised(), null, AskScope.ALL, "plan");
    }

    private Optional<AskAnswer> refuse(String text) {
        return filter.refuse(asked(text));
    }

    @ParameterizedTest(name = "refuses \"{0}\" as {1}")
    @CsvSource(delimiter = '|', textBlock = """
            Where is the car park at Bamburgh?                     | car park information
            Is there parking near Whitby?                          | car park information
            car-park charges at Saltburn                           | car park information
            Any carpark at Hartlepool?                             | car park information
            Is the CAR PARK open?                                  | car park information
            How crowded is Roseberry Topping?                      | visitor numbers or crowd information
            Will there be crowds at sunrise?                       | visitor numbers or crowd information
            Is Seahouses busy on Saturday?                         | visitor numbers or crowd information
            When is it busiest at Whitby?                          | visitor numbers or crowd information
            Is there a queue for the castle?                       | visitor numbers or crowd information
            What are the queues like?                              | visitor numbers or crowd information
            What are the opening times for the abbey?              | opening times
            What are the opening hours?                            | opening times
            Are there toilets at the beach?                        | toilet information
            Is there a toilet nearby?                              | toilet information
            Is there a café near Bamburgh?                         | café, pub, restaurant or shop information
            Any good cafe at Staithes?                             | café, pub, restaurant or shop information
            Where can I buy a coffee at the shop?                  | café, pub, restaurant or shop information
            Is there a pub close to Craster?                       | café, pub, restaurant or shop information
            Best restaurant after sunset?                          | café, pub, restaurant or shop information
            Where are the shops in Whitby?                         | café, pub, restaurant or shop information
            """)
    @DisplayName("each of the owner's phrases refuses, with a fixed missing phrase and no picks")
    void refusesTheOwnersPhrases(String text, String missing) {
        Optional<AskAnswer> answer = refuse(text);

        assertThat(answer).isPresent();
        assertThat(answer.get().answerable()).isFalse();
        assertThat(answer.get().missing()).isEqualTo(missing);
        assertThat(answer.get().picks()).isEmpty();
        assertThat(answer.get().events()).isEmpty();
        assertThat(answer.get().summary()).contains("PhotoCast covers sky colour, weather and tides")
                .endsWith(missing + ".");
    }

    @ParameterizedTest(name = "passes \"{0}\"")
    @ValueSource(strings = {
            "Where is the open horizon best at sunset?",
            "Which national park has the best sunrise?",
            "Is Park Rash worth a visit?",
            "Best view from Parkend this weekend?",
            "Is the sky busy with cloud tonight?",
            "Will the skies be busy at sunset?",
            "Busy skies this weekend?",
            "Are the clouds busy tonight?",
            "Any busy horizon at dawn?",
            "Will the sky look busy?",
            "Opening up in the west by sunset?",
            "Will it open up at the coast tonight?",
            "Best spot for a silhouette this weekend?",
            "Where is it clear this evening?",
            "Is there a spot with a good shopfront reflection?",
            "Best sunset spot near Pubwell?",
            "Anything rare coming up?"})
    @DisplayName("a question the forecast CAN answer is never refused: whole words only, never substrings")
    void passesWhatTheForecastCanAnswer(String text) {
        assertThat(refuse(text)).isEmpty();
    }

    @Test
    @DisplayName("busy is a match as a place word and not beside a sky word, in either order or with a link word")
    void busyIsJudgedByItsNeighbours() {
        assertThat(refuse("Is Whitby busy?")).isPresent();
        assertThat(refuse("Is the sky busy?")).isEmpty();
        assertThat(refuse("busy cloud over the sea")).isEmpty();
        assertThat(refuse("The horizon looks busy")).isEmpty();
        // A sky word far from "busy" does not save it.
        assertThat(refuse("Is the sky clear and is the car busy?")).isPresent();
        assertThat(refuse("Is the sky clear and is the beach busy?")).isPresent();
    }

    @Test
    @DisplayName("the car park and the crowds together get the combined missing phrase")
    void parkingAndCrowdsAreCombined() {
        Optional<AskAnswer> answer = refuse("Is the car park busy this weekend?");

        assertThat(answer).isPresent();
        assertThat(answer.get().missing()).isEqualTo(PhraseAskPreFilter.PARKING_AND_CROWDS);
        assertThat(answer.get().missing().length()).isLessThanOrEqualTo(60);
    }

    @Test
    @DisplayName("with several topics the highest-priority one names what is missing")
    void priorityOrder() {
        assertThat(refuse("Is there a pub with toilets and parking?").get().missing())
                .isEqualTo("car park information");
        assertThat(refuse("Is the pub open? What are the opening times?").get().missing())
                .isEqualTo("opening times");
    }

    @Test
    @DisplayName("a phrase is split by anything that is not a letter or digit, so punctuation cannot hide it")
    void punctuationDoesNotHideAPhrase() {
        assertThat(refuse("car/park?")).isPresent();
        assertThat(refuse("(parking)")).isPresent();
        assertThat(refuse("Sunrise: crowds?")).isPresent();
        assertThat(refuse("what's the queue")).isPresent();
        assertThat(refuse("Is it pub-busy")).isPresent();
    }

    @Test
    @DisplayName("every missing phrase fits the column (60) and the validator's own eight-word cap")
    void missingPhrasesAreWithinTheirCaps() {
        for (String text : List.of("car park", "opening times", "crowds", "toilets", "cafe",
                "car park busy")) {
            String missing = refuse(text).orElseThrow().missing();
            assertThat(missing.length()).isLessThanOrEqualTo(DatabaseAskLog.MAX_MISSING);
            assertThat(missing.split(" ").length).isLessThanOrEqualTo(8);
        }
    }

    @Test
    @DisplayName("a null question or a null sanitised text passes: the filter never throws")
    void nullIsTolerated() {
        assertThat(filter.refuse(null)).isEmpty();
        assertThat(filter.refuse(new AskQuestion(null, null, null, AskScope.ALL, "plan"))).isEmpty();
    }

    @Test
    @DisplayName("words splits on every non-letter, non-digit and lower-cases with the root locale")
    void wordsSplitting() {
        assertThat(PhraseAskPreFilter.words("Car-Park, CAFÉ & 3 pubs!")).containsExactly("car", "park",
                "café", "3", "pubs");
        assertThat(PhraseAskPreFilter.words("   ")).isEmpty();
        // Dotless/dotted I must not be locale-folded: a Turkish default locale cannot change a word.
        assertThat(PhraseAskPreFilter.words("PARKING")).containsExactly("parking");
    }
}
