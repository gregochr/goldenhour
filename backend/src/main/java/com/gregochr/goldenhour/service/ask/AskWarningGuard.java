package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.service.comingup.ComingUpWarningMerger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The typed guard for Met Office weather warnings in an Ask answer (weather-warnings-plan.md section 14, W6).
 *
 * <p>Ask's tools show the model the warnings in force as read-only facts, and the validator holds what the model
 * writes to them: a sentence that says or implies a weather warning is kept only when a warning was shown to the
 * model in this conversation ({@link AskEvidence#warningIds()}) and, when it names a level ("Amber warning"), that
 * level is one of the shown warnings'. A sentence the facts do not support is removed, never rewritten: the guard
 * never softens, upgrades or paraphrases a level. When nothing is left the answer is our own can't-answer, never an
 * invented warning and never a "no warnings" that would read as an all-clear.
 *
 * <p><b>The default is a claim.</b> Any sentence using warn* or alert(s) is a warning claim, whatever the phrasing
 * ("has issued a warning", "is under a Met Office warning"); it is kept only when a warning was shown in this
 * conversation, every level word it names was shown, and it is not an absence claim (which is always removed). A
 * small explicit allowlist of non-weather uses ({@link #BENIGN}) is cut out first, and a sentence is a claim if any
 * warn* or alert is left. There is no sentence-level exemption: a weather claim beside a benign phrase stays a claim.
 *
 * <p>The guard runs only while the warnings feature is on ({@link AskSnapshot.Warnings#enabled()}); with it off every
 * method returns its input, so Ask is exactly what it was before W6. The server, not the model, attaches the
 * warning facts and the Met Office attribution to an answer that mentions one ({@link #attach}).
 */
final class AskWarningGuard {

    /** The summary of the can't-answer when the whole answer rested on an unsupported warning claim. */
    static final String UNKNOWN_SUMMARY = "PhotoCast cannot say whether a Met Office weather warning is in force "
            + "for this. The Met Office has the official warnings.";

    /** The summary an answer keeps its picks and events under when its own summary was only a warning claim. */
    static final String NEUTRAL_SUMMARY = "Here is what the forecast shows.";

    /** The {@code missing} phrase of that can't-answer (5 words). */
    static final String UNKNOWN_MISSING = "weather warning information for this";

    private static final String WARN_WORD = "(?:warning|alert)s?";
    private static final int FLAGS = Pattern.CASE_INSENSITIVE;

    /**
     * The default: any use of warn* or alert(s) is a warning claim. Open-ended phrasing ("has issued a warning",
     * "is under a Met Office warning") is caught by the word, not by a list of sentences; what is exempt is the
     * explicit {@link #BENIGN} allowlist.
     */
    private static final Pattern WARN_TOKEN = Pattern.compile("\\b(?:warn(?:ing|ings|ed|s)?|alerts?)\\b", FLAGS);

    /**
     * Non-weather uses of the words, deliberately allowed and tested: "a word of warning", a sign, label, notice,
     * light, zone or filter; the eclipse safety note's own "safety warning" and "the note warns ... solar filter";
     * "without warning"; "stay alert"; the shepherd's proverb; the aurora alert level the night
     * tool reports. "Early warning" is NOT here: it can introduce a weather claim.
     */
    private static final Pattern BENIGN = Pattern.compile(
            "\\b(?:safety\\s+)?(?:note|it)\\s+warns?\\b[^.!?]{0,40}\\b(?:solar|lens)\\s+filter\\b"
                    + "|\\bwords?\\s+of\\s+warning\\b"
                    + "|\\b(?:warning|alert)s?\\s+(?:sign|signs|label|labels|notice|notices|light|lights|filter|filters"
                    + "|zone|zones|triangle|sticker|stickers)\\b"
                    + "|\\bsafety\\s+(?:note|warning|alert)s?\\b"
                    + "|\\bwithout\\s+(?:any\\s+)?warning\\b"
                    + "|\\b(?:stay|staying|stays|be|remain|keep)\\s+alert\\b"
                    + "|\\bred\\s+sky\\s+at\\s+(?:night|morning),?\\s+shepherd['\u2019]?s?\\s+(?:warning|delight)\\b"
                    + "|\\b(?:aurora|geomagnetic)\\s+alerts?\\b|\\balert\\s+(?:level|state|status)\\b",
            FLAGS);

    /**
     * Any level word at all, to check a sentence that mentions a warning names no level that was not shown. The
     * colour score is called "Red Sky" ("Red Sky 72", "Red Sky score", "Red Sky and Golden Hour"), and that "Red" is
     * a name, not a warning level, so it is skipped; any other "red" (a "red sky later", a "Red warning") still counts.
     */
    private static final Pattern LEVEL_WORD = Pattern.compile(
            "\\b(yellow|amber|red)\\b(?!\\s+sky\\s+(?:\\d|score|potential|and\\s+golden\\b))", FLAGS);

    /** A negator: no, not, none, nothing, any contraction (isn't, aren't, doesn't, with a straight or curly mark). */
    private static final String NEGATOR = "(?:\\b(?:no|not|none|nothing)\\b|\\b\\w+n['\u2019]t\\b"
            + "|\\bfree\\s+(?:of|from)\\b|\\bclear\\s+(?:of|from)\\b)";

    /**
     * A warning said not to exist or to have been lifted: never supported, a warning shown or not. The negator may be
     * up to six words from the warning ("there aren't any active Met Office weather warnings", "no warning", "nothing
     * in force", "all clear" beside a warning), and the contraction marks are straight or curly.
     */
    private static final Pattern NEGATED = Pattern.compile(
            NEGATOR + "(?:[\\s-]+\\w+(?:['\u2019]\\w+)?){0,6}?[\\s-]+" + WARN_WORD + "\\b"
                    + "|\\b" + WARN_WORD + "\\b[^.!?]{0,25}\\b(?:lifted|cancelled|canceled|withdrawn|expired|ended)\\b"
                    + "|\\ball[\\s-]clear\\b[^.!?]{0,40}\\bwarn"
                    + "|\\bwarn[^.!?]{0,40}\\ball[\\s-]clear\\b",
            FLAGS);

    /** An all-clear without the word warning: "nothing in force". Never made. */
    private static final Pattern NOTHING_IN_FORCE = Pattern.compile(
            "\\bnothing\\s+(?:is\\s+)?in\\s+(?:force|place|effect)\\b", FLAGS);

    private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=[.!?])\\s+");

    private final boolean applies;
    private final Set<String> shownLevels;
    private final Set<String> shownIds;

    private AskWarningGuard(boolean applies, Set<String> shownLevels, Set<String> shownIds) {
        this.applies = applies;
        this.shownLevels = shownLevels;
        this.shownIds = shownIds;
    }

    /**
     * The guard for one conversation.
     *
     * @param snapshot the snapshot the conversation ran against (its warnings are the live truth)
     * @param evidence what the conversation's tools showed the model
     * @return the guard; a no-op when the warnings feature is off
     */
    static AskWarningGuard of(AskSnapshot snapshot, AskEvidence evidence) {
        AskSnapshot.Warnings warnings = snapshot.warnings();
        if (!warnings.enabled()) {
            return new AskWarningGuard(false, Set.of(), Set.of());
        }
        Set<String> levels = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (String id : evidence.warningIds()) {
            warnings.find(id).ifPresent(w -> {
                ids.add(w.id());
                if (w.level() != null) {
                    levels.add(w.level().toLowerCase(Locale.ROOT));
                }
            });
        }
        return new AskWarningGuard(true, levels, ids);
    }

    /** What a sentence says about warnings. */
    private enum Claim { NONE, SUPPORTED, UNSUPPORTED }

    private Claim claim(String sentence) {
        if (NOTHING_IN_FORCE.matcher(sentence).find()) {
            return Claim.UNSUPPORTED;
        }
        // Benign uses are cut out first; if no warn* or alert is left, the sentence is not about a warning.
        String rest = BENIGN.matcher(sentence).replaceAll(" ");
        if (!WARN_TOKEN.matcher(rest).find()) {
            return Claim.NONE;
        }
        if (NEGATED.matcher(rest).find()) {
            // "No warning covers Bamburgh" and "the warning was lifted" are claims about absence: never made.
            return Claim.UNSUPPORTED;
        }
        Matcher level = LEVEL_WORD.matcher(rest);
        while (level.find()) {
            if (!shownLevels.contains(level.group(1).toLowerCase(Locale.ROOT))) {
                return Claim.UNSUPPORTED;
            }
        }
        return shownIds.isEmpty() ? Claim.UNSUPPORTED : Claim.SUPPORTED;
    }

    /**
     * The one sentence split every path uses, so removal, attachment and mention checks can never disagree about what
     * a sentence is.
     */
    private static String[] sentences(String text) {
        return SENTENCE_BREAK.split(text);
    }

    /** Whether any sentence of the text is a warning claim the facts support (the same predicate as removal). */
    private boolean hasSupportedClaim(String text) {
        if (text == null) {
            return false;
        }
        for (String sentence : sentences(text)) {
            if (claim(sentence) == Claim.SUPPORTED) {
                return true;
            }
        }
        return false;
    }

    /** Whether any sentence of the text is a warning claim the facts do not support. */
    private boolean hasUnsupportedClaim(String text) {
        for (String sentence : sentences(text)) {
            if (claim(sentence) == Claim.UNSUPPORTED) {
                return true;
            }
        }
        return false;
    }

    /**
     * A summary with every sentence the warning facts do not support removed.
     *
     * @param text the cleaned summary
     * @return the summary unchanged when nothing is unsupported (or the guard is off), else what is left, which
     *         may be blank
     */
    String sanitiseSummary(String text) {
        if (!applies || text == null) {
            return text;
        }
        List<String> kept = new ArrayList<>();
        boolean removed = false;
        for (String sentence : sentences(text)) {
            if (claim(sentence) == Claim.UNSUPPORTED) {
                removed = true;
            } else {
                kept.add(sentence);
            }
        }
        return removed ? String.join(" ", kept) : text;
    }

    /**
     * A short phrase (a pick's or event's reason, the {@code missing} phrase) that is dropped whole when it makes
     * a warning claim the facts do not support.
     *
     * @param text the cleaned phrase, or null
     * @return the phrase, or null when it is unsupported
     */
    String sanitisePhrase(String text) {
        if (!applies || text == null) {
            return text;
        }
        return hasUnsupportedClaim(text) ? null : text;
    }

    /**
     * Attaches the warning facts to an answer that mentions a warning the model was shown: the server's
     * words (level, weather types, validity, headline), the Met Office attribution and link, and whether the feed
     * may be out of date. An answer that mentions none is returned as it is.
     *
     * @param answer   the validated, answerable answer
     * @param snapshot the snapshot the conversation ran against
     * @return the answer, with {@link AskAnswer#warnings()} set when it mentions a shown warning
     */
    AskAnswer attach(AskAnswer answer, AskSnapshot snapshot) {
        if (!applies || shownIds.isEmpty() || !mentions(answer)) {
            return answer;
        }
        AskSnapshot.Warnings warnings = snapshot.warnings();
        List<AskAnswer.WarningCard> cards = new ArrayList<>();
        for (AskSnapshot.Warning w : warnings.items()) {
            if (shownIds.contains(w.id())) {
                cards.add(card(w));
            }
        }
        if (cards.isEmpty()) {
            return answer;
        }
        return answer.withWarnings(
                new AskAnswer.WarningsCard(warnings.stale(), warnings.attribution(), warnings.url(), cards,
                        warnings.checkedAt() == null ? null : warnings.checkedAt().toString()));
    }

    private boolean mentions(AskAnswer answer) {
        return hasSupportedClaim(answer.summary()) || hasSupportedClaim(answer.missing())
                || answer.picks().stream().anyMatch(p -> hasSupportedClaim(p.why()))
                || answer.events().stream().anyMatch(e -> hasSupportedClaim(e.why()));
    }

    private static AskAnswer.WarningCard card(AskSnapshot.Warning w) {
        return new AskAnswer.WarningCard(w.id(), w.version(), w.level(), w.types(),
                ComingUpWarningMerger.phrase(w.level(), w.types()),
                w.validFrom() == null ? null : w.validFrom().toString(),
                w.validTo() == null ? null : w.validTo().toString(), w.headline(), w.updated());
    }
}
