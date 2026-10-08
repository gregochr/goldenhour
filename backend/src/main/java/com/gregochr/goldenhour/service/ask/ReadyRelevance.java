package com.gregochr.goldenhour.service.ask;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What a {@link ReadyQuestion} may carry (plan §2.4): the one relevance rule, applied when a Ready
 * answer is stored ({@link AskReadyPrecompute}), when one is served ({@link AskReadyFreshness}) and
 * when the engine's validator decides whether an events question found an event
 * ({@link AskAnswerValidator}). It is one code path on purpose: a row written under an older, looser
 * rule is withheld at serve time by the very predicates that would refuse it at store time.
 *
 * <p>Keyed on three things the enum states about itself: {@link ReadyQuestion#picks()} (pick question
 * or events question), its event types ({@link ReadyQuestion#eventTypes()}; null reads as "any") and
 * whether its slots must be at high water ({@link ReadyQuestion#COASTAL_HIGH}). The catalogue's
 * identity, tabs, availability ({@code offer}) and anchor stay on the enum.
 */
final class ReadyRelevance {

    private static final String HIGH = "HIGH";

    private ReadyRelevance() {
    }

    /**
     * Whether a slot is a coastal slot at high water. The one definition: {@link ReadyQuestion#COASTAL_HIGH}
     * offers on it and admits picks on it.
     *
     * @param slot the live slot
     * @return true when its served tide state is high water
     */
    static boolean isHighWater(AskSnapshot.Slot slot) {
        return HIGH.equals(slot.tideState());
    }

    /**
     * Whether an event of this type is relevant to the question: the one event predicate. A pick
     * question keeps no event card (its cards are picks); {@code RARE_EVENTS} keeps any;
     * {@code SNOW_TOPS} keeps only the snow topic types ({@code SNOW_TOPS}, {@code SNOW_FRESH},
     * {@code SNOW_MIST}).
     *
     * @param question the question
     * @param type     the event's served type, any case or spelling ({@link AskEventType#key})
     * @return true when the question may carry an event of this type
     */
    static boolean admitsEvent(ReadyQuestion question, String type) {
        Set<String> eventTypes = question.eventTypes();
        if (eventTypes == null) {
            return true;
        }
        return type != null && eventTypes.contains(AskEventType.key(type));
    }

    /**
     * Whether a pick's live slot is of the kind the question asks for, beyond being pick-eligible in
     * scope: {@code COASTAL_HIGH} wants a coastal slot at high water, every other question any slot.
     *
     * @param question the question
     * @param slot     the pick's live slot
     * @return true when the slot suits the question
     */
    static boolean admitsSlot(ReadyQuestion question, AskSnapshot.Slot slot) {
        return question != ReadyQuestion.COASTAL_HIGH || isHighWater(slot);
    }

    /**
     * Whether a pick is relevant to the question: the one pick predicate. An events question admits no
     * pick. A pick question admits one only if it is on one of the question's own windows
     * ({@code BEST_WEEKEND}: the Saturday and Sunday windows; {@code BEST_SOON}: the windows with a
     * pick; {@code BEST_NEXT}: the next window alone; {@code COASTAL_HIGH}: the windows with a coastal
     * slot at high water; {@code AM_OR_PM}: that date's two windows), its live slot is pick-eligible
     * within the question's scope, and the slot suits the question ({@link #admitsSlot}).
     *
     * @param question the question
     * @param pick     the pick
     * @param offer    what the question is asked under
     * @param snapshot the snapshot to read the live slot from
     * @param scope    the question's scope
     * @return true when the question may carry the pick
     */
    static boolean admitsPick(ReadyQuestion question, AskPick pick, ReadyQuestion.Offer offer,
            AskSnapshot snapshot, AskScope scope) {
        if (!question.picks() || !offer.windowIds().contains(pick.windowId())) {
            return false;
        }
        Optional<AskSnapshot.Candidate> candidate = snapshot.candidate(pick.windowId(), pick.locationId());
        return candidate.isPresent()
                && scope.contains(candidate.get().region().name())
                && admitsSlot(question, candidate.get().slot());
    }

    /**
     * An answer reduced to what the question may carry: an events question loses any pick and any event
     * it does not admit; a pick question loses every event. The picks of a pick question are never
     * dropped here (dropping one would renumber the ranks and could remove the BEST BET lead): a wrong
     * pick fails {@link #violation} instead.
     *
     * @param question the question
     * @param answer   the validated answer
     * @return the answer with the irrelevant events and picks removed
     */
    static AskAnswer relevantPart(ReadyQuestion question, AskAnswer answer) {
        List<AskEvent> events = answer.events().stream().filter(e -> admitsEvent(question, e.type())).toList();
        return new AskAnswer(answer.answerable(), answer.summary(),
                question.picks() ? answer.picks() : List.of(), events, answer.missing());
    }

    /**
     * Whether {@link #relevantPart} would remove an event that carries a safety warning. Such an answer
     * cannot be stored: the summary may name the event, and dropping its card would drop the warning
     * the card must show.
     *
     * @param question the question
     * @param answer   the validated answer
     * @return true when an event with a safety note would be dropped
     */
    static boolean dropsWarning(ReadyQuestion question, AskAnswer answer) {
        return answer.events().stream().anyMatch(e -> !admitsEvent(question, e.type()) && e.safetyNote() != null);
    }

    /**
     * Whether an answer is what the question wants and carries nothing it may not: a pick question needs
     * a pick and carries no event, an events question needs an event and carries no pick, every event is
     * of an admitted type ({@link #admitsEvent}) and every pick is admitted ({@link #admitsPick}). Run on
     * the reduced answer when it is stored and on the stored answer when it is served, so a row written
     * under an older, looser rule is withheld rather than served.
     *
     * @param question the question
     * @param answer   the answer
     * @param offer    what the question is asked under
     * @param snapshot the snapshot to read live slots from
     * @param scope    the question's scope
     * @return why the answer must not be stored or served, or empty when it may be
     */
    static Optional<String> violation(ReadyQuestion question, AskAnswer answer, ReadyQuestion.Offer offer,
            AskSnapshot snapshot, AskScope scope) {
        if (question.picks() && answer.picks().isEmpty()) {
            return Optional.of("a pick question with no pick");
        }
        if (!question.picks() && answer.events().isEmpty()) {
            return Optional.of("an events question with no event");
        }
        for (AskEvent event : answer.events()) {
            if (!admitsEvent(question, event.type())) {
                return Optional.of("a " + event.type() + " event is not relevant to " + question.name());
            }
        }
        for (AskPick pick : answer.picks()) {
            if (!admitsPick(question, pick, offer, snapshot, scope)) {
                return Optional.of("pick " + pick.rank() + " is not relevant to " + question.name() + " ("
                        + pick.windowId() + ")");
            }
        }
        return Optional.empty();
    }
}
