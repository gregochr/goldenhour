package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.TargetType;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The Ready catalogue (plan §2.4): the questions PhotoCast answers ahead of time, once per scope,
 * for every reader. A horizon-aware set, not a fixed six — "best spot this weekend?" cannot be
 * answered on a Monday, because nothing beyond the scored horizon is rated.
 *
 * <p>Each question decides, from a snapshot and a scope, whether it can be asked at all
 * ({@link #offer}) and, if so, the <b>text</b> it is asked under and the <b>windows</b> it is
 * about. Text that depends on "now" ({@code tonight}, {@code tomorrow}, a weekday) is built here
 * from the snapshot's UK civil date and stored with the answer: it is fixed at precompute time,
 * and a serve offers the question again against live data and withholds the answer when the text
 * no longer matches (so a "tomorrow" stored on Sunday is never shown on Monday).
 *
 * <p>The predicates use only {@link AskSnapshot#candidates(AskSnapshot.Window, AskScope)} — the
 * one definition of a pick-eligible slot in scope that {@code rank_spots} and the validator also
 * use — so a question is offered exactly when the tools could return something for it.
 */
public enum ReadyQuestion {

    /** Best spot this weekend: a Saturday or Sunday window in the window set has a pick. */
    BEST_WEEKEND(true, true, Events.NONE, "plan", "map") {
        @Override
        Optional<Offer> offer(AskSnapshot snapshot, AskScope scope) {
            List<String> ids = windowsWithCandidates(snapshot, scope, w ->
                    w.date().getDayOfWeek() == DayOfWeek.SATURDAY
                            || w.date().getDayOfWeek() == DayOfWeek.SUNDAY);
            return ids.isEmpty() ? Optional.empty()
                    : Optional.of(new Offer("Best spot this weekend?", ids, null));
        }
    },

    /** Best spot in the next few days: no weekend to ask about, and at least two windows to compare. */
    BEST_SOON(true, true, Events.NONE, "plan", "map") {
        @Override
        Optional<Offer> offer(AskSnapshot snapshot, AskScope scope) {
            if (BEST_WEEKEND.offer(snapshot, scope).isPresent()) {
                return Optional.empty();
            }
            List<String> ids = windowsWithCandidates(snapshot, scope, w -> true);
            return ids.size() < 2 ? Optional.empty()
                    : Optional.of(new Offer("Best spot in the next few days?", ids, null));
        }
    },

    /** Best spot at the next window: tonight, this morning, tomorrow morning and so on. */
    BEST_NEXT(true, true, Events.NONE, "plan", "map") {
        @Override
        Optional<Offer> offer(AskSnapshot snapshot, AskScope scope) {
            if (snapshot.windows().isEmpty()) {
                return Optional.empty();
            }
            AskSnapshot.Window next = snapshot.windows().getFirst();
            if (snapshot.candidates(next, scope).isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new Offer("Best spot " + nextWindowWords(next, snapshot.today()) + "?",
                    List.of(next.id()), next.id()));
        }
    },

    /** Best coastal spot at high tide: some pick-eligible coastal slot has high water at its event. */
    COASTAL_HIGH(true, false, Events.NONE, "map") {
        @Override
        Optional<Offer> offer(AskSnapshot snapshot, AskScope scope) {
            List<String> ids = new ArrayList<>();
            for (AskSnapshot.Window w : snapshot.windows()) {
                if (snapshot.candidates(w, scope).stream().anyMatch(c -> isHighWater(c.slot()))) {
                    ids.add(w.id());
                }
            }
            return ids.isEmpty() ? Optional.empty()
                    : Optional.of(new Offer("Best coastal spot at high tide?", ids, null));
        }

        @Override
        boolean admitsSlot(AskSnapshot.Slot slot) {
            return isHighWater(slot);
        }
    },

    /**
     * Sunrise or sunset on one day: the next date on which both are in the window set, and each has
     * a pick-eligible slot in scope (a comparison needs both sides).
     */
    AM_OR_PM(true, false, Events.NONE, "plan") {
        @Override
        Optional<Offer> offer(AskSnapshot snapshot, AskScope scope) {
            List<AskSnapshot.Window> withCandidates = snapshot.windows().stream()
                    .filter(w -> !snapshot.candidates(w, scope).isEmpty())
                    .toList();
            for (AskSnapshot.Window w : withCandidates) {
                Optional<AskSnapshot.Window> sunrise = onDate(withCandidates, w.date(),
                        TargetType.SUNRISE);
                Optional<AskSnapshot.Window> sunset = onDate(withCandidates, w.date(),
                        TargetType.SUNSET);
                if (sunrise.isPresent() && sunset.isPresent()) {
                    return Optional.of(new Offer("Sunrise or sunset "
                            + dayWords(w.date(), snapshot.today()) + "?",
                            List.of(sunrise.get().id(), sunset.get().id()), null));
                }
            }
            return Optional.empty();
        }
    },

    /** Rare events coming up. Always asked; the answer is stored only when it finds something. */
    RARE_EVENTS(false, false, Events.ANY, "coming-up", "map") {
        @Override
        Optional<Offer> offer(AskSnapshot snapshot, AskScope scope) {
            return Optional.of(new Offer("Any rare events coming up?", List.of(), null));
        }
    },

    /** Snow on the tops. Always asked; the answer is stored only when it finds something. */
    SNOW_TOPS(false, false, Events.SNOW, "coming-up") {
        @Override
        Optional<Offer> offer(AskSnapshot snapshot, AskScope scope) {
            return Optional.of(new Offer("Is there snow on the tops?", List.of(), null));
        }
    };

    private static final String HIGH = "HIGH";

    /**
     * The event types a question keeps. A holder class because an enum constant's arguments cannot
     * name a static field of the enum itself.
     */
    private static final class Events {

        /** A pick question keeps no event card: its cards are picks. */
        static final Set<String> NONE = Set.of();

        /** Every event a tool returned (null reads as "any"). */
        static final Set<String> ANY = null;

        /**
         * The snow hot-topic types: {@code SnowTopsHotTopicStrategy}'s {@code SNOW_TOPS} and the two
         * {@code SnowFreshHotTopicStrategy} raises, {@code SNOW_FRESH} and {@code SNOW_MIST}. No
         * almanac (Coming up) entry type is about snow (its snow readings are conditions, not
         * entries), so none is listed: a snow almanac type would have to be added here on purpose.
         */
        static final Set<String> SNOW = Set.of("SNOW_TOPS", "SNOW_FRESH", "SNOW_MIST");
    }

    private final boolean picks;
    private final boolean anchored;
    private final Set<String> eventTypes;
    private final List<String> tabs;

    ReadyQuestion(boolean picks, boolean anchored, Set<String> eventTypes, String... tabs) {
        this.picks = picks;
        this.anchored = anchored;
        this.eventTypes = eventTypes;
        this.tabs = List.of(tabs);
    }

    /**
     * What a question is asked under, for one scope against one snapshot.
     *
     * @param text            the question text, with any day word fixed
     * @param windowIds       the windows the question is about, chronological; empty for an events
     *                        question
     * @param contextWindowId the one window the conversation should treat as the reader's context
     *                        ({@code BEST_NEXT}'s next window), or null
     */
    public record Offer(String text, List<String> windowIds, String contextWindowId) {

        /** Canonical constructor: takes an immutable copy of the window ids. */
        public Offer {
            windowIds = List.copyOf(windowIds);
        }
    }

    /**
     * Whether the question can be asked now, and under what text and windows.
     *
     * @param snapshot the snapshot
     * @param scope    the regions the question is about
     * @return the offer, or empty when the question is not available
     */
    abstract Optional<Offer> offer(AskSnapshot snapshot, AskScope scope);

    /**
     * Whether this question is answered with picks (a where-or-when question) rather than with
     * event cards.
     *
     * @return true for a pick question
     */
    public boolean picks() {
        return picks;
    }

    /**
     * Whether the answer must lead with the forecast's own BEST BET ({@code BEST_*}; plan §2.3).
     *
     * @return true for {@code BEST_WEEKEND}, {@code BEST_SOON} and {@code BEST_NEXT}
     */
    public boolean anchored() {
        return anchored;
    }

    /**
     * The tabs this question is offered on.
     *
     * @return {@code plan}, {@code map} and/or {@code coming-up}
     */
    public List<String> tabs() {
        return tabs;
    }

    /**
     * The anchor to hand the engine, or null when this question is not a {@code BEST_*} one.
     *
     * @param offer the question's offer
     * @return the anchor over the offer's windows, or null
     */
    public AskAnswerValidator.BestAnchor anchor(Offer offer) {
        return anchored ? new AskAnswerValidator.BestAnchor(Set.copyOf(offer.windowIds())) : null;
    }

    /**
     * Whether an event of this type is relevant to this question: the one event predicate, applied
     * when an answer is stored and again when it is served. A pick question keeps no event card (its
     * cards are picks); {@code RARE_EVENTS} keeps any; {@code SNOW_TOPS} keeps only the snow topic
     * types ({@code SNOW_TOPS}, {@code SNOW_FRESH}, {@code SNOW_MIST}).
     *
     * @param type the event's served type, any case or spelling ({@link AskEventType#key})
     * @return true when the question may carry an event of this type
     */
    public boolean admitsEvent(String type) {
        if (eventTypes == null) {
            return true;
        }
        return type != null && eventTypes.contains(AskEventType.key(type));
    }

    /**
     * Whether a pick's live slot is of the kind this question asks for, beyond being pick-eligible in
     * scope: {@code COASTAL_HIGH} wants a coastal slot at high water, every other question any slot.
     *
     * @param slot the pick's live slot
     * @return true when the slot suits the question
     */
    boolean admitsSlot(AskSnapshot.Slot slot) {
        return true;
    }

    /**
     * Whether a pick is relevant to this question: the one pick predicate, applied when an answer is
     * stored and again when it is served. An events question admits no pick. A pick question admits
     * one only if it is on one of the question's own windows ({@code BEST_WEEKEND}: the Saturday and
     * Sunday windows; {@code BEST_SOON}: the windows with a pick; {@code BEST_NEXT}: the next window
     * alone; {@code COASTAL_HIGH}: the windows with a coastal slot at high water; {@code AM_OR_PM}:
     * that date's two windows), its live slot is pick-eligible within the question's scope, and the
     * slot suits the question ({@link #admitsSlot}).
     *
     * @param pick     the pick
     * @param offer    what the question is asked under
     * @param snapshot the snapshot to read the live slot from
     * @param scope    the question's scope
     * @return true when the question may carry the pick
     */
    boolean admitsPick(AskPick pick, Offer offer, AskSnapshot snapshot, AskScope scope) {
        if (!picks || !offer.windowIds().contains(pick.windowId())) {
            return false;
        }
        Optional<AskSnapshot.Candidate> candidate = snapshot.candidate(pick.windowId(), pick.locationId());
        return candidate.isPresent()
                && scope.contains(candidate.get().region().name())
                && admitsSlot(candidate.get().slot());
    }

    /**
     * An answer reduced to what this question may carry: an events question loses any pick and any
     * event it does not admit; a pick question loses every event. The picks of a pick question are
     * never dropped here (dropping one would renumber the ranks and could remove the BEST BET lead): a
     * wrong pick fails {@link #violation} instead.
     *
     * @param answer the validated answer
     * @return the answer with the irrelevant events and picks removed
     */
    AskAnswer relevantPart(AskAnswer answer) {
        List<AskEvent> events = answer.events().stream().filter(e -> admitsEvent(e.type())).toList();
        return new AskAnswer(answer.answerable(), answer.summary(), picks ? answer.picks() : List.of(),
                events, answer.missing());
    }

    /**
     * Whether {@link #relevantPart} would remove an event that carries a safety warning. Such an
     * answer cannot be stored: the summary may name the event, and dropping its card would drop the
     * warning the card must show.
     *
     * @param answer the validated answer
     * @return true when an event with a safety note would be dropped
     */
    boolean dropsWarning(AskAnswer answer) {
        return answer.events().stream().anyMatch(e -> !admitsEvent(e.type()) && e.safetyNote() != null);
    }

    /**
     * Whether an answer is what this question wants and carries nothing it may not: a pick question
     * needs a pick and carries no event, an events question needs an event and carries no pick, every
     * event is of an admitted type ({@link #admitsEvent}) and every pick is admitted
     * ({@link #admitsPick}). Run on the reduced answer when it is stored and on the stored answer when
     * it is served, so a row written under an older, looser rule is withheld rather than served.
     *
     * @param answer   the answer
     * @param offer    what the question is asked under
     * @param snapshot the snapshot to read live slots from
     * @param scope    the question's scope
     * @return why the answer must not be stored or served, or empty when it may be
     */
    Optional<String> violation(AskAnswer answer, Offer offer, AskSnapshot snapshot,
            AskScope scope) {
        if (picks && answer.picks().isEmpty()) {
            return Optional.of("a pick question with no pick");
        }
        if (!picks && answer.events().isEmpty()) {
            return Optional.of("an events question with no event");
        }
        for (AskEvent event : answer.events()) {
            if (!admitsEvent(event.type())) {
                return Optional.of("a " + event.type() + " event is not relevant to " + name());
            }
        }
        for (AskPick pick : answer.picks()) {
            if (!admitsPick(pick, offer, snapshot, scope)) {
                return Optional.of("pick " + pick.rank() + " is not relevant to " + name() + " ("
                        + pick.windowId() + ")");
            }
        }
        return Optional.empty();
    }

    // -- shared helpers -----------------------------------------------------------------------

    private static boolean isHighWater(AskSnapshot.Slot slot) {
        return HIGH.equals(slot.tideState());
    }

    /** The ids of the windows that pass {@code filter} and have a pick-eligible slot in scope. */
    private static List<String> windowsWithCandidates(AskSnapshot snapshot, AskScope scope,
            java.util.function.Predicate<AskSnapshot.Window> filter) {
        return snapshot.windows().stream()
                .filter(filter)
                .filter(w -> !snapshot.candidates(w, scope).isEmpty())
                .map(AskSnapshot.Window::id)
                .toList();
    }

    private static Optional<AskSnapshot.Window> onDate(List<AskSnapshot.Window> windows,
            LocalDate date, TargetType type) {
        return windows.stream()
                .filter(w -> w.date().equals(date) && w.targetType() == type)
                .findFirst();
    }

    /** {@code today}, {@code tomorrow} or {@code on Saturday}, from the UK civil date. */
    static String dayWords(LocalDate date, LocalDate today) {
        if (date.equals(today)) {
            return "today";
        }
        if (date.equals(today.plusDays(1))) {
            return "tomorrow";
        }
        return "on " + date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }

    /** {@code tonight}, {@code this morning}, {@code tomorrow morning}, {@code on Saturday evening}. */
    static String nextWindowWords(AskSnapshot.Window window, LocalDate today) {
        boolean sunrise = window.targetType() == TargetType.SUNRISE;
        if (window.date().equals(today)) {
            return sunrise ? "this morning" : "tonight";
        }
        return dayWords(window.date(), today) + (sunrise ? " morning" : " evening");
    }
}
