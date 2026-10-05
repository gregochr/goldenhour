package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.DisplayVerdict;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * A read-only picture of the served briefing, for Ask's tools and validator (plan §2.2).
 *
 * <p>Built from {@code BriefingService.getCachedBriefingForApi()} — the Plan tab's own assembly —
 * so retraction, the verdict sample gate and the honesty filter are inherited, never re-derived.
 * It carries only served facts. Nothing here is computed from a rating except the single
 * pick-eligibility test, {@link #isPickEligible}, which exists so that every tool and the
 * validator apply the same rule.
 *
 * <p><b>{@code generatedAt} is a label, not the identity of the ratings.</b> Ratings are
 * re-enriched on every serve and hot topics are recomputed live, so freshness is checked against
 * live data (plan §2.4), never inferred from the build time.
 *
 * @param generatedAt when the briefing was built (UTC), or null
 * @param runLabel    {@code HH:mm} Europe/London of {@code generatedAt}, or null
 * @param today       today on the UK civil calendar
 * @param windows     the window set, chronological: solar windows the Plan tab renders that have
 *                    not passed and are not travel days
 * @param hotTopics   the served hot topics
 * @param comingUp    the almanac entries
 */
public record AskSnapshot(LocalDateTime generatedAt, String runLabel, LocalDate today,
        List<Window> windows, List<Topic> hotTopics, List<ComingUp> comingUp) {

    /** The least served rating a slot may carry and still be offered as a pick. */
    public static final int MIN_PICK_RATING = 3;

    /** Canonical constructor: takes immutable copies; a null list reads as empty. */
    public AskSnapshot {
        windows = windows == null ? List.of() : List.copyOf(windows);
        hotTopics = hotTopics == null ? List.of() : List.copyOf(hotTopics);
        comingUp = comingUp == null ? List.of() : List.copyOf(comingUp);
    }

    /**
     * One window in the window set.
     *
     * @param id         the window id ({@link AskWindowId})
     * @param date       the date of the event
     * @param targetType SUNRISE or SUNSET
     * @param eventTime  the window's served event time (UTC), or null
     * @param verdict    the window's served verdict
     * @param bestRating the window's served best rating, or null
     * @param pick       the window's served forecast-wide pick (BEST BET or ALSO GOOD), or null
     * @param regions    the window's regions
     */
    public record Window(String id, LocalDate date, TargetType targetType,
            LocalDateTime eventTime, DisplayVerdict verdict, Integer bestRating,
            BriefingWindow.Pick pick, List<Region> regions) {

        /** Canonical constructor: takes an immutable copy of {@code regions}. */
        public Window {
            regions = regions == null ? List.of() : List.copyOf(regions);
        }
    }

    /**
     * One region within a window.
     *
     * @param name            the region name
     * @param displayVerdict  the region's served verdict
     * @param meanRating      the region's served mean rating, or null
     * @param verdictEligible whether the Plan tab would let this region carry a verdict
     *                        ({@code BriefingRegion.verdictEligible()})
     * @param slots           the region's slots
     */
    public record Region(String name, DisplayVerdict displayVerdict, Double meanRating,
            boolean verdictEligible, List<Slot> slots) {

        /** Canonical constructor: takes an immutable copy of {@code slots}. */
        public Region {
            slots = slots == null ? List.of() : List.copyOf(slots);
        }
    }

    /**
     * One location's slot in a window.
     *
     * @param locationId     the location id, or null on a slot cached before slots carried one
     * @param name           the location name
     * @param rating         the served rating, or null
     * @param verdict        the slot's served display verdict
     * @param headline       the served headline, or null
     * @param canopy         true when this is a wood, whose rating means the opposite of a sky one
     * @param tideState      the served tide state band at the event, or null for an inland place
     * @param tideAligned    the preference fact: the location's own wanted water is the water at
     *                       the event
     * @param tideFitPhrase  the served tide-fit sentence, or null
     */
    public record Slot(Long locationId, String name, Integer rating, DisplayVerdict verdict,
            String headline, boolean canopy, String tideState, boolean tideAligned,
            String tideFitPhrase) {

        /**
         * Whether this is a coastal slot: it carries a served tide state.
         *
         * @return true when {@code tideState} is present
         */
        public boolean coastal() {
            return tideState != null;
        }
    }

    /**
     * A served hot topic.
     *
     * @param type    the topic type
     * @param label   the topic label
     * @param detail  the topic detail, or null
     * @param date    the topic's date
     * @param regions the regions the topic names; empty when it is not region-specific
     * @param safetyNote the served warning every surface raising this topic must show (the solar
     *                   eclipse's lens-filter warning), or null
     */
    public record Topic(String type, String label, String detail, LocalDate date,
            List<String> regions, String safetyNote) {

        /**
         * A topic with no safety note.
         *
         * @param type    the topic type
         * @param label   the topic label
         * @param detail  the topic detail, or null
         * @param date    the topic's date
         * @param regions the regions the topic names
         */
        public Topic(String type, String label, String detail, LocalDate date,
                List<String> regions) {
            this(type, label, detail, date, regions, null);
        }

        /** Canonical constructor: takes an immutable copy of {@code regions}. */
        public Topic {
            regions = regions == null ? List.of() : List.copyOf(regions);
        }
    }

    /**
     * A served almanac ("Coming up") entry.
     *
     * @param type      the entry type
     * @param title     the entry title
     * @param startDate the first date
     * @param endDate   the last date
     * @param detail    the entry detail, or null
     * @param safetyNote the warning Ask attaches to this entry (the solar eclipse's lens-filter
     *                   note), or null; the feed's own entries carry none
     */
    public record ComingUp(String type, String title, LocalDate startDate, LocalDate endDate,
            String detail, String safetyNote) {
    }

    /**
     * A pick-eligible slot with the window and region it belongs to.
     *
     * @param window the window
     * @param region the region
     * @param slot   the slot
     */
    public record Candidate(Window window, Region region, Slot slot) {
    }

    /**
     * The one pick-eligibility rule (plan §2.2, D-3): a non-null location id, not a wood, a served
     * rating of at least {@value #MIN_PICK_RATING}, and a region the Plan tab would let carry a
     * verdict. There is no rating that exempts an ineligible region and no fallback that admits a
     * canopy slot — a handful of hand-run 4★ ratings must never crown a region the Plan tab
     * refuses to crown.
     *
     * @param region the slot's region
     * @param slot   the slot
     * @return true when the slot may be offered as a pick
     */
    public static boolean isPickEligible(Region region, Slot slot) {
        return slot.locationId() != null
                && !slot.canopy()
                && slot.rating() != null
                && slot.rating() >= MIN_PICK_RATING
                && region.verdictEligible();
    }

    /**
     * Every pick-eligible slot across the window set, in window order then region order then slot
     * order.
     *
     * @return the candidates; never null
     */
    public List<Candidate> candidates() {
        return windows.stream().flatMap(w -> candidates(w).stream()).toList();
    }

    /**
     * The pick-eligible slots of one window.
     *
     * @param window a window of this snapshot
     * @return its candidates; never null
     */
    public List<Candidate> candidates(Window window) {
        return window.regions().stream()
                .flatMap(r -> r.slots().stream()
                        .filter(s -> isPickEligible(r, s))
                        .map(s -> new Candidate(window, r, s)))
                .toList();
    }

    /**
     * The pick-eligible slots of one window within a question's scope. The one definition of what
     * is reachable under a scope: {@code rank_spots} draws its pool from it and the Ready
     * {@code BEST_*} anchor asks it whether there is anything to lead with, so the two cannot
     * disagree.
     *
     * @param window a window of this snapshot
     * @param scope  the region names the question is about, matched case-insensitively; null or
     *               empty means every region
     * @return its in-scope candidates; never null
     */
    public List<Candidate> candidates(Window window, Collection<String> scope) {
        return candidates(window).stream()
                .filter(c -> regionInScope(scope, c.region().name()))
                .toList();
    }

    /**
     * Whether a region is within a question's scope: the one comparison every scope check uses.
     *
     * @param scope      the region names, matched case-insensitively; null or empty means every
     *                   region
     * @param regionName the region to test
     * @return true when the scope is open or names the region
     */
    public static boolean regionInScope(Collection<String> scope, String regionName) {
        return scope == null || scope.isEmpty()
                || scope.stream().anyMatch(s -> s != null && s.equalsIgnoreCase(regionName));
    }

    /**
     * Finds a window by id.
     *
     * @param windowId the id
     * @return the window, or empty when it is not in the window set
     */
    public Optional<Window> window(String windowId) {
        return windows.stream().filter(w -> w.id().equals(windowId)).findFirst();
    }

    /**
     * Finds a location's pick-eligible slot in a window — the check the validator makes before it
     * lets a pick through.
     *
     * @param windowId   the window id
     * @param locationId the location id
     * @return the candidate, or empty when the window is not in the set or the slot is not
     *         pick-eligible
     */
    public Optional<Candidate> candidate(String windowId, long locationId) {
        return window(windowId).flatMap(w -> candidates(w).stream()
                .filter(c -> c.slot().locationId() == locationId)
                .findFirst());
    }
}
