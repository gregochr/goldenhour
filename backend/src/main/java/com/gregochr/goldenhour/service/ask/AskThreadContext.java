package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.service.ask.AskEvidence.EventFact;
import com.gregochr.goldenhour.service.ask.AskEvidence.Pair;
import com.gregochr.goldenhour.service.ask.AskEvidence.ThreadFact;
import com.gregochr.goldenhour.service.ask.ThreadExchange.EventRef;
import com.gregochr.goldenhour.service.ask.ThreadExchange.PickRef;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A thread reconciled with the live forecast, for one conversation (plan
 * {@code ask-thread-plan.md} §2.3 items 2 and 3).
 *
 * <p>At opening it works out, from the snapshot and the question's scope, which of the earlier
 * answers' picks and events are still there to be named: that is the {@link ThreadFact} the validator
 * reads, and it is computed whether or not anything is ever rendered (the stub renders nothing). Only
 * when the prompt is built does it render the earlier exchanges, and then <em>every fact is
 * re-joined from the live snapshot</em>: the thread carries ids and the reader's own words, never a
 * star, a verdict or a time. A pick whose slot has gone says so, and a pick outside the follow-up's
 * scope says that, and neither is offered as evidence.
 *
 * <p>The drive minutes in a pick line are the asker's own, read through the conversation's
 * {@link AskTools} (the same source {@code rank_spots} reads); the id-to-name map for a pick whose slot
 * has gone is loaded at most once, on first need.
 *
 * <p>Not thread-safe; a conversation is single-threaded.
 */
final class AskThreadContext {

    /** A fresh question: nothing earlier, nothing offered, nothing rendered. */
    static final AskThreadContext NONE = new AskThreadContext(AskThread.EMPTY, ThreadFact.NONE,
            Map.of(), null, null, null, null, false);

    /**
     * Said of an earlier night answer in place of its summary: a night summary states things that move
     * under the thread ("active, Kp 6", a clear-sky claim), so it is never rendered verbatim — its picks
     * are re-joined live below it, like every pick.
     */
    static final String NIGHT_SUMMARY_WITHHELD =
            "(an answer about the night sky; its places are listed below with today's figures)";

    /** Said of a pick whose place has left the forecast and whose name is not known either. */
    static final String UNLISTED_PLACE = "a place no longer listed";

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * One earlier exchange as the prompt shows it.
     *
     * @param question  the reader's earlier question
     * @param summary   the words the reader was shown
     * @param pickLines the earlier picks, each re-joined live, in the order the answer gave them
     * @param eventLines the earlier events, each re-joined live
     */
    record ExchangeView(String question, String summary, List<String> pickLines, List<String> eventLines) {
    }

    private final AskThread thread;
    private final ThreadFact fact;
    private final Map<String, EventFact> liveEvents;
    private final AskSnapshot snapshot;
    private final AskScope scope;
    private final AskTools tools;
    private final AskLocationNames names;
    private final boolean canSeeAurora;
    private Map<Long, AskLocationNames.Place> nameById;

    /** Said of an earlier pick the neighbour guard now withholds: its rating has not fallen. */
    static final String HELD_BACK_MARK = " · held back: rated well above the places beside it";

    /** The line that tells the model the reader has turned the earlier places down. */
    static final String TURNED_DOWN_MARK = " · the reader has turned this place down";

    private AskThreadContext(AskThread thread, ThreadFact fact, Map<String, EventFact> liveEvents,
            AskSnapshot snapshot, AskScope scope, AskTools tools, AskLocationNames names,
            boolean canSeeAurora) {
        this.thread = thread;
        this.fact = fact;
        this.liveEvents = liveEvents;
        this.snapshot = snapshot;
        this.scope = scope;
        this.tools = tools;
        this.names = names;
        this.canSeeAurora = canSeeAurora;
    }

