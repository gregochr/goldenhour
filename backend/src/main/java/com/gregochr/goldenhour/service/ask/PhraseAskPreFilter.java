package com.gregochr.goldenhour.service.ask;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The can't-answer pre-filter (plan §2.5 step 3, §1 #15): turns away, free and before anything is
 * built or spent, a question about something PhotoCast holds no data on — car parks, crowds, opening
 * times, toilets, cafés, shops, pubs and restaurants.
 *
 * <p><b>Whole words, never substrings.</b> The design bundle's mock matched a regex
 * ({@code /busy|crowd|park|people|open|…/}) that fired on "open horizon" and "national park". This
 * filter splits the sanitised question into words (anything that is not a letter or a digit is a
 * break, so "car-park" and "car park" are the same phrase) and looks for whole-word phrases only:
 * "Park Rash" has no "car", "open horizon" has no "opening times", and "parking" is a word of its
 * own. A phrase never matches inside a longer word.
 *
 * <p><b>The list</b> (the owner's, plus the plain inflections of each word): car park(s) / carpark /
 * parking; crowd(s) / crowded / crowding; busy / busier / busiest; queue(s) / queued / queuing;
 * opening time(s) / opening hour(s); toilet(s); café(s) / cafe(s); shop(s); pub(s); restaurant(s).
 *
 * <p><b>"Busy" is the one judgement call.</b> The owner's list has it and it is kept, as a whole
 * word, but a sky is "busy" too ("busy skies", "is the sky busy with cloud"), and refusing that
 * question for free would deny a forecast question the product exists to answer. A "busy" next to
 * a sky word ({@code sky}, {@code skies}, {@code cloud}, {@code clouds}, {@code horizon}) — directly
 * before or after it, or before it with an "is/are/looks/gets" between — is therefore not a match.
 * A wrongly passed question costs one engine call (which says "not in the forecast" and is
 * refunded); a wrongly refused one costs the reader their answer, so the doubt goes to passing it on.
 *
 * <p>What the answer says is fixed here, not the model's: the summary names what PhotoCast covers
 * and the {@code missing} phrase names what it does not. Runs before the Ready intent match, so "is
 * the car park busy this weekend" is a can't-answer and never a Ready answer.
 */
@Component
public class PhraseAskPreFilter implements AskPreFilter {

    /** What the question wants that PhotoCast does not hold. Declared in priority order. */
    private enum Topic {
        PARKING("car park information"),
        OPENING("opening times"),
        CROWDS("visitor numbers or crowd information"),
        TOILETS("toilet information"),
        FOOD_AND_SHOPS("café, pub, restaurant or shop information");

        private final String missing;

        Topic(String missing) {
            this.missing = missing;
        }
    }

    /** The {@code missing} phrase when a question asks about the car park <em>and</em> the crowds. */
    static final String PARKING_AND_CROWDS = "visitor numbers or car park data";

    private static final String SUMMARY_PREFIX =
            "PhotoCast covers sky colour, weather and tides. It has no ";

    /** Single words that put a question in a topic. */
    private static final Map<String, Topic> WORDS = Map.ofEntries(
            Map.entry("parking", Topic.PARKING),
            Map.entry("carpark", Topic.PARKING),
            Map.entry("carparks", Topic.PARKING),
            Map.entry("crowd", Topic.CROWDS),
            Map.entry("crowds", Topic.CROWDS),
            Map.entry("crowded", Topic.CROWDS),
            Map.entry("crowding", Topic.CROWDS),
            Map.entry("busy", Topic.CROWDS),
            Map.entry("busier", Topic.CROWDS),
            Map.entry("busiest", Topic.CROWDS),
            Map.entry("queue", Topic.CROWDS),
            Map.entry("queues", Topic.CROWDS),
            Map.entry("queued", Topic.CROWDS),
            Map.entry("queuing", Topic.CROWDS),
            Map.entry("queueing", Topic.CROWDS),
            Map.entry("toilet", Topic.TOILETS),
            Map.entry("toilets", Topic.TOILETS),
            Map.entry("café", Topic.FOOD_AND_SHOPS),
            Map.entry("cafés", Topic.FOOD_AND_SHOPS),
            Map.entry("cafe", Topic.FOOD_AND_SHOPS),
            Map.entry("cafes", Topic.FOOD_AND_SHOPS),
            Map.entry("shop", Topic.FOOD_AND_SHOPS),
            Map.entry("shops", Topic.FOOD_AND_SHOPS),
            Map.entry("pub", Topic.FOOD_AND_SHOPS),
            Map.entry("pubs", Topic.FOOD_AND_SHOPS),
            Map.entry("restaurant", Topic.FOOD_AND_SHOPS),
            Map.entry("restaurants", Topic.FOOD_AND_SHOPS));

    /** The second word of a two-word phrase, by its first word. */
    private static final Map<String, Map<String, Topic>> PAIRS = Map.of(
            "car", Map.of("park", Topic.PARKING, "parks", Topic.PARKING,
                    "parking", Topic.PARKING),
            "opening", Map.of("times", Topic.OPENING, "time", Topic.OPENING,
                    "hours", Topic.OPENING, "hour", Topic.OPENING));

    /** A "busy" beside one of these is a busy sky, not a busy place. */
    private static final Set<String> SKY_WORDS = Set.of("sky", "skies", "cloud", "clouds", "clouded",
            "horizon");

    /** The words that may sit between a sky word and "busy": "the sky is busy". */
    private static final Set<String> LINKING_WORDS = Set.of("is", "are", "looks", "look", "looking",
            "gets", "get", "be");

    @Override
    public Optional<AskAnswer> refuse(AskQuestion question) {
        if (question == null || question.sanitised() == null) {
            return Optional.empty();
        }
        Set<Topic> found = topicsIn(words(question.sanitised()));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        String missing = found.contains(Topic.PARKING) && found.contains(Topic.CROWDS)
                ? PARKING_AND_CROWDS : found.iterator().next().missing;
        return Optional.of(new AskAnswer(false, SUMMARY_PREFIX + missing + ".", List.of(), List.of(),
                missing));
    }

    /**
     * Every topic a list of words touches, in priority order.
     *
     * @param words the question's words, lower-case
     * @return the topics found, highest priority first; empty when the question is clear
     */
    private static Set<Topic> topicsIn(List<String> words) {
        Set<Topic> found = new TreeSet<>();
        for (int i = 0; i < words.size(); i++) {
            String word = words.get(i);
            Topic pair = i + 1 < words.size()
                    ? PAIRS.getOrDefault(word, Map.of()).get(words.get(i + 1)) : null;
            if (pair != null) {
                found.add(pair);
            }
            Topic single = WORDS.get(word);
            if (single != null && !isBusySky(words, i)) {
                found.add(single);
            }
        }
        return found;
    }

    /** Whether the word at {@code index} is a "busy" that describes the sky rather than a place. */
    private static boolean isBusySky(List<String> words, int index) {
        if (!"busy".equals(words.get(index))) {
            return false;
        }
        if (index + 1 < words.size() && SKY_WORDS.contains(words.get(index + 1))) {
            return true;
        }
        if (index >= 1 && SKY_WORDS.contains(words.get(index - 1))) {
            return true;
        }
        return index >= 2 && LINKING_WORDS.contains(words.get(index - 1))
                && SKY_WORDS.contains(words.get(index - 2));
    }

    /**
     * Splits text into lower-case words: a word is a run of letters and digits, and everything else
     * (spaces, hyphens, apostrophes, punctuation) is a break, so "car-park" is "car", "park".
     *
     * @param text the sanitised question
     * @return the words, in order
     */
    static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        text.toLowerCase(Locale.ROOT).codePoints().forEach(cp -> {
            if (Character.isLetterOrDigit(cp)) {
                word.appendCodePoint(cp);
            } else if (word.length() > 0) {
                words.add(word.toString());
                word.setLength(0);
            }
        });
        if (word.length() > 0) {
            words.add(word.toString());
        }
        return words;
    }
}
