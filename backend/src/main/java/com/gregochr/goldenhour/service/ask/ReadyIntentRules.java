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
 *       "tonight" (also "this evening"), "this morning", "tomorrow morning" (also "tomorrow
 *       sunrise"), "tomorrow evening" (also "night", "sunset"), "Saturday evening"…: never another
 *       day or another half of the day.</li>
 *   <li>{@code COASTAL_HIGH}: "high tide" / "high water", optionally with a coastal word.</li>
 *   <li>{@code AM_OR_PM}: "sunrise or sunset" (either order), with the Ready question's own day or no
 *       day at all.</li>
 *   <li>{@code RARE_EVENTS}: a "rare", "special" or "unusual" and an "event(s)", "anything",
 *       "happening" or "things", with nothing time-bound.</li>
 *   <li>{@code SNOW_TOPS}: "snow" and "tops" (or hills, fells, mountains, summits).</li>
 * </ul>
 * The three best-spot questions and the coastal one also need an asking word ({@code best}, {@code
 * good}, {@code where}, {@code spot}…): a bare "this weekend" is not a question.
 */
final class ReadyIntentRules {

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
     * one {@code ReadyQuestion.nextWindowWords} built: "tonight", "this morning", "tomorrow morning",
     * "tomorrow evening", "on Saturday evening". Anything else yields no phrase, so no match.
     */
    static List<List<String>> nextWindowSubjects(String readyText) {
        List<String> text = PhraseAskPreFilter.words(readyText == null ? "" : readyText);
        if (text.size() < 3 || !"best".equals(text.get(0)) || !"spot".equals(text.get(1))) {
            return List.of();
        }
        List<String> when = new ArrayList<>(text.subList(2, text.size()));
        when.remove("on");
        if (when.equals(List.of("tonight"))) {
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
        List<String> rest = withoutSubject(words, subjects);
        return rest != null && rest.stream().anyMatch(WANTS_A_PLACE::contains)
                && allIn(rest, ASKING, extra);
    }

    /**
     * The words with the first subject phrase that occurs in them removed (longest phrases are
     * listed first), or null when none does.
     */
    static List<String> withoutSubject(List<String> words, List<List<String>> subjects) {
        for (List<String> subject : subjects) {
            int at = indexOf(words, subject);
            if (at >= 0) {
                List<String> rest = new ArrayList<>(words);
                rest.subList(at, at + subject.size()).clear();
                return rest;
            }
        }
        return null;
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
