package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.util.DayLabels;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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
 * <p>What a question may <em>carry</em> (which events and picks it admits, and the reduction of an answer
 * to them) is {@link ReadyRelevance}, one code path for store and serve.
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
                if (snapshot.candidates(w, scope).stream().anyMatch(c -> ReadyRelevance.isHighWater(c.slot()))) {
                    ids.add(w.id());
                }
            }
            return ids.isEmpty() ? Optional.empty()
                    : Optional.of(new Offer("Best coastal spot at high tide?", ids, null));
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
     * The event types this question keeps; null means "any". Read by {@link ReadyRelevance}, which owns
     * what a question may carry.
     *
     * @return the kept event types (upper-case keys), empty for a pick question, or null for any
     */
    Set<String> eventTypes() {
        return eventTypes;
    }

    // -- shared helpers -----------------------------------------------------------------------

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
        String relative = DayLabels.relative(date, today);
        return date.equals(today) || date.equals(today.plusDays(1)) ? relative : "on " + relative;
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
