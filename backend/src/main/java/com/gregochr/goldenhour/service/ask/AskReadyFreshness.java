package com.gregochr.goldenhour.service.ask;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
 *       within the question's windows; a coastal-high question's picks still at high water);</li>
 *   <li>for the night-bound {@code NIGHT_SKY}, the night's astro generation is no longer the one the
 *       answer was written from, or the night has passed ({@code nightMoved});</li>
 *   <li>the rating distribution the answer carries for any window it may have quoted is no longer the
 *       live one (equal records, no tolerance), or such a window has left the window set, or the
 *       windows the question covers are no longer the set the answer was built over
 *       ({@code spreadMoved}, ask-window-context plan §2.4).</li>
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
        // hasWindow, not window: a night-bound question names a night, which is not a solar window.
        if (!stored.windowIds().isEmpty() && stored.windowIds().stream().noneMatch(live::hasWindow)) {
            return Verdict.stale("every window the question names has passed");
        }
        Optional<ReadyQuestion.Offer> offer = question.offer(live, scope);
        if (offer.isEmpty()) {
            return Verdict.stale("the question is no longer offered");
        }
        if (!offer.get().text().equals(stored.questionText())) {
            return Verdict.stale("the question is now asked differently: " + offer.get().text());
        }
        if (question.nightBound()) {
            Optional<String> moved = nightMoved(stored, offer.get(), live);
            if (moved.isPresent()) {
                return Verdict.stale(moved.get());
            }
        }
        // A row with no covered set (written before it existed, or a question with no solar windows) is
        // unknown, not stale; one with a set must still describe the same windows.
        if (stored.coveredWindowIds() != null
                && !Set.copyOf(stored.coveredWindowIds()).equals(Set.copyOf(offer.get().coveredWindowIds()))) {
            return Verdict.stale("the windows the question covers have changed");
        }
        // A Ready answer is shared by every reader, so it is re-checked as one who may not see aurora.
        Verdict rechecked = recheck(stored.answer(), live, scope, false);
        if (!rechecked.fresh()) {
            return rechecked;
        }
        AskAnswer decorated = rechecked.answer();
        List<AskPick> picks = decorated.picks();
        if (question.anchored()) {
            Optional<AskSnapshot.Window> lead = AskAnswerValidator.anchoredWindow(live,
                    question.anchor(offer.get()), scope);
            // A stored no-pick answer stands only while the covered windows still hold no game-on place:
            // the very predicate it was admitted under (ReadyRelevance.mayHaveNoPick).
            boolean noPickStands = picks.isEmpty()
                    && ReadyRelevance.mayHaveNoPick(question, offer.get(), live, scope);
            if (lead.isPresent() && !noPickStands
                    && (picks.isEmpty() || !picks.getFirst().windowId().equals(lead.get().id()))) {
                return Verdict.stale("the BEST BET is now on " + lead.get().id());
            }
        }
        Optional<String> violation = ReadyRelevance.violation(question, decorated, offer.get(), live, scope);
        if (violation.isPresent()) {
            return Verdict.stale(violation.get());
        }
        return new Verdict(decorated, null);
    }

    /**
     * Why a night-bound answer no longer describes the night it was written about, or empty when it
     * still does (ask-night plan §2.5). Three conditions, all of the night and none of a pick: the
     * question is now about another night than the one stored (the offer's window differs); the answer
     * carries no night version (a row from before the version existed cannot be shown to be current);
     * and the version is no longer the live one ({@link AskNightVersion#isLive}, which re-derives it
     * from the night the string itself names), which is true when the astro job has re-run the night (a
     * new generation) and when the night has passed (it is no longer in the snapshot). A pick whose own
     * star moved, or that stopped being night-eligible, is the pick checks' to find.
     */
    private static Optional<String> nightMoved(AskReadyStore.Stored stored, ReadyQuestion.Offer offer,
            AskSnapshot live) {
        if (!offer.windowIds().equals(stored.windowIds())) {
            return Optional.of("the question is now about another night");
        }
        if (stored.astroVersion() == null) {
            return Optional.of("the answer carries no night version");
        }
        // The aurora segment is never part of a Ready answer's version: it is user-less.
        if (!AskNightVersion.isLive(stored.astroVersion(), live, false)) {
            return Optional.of("the night forecast has been re-run or has passed");
        }
        return Optional.empty();
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
     * @param canSeeAurora whether the reader may see aurora figures: false for a Ready answer (shared by
     *               every reader, so never aurora), the asker's own role for a typed-cache hit. A night pick
     *               is re-checked against its live night figure under that flag
     * @return the re-decorated answer, or the reason it is withheld
     */
    static Verdict recheck(AskAnswer answer, AskSnapshot live, AskScope scope, boolean canSeeAurora) {
        List<AskPick> picks = new ArrayList<>();
        for (AskPick pick : answer.picks()) {
            if (!live.hasWindow(pick.windowId())) {
                return Verdict.stale("pick " + pick.rank() + "'s window has passed");
            }
            // Solar or night: the live pick under its own eligibility rule (a night pick's reads the
            // asker's aurora access, which a user-less Ready answer never has).
            Optional<AskSnapshot.ResolvedPick> resolved =
                    live.resolvePick(pick.windowId(), pick.locationId(), canSeeAurora);
            if (resolved.isEmpty() || !scope.contains(resolved.get().regionName())) {
                return Verdict.stale("pick " + pick.rank() + " is no longer pick-eligible");
            }
            AskSnapshot.ResolvedPick r = resolved.get();
            if (!Objects.equals(r.rating(), pick.ratingAtAnswer())) {
                return Verdict.stale("pick " + pick.rank() + "'s rating changed");
            }
            if (!Objects.equals(r.verdict(), pick.verdictAtAnswer())) {
                return Verdict.stale("pick " + pick.rank() + "'s verdict changed");
            }
            picks.add(new AskPick(pick.rank(), pick.locationId(), r.locationName(), r.regionName(), r.date(),
                    r.kind(), r.windowId(), pick.why(), r.rating(), r.verdict()));
        }
        List<AskEvent> events = new ArrayList<>();
        for (AskEvent event : answer.events()) {
            Optional<LiveEvent> topic = liveEvent(event, live, scope);
            if (topic.isEmpty()) {
                return Verdict.stale("the " + event.type() + " event on " + event.date()
                        + " is no longer live");
            }
            events.add(new AskEvent(AskEventType.key(event.type()), topic.get().label(), event.date(), event.why(),
                    topic.get().safetyNote()));
        }
        Optional<String> spreadMoved = spreadMoved(answer, live, scope);
        if (spreadMoved.isPresent()) {
            return Verdict.stale(spreadMoved.get());
        }
        return new Verdict(new AskAnswer(answer.answerable(), answer.summary(), picks, events,
                answer.missing(), answer.windows()), null);
    }

    /**
     * Why an answer's carried rating distributions no longer hold, or empty when they all do (or it
     * carries none: a row from before the figures existed is unknown, not stale). Every carried window
     * must still be in the window set and its live {@link AskSnapshot.Spread} must EQUAL the carried one:
     * record equality over every field, the pockets and their means included, so no figure the prompt
     * lets the model quote can drift unchecked. A window that has passed or left the set is stale, not
     * skipped: the prose about it can no longer be verified, and it must not outlive a surviving pick on
     * another window.
     */
    private static Optional<String> spreadMoved(AskAnswer answer, AskSnapshot live, AskScope scope) {
        for (AskAnswer.WindowSpread carried : answer.windows()) {
            Optional<AskSnapshot.Spread> now = live.spread(carried.windowId(), scope);
            if (now.isEmpty()) {
                return Optional.of("window " + carried.windowId() + ", whose rating spread the answer carries, "
                        + "is no longer in the forecast");
            }
            if (!now.get().equals(carried.spread())) {
                return Optional.of("the rating spread of " + carried.windowId() + " has moved");
            }
        }
        return Optional.empty();
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
        LocalDate lastDay = live.today().plusDays(AskSnapshot.MAX_COMING_UP_DAYS - 1L);
        List<LiveEvent> matches = new ArrayList<>();
        for (AskSnapshot.Topic topic : live.hotTopics()) {
            if (AskEventType.same(topic.type(), event.type())
                    && Objects.equals(topic.date(), event.date()) && topic.inScope(scope)) {
                matches.add(new LiveEvent(topic.label(), topic.safetyNote()));
            }
        }
        for (AskSnapshot.ComingUp entry : live.comingUp()) {
            if (AskEventType.same(entry.type(), event.type())
                    && Objects.equals(entry.startDate(), event.date())
                    && !entry.endDate().isBefore(live.today()) && !entry.startDate().isAfter(lastDay)) {
                matches.add(new LiveEvent(entry.title(), entry.safetyNote()));
            }
        }
        return matches.stream().min(Comparator.comparing(m -> m.safetyNote() == null));
    }
}
