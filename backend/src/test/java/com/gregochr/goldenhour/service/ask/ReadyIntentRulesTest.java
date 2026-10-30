package com.gregochr.goldenhour.service.ask;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Ready intent rules, phrase by phrase: for each Ready question, the typed phrasings that are
 * exactly that question and the qualified or different ones that are not. Questions go through the real
 * sanitiser, so what is tested is what the endpoint would see.
 */
class ReadyIntentRulesTest {

    private static final LocalDate SAT = LocalDate.of(2026, 10, 10);
    private static final LocalDate SUN = LocalDate.of(2026, 10, 11);

    private static List<String> words(String typed) {
        AskQuestionSanitiser.Result cleaned = AskQuestionSanitiser.sanitiseTyped(typed);
        assertThat(cleaned.ok()).as(typed).isTrue();
        return PhraseAskPreFilter.words(cleaned.normalised());
    }

    private static AskReadyResponse.Pick pickOn(int rank, LocalDate date) {
        return new AskReadyResponse.Pick(rank, rank, "Spot " + rank, "Northumberland", date,
                AskWindowKind.SUNSET, date + "_sunset", "Clear sky.");
    }

    private static AskReadyResponse.Question ready(ReadyQuestion id, String text, LocalDate... pickDates) {
        List<AskReadyResponse.Pick> picks = new ArrayList<>();
        for (int i = 0; i < pickDates.length; i++) {
            picks.add(pickOn(i + 1, pickDates[i]));
        }
        AskReadyResponse.Answer answer = new AskReadyResponse.Answer(true, "ready", "A good one.", picks,
                List.of(), null, List.of());
        return new AskReadyResponse.Question(id.name(), text, id.tabs(),
                LocalDateTime.of(2026, 10, 9, 5, 2), "06:02", answer);
    }

    private static boolean matches(ReadyQuestion id, String typed, AskReadyResponse.Question ready) {
        return ReadyIntentRules.matches(id, words(typed), ready);
    }

    private static AskReadyResponse.Question weekend() {
        return ready(ReadyQuestion.BEST_WEEKEND, "Best spot this weekend?", SAT, SUN);
    }

    // -- BEST_WEEKEND -----------------------------------------------------------------------------

    @ParameterizedTest(name = "BEST_WEEKEND matches \"{0}\"")
    @ValueSource(strings = {"Best spot this weekend?", "Where's good this weekend?",
            "Where's best at the weekend?", "Where should I go this weekend?",
            "Best place to shoot over the weekend", "Could you tell me the best spot this weekend please"})
    @DisplayName("BEST_WEEKEND: the phrasings that are the weekend question")
    void weekendPositives(String typed) {
        assertThat(matches(ReadyQuestion.BEST_WEEKEND, typed, weekend())).isTrue();
    }

    @ParameterizedTest(name = "BEST_WEEKEND declines \"{0}\"")
    @ValueSource(strings = {
            "Best spot next weekend?", "Best spot this weekend near me?",
            "Best spot this weekend within an hour of home?", "Best spot this weekend in Whitby?",
            "Best spot this weekend, 30 miles at most", "Best spot Saturday morning?",
            "Best spot this weekend at sunrise?", "Worst spot this weekend?",
            "This weekend?", "Weekend", "Is it not good this weekend?", "Best spot this weekend for 5 star?",
            "Best spot this weekend from my house", "Best spot the weekend after next"})
    @DisplayName("BEST_WEEKEND: a place, a drive, another weekend or a time of day is not the weekend question")
    void weekendNegatives(String typed) {
        assertThat(matches(ReadyQuestion.BEST_WEEKEND, typed, weekend())).isFalse();
    }