    /**
     * Reconciles a thread with the live snapshot, optionally marking its places as turned down.
     *
     * @param thread     the validated thread; an empty one gives {@link #NONE}
     * @param snapshot   the snapshot the conversation runs against
     * @param scope      the follow-up's scope, which may differ from the earlier questions'
     * @param tools      the conversation's tools, for the asker's drive minutes and the spot vocabulary
     * @param names      the names of enabled locations, read only for a pick whose slot has gone
     * @param turnedDown whether the follow-up turns the earlier places down: every earlier pick's location
     *                   is then excluded from the answer and marked so in the prompt
     * @param canSeeAurora whether the asker's role may see aurora figures: a client could echo a forged
     *                   {@code _aurora} pick reference, so an earlier aurora pick is offered only to an asker
     *                   who could have been given one, and is otherwise reported as gone
     * @return the context
     */
    static AskThreadContext of(AskThread thread, AskSnapshot snapshot, AskScope scope, AskTools tools,
            AskLocationNames names, boolean turnedDown, boolean canSeeAurora) {
        if (thread == null || thread.isEmpty()) {
            return NONE;
        }
        Set<Long> turnedDownIds = new LinkedHashSet<>();
        Set<Pair> offered = new LinkedHashSet<>();
        Set<Pair> dropped = new LinkedHashSet<>();
        Set<Pair> heldBack = new LinkedHashSet<>();
        Map<String, EventFact> live = new LinkedHashMap<>();
        List<AskSnapshot.ComingUp> timeline = hasEvents(thread)
                ? snapshot.timeline(scope, AskSnapshot.MAX_COMING_UP_DAYS) : List.of();
        for (ThreadExchange exchange : thread.exchanges()) {
            for (PickRef ref : exchange.picks()) {
                Pair pair = new Pair(ref.locationId(), ref.windowId());
                if (turnedDown) {
                    turnedDownIds.add(ref.locationId());
                }
                Optional<AskSnapshot.ResolvedPick> resolved =
                        snapshot.resolvePick(ref.windowId(), ref.locationId(), canSeeAurora);
                boolean stillOffered = resolved.isPresent() && scope.contains(resolved.get().regionName());
                (stillOffered ? offered : dropped).add(pair);
                if (!stillOffered && isHeldBack(snapshot, scope, ref)) {
                    heldBack.add(pair);
                }
            }
            for (EventRef ref : exchange.events()) {
                liveEvent(ref, snapshot, scope, timeline).ifPresent(
                        f -> live.put(AskEventType.offerKey(ref.type(), ref.date()), f));
            }
        }
        // A pick named twice is offered if it is offered at all: nothing is both.
        dropped.removeAll(offered);
        ThreadFact fact = new ThreadFact(offered, dropped, new LinkedHashSet<>(live.values()), turnedDownIds,
                heldBack);
        return new AskThreadContext(thread, fact, live, snapshot, scope, tools, names, canSeeAurora);
    }

    /** Whether an earlier solar pick is in scope and refused for the neighbour mark alone. */
    private static boolean isHeldBack(AskSnapshot snapshot, AskScope scope, PickRef ref) {
        return snapshot.slotAt(ref.windowId(), ref.locationId())
                .filter(c -> scope.contains(c.region().name()))
                .filter(c -> AskSnapshot.isHeldBack(c.region(), c.slot()))
                .isPresent();
    }

    private static boolean hasEvents(AskThread thread) {
        return thread.exchanges().stream().anyMatch(e -> !e.events().isEmpty());
    }

    /** The served fact an earlier event card still corresponds to: a live in-scope topic, else an almanac entry. */
    private static Optional<EventFact> liveEvent(EventRef ref, AskSnapshot snapshot, AskScope scope,
            List<AskSnapshot.ComingUp> timeline) {
        Optional<EventFact> topic = snapshot.hotTopics().stream()
                .filter(t -> t.inScope(scope) && AskEventType.same(t.type(), ref.type())
                        && ref.date().equals(t.date()))
                .map(t -> new EventFact(AskEventType.served(t.type()), t.label(), t.date(), t.safetyNote()))
                .findFirst();
        if (topic.isPresent()) {
            return topic;
        }
        return timeline.stream()
                .filter(e -> AskEventType.same(e.type(), ref.type()) && ref.date().equals(e.startDate()))
                .map(e -> new EventFact(AskEventType.served(e.type()), e.title(), e.startDate(), e.safetyNote()))
                .findFirst();
    }

