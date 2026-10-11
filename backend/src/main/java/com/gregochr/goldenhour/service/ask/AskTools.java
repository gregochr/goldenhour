package com.gregochr.goldenhour.service.ask;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.service.DriveTimeResolver;
import com.gregochr.goldenhour.util.DayLabels;
import com.gregochr.goldenhour.util.GeoUtils;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The read-only tools Claude may call to answer a question (plan §2.2), over one
 * {@link AskSnapshot}. One instance is one conversation: it counts the characters it has returned,
 * remembers exactly which (location, window) pairs and events it handed out so the validator can
 * hold an answer to them, and notes whether the asker's own drive times were used.
 *
 * <p>Every method returns an {@link AskToolResult}; a bad argument is an error result, never an
 * exception. There is no Claude call and no SDK type in this class — the engine (B2a) maps the
 * model's tool-use input onto the argument records and the result's {@code content} onto a
 * {@code tool_result} block.
 *
 * <p><b>What a tool may return.</b> Only pick-eligible slots ({@link AskSnapshot#isPickEligible}):
 * never a wood, never a slot under 3★, never a region the Plan tab would refuse a verdict. The
 * served tide state and the location's tide preference are separate fields and are never merged.
 * Nothing about home reaches a user-less conversation.
 *
 * <p>Not thread-safe; a conversation is single-threaded.
 */
public class AskTools {

    /** Total characters of successful tool output one conversation may receive. */
    public static final int RESULT_CHAR_CAP = 6_000;

    /** The most spots {@code rank_spots} returns. */
    public static final int MAX_SPOTS = 8;

    /** The most topics {@code get_hot_topics} and entries {@code get_coming_up} return. */
    public static final int MAX_EVENTS = 10;

    /** The default for any {@code limit} the model leaves out. */
    public static final int DEFAULT_LIMIT = 5;

    /** The longest headline returned for a spot. */
    static final int HEADLINE_CAP = 120;

    /** The longest detail returned for a topic or an entry. */
    static final int DETAIL_CAP = 200;

    /** The note {@code rank_spots} gives when nothing is pick-eligible. */
    static final String NOTHING_ELIGIBLE_NOTE = "Nothing rated 3★ or better in scope.";

    /** The note {@code rank_spots} gives when the filters leave nothing. */
    static final String NOTHING_MATCHES_NOTE =
            "Nothing pick-eligible matches those filters. Try fewer filters.";

    /** The note {@code rank_spots} gives when a drive limit cannot be applied. */
    static final String NO_DRIVE_TIMES_NOTE =
            "No drive times are stored for this account, so a drive limit cannot be applied.";

    /** The note {@code rank_spots} gives when {@code nearHome} is asked of an account with no saved home. */
    static final String NO_HOME_NOTE = "nearHome is not available: no home postcode is saved.";

    /** The most spots {@code rank_night_spots} returns. */
    public static final int MAX_NIGHT_SPOTS = 10;

    /** The {@code minStars} {@code rank_night_spots} applies when the model leaves it out. */
    static final int DEFAULT_MIN_NIGHT_STARS = 3;

    /** The top of the star scale, the bound of {@code minStars}. */
    static final int MAX_NIGHT_STARS = 5;

    /** The dimmest sky on the Bortle scale, the bound of {@code maxBortle}. */
    static final int MAX_BORTLE = 9;

    /** The longest summary returned for a night spot. */
    static final int NIGHT_SUMMARY_CAP = 120;

    /** The longest cloud explanation ("clear skies") returned for a night spot. */
    static final int CLEAR_SKY_CAP = 100;

    /** What {@code rank_night_spots} notes, and the model repeats, for an asker with no aurora access. */
    static final String AURORA_PRO_NOTE = "aurora ratings are a Pro feature";

    /** The note {@code rank_night_spots} gives when the filters leave no night spot. */
    static final String NOTHING_NIGHT_MATCHES_NOTE =
            "Nothing matches those filters. Try fewer filters or a lower minStars.";

    /** What the aurora state reads for a later night with no result rows: the state cache is tonight's only. */
    static final String AURORA_NOT_FORECAST_YET = "not forecast yet";

    private static final String RANK_NIGHT = AskToolSchemas.RANK_NIGHT_SPOTS;

    private static final String LIMIT_REACHED =
            "Tool output limit reached for this conversation. Answer now with what you have.";

    private static final Set<String> TIDE_STATES = Set.of("HIGH", "MID", "LOW");

    private final AskSnapshot snapshot;
    private final AskUserContext user;
    private final AskScope scope;
    private final DriveTimeResolver driveTimes;
    private final ObjectMapper mapper;
    private final AskLocationNames places;

    private final Set<AskEvidence.Pair> pairs = new LinkedHashSet<>();
    private final Set<AskEvidence.EventFact> events = new LinkedHashSet<>();
    private final List<ToolCall> trace = new ArrayList<>();
    private final Set<LocalDate> nightsConsulted = new TreeSet<>();
    private int charsUsed;
    private boolean personal;
    private boolean uncacheable;
    private Map<Long, Integer> driveMinutes;

    /**
     * Creates the tools for one conversation.
     *
     * @param snapshot   the served snapshot every tool reads
     * @param user       who is asking; a user-less context refuses {@code maxDriveMinutes}
     * @param scope      the regions the question is about
     * @param driveTimes the source of the asker's own drive times
     * @param mapper     serialises results for the model
     */
    public AskTools(AskSnapshot snapshot, AskUserContext user, AskScope scope,
            DriveTimeResolver driveTimes, ObjectMapper mapper) {
        this(snapshot, user, scope, driveTimes, mapper, AskLocationNames.NONE);
    }

    /**
     * Creates the tools for one conversation, with the coordinates {@code nearHome} measures from.
     *
     * @param snapshot   the served snapshot every tool reads
     * @param user       who is asking; a user-less context refuses {@code maxDriveMinutes} and
     *                   {@code nearHome}
     * @param scope      the regions the question is about
     * @param driveTimes the source of the asker's own drive times
     * @param mapper     serialises results for the model
     * @param places     the enabled locations' coordinates, read lazily and only for {@code nearHome};
     *                   a conversation passes one memoised lookup so it costs one query
     */
    AskTools(AskSnapshot snapshot, AskUserContext user, AskScope scope,
            DriveTimeResolver driveTimes, ObjectMapper mapper, AskLocationNames places) {
        this.snapshot = snapshot;
        this.user = user;
        this.scope = Objects.requireNonNull(scope, "scope");
        this.driveTimes = driveTimes;
        this.mapper = mapper;
        this.places = places;
    }

    // -- argument records -------------------------------------------------------------------

    /**
     * Arguments of {@code rank_spots}; every field is optional.
     *
     * @param windowIds       windows to rank within; null or empty means every window
     * @param regionNames     regions to rank within; null or empty means every region in scope
     * @param coastalOnly     true to keep only coastal spots
     * @param tideState       HIGH, MID or LOW: keep only spots whose served tide state matches
     * @param maxDriveMinutes keep only spots within this many minutes' drive of the asker's home
     * @param nearHome        true to keep only spots within the asker's saved local radius of home, as
     *                        the crow flies (the server resolves the radius; no drive time is needed)
     * @param limit           how many spots, at most {@value AskTools#MAX_SPOTS}
     */
    public record RankSpotsArgs(List<String> windowIds, List<String> regionNames,
            Boolean coastalOnly, String tideState, Integer maxDriveMinutes, Boolean nearHome,
            Integer limit) {

        /**
         * Arguments with no {@code nearHome}.
         *
         * @param windowIds       windows to rank within
         * @param regionNames     regions to rank within
         * @param coastalOnly     true to keep only coastal spots
         * @param tideState       HIGH, MID or LOW
         * @param maxDriveMinutes a drive limit in minutes
         * @param limit           how many spots
         */
        public RankSpotsArgs(List<String> windowIds, List<String> regionNames, Boolean coastalOnly,
                String tideState, Integer maxDriveMinutes, Integer limit) {
            this(windowIds, regionNames, coastalOnly, tideState, maxDriveMinutes, null, limit);
        }
    }

    /**
     * Arguments of {@code get_hot_topics}; every field is optional.
     *
     * @param types topic types to keep; null or empty means all
     * @param limit how many topics, at most {@value AskTools#MAX_EVENTS}
     */
    public record HotTopicsArgs(List<String> types, Integer limit) {
    }

    /**
     * Arguments of {@code get_coming_up}; every field is optional.
     *
     * @param days  how many days ahead, at most {@value AskSnapshot#MAX_COMING_UP_DAYS}
     * @param limit how many entries, at most {@value AskTools#MAX_EVENTS}
     */
    public record ComingUpArgs(Integer days, Integer limit) {
    }

    /**
     * Arguments of {@code rank_night_spots}; every field is optional.
     *
     * @param night           {@code tonight} (the default), {@code tomorrow}, or the ISO date of one of the
     *                        two nights {@code list_windows} names
     * @param kind            {@code astro}, {@code aurora} or {@code any} (the default: the union of
     *                        whichever kinds have results)
     * @param regionNames     regions to rank within; null or empty means every region in scope
     * @param minStars        the least rating a spot's figure may carry, 1-5; defaults to 3
     * @param maxBortle       keep only spots at or under this Bortle class, 1-9
     * @param maxDriveMinutes keep only spots within this many minutes' drive of the asker's home
     * @param nearHome        true to keep only spots within the asker's saved local radius of home, as the
     *                        crow flies (exactly as {@code rank_spots})
     * @param limit           how many spots, at most {@value AskTools#MAX_NIGHT_SPOTS}
     */
    public record RankNightSpotsArgs(String night, String kind, List<String> regionNames, Integer minStars,
            Integer maxBortle, Integer maxDriveMinutes, Boolean nearHome, Integer limit) {
    }

    // -- result records ---------------------------------------------------------------------

    /**
     * A forecast-wide pick as {@code list_windows} names it.
     *
     * @param region     the pick's region
     * @param location   the pick's location, or null
     * @param locationId the pick's location id, or null
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PickInfo(String region, String location, Long locationId) {
    }

    /**
     * One window as {@code list_windows} returns it.
     *
     * @param id         the window id
     * @param day        Today, Tomorrow or the weekday
     * @param event      sunrise or sunset
     * @param time       the event time, {@code HH:mm} UK local, or null
     * @param verdict    the window's served verdict
     * @param bestRating the window's served best rating, or null
     * @param bestBet    the BEST BET pick when it is in scope, or null
     * @param alsoGood   the ALSO GOOD pick when it is in scope, or null
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WindowInfo(String id, String day, String event, String time, String verdict,
            Integer bestRating, PickInfo bestBet, PickInfo alsoGood) {
    }

    /**
     * The {@code list_windows} result.
     *
     * @param windows the windows, chronological
     */
    public record ListWindowsResult(List<WindowInfo> windows) {
    }

    /**
     * One spot as {@code rank_spots} returns it.
     *
     * @param locationId    the location id
     * @param name          the location name
     * @param region        the region name
     * @param windowId      the window id
     * @param rating        the served rating
     * @param verdict       the slot's served verdict
     * @param tideState     the served tide state at the event; null for an inland place
     * @param tideAligned   whether the location's own wanted water is the water at the event; null
     *                      for an inland place. Separate from {@code tideState}, never merged
     * @param tideFitPhrase the served tide-fit sentence, or null
     * @param headline      the served headline, at most {@value AskTools#HEADLINE_CAP} characters, or null
     * @param pick          BEST or ALSO when this is the window's forecast-wide pick, else null
     * @param driveMinutes  minutes from the asker's home; present only when a drive limit was given
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SpotInfo(long locationId, String name, String region, String windowId,
            int rating, String verdict, String tideState, Boolean tideAligned,
            String tideFitPhrase, String headline, String pick, Integer driveMinutes) {
    }

    /**
     * The {@code rank_spots} result.
     *
     * @param spots            the spots, best first
     * @param note             why the list is empty, or null
     * @param withinMilesOfHome the radius {@code nearHome} was resolved to, in miles, so the model can
     *                         say "within N miles of home"; null when {@code nearHome} was not used
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RankSpotsResult(List<SpotInfo> spots, String note, Integer withinMilesOfHome) {

        /**
         * A result that did not use {@code nearHome}.
         *
         * @param spots the spots, best first
         * @param note  why the list is empty, or null
         */
        public RankSpotsResult(List<SpotInfo> spots, String note) {
            this(spots, note, null);
        }
    }

    /**
     * One place's night as {@code rank_night_spots} returns it. A figure is present only when that kind has
     * a result for the place and night, and the aurora ones only for an asker who may see them. A pick must
     * be on the window of the figure it cites: {@code astroWindowId} for the sky, {@code auroraWindowId} for
     * the northern lights.
     *
     * @param locationId    the location id
     * @param name          the location name
     * @param region        the region name
     * @param astroWindowId the astro window id, present with the astro figures
     * @param auroraWindowId the aurora window id, present with the aurora figures
     * @param nightStart    the night's nautical dusk, {@code HH:mm} UK local, or null
     * @param nightEnd      the night's nautical dawn, {@code HH:mm} UK local, or null
     * @param bortle        the Bortle class (1 darkest)
     * @param astroStars    the astro conditions rating, 1-5, or null
     * @param clearSky      the served cloud explanation, which is what "clear skies tonight" asks about
     * @param astroSummary  the served astro summary, capped at {@value AskTools#NIGHT_SUMMARY_CAP} characters
     * @param moon          the moon's phase and illumination at the night's midpoint, or null
     * @param auroraStars   the aurora rating, 1-5, or null (never for an asker without aurora access)
     * @param auroraSummary the served aurora summary, capped, or null
     * @param alertLevel    the alert level that drove the aurora scoring, or null
     * @param maxKp         the highest Kp forecast for the night, or null
     * @param driveMinutes  minutes from the asker's home; present only when a drive limit was given
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NightSpotInfo(long locationId, String name, String region, String astroWindowId,
            String auroraWindowId, String nightStart, String nightEnd, int bortle, Integer astroStars,
            String clearSky, String astroSummary, String moon, Integer auroraStars, String auroraSummary,
            String alertLevel, Double maxKp, Integer driveMinutes) {
    }

    /**
     * The {@code rank_night_spots} result.
     *
     * @param night             the night asked about, ISO date
     * @param when              {@code tonight} or {@code tomorrow night}
     * @param spots             the spots, best first
     * @param note              why the list is empty, or what is missing beside a non-empty one, or null
     * @param auroraAvailable   false when the asker may not see aurora ratings; null otherwise
     * @param auroraNote        the clause to say when {@code auroraAvailable} is false, or null
     * @param auroraState       the aurora alert state for tonight, or {@value AskTools#AURORA_NOT_FORECAST_YET}
     *                          for a later night with no results; null for an asker without aurora access, and
     *                          whenever nothing may be reported (an admin simulation is active)
     * @param withinMilesOfHome the radius {@code nearHome} was resolved to, in miles; null otherwise
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RankNightSpotsResult(String night, String when, List<NightSpotInfo> spots, String note,
            Boolean auroraAvailable, String auroraNote, String auroraState, Integer withinMilesOfHome) {
    }

    /**
     * One hot topic as {@code get_hot_topics} returns it.
     *
     * @param type    the topic type
     * @param label   the topic label
     * @param detail  the detail, at most {@value AskTools#DETAIL_CAP} characters, or null
     * @param date    the topic's date (ISO)
     * @param regions the regions it names
     * @param safetyNote the topic's served warning, returned whole (never cut), or null; the
     *                   model may mention it
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TopicInfo(String type, String label, String detail, String date,
            List<String> regions, String safetyNote) {
    }

    /**
     * The {@code get_hot_topics} result.
     *
     * @param topics the topics
     */
    public record HotTopicsResult(List<TopicInfo> topics) {
    }

    /**
     * One almanac entry as {@code get_coming_up} returns it.
     *
     * @param type   the entry type
     * @param title  the entry title
     * @param start  the first date (ISO)
     * @param end    the last date (ISO)
     * @param detail the detail, at most {@value AskTools#DETAIL_CAP} characters, or null
     * @param safetyNote the warning that goes with this entry (a solar eclipse's lens-filter note),
     *                   returned whole, or null; the model may mention it
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ComingUpInfo(String type, String title, String start, String end,
            String detail, String safetyNote) {
    }

    /**
     * The {@code get_coming_up} result.
     *
     * @param entries the entries, soonest first
     */
    public record ComingUpResult(List<ComingUpInfo> entries) {
    }

    /**
     * One entry of a conversation's tool trace.
     *
     * @param tool  the tool name
     * @param error whether the call returned an error result
     * @param chars the characters returned (zero for an error)
     */
    public record ToolCall(String tool, boolean error, int chars) {
    }

    // -- tools ------------------------------------------------------------------------------

    /**
     * {@code list_windows}: every window in the window set, with the served verdict, best rating
     * and the forecast's BEST BET / ALSO GOOD pick when it is in scope.
     *
     * @return the windows
     */
    public AskToolResult<ListWindowsResult> listWindows() {
        List<WindowInfo> windows = new ArrayList<>(snapshot.windows().stream().map(this::windowInfo).toList());
        windows.addAll(nightWindowInfos());
        // An aurora row exists only for an asker who may see aurora: an answer built from it is role-bound, so
        // it is never stored (as rank_night_spots'). Astro rows alone are public and leave it cacheable.
        boolean auroraListed = windows.stream()
                .anyMatch(w -> AskWindowKind.AURORA.name().equalsIgnoreCase(w.event()));
        return finish("list_windows", new ListWindowsResult(windows), () -> {
            if (auroraListed) {
                uncacheable = true;
            }
        });
    }

    /**
     * The night windows {@code list_windows} appends after the solar ones: tonight's and tomorrow night's
     * astro window when it holds a place in scope, and the aurora window likewise <em>only for an asker
     * who may see aurora figures</em>. An aurora window's existence is derived from the aurora results,
     * whose endpoints are PRO/ADMIN, so a LITE conversation (and the user-less Ready one) lists astro
     * windows alone. A night row carries its id, day word, kind and start time and <em>no figure</em> (no
     * {@code bestRating}, no verdict): the figures come only from {@code rank_night_spots}, which is
     * uncacheable, so prose built from this list cannot carry an aurora star into the shared cache.
     */
    private List<WindowInfo> nightWindowInfos() {
        boolean canSeeAurora = user.canSeeAurora();
        List<WindowInfo> infos = new ArrayList<>();
        for (AskSnapshot.Night night : snapshot.nights().nights()) {
            if (night.kind() == AskWindowKind.AURORA && !canSeeAurora) {
                continue;
            }
            boolean hasPlace = night.spots().stream().anyMatch(s -> scope.contains(s.regionName())
                    && AskSnapshot.nightRating(night.kind(), s) != null);
            if (hasPlace) {
                infos.add(new WindowInfo(night.id(), nightWord(night.date()),
                        night.kind().name().toLowerCase(Locale.ROOT), AskClock.londonHHmm(night.start()), null,
                        null, null, null));
            }
        }
        return infos;
    }

    /** {@code Tonight} for the night in progress or next to begin, else {@code Tomorrow night}. */
    private String nightWord(LocalDate date) {
        return date.equals(snapshot.nights().tonight()) ? "Tonight" : "Tomorrow night";
    }

    /**
     * {@code rank_spots}: pick-eligible slots, best first (rating, then the forecast's own pick,
     * then tide preference met, then name).
     *
     * @param args the optional filters
     * @return the spots, or an error result for a bad argument
     */
    public AskToolResult<RankSpotsResult> rankSpots(RankSpotsArgs args) {
        RankSpotsArgs a = args == null ? new RankSpotsArgs(null, null, null, null, null, null, null)
                : args;
        int limit = clamp(a.limit(), DEFAULT_LIMIT, MAX_SPOTS);

        List<AskSnapshot.Window> windows = snapshot.windows();
        if (a.windowIds() != null && !a.windowIds().isEmpty()) {
            List<AskSnapshot.Window> picked = new ArrayList<>();
            for (String id : a.windowIds()) {
                AskSnapshot.Window w = snapshot.window(id).orElse(null);
                if (w == null) {
                    return fail("rank_spots", "Unknown window id '" + id
                            + "'. Call list_windows for the valid ids.");
                }
                picked.add(w);
            }
            windows = picked;
        }

        Set<String> regionFilter = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (a.regionNames() != null) {
            for (String name : a.regionNames()) {
                String canonical = canonicalRegion(name);
                if (canonical == null) {
                    return fail("rank_spots", "Unknown region '" + name
                            + "'. Region names appear in the list_windows and rank_spots results.");
                }
                if (!scope.contains(canonical)) {
                    return fail("rank_spots", "Region '" + canonical
                            + "' is outside the scope of this question.");
                }
                regionFilter.add(canonical);
            }
        }

        String tideState = null;
        if (a.tideState() != null && !a.tideState().isBlank()) {
            tideState = a.tideState().strip().toUpperCase(Locale.ROOT);
            if (!TIDE_STATES.contains(tideState)) {
                return fail("rank_spots", "tideState must be HIGH, MID or LOW.");
            }
        }

        Integer maxDrive = a.maxDriveMinutes();
        if (maxDrive != null) {
            if (!user.hasUser()) {
                return fail("rank_spots", "maxDriveMinutes is not available in this conversation.");
            }
            if (maxDrive < 1) {
                return fail("rank_spots", "maxDriveMinutes must be at least 1.");
            }
            // The answer now depends on who asked, so it must never be shared.
            personal = true;
            if (!user.hasDriveTimes()) {
                return finish("rank_spots", new RankSpotsResult(List.of(), NO_DRIVE_TIMES_NOTE),
                        () -> { });
            }
        }

        boolean nearHome = Boolean.TRUE.equals(a.nearHome());
        if (nearHome) {
            if (!user.hasUser()) {
                return fail("rank_spots", "nearHome is not available in this conversation.");
            }
            // The answer depends on who asked (their home and radius), so it must never be shared.
            personal = true;
            if (!user.hasHome()) {
                return finish("rank_spots", new RankSpotsResult(List.of(), NO_HOME_NOTE), () -> { });
            }
        }

        List<AskSnapshot.Candidate> pool = new ArrayList<>();
        for (AskSnapshot.Window w : windows) {
            // The same in-scope set the BEST anchor consults (AskSnapshot#candidates with a scope).
            for (AskSnapshot.Candidate c : snapshot.candidates(w, scope)) {
                if (regionFilter.isEmpty() || regionFilter.contains(c.region().name())) {
                    pool.add(c);
                }
            }
        }
        if (pool.isEmpty()) {
            return finish("rank_spots", new RankSpotsResult(List.of(), NOTHING_ELIGIBLE_NOTE),
                    () -> { });
        }

        boolean coastalOnly = Boolean.TRUE.equals(a.coastalOnly());
        String wantedTide = tideState;
        Map<Long, Integer> minutes = maxDrive == null ? Map.of() : driveMinutesByLocation();
        Set<Long> nearIds = nearHome ? locationsNearHome() : Set.of();
        List<AskSnapshot.Candidate> kept = pool.stream()
                .filter(c -> !coastalOnly || c.slot().coastal())
                .filter(c -> wantedTide == null || wantedTide.equals(c.slot().tideState()))
                .filter(c -> maxDrive == null || withinDrive(minutes, c, maxDrive))
                .filter(c -> !nearHome || nearIds.contains(c.slot().locationId()))
                .sorted(RANK)
                .limit(limit)
                .toList();

        List<SpotInfo> spots = kept.stream().map(c -> spotInfo(c, maxDrive == null ? null
                : minutes.get(c.slot().locationId()))).toList();
        Integer radius = nearHome ? user.home().radiusMiles() : null;
        String note = spots.isEmpty()
                ? (nearHome ? "Nothing pick-eligible within " + radius + " miles of home matches those filters."
                        : NOTHING_MATCHES_NOTE)
                : null;
        return finish("rank_spots", new RankSpotsResult(spots, note, radius), () -> {
            for (SpotInfo s : spots) {
                pairs.add(new AskEvidence.Pair(s.locationId(), s.windowId()));
            }
        });
    }

    /**
     * {@code rank_night_spots}: tonight's or tomorrow night's places by the astro conditions forecast (cloud,
     * visibility, moon, with the location's Bortle class) and, for an asker who may see them, the aurora
     * forecast, best first (ask-night plan §2.2). Every figure is a served one: no score is derived here.
     *
     * <p><b>The conversation is marked {@linkplain #uncacheable() uncacheable} the moment this is called,
     * whichever branch runs</b>, beside {@code personal}: a night answer depends on the asker's role (the
     * aurora figures) and on facts that move under the typed cache's TTL (the aurora alert state, the astro
     * refresh), so it is never stored. The aurora figures and the aurora alert state reach only an asker
     * whose role may see them ({@link AskUserContext#canSeeAurora}); the alert state is read for the night
     * that is {@code currentNightDate()} only, because the state cache holds no date-keyed state.
     *
     * @param args the optional filters
     * @return the spots, or an error result for a bad argument
     */
    public AskToolResult<RankNightSpotsResult> rankNightSpots(RankNightSpotsArgs args) {
        uncacheable = true;
        RankNightSpotsArgs a = args == null
                ? new RankNightSpotsArgs(null, null, null, null, null, null, null, null) : args;
        AskSnapshot.Nights nights = snapshot.nights();
        if (nights.tonight() == null) {
            return finish(RANK_NIGHT, new RankNightSpotsResult(null, null, List.of(),
                    "No night forecast is available yet.", null, null, null, null), () -> { });
        }
        LocalDate date = nightDate(a.night(), nights);
        if (date == null) {
            return fail(RANK_NIGHT, "Unknown night '" + cap(a.night(), 40)
                    + "'. Use tonight, tomorrow, or the date of a night list_windows names.");
        }
        String kind = a.kind() == null || a.kind().isBlank() ? "any" : a.kind().strip().toLowerCase(Locale.ROOT);
        if (!Set.of("astro", "aurora", "any").contains(kind)) {
            return fail(RANK_NIGHT, "kind must be astro, aurora or any.");
        }
        Set<String> regionFilter = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (a.regionNames() != null) {
            for (String name : a.regionNames()) {
                String canonical = canonicalRegion(name);
                if (canonical == null) {
                    return fail(RANK_NIGHT, "Unknown region '" + cap(name, 40)
                            + "'. Region names appear in the list_windows and rank_night_spots results.");
                }
                if (!scope.contains(canonical)) {
                    return fail(RANK_NIGHT, "Region '" + canonical
                            + "' is outside the scope of this question.");
                }
                regionFilter.add(canonical);
            }
        }
        Integer maxDrive = a.maxDriveMinutes();
        if (maxDrive != null) {
            if (!user.hasUser()) {
                return fail(RANK_NIGHT, "maxDriveMinutes is not available in this conversation.");
            }
            if (maxDrive < 1) {
                return fail(RANK_NIGHT, "maxDriveMinutes must be at least 1.");
            }
            personal = true;
        }
        boolean nearHome = Boolean.TRUE.equals(a.nearHome());
        if (nearHome) {
            if (!user.hasUser()) {
                return fail(RANK_NIGHT, "nearHome is not available in this conversation.");
            }
            personal = true;
        }

        boolean canSeeAurora = user.canSeeAurora();
        boolean wantAstro = !"aurora".equals(kind);
        boolean wantAurora = canSeeAurora && !"astro".equals(kind);
        Optional<AskSnapshot.Night> astroNight = nights.night(date, AskWindowKind.ASTRO);
        Optional<AskSnapshot.Night> auroraNight = canSeeAurora
                ? nights.night(date, AskWindowKind.AURORA) : Optional.empty();
        String when = nightLabel(date, nights);
        Runnable consulted = () -> nightsConsulted.add(date);
        String missing = missingNote(when, wantAstro && astroNight.isEmpty(), wantAurora && auroraNight.isEmpty(),
                wantAstro, wantAurora);
        String auroraState = auroraState(date, nights, wantAurora, auroraNight.isPresent());

        if (maxDrive != null && !user.hasDriveTimes()) {
            return finish(RANK_NIGHT, nightResult(date, when, List.of(), NO_DRIVE_TIMES_NOTE, canSeeAurora,
                    auroraState, null), consulted);
        }
        if (nearHome && !user.hasHome()) {
            return finish(RANK_NIGHT, nightResult(date, when, List.of(), NO_HOME_NOTE, canSeeAurora, auroraState,
                    null), consulted);
        }

        Map<Long, NightRow> pool = new LinkedHashMap<>();
        if (wantAstro) {
            astroNight.ifPresent(n -> n.spots().forEach(s -> pool.put(s.locationId(),
                    new NightRow(s, astroNight.get().id(), null))));
        }
        if (wantAurora) {
            auroraNight.ifPresent(n -> n.spots().forEach(s -> pool.put(s.locationId(), new NightRow(s,
                    s.astroStars() == null ? null : astroNight.map(AskSnapshot.Night::id).orElse(null),
                    n.id()))));
        } else if (!wantAstro) {
            // A kind the asker may not see: nothing is listed and the result says why.
            return finish(RANK_NIGHT, nightResult(date, when, List.of(), null, canSeeAurora, auroraState, null),
                    consulted);
        }
        List<NightRow> inScope = pool.values().stream().filter(r -> scope.contains(r.spot().regionName())).toList();

        String emptyNote = missing;
        if (!regionFilter.isEmpty() && inScope.stream().noneMatch(r -> regionFilter.contains(r.spot().regionName()))) {
            emptyNote = "no night-scored places in " + String.join(", ", regionFilter);
        }
        int minStars = a.minStars() == null ? DEFAULT_MIN_NIGHT_STARS : Math.clamp(a.minStars(), 1, MAX_NIGHT_STARS);
        Integer maxBortle = a.maxBortle() == null ? null : Math.clamp(a.maxBortle(), 1, MAX_BORTLE);
        Map<Long, Integer> minutes = maxDrive == null ? Map.of() : driveMinutesByLocation();
        int limit = clamp(a.limit(), DEFAULT_LIMIT, MAX_NIGHT_SPOTS);
        List<NightSpotInfo> spots = inScope.stream()
                .filter(r -> regionFilter.isEmpty() || regionFilter.contains(r.spot().regionName()))
                .filter(r -> r.best() >= minStars)
                .filter(r -> maxBortle == null || r.spot().bortleClass() != null && r.spot().bortleClass() <= maxBortle)
                .filter(r -> maxDrive == null || minutes.get(r.spot().locationId()) != null
                        && minutes.get(r.spot().locationId()) <= maxDrive)
                .filter(r -> !nearHome || withinHomeRadius(r.spot()))
                .sorted(nightOrder(minutes))
                .limit(limit)
                .map(r -> nightSpotInfo(r, astroNight.orElse(null),
                        maxDrive == null ? null : minutes.get(r.spot().locationId())))
                .toList();
        Integer radius = nearHome ? user.home().radiusMiles() : null;
        String note;
        if (!spots.isEmpty()) {
            note = missing;
        } else if (emptyNote != null) {
            note = emptyNote;
        } else if (nearHome) {
            note = "Nothing within " + radius + " miles of home matches those filters.";
        } else {
            note = NOTHING_NIGHT_MATCHES_NOTE;
        }
        return finish(RANK_NIGHT, nightResult(date, when, spots, note, canSeeAurora, auroraState, radius), () -> {
            consulted.run();
            for (NightSpotInfo s : spots) {
                if (s.astroWindowId() != null) {
                    pairs.add(new AskEvidence.Pair(s.locationId(), s.astroWindowId()));
                }
                if (s.auroraWindowId() != null) {
                    pairs.add(new AskEvidence.Pair(s.locationId(), s.auroraWindowId()));
                }
            }
        });
    }

    /** The night a {@code night} argument names: tonight by default, tomorrow, or an ISO date of one of the two. */
    private static LocalDate nightDate(String night, AskSnapshot.Nights nights) {
        LocalDate tonight = nights.tonight();
        String text = night == null ? "" : night.strip().toLowerCase(Locale.ROOT);
        if (text.isEmpty() || "tonight".equals(text)) {
            return tonight;
        }
        if ("tomorrow".equals(text) || "tomorrow night".equals(text)) {
            return tonight.plusDays(1);
        }
        try {
            LocalDate date = LocalDate.parse(text);
            return date.equals(tonight) || date.equals(tonight.plusDays(1)) ? date : null;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String nightLabel(LocalDate date, AskSnapshot.Nights nights) {
        return date.equals(nights.tonight()) ? "tonight" : "tomorrow night";
    }

    /** What is missing beside, or instead of, the spots: the per-kind empty answer (plan §2.2), never a blanket one. */
    private static String missingNote(String when, boolean astroMissing, boolean auroraMissing, boolean wantAstro,
            boolean wantAurora) {
        if (wantAstro && wantAurora) {
            if (astroMissing && auroraMissing) {
                return "no night forecast for " + when + " yet";
            }
            return astroMissing ? "no astro forecast for " + when + " yet" : null;
        }
        if (wantAstro) {
            return astroMissing ? "no astro forecast for " + when + " yet" : null;
        }
        return wantAurora && auroraMissing ? "no aurora forecast for " + when + " yet" : null;
    }

    /**
     * The aurora alert state for a PRO/ADMIN asker, <em>for the night that is {@code currentNightDate()}
     * only</em>: the state cache holds "the rest of tonight" and no date-keyed state, so a later night is
     * never given tonight's state relabelled; with no result rows it reads "not forecast yet". Null for an
     * asker without aurora access, for a question that did not ask about aurora, and whenever the snapshot
     * holds no state (an admin simulation is active).
     */
    private String auroraState(LocalDate date, AskSnapshot.Nights nights, boolean wantAurora, boolean auroraRows) {
        if (!wantAurora) {
            return null;
        }
        if (date.equals(nights.tonight())) {
            AskSnapshot.AuroraState state = nights.aurora();
            if (state == null) {
                return null;
            }
            if (AlertLevel.QUIET.name().equals(state.level())) {
                return "quiet: no aurora alert is active";
            }
            return "alert " + state.level().toLowerCase(Locale.ROOT)
                    + (state.kp() == null ? "" : String.format(Locale.ROOT, ", Kp %.1f", state.kp()));
        }
        return auroraRows ? null : AURORA_NOT_FORECAST_YET;
    }

    private static RankNightSpotsResult nightResult(LocalDate date, String when, List<NightSpotInfo> spots,
            String note, boolean canSeeAurora, String auroraState, Integer radius) {
        return new RankNightSpotsResult(date.toString(), when, spots, note,
                canSeeAurora ? null : Boolean.FALSE, canSeeAurora ? null : AURORA_PRO_NOTE, auroraState, radius);
    }

    /** One place on the night being ranked, with the window of each kind its figures belong to. */
    private record NightRow(AskSnapshot.NightSpot spot, String astroWindowId, String auroraWindowId) {

        /** The best figure the row shows: the astro rating, or the aurora one when it has that. */
        int best() {
            int astro = astroWindowId == null || spot.astroStars() == null ? 0 : spot.astroStars();
            int aurora = auroraWindowId == null || spot.auroraStars() == null ? 0 : spot.auroraStars();
            return Math.max(astro, aurora);
        }
    }

    /** Best figure first, then the shorter drive when one was asked for, then name and id. */
    private static Comparator<NightRow> nightOrder(Map<Long, Integer> minutes) {
        return Comparator.<NightRow>comparingInt(r -> -r.best())
                .thenComparing(r -> minutes.get(r.spot().locationId()), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(r -> r.spot().name(), Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                .thenComparingLong(r -> r.spot().locationId());
    }

    private boolean withinHomeRadius(AskSnapshot.NightSpot spot) {
        AskUserContext.Home home = user.home();
        return GeoUtils.distanceMiles(home.latitude(), home.longitude(), spot.lat(), spot.lon())
                <= home.radiusMiles();
    }

    private NightSpotInfo nightSpotInfo(NightRow row, AskSnapshot.Night astroNight, Integer minutes) {
        AskSnapshot.NightSpot s = row.spot();
        boolean astro = row.astroWindowId() != null && s.astroStars() != null;
        boolean aurora = row.auroraWindowId() != null && s.auroraStars() != null;
        return new NightSpotInfo(s.locationId(), s.name(), s.regionName(), astro ? row.astroWindowId() : null,
                aurora ? row.auroraWindowId() : null,
                astroNight == null ? null : AskClock.londonHHmm(astroNight.start()),
                astroNight == null ? null : AskClock.londonHHmm(astroNight.end()),
                s.bortleClass() == null ? 0 : s.bortleClass(),
                astro ? s.astroStars() : null, astro ? cap(s.cloudExplanation(), CLEAR_SKY_CAP) : null,
                astro ? cap(s.astroSummary(), NIGHT_SUMMARY_CAP) : null, astro ? moonText(s) : null,
                aurora ? s.auroraStars() : null, aurora ? cap(s.auroraSummary(), NIGHT_SUMMARY_CAP) : null,
                aurora ? s.alertLevel() : null, aurora ? s.maxKp() : null, minutes);
    }

    /** {@code Waxing gibbous, 85% lit}, from the served phase name and illumination. */
    private static String moonText(AskSnapshot.NightSpot s) {
        if (s.moonPhase() == null) {
            return null;
        }
        String phase = s.moonPhase().toLowerCase(Locale.ROOT).replace('_', ' ');
        String text = Character.toUpperCase(phase.charAt(0)) + phase.substring(1);
        return s.moonIlluminationPct() == null ? text : text + ", " + Math.round(s.moonIlluminationPct()) + "% lit";
    }

    /**
     * {@code get_hot_topics}: the served hot topics, optionally of given types, limited to the
     * question's scope when a topic names regions.
     *
     * @param args the optional filters
     * @return the topics
     */
    public AskToolResult<HotTopicsResult> getHotTopics(HotTopicsArgs args) {
        HotTopicsArgs a = args == null ? new HotTopicsArgs(null, null) : args;
        int limit = clamp(a.limit(), DEFAULT_LIMIT, MAX_EVENTS);
        Set<String> types = typeKeys(a.types());
        List<AskSnapshot.Topic> kept = snapshot.hotTopics().stream()
                .filter(t -> types.isEmpty() || types.contains(AskEventType.key(t.type())))
                .filter(t -> t.inScope(scope))
                .limit(limit)
                .toList();
        List<TopicInfo> infos = kept.stream()
                .map(t -> new TopicInfo(t.type(), t.label(), cap(t.detail(), DETAIL_CAP),
                        iso(t.date()), t.regions(), t.safetyNote()))
                .toList();
        return finish("get_hot_topics", new HotTopicsResult(infos), () -> {
            for (AskSnapshot.Topic t : kept) {
                events.add(new AskEvidence.EventFact(AskEventType.served(t.type()), t.label(), t.date(),
                        t.safetyNote()));
            }
        });
    }

    /**
     * {@code get_coming_up}: every event on the next {@code days} days' timeline — the almanac
     * entries that overlap them <em>and</em> the live hot topics dated within them, so the one tool
     * answers "what is coming up" whole. The almanac is the long-range feed and holds nothing the
     * forecast is flagging this week (a solar eclipse that is a live hot topic can be absent from
     * it), so a conversation that consulted only this tool used to be told "nothing" while the
     * forecast was flagging an eclipse. A hot topic that the almanac already lists (the same type,
     * on a date inside the entry's span) is not repeated.
     *
     * @param args the optional window and limit
     * @return the entries, soonest first
     */
    public AskToolResult<ComingUpResult> getComingUp(ComingUpArgs args) {
        ComingUpArgs a = args == null ? new ComingUpArgs(null, null) : args;
        int days = clamp(a.days(), AskSnapshot.MAX_COMING_UP_DAYS, AskSnapshot.MAX_COMING_UP_DAYS);
        int limit = clamp(a.limit(), DEFAULT_LIMIT, MAX_EVENTS);
        List<AskSnapshot.ComingUp> kept = snapshot.timeline(scope, days).stream().limit(limit).toList();
        List<ComingUpInfo> infos = kept.stream()
                .map(e -> new ComingUpInfo(e.type(), e.title(), iso(e.startDate()),
                        iso(e.endDate()), cap(e.detail(), DETAIL_CAP), e.safetyNote()))
                .toList();
        return finish("get_coming_up", new ComingUpResult(infos), () -> {
            for (AskSnapshot.ComingUp e : kept) {
                events.add(new AskEvidence.EventFact(AskEventType.served(e.type()), e.title(), e.startDate(),
                        e.safetyNote()));
            }
        });
    }

    // -- conversation state -----------------------------------------------------------------

    /**
     * What this conversation's tools returned, for the validator.
     *
     * @return the evidence so far
     */
    public AskEvidence evidence() {
        return new AskEvidence(pairs, events, trace.size());
    }

    /**
     * Whether the asker's own drive times or home were used, which makes the answer personal.
     *
     * @return true once {@code maxDriveMinutes} or {@code nearHome} has been used by a user
     */
    public boolean personal() {
        return personal;
    }

    /**
     * Whether the conversation called {@code rank_night_spots} (whichever branch ran), which makes the
     * answer unfit for the typed cache: it depends on the asker's role and on facts that move under the
     * cache's TTL.
     *
     * @return true once {@code rank_night_spots} has been called
     */
    public boolean uncacheable() {
        return uncacheable;
    }

    /**
     * The dates of the nights {@code rank_night_spots} answered about, which version a night answer.
     *
     * @return the dates, earliest first; empty when no night was consulted
     */
    public Set<LocalDate> nightsConsulted() {
        return Collections.unmodifiableSet(new TreeSet<>(nightsConsulted));
    }

    /**
     * Every tool call so far, in order, for the admin dry-run.
     *
     * @return the trace
     */
    public List<ToolCall> trace() {
        return List.copyOf(trace);
    }

    /**
     * The characters of successful output returned so far.
     *
     * @return the running total against {@value #RESULT_CHAR_CAP}
     */
    public int charsUsed() {
        return charsUsed;
    }

    // -- internals --------------------------------------------------------------------------

    /** The order {@code rank_spots} returns spots in, shared with the stub's ranking of a thread's picks. */
    static final Comparator<AskSnapshot.Candidate> RANK =
            Comparator.<AskSnapshot.Candidate>comparingInt(c -> -c.slot().rating())
                    .thenComparingInt(AskTools::pickOrder)
                    .thenComparingInt(c -> c.slot().tideAligned() ? 0 : 1)
                    .thenComparing(c -> c.slot().name(),
                            Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                    .thenComparing(c -> c.window().date())
                    .thenComparing(c -> c.window().targetType())
                    .thenComparingLong(c -> c.slot().locationId());

    private static int pickOrder(AskSnapshot.Candidate c) {
        return pickKind(c).map(kind -> kind == BriefingWindow.PickKind.BEST ? 0 : 1).orElse(2);
    }

    /** Whether this slot is the window's forecast-wide pick, matched by id then by name. */
    private static Optional<BriefingWindow.PickKind> pickKind(AskSnapshot.Candidate c) {
        BriefingWindow.Pick pick = c.window().pick();
        if (pick == null) {
            return Optional.empty();
        }
        boolean same = pick.locationId() != null
                ? pick.locationId().equals(c.slot().locationId())
                : pick.locationName() != null && pick.locationName().equals(c.slot().name());
        return same ? Optional.of(pick.kind()) : Optional.empty();
    }

    private WindowInfo windowInfo(AskSnapshot.Window w) {
        BriefingWindow.Pick pick = w.pick();
        boolean pickInScope = pick != null && scope.contains(pick.regionName());
        PickInfo named = pickInScope
                ? new PickInfo(pick.regionName(), pick.locationName(), pick.locationId()) : null;
        boolean best = pickInScope && pick.kind() == BriefingWindow.PickKind.BEST;
        boolean also = pickInScope && pick.kind() == BriefingWindow.PickKind.ALSO;
        return new WindowInfo(w.id(), dayWord(w.date()), w.targetType().name().toLowerCase(
                Locale.ROOT), clock(w), w.verdict() == null ? null : w.verdict().name(),
                w.bestRating(), best ? named : null, also ? named : null);
    }

    /**
     * One slot as {@code rank_spots} renders it: the one vocabulary the model reads, whether the slot
     * came from a tool result or from a thread's earlier pick.
     *
     * @param c       the slot
     * @param minutes the asker's drive minutes to it, or null
     * @return the rendered spot
     */
    SpotInfo spotInfo(AskSnapshot.Candidate c, Integer minutes) {
        AskSnapshot.Slot s = c.slot();
        Optional<BriefingWindow.PickKind> kind = pickKind(c);
        return new SpotInfo(s.locationId(), s.name(), c.region().name(), c.window().id(),
                s.rating(), s.verdict() == null ? null : s.verdict().name(), s.tideState(),
                s.coastal() ? s.tideAligned() : null, s.tideFitPhrase(),
                cap(s.headline(), HEADLINE_CAP), kind.map(Enum::name).orElse(null), minutes);
    }

    /** {@code Today}, {@code Tomorrow} or the weekday: the shared relative day, capitalised for a tool result. */
    private String dayWord(LocalDate date) {
        return dayWord(date, snapshot.today());
    }

    /** The same day word for any date: {@code Today}, {@code Tomorrow} or the weekday. */
    static String dayWord(LocalDate date, LocalDate today) {
        String relative = DayLabels.relative(date, today);
        return Character.toUpperCase(relative.charAt(0)) + relative.substring(1);
    }

    private static String clock(AskSnapshot.Window w) {
        return AskClock.londonHHmm(w.eventTime());
    }

    private Map<Long, Integer> driveMinutesByLocation() {
        if (driveMinutes == null) {
            driveMinutes = driveTimes.getAllMinutes(user.userId());
        }
        return driveMinutes;
    }

    /**
     * The asker's drive minutes to a location, for a thread's pick line. Unlike {@code rank_spots} this
     * is read without a drive limit, but only for a user who has drive times, and it does not make the
     * answer personal (a follow-up is never cached or shared).
     *
     * @param locationId the location id
     * @return the minutes, or null when the asker has none for it
     */
    Integer driveMinutesOf(long locationId) {
        if (!user.hasUser() || !user.hasDriveTimes()) {
            return null;
        }
        return driveMinutesByLocation().get(locationId);
    }

    /**
     * The ids of the enabled locations within the asker's saved radius of home, in straight-line miles:
     * {@code GeoUtils.distanceMiles}, the measure {@code CloseToHomeService} has always applied to that
     * radius (not the drive time the Map's reach lens uses). A location the lookup does not hold is
     * not near.
     */
    private Set<Long> locationsNearHome() {
        AskUserContext.Home home = user.home();
        Set<Long> near = new HashSet<>();
        places.byId().forEach((id, place) -> {
            if (GeoUtils.distanceMiles(home.latitude(), home.longitude(), place.lat(), place.lon())
                    <= home.radiusMiles()) {
                near.add(id);
            }
        });
        return near;
    }

    private static boolean withinDrive(Map<Long, Integer> minutes, AskSnapshot.Candidate c,
            int max) {
        Integer m = minutes.get(c.slot().locationId());
        return m != null && m <= max;
    }

    private String canonicalRegion(String name) {
        if (name == null) {
            return null;
        }
        String wanted = name.strip().toLowerCase(Locale.ROOT);
        TreeSet<String> names = new TreeSet<>();
        snapshot.windows().forEach(w -> w.regions().forEach(r -> names.add(r.name())));
        // The night spots' regions count too: a question about a region that has night forecasts and no
        // solar window in the set must still be able to name it.
        snapshot.nights().nights().forEach(n -> n.spots().forEach(s -> names.add(s.regionName())));
        return names.stream()
                .filter(n -> n.toLowerCase(Locale.ROOT).equals(wanted))
                .findFirst()
                .orElse(null);
    }

    private <T> AskToolResult<T> fail(String tool, String message) {
        trace.add(new ToolCall(tool, true, 0));
        return AskToolResult.error(message);
    }

    /**
     * Serialises a payload, applies the conversation's character cap, and on success records the
     * call and runs {@code accepted}. A result the cap refuses records nothing as returned.
     */
    private <T> AskToolResult<T> finish(String tool, T payload, Runnable accepted) {
        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return fail(tool, "The result could not be produced.");
        }
        if (charsUsed + json.length() > RESULT_CHAR_CAP) {
            return fail(tool, LIMIT_REACHED);
        }
        charsUsed += json.length();
        trace.add(new ToolCall(tool, false, json.length()));
        accepted.run();
        return new AskToolResult<>(false, json, payload);
    }

    private static int clamp(Integer requested, int fallback, int max) {
        int value = requested == null ? fallback : requested;
        return Math.clamp(value, 1, max);
    }

    private static Set<String> typeKeys(List<String> values) {
        Set<String> out = new HashSet<>();
        if (values != null) {
            values.stream().filter(v -> v != null && !v.isBlank())
                    .forEach(v -> out.add(AskEventType.key(v)));
        }
        return out;
    }

    private static String iso(LocalDate date) {
        return date == null ? null : date.toString();
    }

    /** Cuts text to at most {@code max} characters, marking the cut with an ellipsis. */
    static String cap(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        int end = max - 1;
        // Never split a surrogate pair: a lone half is invalid text.
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end).stripTrailing() + "…";
    }
}