    @Test
    @DisplayName("BEST_WEEKEND: \"Saturday\" matches only while every pick of the answer is on Saturday")
    void weekendDay() {
        AskReadyResponse.Question allSaturday = ready(ReadyQuestion.BEST_WEEKEND,
                "Best spot this weekend?", SAT, SAT);
        AskReadyResponse.Question mixed = weekend();
        AskReadyResponse.Question noPicks = ready(ReadyQuestion.BEST_WEEKEND, "Best spot this weekend?");

        assertThat(matches(ReadyQuestion.BEST_WEEKEND, "Where's good Saturday?", allSaturday)).isTrue();
        assertThat(matches(ReadyQuestion.BEST_WEEKEND, "Where's good this Saturday?", allSaturday)).isTrue();
        assertThat(matches(ReadyQuestion.BEST_WEEKEND, "Where's good Saturday?", mixed)).isFalse();
        assertThat(matches(ReadyQuestion.BEST_WEEKEND, "Where's good Sunday?", allSaturday)).isFalse();
        assertThat(matches(ReadyQuestion.BEST_WEEKEND, "Where's good Saturday?", noPicks)).isFalse();
        assertThat(matches(ReadyQuestion.BEST_WEEKEND, "Where's good Sunday?",
                ready(ReadyQuestion.BEST_WEEKEND, "Best spot this weekend?", SUN))).isTrue();
        // The whole-weekend phrasing never depends on the picks' days.
        assertThat(matches(ReadyQuestion.BEST_WEEKEND, "Best spot this weekend?", noPicks)).isTrue();
    }

    // -- BEST_SOON --------------------------------------------------------------------------------

    private static AskReadyResponse.Question soon() {
        return ready(ReadyQuestion.BEST_SOON, "Best spot in the next few days?",
                LocalDate.of(2026, 10, 6));
    }

    @ParameterizedTest(name = "BEST_SOON matches \"{0}\"")
    @ValueSource(strings = {"Best spot in the next few days?", "Where's good over the next few days?",
            "Best place to go this week?", "Where should I go in the coming days?",
            "Best spot in the next couple of days", "Where's best in the few days"})
    @DisplayName("BEST_SOON: the phrasings that are the next-few-days question")
    void soonPositives(String typed) {
        assertThat(matches(ReadyQuestion.BEST_SOON, typed, soon())).isTrue();
    }

    @ParameterizedTest(name = "BEST_SOON declines \"{0}\"")
    @ValueSource(strings = {"Best spot next week?", "Best spot in the next 3 days?",
            "Best spot in the next few days from home?", "Best spot in the next few days near Whitby?",
            "Best spot in the next few days within an hour?", "Best spot tomorrow?",
            "In the next few days?", "Best spot in the next few days at sunrise?",
            "Best spot in the few days ahead?"})
    @DisplayName("BEST_SOON: another span, a number, a place or a drive is not the next-few-days question")
    void soonNegatives(String typed) {
        assertThat(matches(ReadyQuestion.BEST_SOON, typed, soon())).isFalse();
    }

    // -- BEST_NEXT: the subject is whatever the Ready text says it is for --------------------------

    @ParameterizedTest(name = "BEST_NEXT(tonight) matches \"{0}\"")
    @ValueSource(strings = {"Best spot tonight?", "Where's good tonight?", "Best place to go this evening",
            "Where should I shoot tonight?", "What's the best spot for tonight"})
    @DisplayName("BEST_NEXT, written for tonight: tonight and this evening")
    void nextTonight(String typed) {
        assertThat(matches(ReadyQuestion.BEST_NEXT, typed,
                ready(ReadyQuestion.BEST_NEXT, "Best spot tonight?", SAT))).isTrue();
    }

    @ParameterizedTest(name = "BEST_NEXT(this evening) matches \"{0}\"")
    @ValueSource(strings = {"Best spot tonight", "Best spot this evening", "Where's good tonight?"})
    @DisplayName("BEST_NEXT, written for this evening: tonight and this evening both match")
    void nextThisEvening(String typed) {
        assertThat(matches(ReadyQuestion.BEST_NEXT, typed,
                ready(ReadyQuestion.BEST_NEXT, "Best spot this evening?", SAT))).isTrue();
    }

    @ParameterizedTest(name = "BEST_NEXT(this evening) declines \"{0}\"")
    @ValueSource(strings = {"Best spot this sunset", "Best spot this night", "Best spot this morning"})
    @DisplayName("BEST_NEXT, written for this evening: this sunset and this night are not it")
    void nextThisEveningNegatives(String typed) {
        assertThat(matches(ReadyQuestion.BEST_NEXT, typed,
                ready(ReadyQuestion.BEST_NEXT, "Best spot this evening?", SAT))).isFalse();
    }

