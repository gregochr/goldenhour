package com.gregochr.goldenhour.service.ask;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The words that make a typed question <em>the same question</em> as a Ready one (plan §2.5 step 5):
 * pure, no Spring, no I/O, so every phrase can be tested without a context.
 *
 * <h2>The rule: every word is accounted for</h2>
 * A question matches a Ready question only if, after the Ready question's own <b>subject phrase</b>
 * ("this weekend", "next few days", "high tide", "snow on the tops"…) is taken out, <b>every word
 * left</b> is one the Ready answer is known to be indifferent to — plain asking words ("where's
 * the best spot to go for") and, for a few questions, a handful of subject-specific words. A word the
 * rules do not know is a reason <em>not</em> to match, so the unknown fails closed:
 * <ul>
 *   <li>a named place ("Whitby", "the Cheviot") is unknown, so "best spot in Whitby this weekend"
 *       goes to the engine (the matcher also checks the snapshot's own place names, see
 *       {@link KeywordAskIntentMatcher}, for a place whose name is made of ordinary words);</li>
 *   <li>a drive or distance constraint ("within an hour", "near me", "from home") is unknown, and
 *       the common ones are also refused by name ({@link #QUALIFIERS});</li>
 *   <li>a day or part of a day the Ready question is not about ("next weekend", "Saturday morning"
 *       for a whole-weekend answer) is unknown;</li>
 *   <li>a number, a negation or a comparison ("worst", "not") is unknown.</li>
 * </ul>
 * A question the matcher declines costs one engine call (and is cached); one it wrongly accepts
 * gives a reader an answer to a different question for free — so the matcher is deliberately narrow.
 * It is a keyword classifier, never embeddings (plan §4 #16).
 *
 * <h2>Subjects</h2>
 * <ul>
 *   <li>{@code BEST_WEEKEND}: "this weekend" / "weekend"; or one weekend day ("Saturday", "this
 *       Sunday") — only while <em>every</em> pick of the served answer is on that day, since the
 *       answer is about both days.</li>
 *   <li>{@code BEST_SOON}: "next few days", "next couple of days", "coming days", "few days", "this
 *       week".</li>
 *   <li>{@code BEST_NEXT}: the window the Ready question was written for, read from its own text —
 *       "this evening" (also "tonight"), "this morning", "tomorrow morning" (also "tomorrow
 *       sunrise"), "tomorrow evening" (also "night", "sunset"), "Saturday evening"…: never another
 *       day or another half of the day.</li>
 *   <li>{@code COASTAL_HIGH}: "high tide" / "high water", optionally with a coastal word.</li>
 *   <li>{@code AM_OR_PM}: "sunrise or sunset" (either order), with the Ready question's own day or no
 *       day at all.</li>
 *   <li>{@code NIGHT_SKY}: "tonight" and one night-sky phrase ("night sky", "stars", "stargazing", "milky
 *       way", "dark sky", "clear skies" or "clear"), with nothing left but asking words and "anything" or
 *       "something". "Tonight" is required (the answer is about the running night) and aurora is not a
 *       night-sky word (the shared answer is astro only).</li>
 *   <li>{@code RARE_EVENTS}: a "rare", "special" or "unusual" and an "event(s)", "anything",
 *       "happening" or "things", with nothing time-bound.</li>
 *   <li>{@code SNOW_TOPS}: "snow" and "tops" (or hills, fells, mountains, summits).</li>
 * </ul>
 * The three best-spot questions and the coastal one also need an asking word ({@code best}, {@code
 * good}, {@code where}, {@code spot}…): a bare "this weekend" is not a question.
 */
final class ReadyIntentRules {

    /**
     * The whole-word phrases by which a follow-up turns the earlier places down ("neither of those take my
     * fancy", "I've been to both recently"). Each is a contiguous run of words as
     * {@link PhraseAskPreFilter#words} splits them, so an apostrophe is a break: "don't fancy" is
     * {@code don t fancy}. Deliberately conservative: a bare "those", or "I've been to Bamburgh" with a place
     * the reader names, or a negated "I haven't been to those", is not a turn-down (the reader may be asking
     * ABOUT it), and a wrongly missed turn-down costs only what a follow-up cost before the rule existed.
     */
    private static final List<List<String>> TURN_DOWN_PHRASES = List.of(
            List.of("neither"),
            List.of("not", "those"),
            List.of("none", "of", "those"),
            List.of("been", "to", "both"),
            List.of("been", "to", "those"),
            List.of("been", "to", "them"),
            List.of("been", "to", "all"),
            List.of("don", "t", "fancy"),
            List.of("dont", "fancy"),
            List.of("something", "else"),
            List.of("somewhere", "else"),
            List.of("other", "than", "those"),
            List.of("apart", "from", "those"),
            List.of("instead", "of", "those"));

    /**
     * Whether a follow-up turns the earlier places down: the words hold one of the {@linkplain
     * #TURN_DOWN_PHRASES turn-down phrases}. Meaningful only for a question that has a thread; a fresh
     * question has no earlier places to turn down. When it is true the thread's picks' LOCATIONS are
     * excluded from the answer (the validator drops them, the prompt says so, the stub honours it).
     *
     * @param words the question's words, as {@link PhraseAskPreFilter#words} splits them
     * @return true when a turn-down phrase is present as whole words
     */
    static boolean turnsDownEarlierPlaces(List<String> words) {
        for (List<String> phrase : TURN_DOWN_PHRASES) {
            for (int i = 0; i + phrase.size() <= words.size(); i++) {
                if (words.subList(i, i + phrase.size()).equals(phrase) && !negatedHaveBeen(words, i, phrase)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * "I haven't been to those" / "I've never been to those" say the opposite of "I've been to those": a
     * {@code been to ...} phrase directly after {@code not}, {@code never} or the {@code t} of "haven't" is
     * not a turn-down.
     */
    private static boolean negatedHaveBeen(List<String> words, int at, List<String> phrase) {
        if (!"been".equals(phrase.getFirst()) || at == 0) {
            return false;
        }
        String before = words.get(at - 1);
        return "not".equals(before) || "never".equals(before) || "t".equals(before);
    }

    /**
     * {@link #turnsDownEarlierPlaces(List)} for a question as the engines receive it.
     *
     * @param question the question
     * @return true when its sanitised text turns the earlier places down
     */
    static boolean turnsDownEarlierPlaces(AskQuestion question) {
        return question != null && question.sanitised() != null
                && turnsDownEarlierPlaces(PhraseAskPreFilter.words(question.sanitised()));
    }

    /**
     * Words that carry a constraint a shared Ready answer ignores. Every one is also an unknown word
     * (so the whitelist would refuse it anyway); naming them keeps the intent in the code and in the
     * tests: drive time and distance, home, proximity, and negation or comparison.
     */
    static final Set<String> QUALIFIERS = Set.of("within", "hour", "hours", "minute", "minutes",
            "mins", "min", "mile", "miles", "km", "kilometres", "kilometers", "drive", "driving",
            "near", "nearby", "nearest", "close", "closest", "home", "my", "from", "away", "under",
            "than", "not", "no", "never", "without", "except", "worst", "least", "only", "just");

    /** Plain asking words every Ready question is indifferent to. */
    private static final Set<String> ASKING = Set.of("where", "wheres", "what", "whats", "which", "is",
            "are", "there", "any", "best", "good", "great", "top", "finest", "nicest", "spot", "spots",
            "place", "places", "location", "locations", "to", "go", "shoot", "photograph", "photos",
            "photo", "for", "in", "on", "at", "of", "over", "worth", "going", "visit", "be", "do",
            "should", "it", "will");

    /** A question that wants a place to go needs at least one of these. */
    private static final Set<String> WANTS_A_PLACE = Set.of("best", "good", "great", "top", "finest",
            "nicest", "where", "wheres", "worth", "spot", "spots", "place", "places", "location",
            "locations", "go", "shoot", "visit");

    private static final List<List<String>> WEEKEND_SUBJECTS = List.of(List.of("this", "weekend"),
            List.of("weekend"));
    private static final List<String> WEEKEND_DAYS = List.of("saturday", "sunday");
    private static final List<List<String>> SOON_SUBJECTS = List.of(
            List.of("next", "couple", "of", "days"), List.of("next", "few", "days"),
            List.of("coming", "days"), List.of("few", "days"), List.of("this", "week"));
    private static final List<List<String>> COASTAL_SUBJECTS = List.of(List.of("high", "tide"),
            List.of("high", "tides"), List.of("high", "water"));
    private static final Set<String> COASTAL_WORDS = Set.of("coastal", "coast", "coastline",
            "seaside", "beach", "beaches", "sea", "shore");
    private static final Set<String> AM_OR_PM_WORDS = Set.of("sunrise", "sunset", "or", "better");
    private static final Set<String> EVENT_ADJECTIVES = Set.of("rare", "special", "unusual");
    private static final Set<String> EVENT_NOUNS = Set.of("event", "events", "anything", "happening",
            "things");
    private static final Set<String> EVENT_WORDS = Set.of("coming", "up", "soon", "sky", "skies");
    private static final Set<String> SNOW_PLACES = Set.of("tops", "top", "hills", "fells", "mountains",
            "summits");
    private static final Set<String> SNOW_WORDS = Set.of("snow", "up", "high");

    /**
     * The phrases that name the night sky as the subject of {@code NIGHT_SKY}. Every one is about
     * tonight's astro conditions and nothing else: "aurora" and "northern lights" are deliberately not
     * here (a shared Ready answer is astro only, never an aurora figure), and neither is "bortle" or
     * any place or drive word.
     */
    private static final List<List<String>> NIGHT_SKY_SUBJECTS = List.of(
            List.of("night", "skies"), List.of("night", "sky"), List.of("dark", "skies"),
            List.of("dark", "sky"), List.of("milky", "way"), List.of("clear", "skies"),
            List.of("clear", "sky"), List.of("star", "gazing"), List.of("stargazing"),
            List.of("stars"), List.of("clear"));

    /** The words a night-sky question may add around its subject: nothing about where, which or when else. */
    private static final Set<String> NIGHT_SKY_WORDS = Set.of("anything", "something");

    /** Every word any rule can accept: a question with a word outside it cannot match anything. */
    private static final Set<String> EVERY_ACCEPTED_WORD;

    static {
        Set<String> all = new HashSet<>(ASKING);
        Stream.of(WEEKEND_SUBJECTS, SOON_SUBJECTS, COASTAL_SUBJECTS)
                .flatMap(List::stream).flatMap(List::stream).forEach(all::add);
        all.addAll(WEEKEND_DAYS);
        all.addAll(COASTAL_WORDS);
        all.addAll(AM_OR_PM_WORDS);
        all.addAll(EVENT_ADJECTIVES);
        all.addAll(EVENT_NOUNS);
        all.addAll(EVENT_WORDS);
        all.addAll(SNOW_PLACES);
        all.addAll(SNOW_WORDS);
        NIGHT_SKY_SUBJECTS.forEach(all::addAll);
        all.addAll(NIGHT_SKY_WORDS);
        // The words of a day-specific Ready text: "tonight", "this morning", "on Saturday evening".
        all.addAll(List.of("tonight", "this", "tomorrow", "today", "morning", "evening", "night"));
        for (DayOfWeek day : DayOfWeek.values()) {
            all.add(day.name().toLowerCase(Locale.ROOT));
        }
        EVERY_ACCEPTED_WORD = Set.copyOf(all);
    }

    private ReadyIntentRules() {
    }

    /**
     * A cheap test that runs before anything is read from the database: could any Ready question
     * possibly match these words? False for any question holding a word no rule accepts, which is
     * nearly every question about a place, a drive or anything else, so those never cost a read.
     *
     * @param words the normalised question's words
     * @return true when every word is one some rule accepts and none is a {@linkplain #QUALIFIERS
     *         qualifier}
     */
    static boolean couldMatchAny(List<String> words) {
        return !words.isEmpty() && words.stream().allMatch(w -> EVERY_ACCEPTED_WORD.contains(w)
                && !QUALIFIERS.contains(w));
    }

    /**
     * Whether a typed question is the same question as a fresh Ready one.
     *
     * @param id    which Ready question
     * @param words the normalised question's words
     * @param ready the fresh Ready question as it would be served: its text fixes a day-specific
     *              subject, and its picks fix a weekend day
     * @return true when the Ready answer answers exactly this question
     */
    static boolean matches(ReadyQuestion id, List<String> words, AskReadyResponse.Question ready) {
        if (!couldMatchAny(words)) {
            return false;
        }
        return switch (id) {
            case BEST_WEEKEND -> bestWeekend(words, ready);
            case BEST_SOON -> asksForAPlace(words, SOON_SUBJECTS, Set.of());
            case BEST_NEXT -> bestNext(words, ready);
            case COASTAL_HIGH -> asksForAPlace(words, COASTAL_SUBJECTS, COASTAL_WORDS);
            case AM_OR_PM -> amOrPm(words, ready);
            case NIGHT_SKY -> nightSky(words);
            case RARE_EVENTS -> rareEvents(words);
            case SNOW_TOPS -> snowTops(words);
        };
    }

    /**
     * Which events question, if any, a question is. The same words and the same rules that decide
     * whether a typed question is a Ready one, so a typed "Any rare events coming up?" and the Ready
     * question of that text are one question to the validator's "none while events were offered" test
     * and to the Ready store. Deliberately the strict rules: a question with a time bound or any word
     * the rules do not accept ("anything this weekend?") is not one, because there "nothing" can be a
     * true answer about the day asked.
     *
     * @param words the sanitised question's words
     * @return {@code RARE_EVENTS} or {@code SNOW_TOPS} when the words are that question, else empty
     */
    static Optional<ReadyQuestion> eventsQuestion(List<String> words) {
        if (!couldMatchAny(words)) {
            return Optional.empty();
        }
        if (rareEvents(words)) {
            return Optional.of(ReadyQuestion.RARE_EVENTS);
        }
        return snowTops(words) ? Optional.of(ReadyQuestion.SNOW_TOPS) : Optional.empty();
    }

    /**
     * {@link #eventsQuestion(List)} for a question as the engines receive it: its sanitised text split
     * into words with the sanitiser's own filler words ({@link AskQuestionSanitiser#FILLER_WORDS}) taken
     * out, exactly the words the typed matcher sees, so "Is there snow on the tops?" is the Ready
     * question of that text although "the" is not one a rule accepts.
     *
     * @param question the question
     * @return the events question it is, or empty
     */
    static Optional<ReadyQuestion> eventsQuestion(AskQuestion question) {
        List<String> words = new ArrayList<>(PhraseAskPreFilter.words(question.sanitised()));
        words.removeAll(AskQuestionSanitiser.FILLER_WORDS);
        return eventsQuestion(words);
    }

    // -- the rules ------------------------------------------------------------------------------

    private static boolean bestWeekend(List<String> words, AskReadyResponse.Question ready) {
        if (asksForAPlace(words, WEEKEND_SUBJECTS, Set.of())) {
            return true;
        }
        for (String day : WEEKEND_DAYS) {
            if (asksForAPlace(words, List.of(List.of("this", day), List.of(day)), Set.of())
                    && everyPickIsOn(ready, DayOfWeek.valueOf(day.toUpperCase(Locale.ROOT)))) {
                return true;
            }
        }
        return false;
    }

    /** The answer covers both days, so it answers "Saturday" only when it names nothing else. */
    private static boolean everyPickIsOn(AskReadyResponse.Question ready, DayOfWeek day) {
        List<AskReadyResponse.Pick> picks = ready.answer().picks();
        return !picks.isEmpty() && picks.stream().allMatch(p -> p.date().getDayOfWeek() == day);
    }

    private static boolean bestNext(List<String> words, AskReadyResponse.Question ready) {
        return asksForAPlace(words, nextWindowSubjects(ready.text()), Set.of());
    }

    /**
     * The phrases that name the window a "Best spot …?" Ready text was written for. The text is the
     * one {@code ReadyQuestion.nextWindowWords} built: "this evening" (or the older
     * "tonight"), "this morning", "tomorrow morning", "tomorrow evening", "on Saturday evening". Anything else
     * yields no phrase, so no match.
     */
    static List<List<String>> nextWindowSubjects(String readyText) {
        List<String> text = PhraseAskPreFilter.words(readyText == null ? "" : readyText);
        if (text.size() < 3 || !"best".equals(text.get(0)) || !"spot".equals(text.get(1))) {
            return List.of();
        }
        List<String> when = new ArrayList<>(text.subList(2, text.size()));
        when.remove("on");
        if (when.equals(List.of("this", "evening"))) {
            // The text's own phrase first (ReadyTextContractTest), then the older "tonight".
            return List.of(List.of("this", "evening"), List.of("tonight"));
        }
        if (when.equals(List.of("tonight"))) {
            // Rows stored under the older "Best spot tonight?" text go through here until replaced.
            return List.of(List.of("tonight"), List.of("this", "evening"));
        }
        if (when.equals(List.of("this", "morning"))) {
            return List.of(List.of("this", "morning"));
        }
        if (when.size() == 2 && "morning".equals(when.get(1))) {
            return List.of(when, List.of(when.getFirst(), "sunrise"));
        }
        if (when.size() == 2 && "evening".equals(when.get(1))) {
            return List.of(when, List.of(when.getFirst(), "night"), List.of(when.getFirst(), "sunset"));
        }
        return List.of();
    }

    private static boolean amOrPm(List<String> words, AskReadyResponse.Question ready) {
        List<String> text = PhraseAskPreFilter.words(ready.text());
        // "Sunrise or sunset today?", "...tomorrow?", "...on Saturday?": the day is the last word.
        String day = text.isEmpty() ? "" : text.getLast();
        for (String required : List.of("sunrise", "or", "sunset")) {
            if (!words.contains(required)) {
                return false;
            }
        }
        for (String word : words) {
            boolean ok = AM_OR_PM_WORDS.contains(word) || ASKING.contains(word) || word.equals(day);
            if (!ok) {
                return false;
            }
        }
        return words.stream().filter("or"::equals).count() == 1
                && words.stream().filter("sunrise"::equals).count() == 1
                && words.stream().filter("sunset"::equals).count() == 1;
    }

    /**
     * "Anything for the night sky tonight?", "stars tonight", "stargazing tonight", "clear skies
     * tonight", "milky way tonight", "dark sky tonight". It must name {@code tonight} exactly once (the
     * answer is about the running night, so a question that names no time, or another one, is the
     * engine's), hold one night-sky subject, and have nothing left over but asking words and
     * {@code anything}/{@code something}. A place, a drive word, "aurora", "tomorrow" or a day name is
     * not accepted, so each of those goes to the engine.
     */
    private static boolean nightSky(List<String> words) {
        if (words.stream().filter("tonight"::equals).count() != 1) {
            return false;
        }
        List<String> withoutTonight = new ArrayList<>(words);
        withoutTonight.remove("tonight");
        return withoutSubject(withoutTonight, NIGHT_SKY_SUBJECTS)
                .filter(rest -> allIn(rest, ASKING, NIGHT_SKY_WORDS)).isPresent();
    }

    private static boolean rareEvents(List<String> words) {
        return words.stream().anyMatch(EVENT_ADJECTIVES::contains)
                && words.stream().anyMatch(EVENT_NOUNS::contains)
                && allIn(words, ASKING, EVENT_ADJECTIVES, EVENT_NOUNS, EVENT_WORDS);
    }

    private static boolean snowTops(List<String> words) {
        return words.contains("snow") && words.stream().anyMatch(SNOW_PLACES::contains)
                && allIn(words, ASKING, SNOW_PLACES, SNOW_WORDS);
    }

    // -- shared ---------------------------------------------------------------------------------

    /**
     * A best-place question about one subject: one of the subject phrases is in the words, nothing
     * left over but asking words (and {@code extra}), and something in it asks for a place.
     */
    private static boolean asksForAPlace(List<String> words, List<List<String>> subjects,
            Set<String> extra) {
        return withoutSubject(words, subjects).filter(rest -> rest.stream().anyMatch(WANTS_A_PLACE::contains)
                && allIn(rest, ASKING, extra)).isPresent();
    }

    /**
     * The words with the first subject phrase that occurs in them removed (longest phrases are
     * listed first), or empty when none does.
     */
    static Optional<List<String>> withoutSubject(List<String> words, List<List<String>> subjects) {
        for (List<String> subject : subjects) {
            int at = indexOf(words, subject);
            if (at >= 0) {
                List<String> rest = new ArrayList<>(words);
                rest.subList(at, at + subject.size()).clear();
                return Optional.of(rest);
            }
        }
        return Optional.empty();
    }

    private static int indexOf(List<String> words, List<String> phrase) {
        return Collections.indexOfSubList(words, phrase);
    }

    @SafeVarargs
    private static boolean allIn(Collection<String> words, Set<String>... allowed) {
        return words.stream().allMatch(w -> {
            for (Set<String> set : allowed) {
                if (set.contains(w)) {
                    return true;
                }
            }
            return false;
        });
    }
}
