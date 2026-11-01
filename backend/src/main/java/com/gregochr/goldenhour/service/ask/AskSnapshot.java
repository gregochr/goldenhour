package com.gregochr.goldenhour.service.ask;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.gregochr.goldenhour.entity.TargetType;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.CoverageReason;
import com.gregochr.goldenhour.model.DisplayVerdict;
import com.gregochr.goldenhour.model.HotTopic;
import com.gregochr.goldenhour.service.evaluation.RatingValidator;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

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
 * @param briefingStale true when the served briefing is the last-known-good one rather than a fresh
 *                    build ({@code DailyBriefingResponse.stale()}); Ready precompute does not
 *                    answer from one
 * @param nights      tonight's and tomorrow night's astro and aurora forecasts (ask-night plan §2.1);
 *                    {@link Nights#NONE} when there are none. User-less like the rest of the snapshot:
 *                    aurora figures are in it and are withheld by role where an asker is known
 * @param areas       the named areas ("Hadrian's Wall") look_up_place and rank_spots understand; empty when
 *                    there are none or they could not be read
 * @param locationAliases other names for a location, by location id, each list in the deterministic order of
 *                    {@link AskAreas#sortAliases}; empty when there are none
 */
public record AskSnapshot(LocalDateTime generatedAt, String runLabel, LocalDate today,
        List<Window> windows, List<Topic> hotTopics, List<ComingUp> comingUp,
        boolean briefingStale, Nights nights, List<Area> areas, Map<Long, List<String>> locationAliases) {

    /** The least served rating a slot may carry and still be offered as a pick. */
    public static final int MIN_PICK_RATING = 3;

    /**
     * The least astro rating a night spot may carry and still be offered as a pick. Four, not three:
     * the astro template's BASE score is 3★ ("no factor moved it"), so a 3 is not a good night the way a
     * Claude-judged 3★ sunrise is; 4 means cloud, visibility and moon all left it better than the base.
     */
    public static final int MIN_ASTRO_PICK_RATING = 4;

    /** The least aurora rating a night spot may carry and still be offered as a pick (Claude's own judgement). */
    public static final int MIN_AURORA_PICK_RATING = 3;

    /** The furthest ahead the {@link #timeline} (and so {@code get_coming_up}) looks, in days. */
    public static final int MAX_COMING_UP_DAYS = 90;

    /** Canonical constructor: takes immutable copies; a null list reads as empty, null nights as none. */
    public AskSnapshot {
        windows = windows == null ? List.of() : List.copyOf(windows);
        hotTopics = hotTopics == null ? List.of() : List.copyOf(hotTopics);
        comingUp = comingUp == null ? List.of() : List.copyOf(comingUp);
        nights = nights == null ? Nights.NONE : nights;
        areas = areas == null ? List.of() : List.copyOf(areas);
        locationAliases = locationAliases == null ? Map.of() : copyAliases(locationAliases);
    }

    /**
     * A snapshot with no named areas and no location aliases.
     *
     * @param generatedAt   when the briefing was built (UTC), or null
     * @param runLabel      {@code HH:mm} Europe/London of {@code generatedAt}, or null
     * @param today         today on the UK civil calendar
     * @param windows       the window set
     * @param hotTopics     the served hot topics
     * @param comingUp      the almanac entries
     * @param briefingStale whether the served briefing is the last-known-good one
     * @param nights        the night forecasts
     */
    public AskSnapshot(LocalDateTime generatedAt, String runLabel, LocalDate today,
            List<Window> windows, List<Topic> hotTopics, List<ComingUp> comingUp,
            boolean briefingStale, Nights nights) {
        this(generatedAt, runLabel, today, windows, hotTopics, comingUp, briefingStale, nights, List.of(),
                Map.of());
    }

    /**
     * This snapshot with these named areas and location aliases.
     *
     * @param newAreas   the named areas
     * @param newAliases other names by location id
     * @return the snapshot, otherwise unchanged
     */
    public AskSnapshot withNames(List<Area> newAreas, Map<Long, List<String>> newAliases) {
        return new AskSnapshot(generatedAt, runLabel, today, windows, hotTopics, comingUp, briefingStale, nights,
                newAreas, newAliases);
    }

    private static Map<Long, List<String>> copyAliases(Map<Long, List<String>> aliases) {
        Map<Long, List<String>> out = new java.util.LinkedHashMap<>();
        aliases.forEach((id, names) -> out.put(id, names == null ? List.of() : List.copyOf(names)));
        return java.util.Collections.unmodifiableMap(out);
    }

    /**
     * A named area: a landmark or district such as "Hadrian's Wall" that groups places.
     *
     * @param name        the area's name
     * @param aliases     other names for it, in the deterministic order of {@link AskAreas#sortAliases}
     * @param locationIds the member locations' ids
     */
    public record Area(String name, List<String> aliases, Set<Long> locationIds) {

        /** Canonical constructor: takes immutable copies; null reads as empty. */
        public Area {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            locationIds = locationIds == null ? Set.of() : Set.copyOf(locationIds);
        }
    }

    /**
     * A snapshot with no night forecast.
     *
     * @param generatedAt   when the briefing was built (UTC), or null
     * @param runLabel      {@code HH:mm} Europe/London of {@code generatedAt}, or null
     * @param today         today on the UK civil calendar
     * @param windows       the window set
     * @param hotTopics     the served hot topics
     * @param comingUp      the almanac entries
     * @param briefingStale whether the served briefing is the last-known-good one
     */
    public AskSnapshot(LocalDateTime generatedAt, String runLabel, LocalDate today,
            List<Window> windows, List<Topic> hotTopics, List<ComingUp> comingUp,
            boolean briefingStale) {
        this(generatedAt, runLabel, today, windows, hotTopics, comingUp, briefingStale, Nights.NONE);
    }

    /**
     * A snapshot of a fresh (not last-known-good) briefing.
     *
     * @param generatedAt when the briefing was built (UTC), or null
     * @param runLabel    {@code HH:mm} Europe/London of {@code generatedAt}, or null
     * @param today       today on the UK civil calendar
     * @param windows     the window set
     * @param hotTopics   the served hot topics
     * @param comingUp    the almanac entries
     */
    public AskSnapshot(LocalDateTime generatedAt, String runLabel, LocalDate today,
            List<Window> windows, List<Topic> hotTopics, List<ComingUp> comingUp) {
        this(generatedAt, runLabel, today, windows, hotTopics, comingUp, false, Nights.NONE);
    }

    /**
     * One window in the window set.
     *
     * @param id         the window id ({@link AskWindowId})
     * @param date       the date of the event
     * @param targetType SUNRISE or SUNSET
     * @param eventTime  the window's served event time (UTC), or null
     * @param verdict    the window's served verdict
     * @param bestRating the window's served best rating with any neighbour outlier left out (the very
     *                   figure when none is marked), or null
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
     *                        ({@code BriefingRegion.verdictEligible()}); a VERDICT question, read by
     *                        {@link Spread#of}
     * @param pickEligible    whether the region may be offered as a pick, the same claim as BEST BET
     *                        ({@code BriefingRegion.pickEligible()}): a sufficient sample only, a
     *                        forced-evaluation exemption does not count (2026-10-22); read by
     *                        {@code meetsPickRules} alone
     * @param slots           the region's slots
     * @param coverageReason  why the honesty filter blanked the region (the served
     *                        {@code BriefingRegion.coverageReason}), or null when it did not or the
     *                        payload predates the field; only {@link CoverageReason#STOOD_DOWN} is a
     *                        real weather stand-down
     */
    public record Region(String name, DisplayVerdict displayVerdict, Double meanRating,
            boolean verdictEligible, boolean pickEligible, List<Slot> slots,
            CoverageReason coverageReason) {

        /** Canonical constructor: takes an immutable copy of {@code slots}. */
        public Region {
            slots = slots == null ? List.of() : List.copyOf(slots);
        }

        /**
         * A region the honesty filter did not blank (or whose reason is not known).
         *
         * @param name            the region name
         * @param displayVerdict  the region's served verdict
         * @param meanRating      the region's served mean rating, or null
         * @param eligible        whether the region is both verdict- and pick-eligible (the two agree
         *                        for every region but a forced-evaluation one)
         * @param slots           the region's slots
         */
        public Region(String name, DisplayVerdict displayVerdict, Double meanRating,
                boolean eligible, List<Slot> slots) {
            this(name, displayVerdict, meanRating, eligible, eligible, slots, null);
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
     * @param neighbourOutlier true when the neighbour guard marked this slot as rated far above the
     *                       places beside it ({@code BriefingSlot.neighbourOutlier}): its star stays,
     *                       but it is never offered as a pick
     * @param summary        the served evaluation's prose reason for the sky, or null (cleared on retraction)
     * @param skyRating      the sky's own component score, before the tide is combined in, or null
     * @param fierySky       the served Fiery Sky potential out of 100, or null
     * @param goldenHour     the served Golden Hour potential out of 100, or null
     * @param nearestTide    the served nearest high or low water and its offset from the light
     *                       ({@code LocationTideFact.nearestSolarOffsetPhrase}), or null; it is of
     *                       either kind, so it never says when the tide would suit
     */
    public record Slot(Long locationId, String name, Integer rating, DisplayVerdict verdict,
            String headline, boolean canopy, String tideState, boolean tideAligned,
            String tideFitPhrase, boolean neighbourOutlier, String summary, Integer skyRating,
            Integer fierySky, Integer goldenHour, String nearestTide) {

        /**
         * A slot with no nearest-tide phrase.
         *
         * @param locationId       the location id, or null
         * @param name             the location name
         * @param rating           the served rating, or null
         * @param verdict          the slot's served display verdict
         * @param headline         the served headline, or null
         * @param canopy           true when this is a wood
         * @param tideState        the served tide state band, or null for an inland place
         * @param tideAligned      the preference fact
         * @param tideFitPhrase    the served tide-fit sentence, or null
         * @param neighbourOutlier true when the neighbour guard marked this slot
         * @param summary          the served prose reason, or null
         * @param skyRating        the sky's own component score, or null
         * @param fierySky         the served Fiery Sky potential, or null
         * @param goldenHour       the served Golden Hour potential, or null
         */
        public Slot(Long locationId, String name, Integer rating, DisplayVerdict verdict,
                String headline, boolean canopy, String tideState, boolean tideAligned,
                String tideFitPhrase, boolean neighbourOutlier, String summary, Integer skyRating,
                Integer fierySky, Integer goldenHour) {
            this(locationId, name, rating, verdict, headline, canopy, tideState, tideAligned,
                    tideFitPhrase, neighbourOutlier, summary, skyRating, fierySky, goldenHour, null);
        }

        /**
         * A slot with no evaluation detail.
         *
         * @param locationId       the location id, or null
         * @param name             the location name
         * @param rating           the served rating, or null
         * @param verdict          the slot's served display verdict
         * @param headline         the served headline, or null
         * @param canopy           true when this is a wood
         * @param tideState        the served tide state band, or null for an inland place
         * @param tideAligned      the preference fact
         * @param tideFitPhrase    the served tide-fit sentence, or null
         * @param neighbourOutlier true when the neighbour guard marked this slot
         */
        public Slot(Long locationId, String name, Integer rating, DisplayVerdict verdict,
                String headline, boolean canopy, String tideState, boolean tideAligned,
                String tideFitPhrase, boolean neighbourOutlier) {
            this(locationId, name, rating, verdict, headline, canopy, tideState, tideAligned,
                    tideFitPhrase, neighbourOutlier, null, null, null, null);
        }

        /**
         * A slot the neighbour guard has not marked.
         *
         * @param locationId    the location id, or null
         * @param name          the location name
         * @param rating        the served rating, or null
         * @param verdict       the slot's served display verdict
         * @param headline      the served headline, or null
         * @param canopy        true when this is a wood
         * @param tideState     the served tide state band, or null for an inland place
         * @param tideAligned   the preference fact
         * @param tideFitPhrase the served tide-fit sentence, or null
         */
        public Slot(Long locationId, String name, Integer rating, DisplayVerdict verdict,
                String headline, boolean canopy, String tideState, boolean tideAligned,
                String tideFitPhrase) {
            this(locationId, name, rating, verdict, headline, canopy, tideState, tideAligned,
                    tideFitPhrase, false);
        }

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
     * @param eventType  the served solar anchor ({@code SUNRISE}, {@code SUNSET}, {@code NIGHT}),
     *                   or null when the topic has none; a {@code NIGHT} topic dated D also
     *                   covers D+1's morning, exactly as {@code PlanWindowProjector.keysFor}
     *                   buckets it
     */
    public record Topic(String type, String label, String detail, LocalDate date,
            List<String> regions, String safetyNote, String eventType) {

        /**
         * This topic standing for itself and a later topic of the same kind and date: its own
         * fields, the union of both region lists (empty, meaning region-less, if either is), and
         * the first non-null safety note.
         *
         * @param later the later topic of the same kind and date
         * @return the folded topic
         */
        Topic foldedWith(Topic later) {
            List<String> merged;
            if (regions.isEmpty() || later.regions().isEmpty()) {
                merged = List.of();
            } else {
                Set<String> union = new LinkedHashSet<>(regions);
                union.addAll(later.regions());
                merged = List.copyOf(union);
            }
            return new Topic(type, label, detail, date, merged,
                    safetyNote != null ? safetyNote : later.safetyNote(), eventType);
        }

        /**
         * A topic with no safety note and no solar anchor.
         *
         * @param type    the topic type
         * @param label   the topic label
         * @param detail  the topic detail, or null
         * @param date    the topic's date
         * @param regions the regions the topic names
         */
        public Topic(String type, String label, String detail, LocalDate date,
                List<String> regions) {
            this(type, label, detail, date, regions, null, null);
        }

        /**
         * A topic with no solar anchor.
         *
         * @param type       the topic type
         * @param label      the topic label
         * @param detail     the topic detail, or null
         * @param date       the topic's date
         * @param regions    the regions the topic names
         * @param safetyNote the served warning, or null
         */
        public Topic(String type, String label, String detail, LocalDate date,
                List<String> regions, String safetyNote) {
            this(type, label, detail, date, regions, safetyNote, null);
        }

        /**
         * The last civil date this topic's window reaches: the day after its date for a
         * {@code NIGHT} topic (its morning half), else its own date. Null for an undated topic.
         *
         * <p>The aurora strategy dates its alert topic by the poller's night, which before dawn is
         * yesterday's — a date test on {@code date} alone would then leave out a topic whose
         * remaining half is this morning's sunrise, while the Plan card shows its badge. The same
         * rule as {@link HotTopic#coveredDates()}, over the served anchor this record carries.
         *
         * @return the last date covered, or null
         */
        public LocalDate lastDate() {
            if (date == null) {
                return null;
            }
            return HotTopic.EVENT_NIGHT.equals(eventType) ? date.plusDays(1) : date;
        }

        /**
         * Whether any date this topic covers falls inside {@code [from, to]}.
         *
         * @param from the first date, inclusive
         * @param to   the last date, inclusive
         * @return true when the topic's span and the range overlap; false for an undated topic
         */
        public boolean coversAnyOf(LocalDate from, LocalDate to) {
            return date != null && !lastDate().isBefore(from) && !date.isAfter(to);
        }

        /** Canonical constructor: takes an immutable copy of {@code regions}. */
        public Topic {
            regions = regions == null ? List.of() : List.copyOf(regions);
        }

        /**
         * Whether this topic is within a question's scope: a topic naming regions is in scope when
         * one of them is, and a topic naming none always is. The one definition: {@code
         * get_hot_topics} filters with it and the Ready serve-time freshness check asks it.
         *
         * @param scope the question's scope
         * @return true when the topic is in scope
         */
        public boolean inScope(AskScope scope) {
            return scope.isEverywhere() || regions.isEmpty() || regions.stream().anyMatch(scope::contains);
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
     * @param coincidentTypes the types of the topics a merged entry also holds (its coincidence
     *                   lines' types), empty when unmerged — so a live hot topic of a merged-in
     *                   type is not listed a second time beside the host
     */
    public record ComingUp(String type, String title, LocalDate startDate, LocalDate endDate,
            String detail, String safetyNote, List<String> coincidentTypes) {

        /**
         * Normalises {@code coincidentTypes} so a consumer never has to null-check it.
         */
        public ComingUp {
            coincidentTypes = coincidentTypes == null ? List.of() : List.copyOf(coincidentTypes);
        }

        /**
         * An unmerged entry — one that holds no other topic.
         *
         * @param type       the entry type
         * @param title      the entry title
         * @param startDate  the first date
         * @param endDate    the last date
         * @param detail     the entry detail, or null
         * @param safetyNote the attached warning, or null
         */
        public ComingUp(String type, String title, LocalDate startDate, LocalDate endDate,
                String detail, String safetyNote) {
            this(type, title, startDate, endDate, detail, safetyNote, List.of());
        }
    }

    /**
     * A window's rating distribution over a scope, the figures {@code list_windows} serves and the
     * answers carry (ask-window-context plan §2.1): counts, never a fitted curve, so the model can say
     * "of 180 rated places, 2 are 4★" from a number a tool returned instead of doing the arithmetic.
     *
     * <p><b>Population.</b> Every in-scope region of the window is one of four kinds, read off what the
     * honesty filter served: <em>counted</em> (has slots and {@code verdictEligible}: its slots enter
     * the bands and the pockets), <em>thin</em> (has slots but the Plan tab refuses it a verdict: its
     * slots are not in the bands, so a 4★ {@code rank_spots} would never return is never a
     * {@code gameOn}), <em>stood down</em> (no slots and the filter's reason was
     * {@link CoverageReason#STOOD_DOWN}) and <em>not scored</em> (no slots for any other reason,
     * including the {@link CoverageReason#UNKNOWN} the filter words as a stand-down too, and a reason
     * that is not known). Within a counted region a slot is out when it is a wood, or held back by the
     * neighbour guard (counted in {@code heldBack}); a slot without a rating on Claude's 1-5 scale is
     * {@code unrated} (and, with the served verdict {@code STAND_DOWN}, also counted in the
     * informational {@code stoodDown}). The rating is the combined served one, the star every other
     * Ask figure is on.
     *
     * <p>A record of ints, strings and lists, so {@code equals} is the freshness comparison and no
     * tolerance exists to tune. Serialised NON_NULL; the optional tallies are omitted when zero.
     *
     * @param over       the population's name for the prose: {@code every region}, or the scope's names
     * @param rated      how many places in the counted regions carry a rating
     * @param bands      the rated places at 1, 2, 3, 4 and 5★ (five integers)
     * @param gameOn     rated 4★ or better
     * @param gameOnPct  {@code gameOn} as a rounded percentage of {@code rated}; 0 when none is rated
     * @param go         rated 5★
     * @param below4     rated 3★ or lower
     * @param stars1to2  rated 1★ or 2★
     * @param unrated    places in the counted regions with no rating; null when none
     * @param stoodDown  of those, places the briefing's own weather verdict stood down; null when none
     * @param heldBack   places the neighbour guard withholds; null when none
     * @param regions    the in-scope regions by kind
     * @param pockets    the counted regions holding a game-on place, most game-on places first, at most
     *                   {@value #MAX_POCKETS}
     * @param morePockets how many pockets were cut; null when none was
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Spread(String over, int rated, List<Integer> bands, int gameOn, int gameOnPct, int go,
            int below4, int stars1to2, Integer unrated, Integer stoodDown, Integer heldBack,
            Regions regions, List<Pocket> pockets, Integer morePockets) {

        /** The name of the population when the scope is every region. */
        public static final String EVERY_REGION = "every region";

        /** The most pockets a spread lists. */
        public static final int MAX_POCKETS = 4;

        /** The lowest star the owner's scale calls "game on". */
        public static final int GAME_ON_FROM = 4;

        private static final int STARS = 5;

        /** Canonical constructor: immutable copies; a null list reads as empty. */
        public Spread {
            bands = bands == null ? List.of() : List.copyOf(bands);
            pockets = pockets == null ? List.of() : List.copyOf(pockets);
        }

        /**
         * The in-scope regions of a window by kind. The tallies other than {@code counted} are omitted
         * when zero.
         *
         * @param counted   regions whose slots are in the bands
         * @param thin      regions with slots the Plan tab refuses a verdict
         * @param stoodDown regions the briefing stood down whole by weather
         * @param notScored regions with no slots for any other reason
         */
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Regions(int counted, Integer thin, Integer stoodDown, Integer notScored) {
        }

        /**
         * A region holding at least one game-on place.
         *
         * @param region the region name
         * @param gameOn how many rated places in it are 4★ or better
         * @param mean   the region's mean rating over the spread's population, to one decimal
         */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Pocket(String region, int gameOn, double mean) {
        }

        /**
         * Folds a window's regions that the scope contains into a spread.
         *
         * @param window the window
         * @param scope  the regions the question is about
         * @return the spread; its {@code rated} is 0 and its bands empty when no counted region is rated
         */
        public static Spread of(Window window, AskScope scope) {
            int[] bands = new int[STARS];
            int unrated = 0;
            int slotsStoodDown = 0;
            int heldBack = 0;
            int counted = 0;
            int thin = 0;
            int regionsStoodDown = 0;
            int notScored = 0;
            List<Pocket> pockets = new ArrayList<>();
            for (Region region : window.regions()) {
                if (!scope.contains(region.name())) {
                    continue;
                }
                if (region.slots().isEmpty()) {
                    if (region.coverageReason() == CoverageReason.STOOD_DOWN) {
                        regionsStoodDown++;
                    } else {
                        notScored++;
                    }
                    continue;
                }
                if (!region.verdictEligible()) {
                    thin++;
                    continue;
                }
                counted++;
                int regionRated = 0;
                int regionGameOn = 0;
                int regionSum = 0;
                for (Slot slot : region.slots()) {
                    if (slot.canopy()) {
                        continue;
                    }
                    if (slot.neighbourOutlier() && slotMeetsPickRules(slot)) {
                        // The spread is a VERDICT question: the region is verdict-eligible here
                        // (checked above), so the mark alone holds the slot back, as before the
                        // pick/verdict split of 2026-10-22.
                        heldBack++;
                    } else if (RatingValidator.isInRange(slot.rating())) {
                        bands[slot.rating() - 1]++;
                        regionRated++;
                        regionSum += slot.rating();
                        regionGameOn += slot.rating() >= GAME_ON_FROM ? 1 : 0;
                    } else {
                        unrated++;
                        slotsStoodDown += slot.verdict() == DisplayVerdict.STAND_DOWN ? 1 : 0;
                    }
                }
                if (regionGameOn > 0) {
                    pockets.add(new Pocket(region.name(), regionGameOn,
                            Math.round(regionSum * 10.0 / regionRated) / 10.0));
                }
            }
            pockets.sort(Comparator.comparingInt(Pocket::gameOn).reversed()
                    .thenComparing(Comparator.comparingDouble(Pocket::mean).reversed())
                    .thenComparing(Pocket::region));
            int rated = 0;
            for (int n : bands) {
                rated += n;
            }
            int gameOn = bands[GAME_ON_FROM - 1] + bands[STARS - 1];
            List<Integer> bandList = new ArrayList<>(STARS);
            for (int n : bands) {
                bandList.add(n);
            }
            return new Spread(overName(scope), rated, bandList, gameOn,
                    rated == 0 ? 0 : (int) Math.round(100.0 * gameOn / rated), bands[STARS - 1],
                    rated - gameOn, bands[0] + bands[1], orNull(unrated), orNull(slotsStoodDown),
                    orNull(heldBack),
                    new Regions(counted, orNull(thin), orNull(regionsStoodDown), orNull(notScored)),
                    pockets.size() > MAX_POCKETS ? pockets.subList(0, MAX_POCKETS) : pockets,
                    pockets.size() > MAX_POCKETS ? pockets.size() - MAX_POCKETS : null);
        }

        private static Integer orNull(int count) {
            return count == 0 ? null : count;
        }

        /** {@code every region}, or the scope's names: {@code A}, {@code A and B}, {@code A, B and C}. */
        private static String overName(AskScope scope) {
            if (scope.isEverywhere() || scope.names().isEmpty()) {
                return EVERY_REGION;
            }
            List<String> names = List.copyOf(scope.names());
            if (names.size() == 1) {
                return names.getFirst();
            }
            return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.getLast();
        }
    }

    /**
     * The spread of one solar window over a scope.
     *
     * @param windowId a solar window id
     * @param scope    the regions the question is about
     * @return the spread, or empty when the window is not in the window set (a night has none)
     */
    public Optional<Spread> spread(String windowId, AskScope scope) {
        return window(windowId).map(w -> Spread.of(w, scope));
    }

    /**
     * The game-on candidates of the given windows: pick-eligible slots in scope rated
     * {@value Spread#GAME_ON_FROM}★ or better that {@code admits} accepts. The set test behind the
     * honest "nothing worth going out for" answer (ask-window-context plan §2.3): a question whose
     * covered windows hold none may be answered with no pick. A window id the snapshot does not hold
     * contributes nothing.
     *
     * @param windowIds the windows the question covers
     * @param scope     the regions the question is about
     * @param admits    the question's own slot predicate ({@code ReadyRelevance.admitsSlot}); every
     *                  slot for a question with no extra requirement
     * @return the candidates, in window order; never null
     */
    public List<Candidate> gameOnCandidates(Collection<String> windowIds, AskScope scope,
            Predicate<Slot> admits) {
        return windows.stream()
                .filter(w -> windowIds.contains(w.id()))
                .flatMap(w -> candidates(w, scope).stream())
                .filter(c -> c.slot().rating() >= Spread.GAME_ON_FROM && admits.test(c.slot()))
                .toList();
    }

    /**
     * Whether any of the given windows holds a game-on candidate the question admits: the ONE predicate
     * every site that decides whether a no-pick answer stands consults (the validator's anchor rule,
     * {@code ReadyRelevance.violation} and the Ready freshness check). Never inferred from the BEST BET
     * anchor lookup, which is empty for other reasons too.
     *
     * @param windowIds the windows the question covers
     * @param scope     the regions the question is about
     * @param admits    the question's own slot predicate
     * @return true when at least one game-on candidate exists
     */
    public boolean hasGameOn(Collection<String> windowIds, AskScope scope, Predicate<Slot> admits) {
        return !gameOnCandidates(windowIds, scope, admits).isEmpty();
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
     * The night forecasts of the snapshot (ask-night plan §2.1).
     *
     * @param tonight the date of the night in progress or next to begin, per
     *                {@code AuroraForecastRunService.currentNightDate()} — never the UK civil date, which
     *                at 00:30 would name the unscored next night and drop the one in progress; null when
     *                the snapshot carries no nights
     * @param nights  the nights, each kind separately: for {@code tonight} and {@code tonight + 1}, an
     *                ASTRO night when astro rows exist for it and an AURORA night when non-simulated
     *                aurora results do
     * @param aurora  the aurora alert state for tonight, or null when it must not be reported (an
     *                admin simulation is active, or the snapshot carries no nights)
     */
    public record Nights(LocalDate tonight, List<Night> nights, AuroraState aurora) {

        /** No night forecast at all. */
        public static final Nights NONE = new Nights(null, List.of(), null);

        /** Canonical constructor: an immutable copy; a null list reads as empty. */
        public Nights {
            nights = nights == null ? List.of() : List.copyOf(nights);
        }

        /**
         * Finds a night by its window id.
         *
         * @param windowId {@code yyyy-MM-dd_astro} or {@code yyyy-MM-dd_aurora}
         * @return the night, or empty when it is not in the set
         */
        public Optional<Night> night(String windowId) {
            return nights.stream().filter(n -> n.id().equals(windowId)).findFirst();
        }

        /**
         * Finds a night by date and kind.
         *
         * @param date the date whose dusk opens the night
         * @param kind ASTRO or AURORA
         * @return the night, or empty when there is none
         */
        public Optional<Night> night(LocalDate date, AskWindowKind kind) {
            return nights.stream().filter(n -> n.date().equals(date) && n.kind() == kind).findFirst();
        }

        /**
         * Tonight's ASTRO night: the night of the poller's current date, when astro conditions were
         * scored for it. The one night a user-less Ready answer is written about.
         *
         * @return the night, or empty when the snapshot carries no nights or no astro row for tonight
         */
        public Optional<Night> tonightAstro() {
            return tonight == null ? Optional.empty() : night(tonight, AskWindowKind.ASTRO);
        }

        /**
         * The distinct dates the snapshot holds a night for, earliest first.
         *
         * @return the dates
         */
        public List<LocalDate> dates() {
            return List.copyOf(new TreeSet<>(nights.stream().map(Night::date).toList()));
        }
    }

    /**
     * One kind of night: the astro conditions or the aurora results scored for the night that opens at
     * dusk on {@code date}.
     *
     * @param id         the window id ({@link AskWindowId}), {@code yyyy-MM-dd_astro} or {@code _aurora}
     * @param date       the date whose dusk opens the night
     * @param kind       ASTRO or AURORA
     * @param start      the night's nautical dusk (UTC), or null when no window is known: an aurora night
     *                   carries its date's astro window, and the aurora entity holds none of its own
     * @param end        the night's nautical dawn (UTC), or null
     * @param generation the instant of the run these spots came from: for ASTRO the night's latest
     *                   generation ({@code max(run_timestamp)}, one instant per scoring run), the identity
     *                   a night answer is versioned on; for AURORA the latest result's run instant
     * @param spots      the places the night was scored for: enabled, with a Bortle class and a region,
     *                   never canopy-only, and (for ASTRO) of the latest generation only
     */
    public record Night(String id, LocalDate date, AskWindowKind kind, LocalDateTime start,
            LocalDateTime end, Instant generation, List<NightSpot> spots) {

        /** Canonical constructor: an immutable copy; a null list reads as empty. */
        public Night {
            spots = spots == null ? List.of() : List.copyOf(spots);
        }
    }

    /**
     * One place's night forecast. The astro and aurora figures are each null when that kind has no row
     * for the place and night; an AURORA night's spot carries its astro figures beside the aurora ones
     * when the same place has an astro row for the date.
     *
     * @param locationId            the location id
     * @param name                  the location name
     * @param regionName            the region name; never null (a place with no region is not a spot)
     * @param lat                   the latitude, for {@code nearHome}
     * @param lon                   the longitude, for {@code nearHome}
     * @param canopy                true when the place's only subject is under canopy, whose star means
     *                              the opposite of a sky star; such a spot is never pick-eligible
     * @param bortleClass           the Bortle class (1 darkest); never null here
     * @param skyBrightnessSqm      the sky quality reading, or null
     * @param astroStars            the astro conditions rating (1-5), or null
     * @param astroSummary          the served astro summary, or null
     * @param cloudExplanation      the served cloud explanation: what "clear skies tonight" asks about
     * @param visibilityExplanation the served visibility explanation, or null
     * @param moonExplanation       the served moon explanation, or null
     * @param moonPhase             the lunar phase name at the night's midpoint, or null
     * @param moonIlluminationPct   the moon's illumination at the midpoint (0-100), or null
     * @param auroraStars           the aurora rating (1-5), or null
     * @param auroraSummary         the served aurora summary, or null
     * @param triaged               true when the aurora row is a weather-triage template, not a Claude score
     * @param triageReason          why the triage stood it down, or null
     * @param alertLevel            the alert level that drove the aurora scoring, or null
     * @param maxKp                 the highest Kp forecast for the night's dark window, or null
     */
    public record NightSpot(long locationId, String name, String regionName, double lat, double lon,
            boolean canopy, Integer bortleClass, Double skyBrightnessSqm, Integer astroStars,
            String astroSummary, String cloudExplanation, String visibilityExplanation,
            String moonExplanation, String moonPhase, Double moonIlluminationPct, Integer auroraStars,
            String auroraSummary, boolean triaged, String triageReason, String alertLevel, Double maxKp) {
    }

    /**
     * The aurora alert state for tonight, read from {@code AuroraStateCache}.
     *
     * @param level the alert level name; {@code QUIET} when no alert is active
     * @param kp    the Kp that drove the alert, or null when none is active or none was recorded
     */
    public record AuroraState(String level, Double kp) {
    }

    /**
     * A pick resolved against the live snapshot, solar or night: the one shape the validator, the
     * thread re-join and the Ready freshness re-check read, so a night pick is held to the same
     * three-step test as a solar one (offered, live, in scope) with the night eligibility rule.
     *
     * @param kind         the window's kind
     * @param windowId     the window id
     * @param date         the window's date
     * @param locationName the live location name
     * @param regionName   the live region name
     * @param rating       the live served rating: the slot's rating, or a night spot's eligible figure
     * @param verdict      the live display verdict name; null for a night pick
     */
    public record ResolvedPick(AskWindowKind kind, String windowId, LocalDate date, String locationName,
            String regionName, Integer rating, String verdict) {
    }

    /**
     * A night spot with the night it belongs to.
     *
     * @param night the night
     * @param spot  the spot
     */
    public record NightCandidate(Night night, NightSpot spot) {
    }

    /**
     * The night pick-eligibility rule (ask-night plan §2.4), the sibling of {@link #isPickEligible} and
     * the one place it is decided. Never a canopy-only place. On an ASTRO night the astro rating is at
     * least {@value #MIN_ASTRO_PICK_RATING}; on an AURORA night the asker may see aurora figures at all
     * ({@code canSeeAurora}, derived from the role and threaded here because a snapshot is user-less),
     * the row is a Claude score rather than a weather-triage template, and the aurora rating is at
     * least {@value #MIN_AURORA_PICK_RATING}. Ratings are held to Claude's 1-5 scale first.
     *
     * @param kind         the night's kind
     * @param spot         the spot
     * @param canSeeAurora whether the asker's role may see aurora figures
     * @return true when the spot may be offered as a pick on that night
     */
    public static boolean isNightPickEligible(AskWindowKind kind, NightSpot spot, boolean canSeeAurora) {
        if (spot.canopy() || spot.name() == null || spot.name().isBlank()) {
            return false;
        }
        return switch (kind) {
            case ASTRO -> RatingValidator.isInRange(spot.astroStars())
                    && spot.astroStars() >= MIN_ASTRO_PICK_RATING;
            case AURORA -> canSeeAurora && !spot.triaged() && RatingValidator.isInRange(spot.auroraStars())
                    && spot.auroraStars() >= MIN_AURORA_PICK_RATING;
            default -> false;
        };
    }

    /**
     * The figure a night spot is held to on a night of this kind: the astro or the aurora rating.
     *
     * @param kind ASTRO or AURORA
     * @param spot the spot
     * @return the rating, or null when the spot has none of that kind or the kind is not a night
     */
    public static Integer nightRating(AskWindowKind kind, NightSpot spot) {
        return switch (kind) {
            case ASTRO -> spot.astroStars();
            case AURORA -> spot.auroraStars();
            default -> null;
        };
    }

    /**
     * Finds a location's spot on a night whether or not it is pick-eligible. Only the thread's prompt
     * lines use this, to say what became of an earlier night pick; nothing is served from it.
     *
     * @param windowId   a night window id
     * @param locationId the location id
     * @return the spot with its night, or empty when the night is not in the set or the place is not on it
     */
    public Optional<NightCandidate> nightSlotAt(String windowId, long locationId) {
        return nights.night(windowId).flatMap(n -> n.spots().stream()
                .filter(s -> s.locationId() == locationId)
                .findFirst()
                .map(s -> new NightCandidate(n, s)));
    }

    /**
     * Finds a location's pick-eligible spot on a night: the night's counterpart of {@link #candidate}.
     *
     * @param windowId     a night window id
     * @param locationId   the location id
     * @param canSeeAurora whether the asker's role may see aurora figures
     * @return the spot with its night, or empty when the night is not in the set, the place is not on it,
     *         or it is not eligible
     */
    public Optional<NightCandidate> nightCandidate(String windowId, long locationId, boolean canSeeAurora) {
        return nightSlotAt(windowId, locationId)
                .filter(c -> isNightPickEligible(c.night().kind(), c.spot(), canSeeAurora));
    }

    /**
     * The pick-eligible spots of one night within a question's scope: the night counterpart of
     * {@link #candidates(Window, AskScope)}, and the one definition of what is reachable on a night under
     * a scope. {@code ReadyQuestion.NIGHT_SKY} offers on it, so the question is asked exactly when
     * {@code rank_night_spots} could return something for it.
     *
     * @param night        a night of this snapshot
     * @param scope        the regions the question is about
     * @param canSeeAurora whether the asker's role may see aurora figures (false for a Ready answer)
     * @return its in-scope, pick-eligible spots; never null
     */
    public List<NightCandidate> nightCandidates(Night night, AskScope scope, boolean canSeeAurora) {
        return night.spots().stream()
                .filter(s -> scope.contains(s.regionName()))
                .filter(s -> isNightPickEligible(night.kind(), s, canSeeAurora))
                .map(s -> new NightCandidate(night, s))
                .toList();
    }

    /**
     * Whether a window id names a window the snapshot holds: a solar window of the set, or a night.
     *
     * @param windowId the window id
     * @return true when the window is in the snapshot
     */
    public boolean hasWindow(String windowId) {
        return window(windowId).isPresent() || nights.night(windowId).isPresent();
    }

    /**
     * Resolves a pick against the live snapshot, solar or night, applying the matching eligibility
     * rule: the one lookup the validator, the thread re-join and the Ready freshness re-check make.
     *
     * @param windowId     the window id
     * @param locationId   the location id
     * @param canSeeAurora whether the asker's role may see aurora figures (only a night pick reads it)
     * @return the live pick, or empty when the window is not in the set or the slot is not eligible
     */
    public Optional<ResolvedPick> resolvePick(String windowId, long locationId, boolean canSeeAurora) {
        boolean night = AskWindowId.parse(windowId).map(p -> p.kind().isNight()).orElse(false);
        if (night) {
            return nightCandidate(windowId, locationId, canSeeAurora).map(c -> new ResolvedPick(
                    c.night().kind(), c.night().id(), c.night().date(), c.spot().name(),
                    c.spot().regionName(), nightRating(c.night().kind(), c.spot()), null));
        }
        return candidate(windowId, locationId).map(c -> new ResolvedPick(
                AskWindowKind.of(c.window().targetType()), c.window().id(), c.window().date(),
                c.slot().name(), c.region().name(), c.slot().rating(),
                c.slot().verdict() == null ? null : c.slot().verdict().name()));
    }

    /**
     * The one pick-eligibility rule (plan §2.2, D-3): a non-null location id and a name to put on
     * the card, not a wood, a served rating on Claude's 1-5 scale
     * ({@link RatingValidator#isInRange}, the bound the Plan projector uses) and at least
     * {@value #MIN_PICK_RATING}, and a region the Plan tab would let carry a verdict, and not a
     * neighbour outlier (a rating far above every nearby place, which the guard withholds from being
     * named as the best). There is no rating that exempts an ineligible region and no fallback that
     * admits a canopy slot — a handful of hand-run 4★ ratings must never crown a region the Plan tab
     * refuses to crown.
     *
     * @param region the slot's region
     * @param slot   the slot
     * @return true when the slot may be offered as a pick
     */
    public static boolean isPickEligible(Region region, Slot slot) {
        return meetsPickRules(region, slot) && !slot.neighbourOutlier();
    }

    /**
     * Whether the slot is a pick in every respect but one: the neighbour guard has marked it as rated
     * far above the places beside it. Only the thread's wording asks, to say why an earlier pick is no
     * longer offered ("held back") rather than that its rating fell, which it has not.
     *
     * @param region the slot's region
     * @param slot   the slot
     * @return true when {@link #isPickEligible} refuses the slot for the mark alone
     */
    public static boolean isHeldBack(Region region, Slot slot) {
        return slot.neighbourOutlier() && meetsPickRules(region, slot);
    }

    private static boolean meetsPickRules(Region region, Slot slot) {
        return slotMeetsPickRules(slot) && region.pickEligible();
    }

    /** The slot-level half of the pick rules, independent of which region sample test applies. */
    private static boolean slotMeetsPickRules(Slot slot) {
        return slot.locationId() != null
                && slot.name() != null && !slot.name().isBlank()
                && !slot.canopy()
                // On Claude's 1-5 scale (the Plan path's own bound), then at the floor: a malformed
                // 491 is refused by the Plan ranking and must not sort to the top here.
                && RatingValidator.isInRange(slot.rating())
                && slot.rating() >= MIN_PICK_RATING;
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
     * @param scope  the regions the question is about
     * @return its in-scope candidates; never null
     */
    public List<Candidate> candidates(Window window, AskScope scope) {
        return candidates(window).stream()
                .filter(c -> scope.contains(c.region().name()))
                .toList();
    }

    /**
     * The one definition of what {@code get_coming_up} can return, shared with the validator's
     * "was anything offered" test so the two cannot disagree: the almanac entries overlapping the
     * next {@code days} civil dates plus the in-scope hot topics dated inside them that the almanac
     * does not already list, soonest first. A hot topic is stood in the timeline as a one-day entry
     * titled with its label, carrying its served detail and safety note.
     *
     * <p>"Dated inside them" reads the dates a topic COVERS ({@link Topic#coversAnyOf}), not its
     * date alone: a {@code NIGHT} topic dated yesterday — the aurora alert for the night still
     * running before dawn — reaches this morning's sunrise, so it is listed. ⚠️ It stands on its
     * OWN date, yesterday, even so: {@code AskReadyFreshness.liveEvent} re-finds an event by the
     * live topic's {@code date}, and {@code get_hot_topics} reports the same topic on that date, so
     * moving the entry onto today would make an answer built from it read as no longer live on
     * the very next serve (a Codex review of #1056). The night is named by its dusk date everywhere.
     *
     * @param scope the regions the question is about
     * @param days  how many days ahead, from today
     * @return the timeline, soonest first, unlimited
     */
    public List<ComingUp> timeline(AskScope scope, int days) {
        LocalDate from = today;
        // N days is N civil dates from today: AlmanacService.getFeed ends at today + N - 1.
        LocalDate to = from.plusDays(days - 1L);
        List<ComingUp> entries = new ArrayList<>(comingUp.stream()
                .filter(e -> !e.endDate().isBefore(from) && !e.startDate().isAfter(to))
                .toList());
        List<ComingUp> almanac = List.copyOf(entries);
        onePerKindAndDay(hotTopics.stream()
                .filter(t -> t.coversAnyOf(from, to))
                .filter(t -> t.inScope(scope))
                .toList()).stream()
                .filter(t -> almanac.stream().noneMatch(e -> listedBy(e, t)))
                .map(t -> new ComingUp(t.type(), t.label(), t.date(), t.date(), t.detail(),
                        t.safetyNote()))
                .forEach(entries::add);
        entries.sort(Comparator.comparing(ComingUp::startDate)
                .thenComparing(ComingUp::title)
                .thenComparing(ComingUp::type)
                .thenComparing(ComingUp::endDate));
        return List.copyOf(entries);
    }

    /**
     * The topics with every repeat of a kind on one date folded into the first, order unchanged.
     *
     * <p>A detector may emit one topic per (date, event): valley mist and storm surge both carry a
     * sunrise and a sunset topic on a day that qualifies at both. That split is what puts each badge
     * on its own Plan window, but to Ask the two are one event, and listed twice they spend two of a
     * tool's few result slots, so a run of such days could crowd every other topic out of
     * {@code get_hot_topics} and {@code get_coming_up} (a Codex review of #1300). The kind is
     * {@link AskEventType#key}, the identity every Ask comparison of types uses.
     *
     * <p>The first topic of a kind and date stands for the day, but its {@code regions} become the
     * union of every folded topic's, so a sunset-only region is not dropped from what Ask is told
     * (the same review's second round). An empty list means region-less, true of everywhere, so it
     * wins the union; the first non-null safety note is kept. Call it after the scope filter, so
     * only in-scope topics are folded.
     *
     * @param topics the topics, in the order they will be offered
     * @return one topic per kind and date, in first-seen order
     */
    static List<Topic> onePerKindAndDay(List<Topic> topics) {
        Map<String, Topic> byKindAndDay = new LinkedHashMap<>();
        for (Topic t : topics) {
            byKindAndDay.merge(AskEventType.key(t.type()) + "|" + t.date(), t, Topic::foldedWith);
        }
        return List.copyOf(byKindAndDay.values());
    }

    /**
     * Whether an almanac entry already lists a live topic: the same type, or the type of a topic the
     * entry merged in ({@link AskEventType#same}, which reads the almanac's {@code lunar-eclipse} and
     * a hot topic's {@code LUNAR_ECLIPSE} as one), on a date inside its span.
     */
    private static boolean listedBy(ComingUp entry, Topic topic) {
        boolean sameType = AskEventType.same(entry.type(), topic.type())
                || entry.coincidentTypes().stream().anyMatch(t -> AskEventType.same(t, topic.type()));
        return sameType
                && !topic.date().isBefore(entry.startDate()) && !topic.date().isAfter(entry.endDate());
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

    /**
     * Finds a location's slot in a window whether or not it is pick-eligible. Only the thread's
     * prompt lines use this, to say what became of an earlier pick; nothing is ever served from it
     * (every served pick goes through {@link #candidate}).
     *
     * @param windowId   the window id
     * @param locationId the location id
     * @return the slot with its window and region, or empty when the window is not in the set or the
     *         location has no slot in it
     */
    public Optional<Candidate> slotAt(String windowId, long locationId) {
        return window(windowId).flatMap(w -> w.regions().stream()
                .flatMap(r -> r.slots().stream()
                        .filter(s -> s.locationId() != null && s.locationId() == locationId)
                        .map(s -> new Candidate(w, r, s)))
                .findFirst());
    }
}