    @ParameterizedTest(name = "BEST_NEXT(tonight) declines \"{0}\"")
    @ValueSource(strings = {"Best spot tomorrow evening?", "Best spot this morning?", "Best spot tonight near me?",
            "Best spot tonight at sunrise?", "Best spot tomorrow?", "Best spot tonight within an hour?",
            "Best spot tonight in Whitby?", "Best spot this weekend?", "Tonight?"})
    @DisplayName("BEST_NEXT, written for tonight: another day or half-day, a place or a drive is not it")
    void nextTonightNegatives(String typed) {
        assertThat(matches(ReadyQuestion.BEST_NEXT, typed,
                ready(ReadyQuestion.BEST_NEXT, "Best spot tonight?", SAT))).isFalse();
    }

    @Test
    @DisplayName("BEST_NEXT follows its own text: this morning, tomorrow morning, tomorrow evening, Saturday evening")
    void nextFollowsItsText() {
        AskReadyResponse.Question thisMorning = ready(ReadyQuestion.BEST_NEXT, "Best spot this morning?", SAT);
        AskReadyResponse.Question tomorrowMorning = ready(ReadyQuestion.BEST_NEXT,
                "Best spot tomorrow morning?", SAT);
        AskReadyResponse.Question tomorrowEvening = ready(ReadyQuestion.BEST_NEXT,
                "Best spot tomorrow evening?", SAT);
        AskReadyResponse.Question saturdayEvening = ready(ReadyQuestion.BEST_NEXT,
                "Best spot on Saturday evening?", SAT);
        AskReadyResponse.Question sundayMorning = ready(ReadyQuestion.BEST_NEXT,
                "Best spot on Sunday morning?", SUN);

        assertThat(matches(ReadyQuestion.BEST_NEXT, "Best spot this morning", thisMorning)).isTrue();
        assertThat(matches(ReadyQuestion.BEST_NEXT, "Best spot tonight", thisMorning)).isFalse();
        assertThat(matches(ReadyQuestion.BEST_NEXT, "Best spot tomorrow morning", thisMorning)).isFalse();

        assertThat(matches(ReadyQuestion.BEST_NEXT, "Best spot tomorrow morning", tomorrowMorning)).isTrue();
        assertThat(matches(ReadyQuestion.BEST_NEXT, "Where's good tomorrow sunrise", tomorrowMorning)).isTrue();
        assertThat(matches(ReadyQuestion.BEST_NEXT, "Best spot tomorrow evening", tomorrowMorning)).isFalse();
        assertThat(matches(ReadyQuestion.BEST_NEXT, "Best spot tomorrow", tomorrowMorning)).isFalse();

        assertThat(matches(ReadyQuestion.BEST_NEXT, "Best spot tomorrow evening", tomorrowEvening)).isTrue();
        assertThat(matches(ReadyQuestion.BEST_NEXT, "Best spot tomorrow night", tomorrowEvening)).isTrue();
        assertThat(matches(ReadyQuestion.BEST_NEXT, "Where's good tomorrow sunset", tomorrowEvening)).isTrue();
        assertThat(matches(ReadyQuestion.BEST_NEXT, "Best spot tonight", tomorrowEvening)).isFalse();

        assertThat(matches(ReadyQuestion.BEST_NEXT, "Best spot Saturday evening", saturdayEvening)).isTrue();
        assertThat(matches(ReadyQuestion.BEST_NEXT, "Best spot on Saturday sunset", saturdayEvening)).isTrue();
        assertThat(matches(ReadyQuestion.BEST_NEXT, "Best spot Saturday night", saturdayEvening)).isTrue();
        assertThat(matches(ReadyQuestion.BEST_NEXT, "Best spot Sunday evening", saturdayEvening)).isFalse();
        assertThat(matches(ReadyQuestion.BEST_NEXT, "Best spot Saturday morning", saturdayEvening)).isFalse();

        assertThat(matches(ReadyQuestion.BEST_NEXT, "Where's good Sunday morning", sundayMorning)).isTrue();
        assertThat(matches(ReadyQuestion.BEST_NEXT, "Where's good Sunday evening", sundayMorning)).isFalse();
    }

