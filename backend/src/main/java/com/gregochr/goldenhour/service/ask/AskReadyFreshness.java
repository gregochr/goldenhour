package com.gregochr.goldenhour.service.ask;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The serve-time freshness check of a Ready answer (plan §2.4, D-5), made against <b>live</b> data.
 *
 * <p><b>All or nothing.</b> A stored answer is served whole or not at all: there is no partial
 * answer under stale prose. It is withheld when any of these holds:
 * <ol>
 *   <li>every window it names has passed (or has left the window set: a travel day, or past the
 *       Plan tab's six);</li>
 *   <li>the question is no longer offered under the same text — which catches a day word that has
 *       gone stale ("tomorrow morning" stored on Sunday), a weekend question superseded by the
 *       next-few-days one, and a window set that has emptied;</li>
 *   <li>any pick's window has passed, or its slot is no longer pick-eligible in scope;</li>
 *   <li>any pick's live rating or live verdict differs from the one stored with it;</li>
 *   <li>any event's topic is no longer live (no longer returned by the current hot topics or
 *       Coming up feed, for that type and date);</li>
 *   <li>for a {@code BEST_*} question, the live BEST BET window is not the one the answer leads
 *       with;</li>
 *   <li>the answer would no longer pass the same checks it was stored under (every pick still
 *       within the question's windows; a coastal-high question's picks still at high water).</li>
 * </ol>
 * Rules 1, 3, 4 and 5 are the plan's; rules 2, 6 and 7 are the same test applied to the rest of what
 * the answer claims, so a day word, a supersession or a moved BEST BET cannot slip past the plan's
 * four.
 *
 * <p>What survives is <b>re-decorated from the live snapshot</b> — names, dates, event labels and
 * safety notes — never taken from the stored text: the stored {@code why} prose is the only thing
 * kept from the model.
 */
final class AskReadyFreshness {

    private AskReadyFreshness() {
    }

    /**
     * The verdict on one stored answer.
     *
     * @param answer the answer, re-decorated from the live snapshot, or null when withheld
     * @param reason why it was withheld, or null when it is fresh
     */
    record Verdict(AskAnswer answer, String reason) {

        boolean fresh() {
            return answer != null;
        }

        static Verdict stale(String reason) {
            return new Verdict(null, reason);
        }
    }

    /**
     * Checks one stored answer against the live snapshot.
     *
     * @param question the Ready question
     * @param stored   the stored row
     * @param live     the live snapshot
     * @param scope    the row's scope
     * @return the re-decorated answer, or the reason it is withheld
     */
    static Verdict check(ReadyQuestion question, AskReadyStore.Stored stored, AskSnapshot live,
            AskScope scope) {
        if (!stored.windowIds().isEmpty()
                && stored.windowIds().stream().noneMatch(id -> live.window(id).isPresent())) {
            return Verdict.stale("every window the question names has passed");
        }
        Optional<ReadyQuestion.Offer> offer = question.offer(live, scope);
        if (offer.isEmpty()) {
            return Verdict.stale("the question is no longer offered");
        }
        if (!offer.get().text().equals(stored.questionText())) {
            return Verdict.stale("the question is now asked differently: " + offer.get().text());
        }
        Verdict rechecked = recheck(stored.answer(), live, scope);
        if (!rechecked.fresh()) {
            return rechecked;
        }
        AskAnswer decorated = rechecked.answer();
        List<AskPick> picks = decorated.picks();
        if (question.anchored()) {
            Optional<AskSnapshot.Window> lead = AskAnswerValidator.anchoredWindow(live,
                    question.anchor(offer.get()), scope);
            if (lead.isPresent()
                    && (picks.isEmpty() || !picks.getFirst().windowId().equals(lead.get().id()))) {
                return Verdict.stale("the BEST BET is now on " + lead.get().id());
            }
        }
        Optional<String> violation = question.violation(decorated, offer.get(), live, scope);
        if (violation.isPresent()) {
            return Verdict.stale(violation.get());
        }
        return new Verdict(decorated, null);
    }