    /**
     * What the thread offers the validator.
     *
     * @return the offered picks and events, and the picks that have gone
     */
    ThreadFact fact() {
        return fact;
    }

    /**
     * Whether the follow-up turns the earlier places down, so the prompt must say they are not to be picked.
     *
     * @return true when the earlier picks' locations are excluded
     */
    boolean turnedDown() {
        return !fact.turnedDown().isEmpty();
    }

    /**
     * Whether there is any earlier exchange at all.
     *
     * @return true for a fresh question
     */
    boolean isEmpty() {
        return thread.isEmpty();
    }

    /**
     * How many earlier exchanges the conversation carries.
     *
     * @return the count
     */
    int size() {
        return thread.size();
    }

    /**
     * The earlier exchanges as the prompt shows them, each pick and event re-joined from the live
     * snapshot.
     *
     * @return the exchanges, oldest first; empty for a fresh question
     */
    List<ExchangeView> exchanges() {
        List<ExchangeView> views = new ArrayList<>();
        for (ThreadExchange exchange : thread.exchanges()) {
            List<String> picks = new ArrayList<>();
            int rank = 1;
            for (PickRef ref : exchange.picks()) {
                picks.add(rank++ + ". " + pickLine(ref));
            }
            List<String> events = exchange.events().stream().map(this::eventLine).toList();
            // A night answer's summary is never shown verbatim: it states things that move under the
            // thread. Its picks are re-joined live, like every pick, and say what the figures are now.
            String summary = exchange.nightVersion() == null ? quoted(exchange.summary())
                    : NIGHT_SUMMARY_WITHHELD;
            views.add(new ExchangeView(quoted(exchange.question()), summary, picks, events));
        }
        return views;
    }

    /** Text the reader or the model wrote, made safe to sit between the prompt's own quotation marks. */
    private static String quoted(String text) {
        if (text == null) {
            return "";
        }
        return WHITESPACE.matcher(text.replace('"', '\'')).replaceAll(" ").strip();
    }

    private String pickLine(PickRef ref) {
        String line = pickLineBody(ref);
        return fact.turnedDown().contains(ref.locationId()) ? line + TURNED_DOWN_MARK : line;
    }

    private String pickLineBody(PickRef ref) {
        if (AskWindowId.parse(ref.windowId()).map(p -> p.kind().isNight()).orElse(false)) {
            return nightPickLineBody(ref);
        }
        Optional<AskSnapshot.Candidate> slot = snapshot.slotAt(ref.windowId(), ref.locationId());
        if (slot.isEmpty()) {
            return nameOf(ref.locationId()) + " · no longer in the forecast";
        }
        AskSnapshot.Candidate c = slot.get();
        if (!scope.contains(c.region().name())) {
            return c.slot().name() + " · outside the area you are now asking about";
        }
        if (AskSnapshot.isHeldBack(c.region(), c.slot())) {
            return c.slot().name() + HELD_BACK_MARK;
        }
        if (!AskSnapshot.isPickEligible(c.region(), c.slot())) {
            return c.slot().name() + " · no longer rated well enough to pick";
        }
        AskTools.SpotInfo spot = tools.spotInfo(c, tools.driveMinutesOf(ref.locationId()));
        // The ids are what rank_spots returns and submit_answer takes: without them the model can only
        // guess a locationId for an earlier pick it wants to name again.
        StringBuilder line = new StringBuilder(spot.name()).append(" (").append(spot.region()).append(") · ")
                .append("locationId ").append(spot.locationId()).append(" · windowId ").append(spot.windowId())
                .append(" · ").append(AskTools.dayWord(c.window().date(), snapshot.today())).append(' ')
                .append(c.window().targetType().name().toLowerCase(Locale.ROOT))
                .append(" · ").append(spot.rating()).append('★');
        if (spot.verdict() != null) {
            line.append(" · ").append(spot.verdict());
        }
        if (spot.driveMinutes() != null) {
            line.append(" · ").append(spot.driveMinutes()).append(" min from home");
        }
        if (Boolean.TRUE.equals(spot.tideAligned())) {
            line.append(" · tide suits");
        } else if (spot.tideState() != null) {
            line.append(" · tide ").append(spot.tideState().toLowerCase(Locale.ROOT))
                    .append(", not the water it wants");
        }
        return line.toString();
    }