    @Test
    @DisplayName("BEST_NEXT: a Ready text it cannot read yields no subject, so nothing matches")
    void nextWithAnUnreadableText() {
        assertThat(ReadyIntentRules.nextWindowSubjects(null)).isEmpty();
        assertThat(ReadyIntentRules.nextWindowSubjects("")).isEmpty();
        assertThat(ReadyIntentRules.nextWindowSubjects("Best spot?")).isEmpty();
        assertThat(ReadyIntentRules.nextWindowSubjects("Where next?")).isEmpty();
        assertThat(ReadyIntentRules.nextWindowSubjects("Best spot someday")).isEmpty();
        assertThat(matches(ReadyQuestion.BEST_NEXT, "Best spot tonight",
                ready(ReadyQuestion.BEST_NEXT, "Something else entirely", SAT))).isFalse();
    }

    // -- COASTAL_HIGH -----------------------------------------------------------------------------

    private static AskReadyResponse.Question coastal() {
        return ready(ReadyQuestion.COASTAL_HIGH, "Best coastal spot at high tide?", SAT);
    }

    @ParameterizedTest(name = "COASTAL_HIGH matches \"{0}\"")
    @ValueSource(strings = {"Best coastal spot at high tide?", "Where's good at high tide?",
            "Best beach spot at high water", "Where should I shoot on the coast at high tide?",
            "Best spot for high tides"})
    @DisplayName("COASTAL_HIGH: the phrasings that are the high-tide question")
    void coastalPositives(String typed) {
        assertThat(matches(ReadyQuestion.COASTAL_HIGH, typed, coastal())).isTrue();
    }

    @ParameterizedTest(name = "COASTAL_HIGH declines \"{0}\"")
    @ValueSource(strings = {"Best spot at low tide?", "When is high tide?", "Best coastal spot at high tide near me?",
            "Best coastal spot at high tide in Whitby?", "Best coastal spot at high tide tomorrow?",
            "Best coastal spot at mid tide?", "High tide", "Best spot at high tide this weekend?"})
    @DisplayName("COASTAL_HIGH: low or mid tide, a time, a place or a drive is not the high-tide question")
    void coastalNegatives(String typed) {
        assertThat(matches(ReadyQuestion.COASTAL_HIGH, typed, coastal())).isFalse();
    }

    // -- AM_OR_PM ---------------------------------------------------------------------------------

    private static AskReadyResponse.Question amOrPm(String text) {
        return ready(ReadyQuestion.AM_OR_PM, text, SAT);
    }

    @ParameterizedTest(name = "AM_OR_PM(tomorrow) matches \"{0}\"")
    @ValueSource(strings = {"Sunrise or sunset tomorrow?", "Sunrise or sunset?", "Sunset or sunrise tomorrow?",
            "Which is better, sunrise or sunset?", "Should I go for sunrise or sunset tomorrow?"})
    @DisplayName("AM_OR_PM, written for tomorrow: tomorrow's day, or no day at all")
    void amOrPmPositives(String typed) {
        assertThat(matches(ReadyQuestion.AM_OR_PM, typed, amOrPm("Sunrise or sunset tomorrow?"))).isTrue();
    }

    @ParameterizedTest(name = "AM_OR_PM(tomorrow) declines \"{0}\"")
    @ValueSource(strings = {"Sunrise or sunset today?", "Sunrise or sunset on Saturday?",
            "Sunrise or sunset near me?", "Sunrise or sunset or sunrise?", "Sunrise or sunrise?",
            "Sunrise tomorrow?", "Sunrise or sunset tomorrow in Whitby?", "Sunrise or sunset this weekend?",
            "Sunrise or sunset within an hour?"})
    @DisplayName("AM_OR_PM: another day, a place, a drive, or not both halves is not the comparison")
    void amOrPmNegatives(String typed) {
        assertThat(matches(ReadyQuestion.AM_OR_PM, typed, amOrPm("Sunrise or sunset tomorrow?"))).isFalse();
    }

    @Test
    @DisplayName("AM_OR_PM follows its own day word: today, tomorrow, on Saturday")
    void amOrPmFollowsItsDay() {
        assertThat(matches(ReadyQuestion.AM_OR_PM, "Sunrise or sunset today?",
                amOrPm("Sunrise or sunset today?"))).isTrue();
        assertThat(matches(ReadyQuestion.AM_OR_PM, "Sunrise or sunset tomorrow?",
                amOrPm("Sunrise or sunset today?"))).isFalse();
        assertThat(matches(ReadyQuestion.AM_OR_PM, "Sunrise or sunset Saturday?",
                amOrPm("Sunrise or sunset on Saturday?"))).isTrue();
        assertThat(matches(ReadyQuestion.AM_OR_PM, "Sunrise or sunset on Saturday?",
                amOrPm("Sunrise or sunset on Saturday?"))).isTrue();
        assertThat(matches(ReadyQuestion.AM_OR_PM, "Sunrise or sunset Sunday?",
                amOrPm("Sunrise or sunset on Saturday?"))).isFalse();
    }