    /**
     * The part of the freshness test that depends only on what the answer itself claims, with no
     * Ready question behind it: every pick's window still in the window set, its slot still
     * pick-eligible in scope with the rating and verdict it was written under, and every event's
     * topic still live. Shared by {@link #check} (a Ready answer) and the typed-answer cache (a
     * paid-for answer served again), so the two cannot disagree about what "still true" means.
     * Re-decorates what survives from the live snapshot.
     *
     * @param answer the stored answer
     * @param live   the live snapshot
     * @param scope  the answer's scope
     * @return the re-decorated answer, or the reason it is withheld
     */
    static Verdict recheck(AskAnswer answer, AskSnapshot live, AskScope scope) {
        List<AskPick> picks = new ArrayList<>();
        for (AskPick pick : answer.picks()) {
            Optional<AskSnapshot.Window> window = live.window(pick.windowId());
            if (window.isEmpty()) {
                return Verdict.stale("pick " + pick.rank() + "'s window has passed");
            }
            Optional<AskSnapshot.Candidate> candidate =
                    live.candidate(pick.windowId(), pick.locationId());
            if (candidate.isEmpty()
                    || !scope.contains(candidate.get().region().name())) {
                return Verdict.stale("pick " + pick.rank() + " is no longer pick-eligible");
            }
            AskSnapshot.Slot slot = candidate.get().slot();
            String verdict = slot.verdict() == null ? null : slot.verdict().name();
            if (!Objects.equals(slot.rating(), pick.ratingAtAnswer())) {
                return Verdict.stale("pick " + pick.rank() + "'s rating changed");
            }
            if (!Objects.equals(verdict, pick.verdictAtAnswer())) {
                return Verdict.stale("pick " + pick.rank() + "'s verdict changed");
            }
            picks.add(new AskPick(pick.rank(), pick.locationId(), slot.name(),
                    candidate.get().region().name(), window.get().date(), window.get().targetType(),
                    window.get().id(), pick.why(), slot.rating(), verdict));
        }
        List<AskEvent> events = new ArrayList<>();
        for (AskEvent event : answer.events()) {
            Optional<LiveEvent> topic = liveEvent(event, live, scope);
            if (topic.isEmpty()) {
                return Verdict.stale("the " + event.type() + " event on " + event.date()
                        + " is no longer live");
            }
            events.add(new AskEvent(event.type(), topic.get().label(), event.date(), event.why(),
                    topic.get().safetyNote()));
        }
        return new Verdict(new AskAnswer(answer.answerable(), answer.summary(), picks, events,
                answer.missing()), null);
    }

    /** What a live topic or entry contributes to an event card. */
    private record LiveEvent(String label, String safetyNote) {
    }

    /**
     * The live topic or Coming up entry an event refers to: the same type on the same date, in scope.
     * A hot topic is matched on its date and an almanac entry on its first date (what the tools
     * return), and an entry counts only while it is inside the {@code get_coming_up} horizon and has
     * not ended. Of two matches the one carrying a safety note wins, so a warning is never lost.
     */
    private static Optional<LiveEvent> liveEvent(AskEvent event, AskSnapshot live,
            AskScope scope) {
        String type = event.type() == null ? "" : event.type().strip().toUpperCase(Locale.ROOT);
        LocalDate lastDay = live.today().plusDays(AskTools.MAX_COMING_UP_DAYS - 1L);
        List<LiveEvent> matches = new ArrayList<>();
        for (AskSnapshot.Topic topic : live.hotTopics()) {
            if (topic.type() != null && topic.type().strip().toUpperCase(Locale.ROOT).equals(type)
                    && Objects.equals(topic.date(), event.date()) && topic.inScope(scope)) {
                matches.add(new LiveEvent(topic.label(), topic.safetyNote()));
            }
        }
        for (AskSnapshot.ComingUp entry : live.comingUp()) {
            if (entry.type() != null && entry.type().strip().toUpperCase(Locale.ROOT).equals(type)
                    && Objects.equals(entry.startDate(), event.date())
                    && !entry.endDate().isBefore(live.today()) && !entry.startDate().isAfter(lastDay)) {
                matches.add(new LiveEvent(entry.title(), entry.safetyNote()));
            }
        }
        return matches.stream().min(Comparator.comparing(m -> m.safetyNote() == null));
    }
}
