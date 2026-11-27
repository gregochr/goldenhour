package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.service.ask.AskTools.CompanionPairing;
import com.gregochr.goldenhour.service.ask.AskTools.SpotInfo;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one sentence the engine adds when {@code rank_spots} paired the lead with a companion (a favourite of the
 * reader's that ranks lower, a place the lead only edged on a star, or a place rated higher elsewhere) and the
 * model's summary left the companion out. The sentence is built only from served fields (the companion's name and
 * rating, the lead's name), so it adds no pick and no figure the tools did not return.
 *
 * <p>It is appended only when the answer's first pick is the stored lead (so an answer about another window, a
 * named area or another call is left alone), when the summary does not already name the companion with its own
 * star in one sentence, and when the result stays within both the word cap and the code-point cap. The model's
 * text is never truncated to make room.
 */
final class AskCompanionLine {

    /** Words that say a tied companion is level with the lead. */
    private static final Pattern SAME_WORDS = Pattern.compile("\\b(same|equal|tie|also|both|as well)",
            Pattern.CASE_INSENSITIVE);

    /** Whitespace runs, for counting words. */
    private static final Pattern SPACES = Pattern.compile("\\s+");

    /** A sentence terminator: a full stop, ! or ? that is followed by whitespace or the text end. */
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?](?=\\s|$)");

    /** A summary that already ends in a full stop, ! or ?. */
    private static final Pattern TERMINAL = Pattern.compile("[.!?]$");

    private AskCompanionLine() {
    }

    /**
     * The summary with the companion sentence added when it is due, else the summary as it is.
     *
     * @param summary the validated summary
     * @param picks   the answer's picks, ranked
     * @param pairing the latest {@code rank_spots} pairing, or null
     * @return the summary to serve
     */
    static String apply(String summary, List<AskPick> picks, CompanionPairing pairing) {
        if (summary == null || pairing == null || pairing.lead() == null || pairing.companion() == null
                || pairing.companionKind() == null || picks == null || picks.isEmpty()) {
            return summary;
        }
        SpotInfo lead = pairing.lead();
        AskPick first = picks.get(0);
        if (first.locationId() != lead.locationId() || !lead.windowId().equals(first.windowId())) {
            return summary;
        }
        if (alreadyNamed(summary, pairing)) {
            return summary;
        }
        String sentence = sentence(pairing);
        String base = summary.strip();
        String joined = endsSentence(base) ? base + " " + sentence : base + ". " + sentence;
        if (wordCount(joined) > AskAnswerValidator.SUMMARY_WORDS
                || joined.codePointCount(0, joined.length()) > AskAnswerValidator.THREAD_SUMMARY_MAX_CODE_POINTS) {
            return summary;
        }
        return joined;
    }

    /**
     * The fixed sentence for a pairing.
     *
     * @param pairing the pairing
     * @return the sentence, ending in a full stop
     */
    static String sentence(CompanionPairing pairing) {
        SpotInfo companion = pairing.companion();
        String name = companion.name();
        int stars = companion.rating();
        return switch (pairing.companionKind()) {
            case YOUR_FAVOURITE -> name + ", your favourite, is " + stars + "★.";
            case TIED -> name + " also scores " + stars + "★, the same as " + pairing.lead().name() + ".";
            case HIGHER_ELSEWHERE -> name + " scores higher, at " + stars + "★.";
        };
    }

    /**
     * Whether one sentence of the summary names the companion and gives its own star (and, for a tie, says it is
     * level). The name is found first, then the sentence runs from there to the next sentence end; a full stop
     * inside the name ("St. Mary's Lighthouse") is part of the name, so it never ends the sentence early.
     */
    private static boolean alreadyNamed(String summary, CompanionPairing pairing) {
        String needle = pairing.companion().name().toLowerCase(Locale.ROOT);
        String lower = summary.toLowerCase(Locale.ROOT);
        Pattern star = Pattern.compile("(?<!\\d)" + pairing.companion().rating() + "\\s?★");
        int from = 0;
        while (true) {
            int at = lower.indexOf(needle, from);
            if (at < 0) {
                return false;
            }
            int nameEnd = at + needle.length();
            Matcher end = SENTENCE_END.matcher(summary);
            int stop = end.find(nameEnd) ? end.end() : summary.length();
            String sentence = summary.substring(at, stop);
            if (star.matcher(sentence).find() && (pairing.companionKind() != AskTools.CompanionKind.TIED
                    || SAME_WORDS.matcher(sentence).find())) {
                return true;
            }
            from = nameEnd;
        }
    }

    private static boolean endsSentence(String text) {
        return TERMINAL.matcher(text).find();
    }

    private static int wordCount(String text) {
        return SPACES.split(text.strip()).length;
    }
}