    // -- NIGHT_SKY --------------------------------------------------------------------------------

    private static AskReadyResponse.Question nightSky() {
        return new AskReadyResponse.Question(ReadyQuestion.NIGHT_SKY.name(), ReadyQuestion.NIGHT_SKY_TEXT,
                ReadyQuestion.NIGHT_SKY.tabs(), LocalDateTime.of(2026, 10, 9, 5, 2), "06:02",
                new AskReadyResponse.Answer(true, "ready", "Whitby is clear.", List.of(), List.of(), null,
                        List.of()),
                "n1;2026-10-09=1760000000000");
    }

    @ParameterizedTest(name = "NIGHT_SKY matches \"{0}\"")
    @ValueSource(strings = {"Anything for the night sky tonight?", "Any stars tonight?", "Stars tonight",
            "Stargazing tonight?", "Is there a milky way tonight?", "Any clear skies tonight?",
            "Is it clear tonight?", "Clear tonight?", "Dark sky tonight?", "Good night sky tonight?",
            "Where's good for stargazing tonight?", "Anything good for stars tonight?",
            "Please, any stars tonight?", "Star gazing tonight?", "Best stars tonight?"})
    @DisplayName("NIGHT_SKY: the phrasings that are the night-sky-tonight question")
    void nightSkyPositives(String typed) {
        assertThat(matches(ReadyQuestion.NIGHT_SKY, typed, nightSky())).isTrue();
    }

    @ParameterizedTest(name = "NIGHT_SKY declines \"{0}\"")
    @ValueSource(strings = {
            "Anything for the night sky?", "Any stars?", "Night sky", "Tonight",
            "Anything for the night sky tomorrow night?", "Any stars tomorrow?", "Any stars this weekend?",
            "Any stars on Saturday night?", "Stars tonight and tomorrow?", "Any stars tonight tonight?",
            "Anything for the night sky tonight in Whitby?", "Any stars tonight at the coast?",
            "Stars tonight near me?", "Best stars tonight within an hour?", "Stars tonight from home?",
            "Northern lights tonight?", "Any aurora tonight?", "Is the aurora visible tonight?",
            "Is the Bortle low tonight?", "Clear sunset tonight?", "Is the weather clear tonight?",
            "No stars tonight?", "Worst stars tonight?", "Any stars tonight at 10pm?", "Best spot tonight?",
            "Any rare events tonight?"})
    @DisplayName("NIGHT_SKY: no time, another night, a place, a drive, aurora, Bortle or a different subject is not "
            + "the question")
    void nightSkyNegatives(String typed) {
        assertThat(matches(ReadyQuestion.NIGHT_SKY, typed, nightSky())).isFalse();
    }

    @Test
    @DisplayName("NIGHT_SKY: the question text it is stored under is read back as itself by its own rule")
    void nightSkyOwnText() {
        assertThat(matches(ReadyQuestion.NIGHT_SKY, ReadyQuestion.NIGHT_SKY_TEXT, nightSky())).isTrue();
    }

    @Test
    @DisplayName("NIGHT_SKY is no other question's: its phrasings match no other Ready question, and none of the "
            + "other questions' phrasings match it")
    void nightSkyIsExclusive() {
        for (String typed : List.of("Anything for the night sky tonight?", "Any stars tonight?",
                "Clear skies tonight?", "Is it clear tonight?")) {
            for (ReadyQuestion other : ReadyQuestion.values()) {
                if (other != ReadyQuestion.NIGHT_SKY) {
                    assertThat(matches(other, typed, ready(other, anyTextFor(other), SAT, SAT)))
                            .as(typed + " vs " + other).isFalse();
                }
            }
        }
        for (String typed : List.of("Best spot tonight?", "Best spot this weekend?", "Any rare events coming up?",
                "Is there snow on the tops?", "Sunrise or sunset tomorrow?", "Best coastal spot at high tide?")) {
            assertThat(matches(ReadyQuestion.NIGHT_SKY, typed, nightSky())).as(typed).isFalse();
        }
    }

