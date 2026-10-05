package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.service.evaluation.PromptUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Holds a model's submitted answer to what the tools actually returned (plan §2.3 <em>Validation</em>).
 * Pure: no I/O, no clock.
 *
 * <p>The model supplies only a location id, a window id and a reason per pick, and a type and a
 * reason per event. Everything a card shows — name, region, date, event, rating, verdict, label —
 * is joined here from the snapshot, so a card can never state something served data does not.
 *
 * <p><b>Residual, stated:</b> the summary's prose is not fact-checked. The cards beside it carry
 * served facts.
 */
@Component
public class AskAnswerValidator {

    private static final Logger LOG = LoggerFactory.getLogger(AskAnswerValidator.class);

    /** The most picks an answer may carry. */
    public static final int MAX_PICKS = 3;

    /** Word cap of a summary. */
    public static final int SUMMARY_WORDS = 60;

    /** Word cap of one pick's or event's reason. */
    public static final int WHY_WORDS = 24;

    /** Word cap of the {@code missing} phrase. */
    public static final int MISSING_WORDS = 8;

    private static final Pattern URL_LIKE = Pattern.compile(
            "(?i)(?:\\b(?:https?|ftp)://\\S+|\\bmailto:\\S+|\\bwww\\.\\S+"
                    + "|\\b[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.(?:com|net|org|co\\.uk|uk|io|app|dev|info"
                    + "|online|me|ly|gl|be|to)\\b(?:/\\S*)?)");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * An answer as the model submitted it, before any check.
     *
     * @param answerable whether the model says the forecast can answer
     * @param summary    the model's summary
     * @param picks      the model's picks, in its order
     * @param events     the model's events
     * @param missing    what the model says PhotoCast lacks
     */
    public record Raw(boolean answerable, String summary, List<RawPick> picks,
            List<RawEvent> events, String missing) {

        /** Canonical constructor: a null list reads as empty. */
        public Raw {
            picks = picks == null ? List.of() : List.copyOf(picks);
            events = events == null ? List.of() : List.copyOf(events);
        }
    }

    /**
     * A pick as the model submitted it.
     *
     * @param locationId the location id
     * @param windowId   the window id
     * @param why        the model's reason
     */
    public record RawPick(long locationId, String windowId, String why) {
    }

    /**
     * An event as the model submitted it.
     *
     * @param type the event type
     * @param date the date the model names, or null to take the served one
     * @param why  the model's reason
     */
    public record RawEvent(String type, LocalDate date, String why) {
    }

    /**
     * The extra rule of a Ready {@code BEST_*} question: it must lead with the Plan tab's own BEST
     * BET (plan §1 #6) — two different "bests" on one screen is the aggregator divergence the
     * project has already paid for.
     *
     * <p>It has no scope of its own: the question's one scope is the {@code scope} argument of
     * {@link #validate}, so the anchor and the pick filter cannot be given two that differ.
     *
     * @param windowIds the windows the question covers
     */
    public record BestAnchor(Set<String> windowIds) {

        /** Canonical constructor: takes an immutable copy; null reads as empty. */
        public BestAnchor {
            windowIds = windowIds == null ? Set.of() : Set.copyOf(windowIds);
        }
    }

    /**
     * The verdict on a submitted answer.
     *
     * @param answer the validated answer, or null when the answer was discarded
     * @param reason why it was discarded, or null when it was accepted
     */
    public record Result(AskAnswer answer, String reason) {

        /**
         * Whether the answer survived.
         *
         * @return true when {@code answer} is present
         */
        public boolean accepted() {
            return answer != null;
        }
    }

    /**
     * Validates an answer.
     *
     * @param raw      the model's submitted answer
     * @param snapshot the snapshot the conversation ran against
     * @param evidence what the conversation's tools returned
     * @param scope    the region names the question is about, matched case-insensitively; null or
     *                 empty means every region. Enforced here, not left to the evidence being
     *                 scoped: a pick whose region is outside it is dropped
     * @param anchor   the Ready {@code BEST_*} rule, or null for every other question
     * @return the validated answer, or the reason it was discarded
     */
    public Result validate(Raw raw, AskSnapshot snapshot, AskEvidence evidence,
            Collection<String> scope, BestAnchor anchor) {
        String summary = clean(raw.summary(), SUMMARY_WORDS);
        if (summary == null || summary.isBlank()) {
            return discard("no summary");
        }
        String missing = clean(raw.missing(), MISSING_WORDS);
        if (missing != null && missing.isBlank()) {
            missing = null;
        }
        if (!raw.answerable()) {
            if (anchor != null && anchoredWindow(snapshot, anchor, scope).isPresent()) {
                return discard("a BEST question cannot be unanswerable while the forecast has a BEST BET");
            }
            return new Result(new AskAnswer(false, summary, List.of(), List.of(), missing), null);
        }

        List<AskPick> picks = validPicks(raw.picks(), snapshot, evidence, scope);
        List<AskEvent> events = validEvents(raw.events(), evidence);
        if (picks.isEmpty() && events.isEmpty() && !evidence.anyToolCalled()) {
            return discard("answerable with no pick, no event and no tool call");
        }
        if (anchor != null) {
            Optional<AskSnapshot.Window> lead = anchoredWindow(snapshot, anchor, scope);
            if (lead.isPresent()
                    && (picks.isEmpty() || !picks.getFirst().windowId().equals(lead.get().id()))) {
                LOG.warn("[ASK] Discarded a BEST answer that does not lead with the BEST BET window {}",
                        lead.get().id());
                return discard("pick 1 is not on the BEST BET window " + lead.get().id());
            }
        }
        return new Result(new AskAnswer(true, summary, picks, events, null), null);
    }