    /**
     * An earlier night pick, re-joined live in {@code rank_night_spots}' vocabulary (the ids the model needs
     * to name it again, the figure of the window's own kind, the Bortle class, the clear-sky line). An
     * aurora pick the asker could not have been given reads as gone, whatever the snapshot holds.
     */
    private String nightPickLineBody(PickRef ref) {
        Optional<AskSnapshot.NightCandidate> slot = snapshot.nightSlotAt(ref.windowId(), ref.locationId());
        boolean hidden = slot.isPresent() && slot.get().night().kind() == AskWindowKind.AURORA && !canSeeAurora;
        if (slot.isEmpty() || hidden) {
            return nameOf(ref.locationId()) + " · no longer in the forecast";
        }
        AskSnapshot.Night night = slot.get().night();
        AskSnapshot.NightSpot spot = slot.get().spot();
        if (!scope.contains(spot.regionName())) {
            return spot.name() + " · outside the area you are now asking about";
        }
        if (!AskSnapshot.isNightPickEligible(night.kind(), spot, canSeeAurora)) {
            return spot.name() + " · no longer rated well enough to pick";
        }
        StringBuilder line = new StringBuilder(spot.name()).append(" (").append(spot.regionName()).append(") · ")
                .append("locationId ").append(spot.locationId()).append(" · windowId ").append(night.id())
                .append(" · ").append(night.date().equals(snapshot.nights().tonight()) ? "tonight"
                        : "tomorrow night")
                .append(' ').append(night.kind().name().toLowerCase(Locale.ROOT)).append(" · ")
                .append(AskSnapshot.nightRating(night.kind(), spot)).append('★');
        if (spot.bortleClass() != null) {
            line.append(" · Bortle ").append(spot.bortleClass());
        }
        if (night.kind() == AskWindowKind.ASTRO && spot.cloudExplanation() != null) {
            line.append(" · ").append(AskTools.cap(spot.cloudExplanation(), AskTools.CLEAR_SKY_CAP));
        }
        if (night.kind() == AskWindowKind.AURORA && canSeeAurora) {
            // The CURRENT aurora facts, so "what Kp was that?" or "why is the aurora good there?" can be answered
            // without another ranking call. Only for an asker who may see aurora: the hidden branch above has
            // already reported an aurora pick as gone for anyone else, and this repeats the gate.
            if (spot.alertLevel() != null) {
                line.append(" · alert ").append(spot.alertLevel().toLowerCase(Locale.ROOT));
            }
            if (spot.maxKp() != null) {
                line.append(String.format(Locale.ROOT, " · max Kp %.1f", spot.maxKp()));
            }
            if (spot.auroraSummary() != null) {
                line.append(" · ").append(AskTools.cap(spot.auroraSummary(), AskTools.NIGHT_SUMMARY_CAP));
            }
        }
        Integer drive = tools.driveMinutesOf(ref.locationId());
        if (drive != null) {
            line.append(" · ").append(drive).append(" min from home");
        }
        return line.toString();
    }

    private String nameOf(Long locationId) {
        if (nameById == null) {
            nameById = names == null ? Map.of() : names.byId();
        }
        AskLocationNames.Place place = locationId == null ? null : nameById.get(locationId);
        String name = place == null ? null : place.name();
        return name == null || name.isBlank() ? UNLISTED_PLACE : name;
    }

    private String eventLine(EventRef ref) {
        EventFact live = liveEvents.get(AskEventType.offerKey(ref.type(), ref.date()));
        if (live != null) {
            return live.label() + " · " + AskTools.dayWord(live.date(), snapshot.today());
        }
        LocalDate date = ref.date();
        return AskEventType.key(ref.type()) + " · " + date + " · no longer flagged";
    }
}