    @Test
    @DisplayName("NIGHT_SKY's words are accepted by the cheap pre-check, and aurora, Bortle and place words are not")
    void nightSkyWordsAndThePreCheck() {
        assertThat(ReadyIntentRules.couldMatchAny(List.of("night", "sky", "tonight"))).isTrue();
        assertThat(ReadyIntentRules.couldMatchAny(List.of("milky", "way", "tonight"))).isTrue();
        assertThat(ReadyIntentRules.couldMatchAny(List.of("stargazing", "tonight"))).isTrue();
        assertThat(ReadyIntentRules.couldMatchAny(List.of("aurora", "tonight"))).isFalse();
        assertThat(ReadyIntentRules.couldMatchAny(List.of("bortle", "tonight"))).isFalse();
        assertThat(ReadyIntentRules.couldMatchAny(List.of("stars", "tonight", "whitby"))).isFalse();
    }

    // -- RARE_EVENTS ------------------------------------------------------------------------------

    private static AskReadyResponse.Question rare() {
        return ready(ReadyQuestion.RARE_EVENTS, "Any rare events coming up?");
    }

    @ParameterizedTest(name = "RARE_EVENTS matches \"{0}\"")
    @ValueSource(strings = {"Any rare events coming up?", "Is there anything rare happening?",
            "Any special events coming up", "Anything unusual happening in the sky?",
            "Are there any rare things happening soon"})
    @DisplayName("RARE_EVENTS: the phrasings that are the rare-events question")
    void rarePositives(String typed) {
        assertThat(matches(ReadyQuestion.RARE_EVENTS, typed, rare())).isTrue();
    }

    @ParameterizedTest(name = "RARE_EVENTS declines \"{0}\"")
    @ValueSource(strings = {"Any rare events this week?", "Any rare events near me?", "Any rare events in Whitby?",
            "Any events coming up?", "Any rare events tonight?", "Rare events within an hour?",
            "Any rare events in the next 3 days?", "What is not rare?", "Rare"})
    @DisplayName("RARE_EVENTS: a time bound, a place, a drive or no rarity is not the question")
    void rareNegatives(String typed) {
        assertThat(matches(ReadyQuestion.RARE_EVENTS, typed, rare())).isFalse();
    }

    // -- SNOW_TOPS --------------------------------------------------------------------------------

    private static AskReadyResponse.Question snow() {
        return ready(ReadyQuestion.SNOW_TOPS, "Is there snow on the tops?");
    }

    @ParameterizedTest(name = "SNOW_TOPS matches \"{0}\"")
    @ValueSource(strings = {"Is there snow on the tops?", "Any snow on the hills?", "Snow on the fells?",
            "Is there any snow up on the mountains?"})
    @DisplayName("SNOW_TOPS: the phrasings that are the snow-on-the-tops question")
    void snowPositives(String typed) {
        assertThat(matches(ReadyQuestion.SNOW_TOPS, typed, snow())).isTrue();
    }

    @ParameterizedTest(name = "SNOW_TOPS declines \"{0}\"")
    @ValueSource(strings = {"Is there snow on the tops tomorrow?", "Snow on the tops near me?",
            "Snow in Whitby?", "Is it snowing on the tops?", "Snow tonight?", "Snow on the tops this weekend?",
            "Is there no snow on the tops?", "Any snow?", "The tops", "What's the snow like on the tops?"})
    @DisplayName("SNOW_TOPS: a time, a place, a drive, a negation or a different subject is not the question")
    void snowNegatives(String typed) {
        assertThat(matches(ReadyQuestion.SNOW_TOPS, typed, snow())).isFalse();
    }

    // -- the gates every rule shares --------------------------------------------------------------