    /**
     * The window a BEST question's pick 1 must be on: a covered window carrying the forecast's BEST
     * pick, in scope, that has at least one pick-eligible slot to lead with <em>within the
     * question's scope</em> ({@link AskSnapshot#candidates(AskSnapshot.Window, java.util.Collection)},
     * the same set {@code rank_spots} draws from). Without that last clause a BEST BET whose own
     * location is under 3★, or whose only eligible neighbours sit in a region the question did not
     * ask about, would make every answer unsatisfiable.
     */
    static Optional<AskSnapshot.Window> anchoredWindow(AskSnapshot snapshot,
            BestAnchor anchor, Collection<String> scope) {
        return snapshot.windows().stream()
                .filter(w -> anchor.windowIds().contains(w.id()))
                .filter(w -> w.pick() != null && w.pick().kind() == BriefingWindow.PickKind.BEST)
                .filter(w -> AskSnapshot.regionInScope(scope, w.pick().regionName()))
                .filter(w -> !snapshot.candidates(w, scope).isEmpty())
                .findFirst();
    }

    private List<AskPick> validPicks(List<RawPick> raw, AskSnapshot snapshot, AskEvidence evidence,
            Collection<String> scope) {
        List<AskPick> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (RawPick pick : raw) {
            if (out.size() >= MAX_PICKS) {
                break;
            }
            if (!evidence.pairs().contains(new AskEvidence.Pair(pick.locationId(), pick.windowId()))
                    || seen.contains(pick.locationId())) {
                continue;
            }
            Optional<AskSnapshot.Candidate> candidate =
                    snapshot.candidate(pick.windowId(), pick.locationId());
            if (candidate.isEmpty()
                    || !AskSnapshot.regionInScope(scope, candidate.get().region().name())) {
                continue;
            }
            seen.add(pick.locationId());
            AskSnapshot.Candidate c = candidate.get();
            String why = clean(pick.why(), WHY_WORDS);
            out.add(new AskPick(out.size() + 1, pick.locationId(), c.slot().name(),
                    c.region().name(), c.window().date(), c.window().targetType(),
                    c.window().id(), why == null ? "" : why, c.slot().rating(),
                    c.slot().verdict() == null ? null : c.slot().verdict().name()));
        }
        return out;
    }

    /**
     * An event survives only if its type was returned by an events tool. The label, date and safety
     * note shown are the served ones: the model's date picks between served dates of that type and
     * is never trusted on its own, and {@link RawEvent} has no field through which the model could
     * write a safety note. A served warning (the solar eclipse's lens-filter note) therefore always
     * reaches the validated event; no path drops it.
     */
    private List<AskEvent> validEvents(List<RawEvent> raw, AskEvidence evidence) {
        List<AskEvent> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (RawEvent event : raw) {
            String type = event.type() == null ? "" : event.type().strip().toUpperCase(Locale.ROOT);
            Optional<AskEvidence.EventFact> fact = evidence.events().stream()
                    .filter(f -> f.type().equals(type))
                    .filter(f -> event.date() == null || event.date().equals(f.date()))
                    .min(Comparator.comparing(AskEvidence.EventFact::date,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                            // Of otherwise equal facts the one carrying a warning wins: never lose it.
                            .thenComparing(f -> f.safetyNote() == null));
            if (fact.isEmpty() || !seen.add(fact.get().type() + "|" + fact.get().date())) {
                continue;
            }
            String why = clean(event.why(), WHY_WORDS);
            out.add(new AskEvent(fact.get().type(), fact.get().label(), fact.get().date(),
                    why == null ? "" : why, fact.get().safetyNote()));
        }
        return out;
    }

    /**
     * Cleans model text: URL-like strings removed, the model's own brand names replaced, whitespace
     * collapsed, then capped at {@code maxWords} words.
     *
     * @param text     the text; may be null
     * @param maxWords the word cap
     * @return the cleaned text, or null for null input
     */
    static String clean(String text, int maxWords) {
        if (text == null) {
            return null;
        }
        String noUrls = URL_LIKE.matcher(text).replaceAll(" ");
        String branded = PromptUtils.sanitizeBrand(noUrls);
        String collapsed = WHITESPACE.matcher(branded).replaceAll(" ").strip();
        return PromptUtils.truncateToWords(collapsed, maxWords);
    }

    private static Result discard(String reason) {
        return new Result(null, reason);
    }
}
