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
    private static final String TYPE_WORD = "(?:snow|wind|rain|ice|icy|fog|thunder|thunderstorm|storm|heat|flood"
            + "|flooding|lightning)";
    private static final int FLAGS = Pattern.CASE_INSENSITIVE;

    /** A level named with the word warning or alert, a few words apart: "Amber warning", "red snow warning". */
    private static final Pattern LEVEL = Pattern.compile(
            "\\b(yellow|amber|red)\\b(?:[\\s-]+\\w+){0,3}?[\\s-]+" + WARN_WORD + "\\b", FLAGS);

    /** Any level word at all, to check a sentence that mentions a warning names no level that was not shown. */
    private static final Pattern LEVEL_WORD = Pattern.compile("\\b(yellow|amber|red)\\b", FLAGS);

    /** A warning said without a level: by type, as a weather warning, as one in force, "warning of snow", warned. */
    private static final Pattern GENERIC = Pattern.compile(
            "\\b" + TYPE_WORD + "\\s+" + WARN_WORD + "\\b"
                    + "|\\b(?:weather|severe\\s+weather)\\s+" + WARN_WORD + "\\b"
                    + "|\\bwarnings?\\s+(?:(?:is|are|has\\s+been|have\\s+been|remains?)\\s+)?"
                    + "(?:in\\s+force|in\\s+place|in\\s+effect|issued|out)\\b"
                    + "|\\bwarnings?\\s+of\\s+" + TYPE_WORD + "\\b"
                    + "|\\b(?:warned|warns)\\s+(?:of|about)\\b",
            FLAGS);

    /** A warning said not to exist, or to have been lifted: never supported, shown warning or not. */
    private static final Pattern NEGATED = Pattern.compile(
            "\\b(?:no|not|none|clear\\s+of|free\\s+of)\\b(?:[\\s-]+\\w+){0,3}?[\\s-]+" + WARN_WORD + "\\b"
                    + "|\\b" + WARN_WORD + "\\b[^.!?]{0,25}\\b(?:lifted|cancelled|canceled|withdrawn|expired|ended)\\b",
            FLAGS);

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
        if (NEGATED.matcher(sentence).find()) {
            // "No warning covers Bamburgh" and "the warning was lifted" are claims about absence: never made.
            return Claim.UNSUPPORTED;
        }
        boolean mentions = LEVEL.matcher(sentence).find() || GENERIC.matcher(sentence).find();
        if (!mentions) {
            return Claim.NONE;
        }
        Matcher level = LEVEL_WORD.matcher(sentence);
        while (level.find()) {
            if (!shownLevels.contains(level.group(1).toLowerCase(Locale.ROOT))) {
                return Claim.UNSUPPORTED;
            }
        }
        return shownIds.isEmpty() ? Claim.UNSUPPORTED : Claim.SUPPORTED;
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
        for (String sentence : SENTENCE_BREAK.split(text)) {
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
        return claim(text) == Claim.UNSUPPORTED ? null : text;
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
                new AskAnswer.WarningsCard(warnings.stale(), warnings.attribution(), warnings.url(), cards));
    }

    private boolean mentions(AskAnswer answer) {
        if (claim(answer.summary()) == Claim.SUPPORTED) {
            return true;
        }
        return answer.picks().stream().anyMatch(p -> p.why() != null && claim(p.why()) == Claim.SUPPORTED)
                || answer.events().stream().anyMatch(e -> e.why() != null && claim(e.why()) == Claim.SUPPORTED);
    }

    private static AskAnswer.WarningCard card(AskSnapshot.Warning w) {
        return new AskAnswer.WarningCard(w.id(), w.version(), w.level(), w.types(),
                ComingUpWarningMerger.phrase(w.level(), w.types()),
                w.validFrom() == null ? null : w.validFrom().toString(),
                w.validTo() == null ? null : w.validTo().toString(), w.headline(), w.updated());
    }
}