    @Test
    @DisplayName("an explicit qualifier or an unknown word stops every rule before any of them runs")
    void qualifiersStopEverything() {
        for (String qualifier : ReadyIntentRules.QUALIFIERS) {
            List<String> withQualifier = Arrays.asList("best", "spot", "this", "weekend", qualifier);
            assertThat(ReadyIntentRules.couldMatchAny(withQualifier)).as(qualifier).isFalse();
        }
        assertThat(ReadyIntentRules.couldMatchAny(List.of())).isFalse();
        assertThat(ReadyIntentRules.couldMatchAny(List.of("best", "spot", "this", "weekend"))).isTrue();
        assertThat(ReadyIntentRules.couldMatchAny(List.of("best", "spot", "whitby"))).isFalse();
        assertThat(ReadyIntentRules.couldMatchAny(List.of("best", "spot", "42"))).isFalse();
        assertThat(ReadyIntentRules.matches(ReadyQuestion.BEST_WEEKEND, List.of(), weekend())).isFalse();
    }

    @Test
    @DisplayName("every one of the eight Ready questions has a rule that can match its own text")
    void everyReadyQuestionHasARule() {
        String[] typed = {"Best spot this weekend?", "Best spot in the next few days?", "Best spot tonight?",
                "Best coastal spot at high tide?", "Sunrise or sunset tomorrow?",
                "Anything for the night sky tonight?", "Any rare events coming up?",
                "Is there snow on the tops?"};
        String[] texts = {"Best spot this weekend?", "Best spot in the next few days?", "Best spot tonight?",
                "Best coastal spot at high tide?", "Sunrise or sunset tomorrow?",
                "Anything for the night sky tonight?", "Any rare events coming up?",
                "Is there snow on the tops?"};
        assertThat(ReadyQuestion.values()).hasSize(typed.length);
        for (int i = 0; i < typed.length; i++) {
            ReadyQuestion id = ReadyQuestion.values()[i];
            assertThat(matches(id, typed[i], ready(id, texts[i], SAT, SAT))).as(id.name()).isTrue();
        }
    }

    @Test
    @DisplayName("a typed question matches at most one Ready question, whichever the catalogue holds")
    void noQuestionMatchesTwo() {
        List<String> typed = List.of("Best spot this weekend?", "Best spot in the next few days?",
                "Best spot tonight?", "Best coastal spot at high tide?", "Sunrise or sunset tomorrow?",
                "Any rare events coming up?", "Is there snow on the tops?", "Best spot?",
                "Anything for the night sky tonight?", "Any stars tonight?", "Is it clear tonight?");
        for (String text : typed) {
            long matched = Arrays.stream(ReadyQuestion.values()).filter(id -> matches(id, text,
                    ready(id, anyTextFor(id), SAT, SAT))).count();
            assertThat(matched).as(text).isLessThanOrEqualTo(1);
        }
    }

    private static String anyTextFor(ReadyQuestion id) {
        return switch (id) {
            case BEST_WEEKEND -> "Best spot this weekend?";
            case BEST_SOON -> "Best spot in the next few days?";
            case BEST_NEXT -> "Best spot tonight?";
            case COASTAL_HIGH -> "Best coastal spot at high tide?";
            case AM_OR_PM -> "Sunrise or sunset tomorrow?";
            case NIGHT_SKY -> ReadyQuestion.NIGHT_SKY_TEXT;
            case RARE_EVENTS -> "Any rare events coming up?";
            case SNOW_TOPS -> "Is there snow on the tops?";
        };
    }

    @ParameterizedTest(name = "eventsQuestion(\"{0}\") is {1}")
    @CsvSource(delimiter = '|', value = {
            "Any rare events coming up?|RARE_EVENTS",
            "Anything special happening?|RARE_EVENTS",
            "Is there snow on the tops?|SNOW_TOPS",
            "Snow on the fells?|SNOW_TOPS"})
    @DisplayName("the events questions a typed question can be, by the Ready matcher's own rules")
    void eventsQuestion_isTheReadyQuestion(String typed, String expected) {
        assertThat(ReadyIntentRules.eventsQuestion(words(typed))).contains(ReadyQuestion.valueOf(expected));
    }

    @ParameterizedTest(name = "eventsQuestion(\"{0}\") is empty")
    @ValueSource(strings = {"Best spot tonight?", "Any rare events this weekend?",
            "Any rare events within an hour of home?", "What's the pollen count?"})
    @DisplayName("a pick question, a time-bound or qualified events question and a stranger are not events "
            + "questions: there 'nothing' can be a true answer")
    void eventsQuestion_isEmptyOtherwise(String typed) {
        assertThat(ReadyIntentRules.eventsQuestion(PhraseAskPreFilter.words(typed))).isEmpty();
    }

    @Test
    @DisplayName("an empty question is not an events question")
    void eventsQuestion_emptyWords() {
        assertThat(ReadyIntentRules.eventsQuestion(List.of())).isEmpty();
    }

    @ParameterizedTest(name = "an engine question \"{0}\" is {1}")
    @CsvSource(delimiter = '|', value = {
            "Any rare events coming up?|RARE_EVENTS",
            "Is there snow on the tops?|SNOW_TOPS",
            "Could you tell me, is there snow on the tops please?|SNOW_TOPS"})
    @DisplayName("the engines' own Ready texts (and a politely padded one) are events questions: the sanitiser's "
            + "filler words are dropped as the typed matcher drops them")
    void eventsQuestion_ofAnEngineQuestion(String text, String expected) {
        AskQuestion question = new AskQuestion(text, text.toLowerCase(java.util.Locale.ROOT), null, AskScope.ALL,
                "plan");

        assertThat(ReadyIntentRules.eventsQuestion(question)).contains(ReadyQuestion.valueOf(expected));
    }

    // -- turning the earlier places down ----------------------------------------------------------

    private static boolean turnsDown(String typed) {
        AskQuestionSanitiser.Result cleaned = AskQuestionSanitiser.sanitiseTyped(typed);
        assertThat(cleaned.ok()).as(typed).isTrue();
        return ReadyIntentRules.turnsDownEarlierPlaces(
                new AskQuestion(cleaned.sanitised(), cleaned.normalised(), null, AskScope.ALL, "plan"));
    }

    @ParameterizedTest(name = "\"{0}\" turns the earlier places down")
    @ValueSource(strings = {
            "Hmm... neither of those take my fancy. I've been to both recently. What's the best sunset option?",
            "Neither", "Not those, something nearer",
            "None of those please", "I've been to both", "I have been to those", "Been to them all",
            "I've been to all of them", "I don't fancy either of those", "I dont fancy them",
            "Something else?", "Is there somewhere else?", "Other than those, where is good?",
            "Apart from those, what is best?", "Instead of those, anything on the coast?",
            "NEITHER OF THOSE", "i\u2019ve been to both"})
    @DisplayName("each turn-down phrase, in the words the reader types them, is a turn-down")
    void turnsDown_phrases(String typed) {
        assertThat(turnsDown(typed)).isTrue();
    }

    @ParameterizedTest(name = "\"{0}\" does not turn anything down")
    @ValueSource(strings = {
            "I've been to Bamburgh", "I've been to Bamburgh, how is it tomorrow?", "Rank those",
            "Which of those is best?", "those", "Anything closer to home?", "What about the other one?",
            "Is Whitby better than the others?", "Not today, tomorrow?", "None", "Something good tonight?",
            "Where else is the tide right?", "Best sunset option?", "Neighbours of Whitby",
            "I have not been to those", "I haven't been to those", "I've never been to those"})
    @DisplayName("a place named alone, 'those' alone, a comparison and a different word that merely shares one "
            + "are not turn-downs: the rule is whole-word phrases and nothing looser")
    void turnsDown_negatives(String typed) {
        assertThat(turnsDown(typed)).isFalse();
    }

    @Test
    @DisplayName("the word test is whole-word and order-sensitive: a phrase split by another word or reversed "
            + "does not match, and empty words do not match")
    void turnsDown_wordTest() {
        assertThat(ReadyIntentRules.turnsDownEarlierPlaces(List.of("none", "really", "of", "those"))).isFalse();
        assertThat(ReadyIntentRules.turnsDownEarlierPlaces(List.of("those", "not"))).isFalse();
        assertThat(ReadyIntentRules.turnsDownEarlierPlaces(List.of("neither"))).isTrue();
        assertThat(ReadyIntentRules.turnsDownEarlierPlaces(List.of())).isFalse();
        assertThat(ReadyIntentRules.turnsDownEarlierPlaces((AskQuestion) null)).isFalse();
        assertThat(ReadyIntentRules.turnsDownEarlierPlaces(
                new AskQuestion(null, null, null, AskScope.ALL, "plan"))).isFalse();
    }
}
