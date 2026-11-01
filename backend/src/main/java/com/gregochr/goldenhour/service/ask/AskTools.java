package com.gregochr.goldenhour.service.ask;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.model.CoverageReason;
import com.gregochr.goldenhour.service.DriveTimeResolver;
import com.gregochr.goldenhour.service.evaluation.ForecastResultHandler;
import com.gregochr.goldenhour.service.evaluation.RatingValidator;
import com.gregochr.goldenhour.util.DayLabels;
import com.gregochr.goldenhour.util.GeoUtils;
import com.gregochr.goldenhour.util.PlaceNames;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
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
 * <p><b>What a tool may return.</b> Every recommending tool returns only pick-eligible slots
 * ({@link AskSnapshot#isPickEligible}): never a wood, never a slot under 3★, never a region the Plan tab
 * would refuse a verdict. The one exception is {@code look_up_place}, which explains one named place's
 * served rating whatever its eligibility and adds nothing to the evidence, so it can never make a pick. The
 * served tide state and the location's tide preference are separate fields and are never merged.
 * Nothing about home reaches a user-less conversation.
 *
 * <p>Not thread-safe; a conversation is single-threaded.
 */
public class AskTools {

    /** Total characters of successful tool output one conversation may receive. */
    public static final int RESULT_CHAR_CAP = 9_000;

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

    /** The most distinct places {@code look_up_place} returns for one name. */
    static final int MAX_PLACE_MATCHES = 4;

    /**
     * The most (place, window) rows {@code look_up_place} returns. The conversation's character cap is shared with
     * {@code list_windows}, which comes first, so one lookup is held to under half of it
     * ({@code AskToolsLookUpPlaceTest} measures the worst case).
     */
    static final int MAX_PLACE_ROWS = 6;

    /**
     * The row cap when any row carries {@link PlaceDetail} or a {@link PlaceInfo#nearestTide}: a detail row costs
     * about 300 characters, so six would not fit the 4,500-character bound.
     */
    static final int MAX_PLACE_ROWS_WITH_DETAIL = 4;

    /**
     * The longest {@link PlaceInfo#nearestTide} phrase a row carries. Real phrases ("HW 06:42 · 40m after sunrise")
     * are 30 characters or fewer.
     */
    static final int NEAREST_TIDE_CAP = 60;

    /** The most later-window tides one {@code look_up_place} result carries (ask-place-tide plan T2-9). */
    static final int MAX_LATER_TIDE_ROWS = 2;

    /** The most rows of the one place a {@code look_up_place} answer is about that carry {@link PlaceDetail}. */
    static final int MAX_DETAIL_ROWS = 4;

    /** The longest forecast summary a {@link PlaceDetail} carries. */
    static final int SUMMARY_CAP = 200;

    /** The most windows a named-area {@code look_up_place} lists. */
    static final int MAX_AREA_WINDOWS = 4;

    /** The most places a named-area {@code look_up_place} lists for one window. */
    static final int MAX_AREA_PLACES = 6;

    /** The most place names the "also matched" note gives. */
    static final int MAX_ALSO_MATCHED = 3;

    /** What a named-area lookup notes when every member with a forecast is outside the question's regions. */
    static final String AREA_OUT_OF_SCOPE_NOTE = "That named area is outside the regions this question is about.";

    /** What a named-area lookup notes when no member has a slot in the windows. */
    static final String AREA_NO_PLACES_NOTE = "None of the named area's places is in these windows.";

    /** What a named-area lookup notes when some window listed only its best places. */
    static final String AREA_PLACES_CUT_NOTE =
            "Some windows list only their best " + MAX_AREA_PLACES + " places; ask about a place by name.";

    /** What every named-area lookup ends with. */
    static final String AREA_SUGGEST_NOTE = "To suggest places here, call rank_spots with areaName.";

    /**
     * The longest list of known named areas an unknown {@code areaName} error gives. Sized so every name fits
     * with the 24 sub-regions beside the ordinary areas (the 25 names are about 650 characters); a longer list
     * is cut, not refused.
     */
    static final int KNOWN_AREAS_CAP = 1_200;

    /** The most other area names the "also matched areas" note gives. */
    static final int MAX_ALSO_AREAS = 5;

    /** What {@code look_up_place} notes when no place in the windows carries the name. */
    static final String NO_SUCH_PLACE_NOTE = "No place by that name is in these windows.";

    /** What {@code look_up_place} notes when the name matches only places outside the question's regions. */
    static final String PLACE_OUT_OF_SCOPE_NOTE = "That place is outside the regions this question is about.";

    /** The most place names {@code look_up_place} suggests when a misspelling is as near to several names. */
    static final int MAX_SPELLING_SUGGESTIONS = 3;

    /** The longest reader spelling {@code closestTo} echoes back. */
    static final int CLOSEST_TO_CAP = 60;

    /** What {@code look_up_place} notes when a misspelling is as near to several names as to one. */
    static final String SPELLING_TIE_NOTE = "No place has that spelling; closest names are in suggestions.";

    private static final String LOOK_UP = AskToolSchemas.LOOK_UP_PLACE;

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
    private final List<String> windowBound;

    private final Set<AskEvidence.Pair> pairs = new LinkedHashSet<>();
    private final Set<AskEvidence.EventFact> events = new LinkedHashSet<>();
    private final Set<String> listedWindows = new LinkedHashSet<>();
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
        this(snapshot, user, scope, driveTimes, mapper, places, null);
    }

    /**
     * Creates the tools for one conversation, with the windows {@code rank_spots} may rank within.
     *
     * @param snapshot    the served snapshot every tool reads
     * @param user        who is asking
     * @param scope       the regions the question is about
     * @param driveTimes  the source of the asker's own drive times
     * @param mapper      serialises results for the model
     * @param places      the enabled locations' coordinates, read lazily and only for {@code nearHome}
     * @param windowBound the window ids {@code rank_spots} is confined to (a Ready pick question's own
     *                    offer windows), or null for every window
     */
    AskTools(AskSnapshot snapshot, AskUserContext user, AskScope scope,
            DriveTimeResolver driveTimes, ObjectMapper mapper, AskLocationNames places,
            List<String> windowBound) {
        this.windowBound = windowBound == null ? null : List.copyOf(windowBound);
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
     * @param areaName        only places in this named area, by its name or another name for it; typed
     *                        conversations only
     */
    public record RankSpotsArgs(List<String> windowIds, List<String> regionNames,
            Boolean coastalOnly, String tideState, Integer maxDriveMinutes, Boolean nearHome,
            Integer limit, String areaName) {

        /**
         * Arguments with no {@code areaName}.
         *
         * @param windowIds       windows to rank within
         * @param regionNames     regions to rank within
         * @param coastalOnly     true to keep only coastal spots
         * @param tideState       HIGH, MID or LOW
         * @param maxDriveMinutes a drive limit in minutes
         * @param nearHome        true to keep only spots near home
         * @param limit           how many spots
         */
        public RankSpotsArgs(List<String> windowIds, List<String> regionNames, Boolean coastalOnly,
                String tideState, Integer maxDriveMinutes, Boolean nearHome, Integer limit) {
            this(windowIds, regionNames, coastalOnly, tideState, maxDriveMinutes, nearHome, limit, null);
        }

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
            this(windowIds, regionNames, coastalOnly, tideState, maxDriveMinutes, null, limit, null);
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
     * @param bestRating the window's served best rating with any neighbour outlier left out, or null
     * @param bestBet    the BEST BET pick when it is in scope, or null
     * @param alsoGood   the ALSO GOOD pick when it is in scope, or null
     * @param spread     the window's rating distribution over the question's scope
     *                   ({@link AskSnapshot.Spread}); null for a night row, which carries no figure
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WindowInfo(String id, String day, String event, String time, String verdict,
            Integer bestRating, PickInfo bestBet, PickInfo alsoGood, AskSnapshot.Spread spread) {

        /**
         * A window with no spread: a night row.
         *
         * @param id         the window id
         * @param day        Today, Tomorrow or the weekday
         * @param event      sunrise, sunset, astro or aurora
         * @param time       the event time, or null
         * @param verdict    the window's served verdict, or null
         * @param bestRating the window's served best rating, or null
         * @param bestBet    the BEST BET pick, or null
         * @param alsoGood   the ALSO GOOD pick, or null
         */
        public WindowInfo(String id, String day, String event, String time, String verdict,
                Integer bestRating, PickInfo bestBet, PickInfo alsoGood) {
            this(id, day, event, time, verdict, bestRating, bestBet, alsoGood, null);
        }
    }

    /**
     * A later window as {@code list_windows} lists it: a sunrise or sunset that is not rated, whose tide
     * {@code look_up_place} can give (ask-place-tide plan T2-4).
     *
     * @param id    the window id, for {@code look_up_place} only
     * @param day   Today, Tomorrow or the weekday
     * @param event sunrise or sunset
     * @param time  the event's UK clock time
     */
    public record LaterWindowInfo(String id, String day, String event, String time) {
    }

    /**
     * The {@code list_windows} result.
     *
     * @param windows      the windows, chronological
     * @param laterWindows later sunrises and sunsets that are not rated, for tide questions; typed
     *                     conversations only, and omitted when empty so a user-less result keeps its bytes
     */
    public record ListWindowsResult(List<WindowInfo> windows,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<LaterWindowInfo> laterWindows) {
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
     * @param fierySky      the slot's served Fiery Sky potential out of 100; present only in a typed
     *                      conversation (never the Ready precompute) and only when the slot has one
     * @param goldenHour    the slot's served Golden Hour potential out of 100; same presence rule as
     *                      {@code fierySky}
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SpotInfo(long locationId, String name, String region, String windowId,
            int rating, String verdict, String tideState, Boolean tideAligned,
            String tideFitPhrase, String headline, String pick, Integer driveMinutes,
            Integer fierySky, Integer goldenHour) {
    }

    /**
     * The {@code rank_spots} result.
     *
     * @param spots            the spots, best first
     * @param note             why the list is empty, or how many places the neighbour guard withheld (never
     *                         which), or null
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
     * Arguments of {@code look_up_place}.
     *
     * @param name      the place's name, or part of it; required
     * @param windowIds windows to look in; null or empty means every solar window
     */
    public record LookUpPlaceArgs(String name, List<String> windowIds) {
    }

    /**
     * Why a looked-up place is, or is not, one PhotoCast recommends. The order here is the order the status is
     * decided in ({@link #classify}), so a slot that fails several tests reads as the first it fails. The rating
     * tests come before the region and guard ones: a 2-star place is below the floor whatever its region, and
     * "too few places were rated to recommend one" would wrongly imply it might otherwise be recommended.
     */
    enum PlaceStatus {
        /** No rating on Claude's scale. */
        UNRATED,
        /** A wood: its rating is about the light under the trees. */
        CANOPY,
        /** Rated below the game-on floor. */
        BELOW_GAME_ON,
        /** Rated far above the places beside it; never suggested. */
        NEIGHBOUR_OUTLIER,
        /** Its region has too few rated places to recommend one. */
        THIN_SAMPLE,
        /** Pick-eligible: the same test {@code rank_spots} applies. */
        PICK
    }

    /**
     * One place at one window as {@code look_up_place} returns it. The rating, verdict, headline and tide are the
     * served figures; {@code status} says whether PhotoCast recommends the place.
     *
     * @param locationId    the location id
     * @param name          the location name
     * @param region        the region name
     * @param windowId      the window id
     * @param day           Today, Tomorrow or the weekday
     * @param event         sunrise or sunset
     * @param rating        the served rating, or null when it is absent or off the 1-5 scale
     * @param verdict       the slot's served verdict, or null
     * @param headline      the served headline, at most {@value AskTools#HEADLINE_CAP} characters, or null
     * @param tideState     the served tide state at the event; null for an inland place
     * @param tideAligned   whether the location's own wanted water is the water at the event; null inland
     * @param tideFitPhrase the served tide-fit sentence, or null
     * @param status        a {@link PlaceStatus} name; its words are in the result's {@code reasons}
     * @param matchedAlias  the other name the reader's words matched when the place's own name did not, or null
     * @param detail        the forecast's own reason, only on the rows of the one place the answer is about; null
     *                      otherwise
     * @param nearestTide   the nearest high or low water and its offset from the light (for example
     *                      {@code HW 06:42 · 40m after sunrise}), only on a tide-miss row of the one place the
     *                      answer is about, at most {@value AskTools#NEAREST_TIDE_CAP} characters; of either kind,
     *                      so it never says when the tide would suit; null otherwise
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PlaceInfo(long locationId, String name, String region, String windowId, String day,
            String event, Integer rating, String verdict, String headline, String tideState,
            Boolean tideAligned, String tideFitPhrase, String status, String matchedAlias,
            PlaceDetail detail, String nearestTide) {

        /**
         * A place row with no nearest-tide phrase.
         *
         * @param locationId    the location id
         * @param name          the location name
         * @param region        the region name
         * @param windowId      the window id
         * @param day           Today, Tomorrow or the weekday
         * @param event         sunrise or sunset
         * @param rating        the served rating, or null
         * @param verdict       the served verdict, or null
         * @param headline      the served headline, or null
         * @param tideState     the served tide state, or null
         * @param tideAligned   whether the wanted water is the water at the event, or null
         * @param tideFitPhrase the served tide-fit sentence, or null
         * @param status        a {@link PlaceStatus} name
         * @param matchedAlias  the other name the reader's words matched, or null
         * @param detail        the forecast's own reason, or null
         */
        public PlaceInfo(long locationId, String name, String region, String windowId, String day,
                String event, Integer rating, String verdict, String headline, String tideState,
                Boolean tideAligned, String tideFitPhrase, String status, String matchedAlias,
                PlaceDetail detail) {
            this(locationId, name, region, windowId, day, event, rating, verdict, headline, tideState,
                    tideAligned, tideFitPhrase, status, matchedAlias, detail, null);
        }

        /**
         * A place row with no detail.
         *
         * @param locationId    the location id
         * @param name          the location name
         * @param region        the region name
         * @param windowId      the window id
         * @param day           Today, Tomorrow or the weekday
         * @param event         sunrise or sunset
         * @param rating        the served rating, or null
         * @param verdict       the served verdict, or null
         * @param headline      the served headline, or null
         * @param tideState     the served tide state, or null
         * @param tideAligned   whether the wanted water is the water at the event, or null
         * @param tideFitPhrase the served tide-fit sentence, or null
         * @param status        a {@link PlaceStatus} name
         * @param matchedAlias  the other name the reader's words matched, or null
         */
        public PlaceInfo(long locationId, String name, String region, String windowId, String day,
                String event, Integer rating, String verdict, String headline, String tideState,
                Boolean tideAligned, String tideFitPhrase, String status, String matchedAlias) {
            this(locationId, name, region, windowId, day, event, rating, verdict, headline, tideState,
                    tideAligned, tideFitPhrase, status, matchedAlias, null);
        }

        /**
         * A place matched by its own name.
         *
         * @param locationId    the location id
         * @param name          the location name
         * @param region        the region name
         * @param windowId      the window id
         * @param day           Today, Tomorrow or the weekday
         * @param event         sunrise or sunset
         * @param rating        the served rating, or null
         * @param verdict       the served verdict, or null
         * @param headline      the served headline, or null
         * @param tideState     the served tide state, or null
         * @param tideAligned   whether the wanted water is the water at the event, or null
         * @param tideFitPhrase the served tide-fit sentence, or null
         * @param status        a {@link PlaceStatus} name
         */
        public PlaceInfo(long locationId, String name, String region, String windowId, String day,
                String event, Integer rating, String verdict, String headline, String tideState,
                Boolean tideAligned, String tideFitPhrase, String status) {
            this(locationId, name, region, windowId, day, event, rating, verdict, headline, tideState,
                    tideAligned, tideFitPhrase, status, null);
        }
    }

    /**
     * The forecast's own reason for a looked-up rating (ask-place-explain plan E1): served evaluation fields only,
     * never raw weather figures.
     *
     * @param summary    the sky prose, quote-safe and at most {@value AskTools#SUMMARY_CAP} characters, or null
     * @param skyRating  the sky's own score before the tide is combined in, or null
     * @param fierySky   the Fiery Sky potential out of 100, or null
     * @param goldenHour the Golden Hour potential out of 100, or null
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PlaceDetail(String summary, Integer skyRating, Integer fierySky, Integer goldenHour) {
    }

    /**
     * The {@code look_up_place} result.
     *
     * @param places  the matching places, one entry per (place, window), at most {@value AskTools#MAX_PLACE_ROWS}
     * @param reasons our fixed words for each status present in {@code places} that has any (not BELOW_GAME_ON or
     *                PICK), said once here rather than on every row; null when none
     * @param note    why the list is empty, or what was left out, or null
     * @param area    the named area the name matched, listing its places for each window; null unless a named
     *                area won (an exact place match always beats one)
     * @param closestTo   the reader's own spelling when the result is for the closest name to it (E2); null
     *                    otherwise
     * @param suggestions up to {@value AskTools#MAX_SPELLING_SUGGESTIONS} in-scope place names the reader's
     *                    spelling might mean, when no one name is clearly closest; null otherwise
     * @param laterTides  the first place's tide at each requested later window (not rated), at most
     *                    {@value AskTools#MAX_LATER_TIDE_ROWS}, chronological; null when none
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LookUpPlaceResult(List<PlaceInfo> places, Map<String, String> reasons, String note,
            AreaInfo area, String closestTo, List<String> suggestions, List<LaterTideInfo> laterTides) {

        /**
         * A result with no later tides.
         *
         * @param places      the places
         * @param reasons     our words for each status present, or null
         * @param note        the note, or null
         * @param area        the named area, or null
         * @param closestTo   the reader's own spelling when this is a correction, or null
         * @param suggestions the names the spelling might mean, or null
         */
        public LookUpPlaceResult(List<PlaceInfo> places, Map<String, String> reasons, String note, AreaInfo area,
                String closestTo, List<String> suggestions) {
            this(places, reasons, note, area, closestTo, suggestions, null);
        }

        /**
         * A result that is not a spelling correction.
         *
         * @param places  the places
         * @param reasons our words for each status present, or null
         * @param note    the note, or null
         * @param area    the named area, or null
         */
        public LookUpPlaceResult(List<PlaceInfo> places, Map<String, String> reasons, String note, AreaInfo area) {
            this(places, reasons, note, area, null, null);
        }

        /**
         * A result with no named area.
         *
         * @param places  the places
         * @param reasons our words for each status present, or null
         * @param note    the note, or null
         */
        public LookUpPlaceResult(List<PlaceInfo> places, Map<String, String> reasons, String note) {
            this(places, reasons, note, null, null, null);
        }

        /**
         * A result with no reasons.
         *
         * @param places the places
         * @param note   the note, or null
         */
        public LookUpPlaceResult(List<PlaceInfo> places, String note) {
            this(places, null, note, null, null, null);
        }
    }

    /**
     * One place's tide at a later window as {@code look_up_place} returns it: the tide only, never a rating.
     *
     * @param windowId      the later window's id
     * @param day           Today, Tomorrow or the weekday
     * @param event         sunrise or sunset
     * @param tideState     the served tide state at the event
     * @param tideAligned   whether the location's own wanted water is the water at the event
     * @param tideFitPhrase the served tide-fit sentence, or null
     * @param nearestTide   the nearest high or low water and its offset from the light, only on a miss, or null
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LaterTideInfo(String windowId, String day, String event, String tideState, boolean tideAligned,
            String tideFitPhrase, String nearestTide) {
    }

    /**
     * A named area as {@code look_up_place} returns it: how its places rate in each window.
     *
     * @param name    the named area's name
     * @param places  how many of its places are in the question's regions and have a forecast in the windows
     * @param windows the windows, at most {@value AskTools#MAX_AREA_WINDOWS}
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AreaInfo(String name, int places, List<AreaWindow> windows) {
    }

    /**
     * One window of a named area.
     *
     * @param windowId         the window id
     * @param day              Today, Tomorrow or the weekday
     * @param event            sunrise or sunset
     * @param rated            how many of the named area's places in scope carry a rating (a wood's excluded)
     * @param fourStarOrBetter how many of those are rated {@value AskSnapshot.Spread#GAME_ON_FROM} or more
     * @param places           the best of them, at most {@value AskTools#MAX_AREA_PLACES}
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AreaWindow(String windowId, String day, String event, int rated, int fourStarOrBetter,
            List<AreaPlace> places) {
    }

    /**
     * One place of a named area at one window.
     *
     * @param locationId the location id
     * @param name       the location name
     * @param rating     the served rating, or null when it is absent or off the 1-5 scale
     * @param status     a {@link PlaceStatus} name; its words are in the result's {@code reasons}
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AreaPlace(long locationId, String name, Integer rating, String status) {
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
     * (the served figure with neighbour outliers left out, so it does not advertise a rating only an
     * outlier holds), the window's rating distribution over the question's scope
     * ({@link AskSnapshot.Spread}) and the forecast's BEST BET / ALSO GOOD pick when it is in scope.
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
        // Typed conversations only: the user-less (Ready) result never mentions a later window.
        List<LaterWindowInfo> later = user.hasUser() ? snapshot.laterWindows().stream()
                .map(w -> new LaterWindowInfo(w.id(), dayWord(w.date()),
                        w.targetType().name().toLowerCase(Locale.ROOT), AskClock.londonHHmm(w.eventTime())))
                .toList() : List.of();
        return finish("list_windows", new ListWindowsResult(windows, later), () -> {
            if (auroraListed) {
                uncacheable = true;
            }
            // Only a result the cap accepted was seen by the model, so only its windows are carried.
            windows.stream().filter(w -> w.spread() != null).forEach(w -> listedWindows.add(w.id()));
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
        RankSpotsArgs a = args == null ? new RankSpotsArgs(null, null, null, null, null, null, null, null)
                : args;
        int limit = clamp(a.limit(), DEFAULT_LIMIT, MAX_SPOTS);

        List<AskSnapshot.Window> windows = snapshot.windows();
        boolean windowsNamed = a.windowIds() != null && !a.windowIds().isEmpty();
        if (windowsNamed) {
            List<AskSnapshot.Window> picked = new ArrayList<>();
            for (String id : a.windowIds()) {
                AskSnapshot.Window w = snapshot.window(id).orElse(null);
                if (w == null) {
                    if (snapshot.laterWindow(id).isPresent()) {
                        return fail("rank_spots", cap(id, 40) + " is not rated: use look_up_place for its tide.");
                    }
                    return fail("rank_spots", "Unknown window id '" + id
                            + "'. Call list_windows for the valid ids.");
                }
                picked.add(w);
            }
            windows = picked;
        }
        if (windowBound != null) {
            // A Ready pick question may only pick from its own offer's windows (ReadyRelevance.admitsPick),
            // so the tool ranks only those. Ids outside the bound are dropped, not refused: the prompt
            // names a superset of the bound, so a model passing the ids it was given must still be served.
            List<AskSnapshot.Window> inBound = windows.stream()
                    .filter(w -> windowBound.contains(w.id())).toList();
            if (inBound.isEmpty() && windowsNamed) {
                return fail("rank_spots", "None of those windows has a place this question can pick."
                        + " Rank within: " + String.join(", ", windowBound) + ".");
            }
            windows = inBound;
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

        Set<Long> areaIds = null;
        if (a.areaName() != null && !a.areaName().isBlank()) {
            // The Ready test is "no user" (ClaudeAskEngine); a named area is a typed-conversation filter only.
            if (!user.hasUser()) {
                return fail("rank_spots", "areaName is not available in this conversation.");
            }
            AskSnapshot.Area area = matchArea(snapshot.areas(), placeWords(a.areaName()));
            if (area == null) {
                String known = snapshot.areas().isEmpty() ? "none"
                        : String.join(", ", snapshot.areas().stream().map(AskSnapshot.Area::name).toList());
                return fail("rank_spots", "Unknown named area '" + cap(a.areaName().strip(), 40) + "'. Known: "
                        + cap(known, KNOWN_AREAS_CAP) + ".");
            }
            areaIds = area.locationIds();
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
                if ((regionFilter.isEmpty() || regionFilter.contains(c.region().name()))
                        && (areaIds == null || areaIds.contains(c.slot().locationId()))) {
                    pool.add(c);
                }
            }
        }
        String heldBackNote = heldBackNote(windows, regionFilter, areaIds);
        if (pool.isEmpty()) {
            return finish("rank_spots", new RankSpotsResult(List.of(),
                    joinNotes(NOTHING_ELIGIBLE_NOTE, heldBackNote)), () -> { });
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
        return finish("rank_spots", new RankSpotsResult(spots, joinNotes(note, heldBackNote), radius), () -> {
            for (SpotInfo s : spots) {
                pairs.add(new AskEvidence.Pair(s.locationId(), s.windowId()));
            }
        });
    }

    /**
     * The note {@code rank_spots} adds when the neighbour guard withheld places from the windows and regions it
     * searched: how many, never which. Counted over the distinct locations in scope that are refused for the mark
     * alone ({@link AskSnapshot#isHeldBack}); null when there are none. A named-area filter ({@code areaIds}, null
     * for none) counts only that area's members. A tool-result note, so a fresh question
     * about such a place is not told it is simply "not in the forecast", and no name, id or star is given.
     */
    private String heldBackNote(List<AskSnapshot.Window> windows, Set<String> regionFilter, Set<Long> areaIds) {
        Set<Long> held = new java.util.HashSet<>();
        for (AskSnapshot.Window w : windows) {
            for (AskSnapshot.Region r : w.regions()) {
                if (!scope.contains(r.name()) || !(regionFilter.isEmpty() || regionFilter.contains(r.name()))) {
                    continue;
                }
                for (AskSnapshot.Slot s : r.slots()) {
                    if (AskSnapshot.isHeldBack(r, s) && (areaIds == null || areaIds.contains(s.locationId()))) {
                        held.add(s.locationId());
                    }
                }
            }
        }
        if (held.isEmpty()) {
            return null;
        }
        return held.size() == 1
                ? "1 place in these windows is rated well above the places beside it and is not offered."
                : held.size() + " places in these windows are rated well above the places beside them and are "
                        + "not offered.";
    }

    private static String joinNotes(String first, String second) {
        if (first == null) {
            return second;
        }
        return second == null ? first : first + " " + second;
    }

    /**
     * {@code look_up_place}: one named place's served rating at each requested window, whether or not PhotoCast
     * would recommend it (look-up plan §4.1). An explanation, never a pick: it adds <em>nothing</em> to the
     * conversation's evidence pairs, so the validator drops any pick made from it, and a place the model wants
     * to recommend must come from {@code rank_spots}. It answers only for places in the question's scope.
     *
     * @param args the place's name and the optional windows
     * @return the places, or an error result for a missing name or an unknown window id
     */
    public AskToolResult<LookUpPlaceResult> lookUpPlace(LookUpPlaceArgs args) {
        List<String> wanted = args == null || args.name() == null ? List.of() : placeWords(args.name());
        if (wanted.isEmpty()) {
            return fail(LOOK_UP, "name is required.");
        }
        List<AskSnapshot.Window> windows = snapshot.windows();
        List<AskSnapshot.LaterWindow> later = List.of();
        if (args.windowIds() != null && !args.windowIds().isEmpty()) {
            List<AskSnapshot.Window> picked = new ArrayList<>();
            List<AskSnapshot.LaterWindow> pickedLater = new ArrayList<>();
            for (String id : args.windowIds()) {
                AskSnapshot.Window w = snapshot.window(id).orElse(null);
                AskSnapshot.LaterWindow l = w == null ? snapshot.laterWindow(id).orElse(null) : null;
                if (w == null && l == null) {
                    return fail(LOOK_UP, "Unknown window id '" + cap(id, 40)
                            + "'. Call list_windows for the valid ids.");
                }
                if (w != null && !picked.contains(w)) {
                    picked.add(w);
                }
                if (l != null && !pickedLater.contains(l)) {
                    pickedLater.add(l);
                }
            }
            windows = picked;
            later = pickedLater.stream().sorted(Comparator.comparing(AskSnapshot.LaterWindow::date)
                    .thenComparing(AskSnapshot.LaterWindow::targetType)).toList();
        }

        Attempt first = attempt(wanted, windows, later, null);
        if (first.result() != null) {
            return first.result();
        }
        return closestSpelling(args.name(), wanted, windows, later);
    }

    /** What one exact-and-area pass found: the result, or null when it found nothing in the question's scope. */
    private record Attempt(AskToolResult<LookUpPlaceResult> result, boolean outOfScope) {
    }

    /**
     * The exact pass then the area pass of {@code look_up_place} over the given windows (look-up plan §4.1).
     * {@code closestTo} is the reader's own spelling when the words are a correction of it, else null.
     */
    private Attempt attempt(List<String> wanted, List<AskSnapshot.Window> windows,
            List<AskSnapshot.LaterWindow> later, String closestTo) {
        // Matches by location id, in first-seen order; the same place in several windows is one match.
        Map<Long, PlaceMatch> inScope = new LinkedHashMap<>();
        boolean outOfScope = false;
        for (AskSnapshot.Window w : windows) {
            for (AskSnapshot.Region r : w.regions()) {
                boolean visible = scope.contains(r.name());
                for (AskSnapshot.Slot slot : r.slots()) {
                    if (slot.locationId() == null || slot.name() == null) {
                        continue;
                    }
                    NameMatch found = matchPlace(slot.name(),
                            snapshot.locationAliases().getOrDefault(slot.locationId(), List.of()), wanted);
                    if (found == null) {
                        continue;
                    }
                    if (!visible) {
                        outOfScope = true;
                        continue;
                    }
                    PlaceMatch match = inScope.computeIfAbsent(slot.locationId(),
                            id -> new PlaceMatch(slot.name()));
                    match.exact |= found.exact();
                    match.matchedAlias = found.matchedAlias();
                    match.rows.add(new PlaceRow(w, r, slot));
                }
            }
        }
        // The requested later windows' tides, merged by location id into the same match (ask-place-tide plan T2-7).
        for (AskSnapshot.LaterWindow lw : later) {
            for (AskSnapshot.LaterTide tide : lw.tides()) {
                NameMatch found = matchPlace(tide.name(),
                        snapshot.locationAliases().getOrDefault(tide.locationId(), List.of()), wanted);
                if (found == null) {
                    continue;
                }
                if (!scope.contains(tide.region())) {
                    outOfScope = true;
                    continue;
                }
                PlaceMatch match = inScope.computeIfAbsent(tide.locationId(), id -> new PlaceMatch(tide.name()));
                match.exact |= found.exact();
                match.matchedAlias = found.matchedAlias();
                if (tide.tideState() != null) {
                    match.laterRows.add(new LaterRow(lw, tide));
                }
            }
        }
        // An exact place match, by name or alias, always beats a named area, so a place is never hidden by one.
        boolean exactPlace = inScope.values().stream().anyMatch(m -> m.exact);
        List<AskSnapshot.Area> matchedAreas = exactPlace ? List.of() : matchingAreas(snapshot.areas(), wanted);
        if (!matchedAreas.isEmpty()) {
            return new Attempt(namedAreaResult(matchedAreas.getFirst(), matchedAreas.subList(1, matchedAreas.size()),
                    windows, inScope, closestTo), outOfScope);
        }
        if (inScope.isEmpty()) {
            return new Attempt(null, outOfScope);
        }

        List<Map.Entry<Long, PlaceMatch>> ordered = inScope.entrySet().stream()
                .sorted(Comparator.<Map.Entry<Long, PlaceMatch>>comparingInt(e -> e.getValue().exact ? 0 : 1)
                        .thenComparing(e -> e.getValue().name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparingLong(Map.Entry::getKey))
                .toList();
        // The exact match's rows first, then the others', each place's in window order; then the row cap.
        List<PlaceInfo> all = new ArrayList<>();
        List<PlaceStatus> statuses = new ArrayList<>();
        List<AskSnapshot.Region> regions = new ArrayList<>();
        // The forecast's reason goes only on the rows of the one place the answer is about: the first when it is
        // exact, or when it is the only place matched (ask-place-explain plan X5).
        boolean aboutOnePlace = ordered.getFirst().getValue().exact || ordered.size() == 1;
        boolean anyDetail = false;
        int detailRows = 0;
        boolean firstPlace = true;
        for (Map.Entry<Long, PlaceMatch> entry : ordered.stream().limit(MAX_PLACE_MATCHES).toList()) {
            for (PlaceRow row : entry.getValue().rows) {
                PlaceStatus status = classify(row.region(), row.slot());
                PlaceDetail detail = null;
                String nearestTide = null;
                if (firstPlace && aboutOnePlace && detailRows < MAX_DETAIL_ROWS) {
                    detail = detail(row.slot(), status);
                    if (detail != null) {
                        detailRows++;
                        anyDetail = true;
                    }
                }
                // The nearest high or low water goes only on a tide miss of the one place the answer is about: a
                // match's fit sentence already carries it (ask-place-tide plan T-2).
                if (firstPlace && aboutOnePlace && row.slot().coastal() && !row.slot().tideAligned()
                        && row.slot().nearestTide() != null && !row.slot().nearestTide().isBlank()) {
                    nearestTide = cap(row.slot().nearestTide(), NEAREST_TIDE_CAP);
                    anyDetail = true;
                }
                all.add(placeInfo(entry.getKey(), row, status, entry.getValue().matchedAlias, detail, nearestTide));
                statuses.add(status);
                regions.add(row.region());
            }
            firstPlace = false;
        }
        // The first place's later tides, chronological, at most MAX_LATER_TIDE_ROWS; they are kept first, so the
        // rated rows are cut to leave room (ask-place-tide plan T2-9).
        List<LaterTideInfo> laterTides = aboutOnePlace ? laterTideInfos(ordered.getFirst().getValue()) : List.of();
        int rowCap = anyDetail || !laterTides.isEmpty() ? MAX_PLACE_ROWS_WITH_DETAIL : MAX_PLACE_ROWS;
        rowCap -= laterTides.size();
        Map<String, String> reasons = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(all.size(), rowCap); i++) {
            String reason = statusReason(statuses.get(i), regions.get(i));
            if (reason != null) {
                reasons.putIfAbsent(statuses.get(i).name(), reason);
            }
        }
        List<PlaceInfo> places = all.stream().limit(rowCap).toList();
        String note = null;
        int more = ordered.size() - MAX_PLACE_MATCHES;
        if (more > 0) {
            note = more + " more places match; use a longer name.";
        }
        if (all.size() > rowCap) {
            note = joinNotes(note, "Showing " + rowCap + " of " + all.size()
                    + " place-windows; pass windowIds to narrow.");
        }
        // Deliberately nothing recorded as evidence: a looked-up place is explained, never offered as a pick.
        // A later window becomes rated when its sunrise passes and the horizon shifts, so an answer built from a
        // later tide is never stored (ask-place-tide plan T2-10).
        return new Attempt(finish(LOOK_UP, new LookUpPlaceResult(places, reasons.isEmpty() ? null : reasons, note,
                null, closestTo, null, laterTides.isEmpty() ? null : laterTides), () -> {
                    if (!laterTides.isEmpty()) {
                        uncacheable = true;
                    }
                }), outOfScope);
    }

    /** The later tide rows of a matched place: at most {@value #MAX_LATER_TIDE_ROWS}, in window order. */
    private List<LaterTideInfo> laterTideInfos(PlaceMatch match) {
        List<LaterTideInfo> out = new ArrayList<>();
        for (LaterRow row : match.laterRows) {
            if (out.size() >= MAX_LATER_TIDE_ROWS) {
                break;
            }
            AskSnapshot.LaterTide t = row.tide();
            AskSnapshot.LaterWindow w = row.window();
            String nearest = t.coastal() && !t.tideAligned() && t.nearestTide() != null
                    && !t.nearestTide().isBlank() ? cap(t.nearestTide(), NEAREST_TIDE_CAP) : null;
            out.add(new LaterTideInfo(w.id(), dayWord(w.date()), w.targetType().name().toLowerCase(Locale.ROOT),
                    t.tideState(), t.tideAligned(), t.tideFitPhrase(), nearest));
        }
        return out;
    }

    /** Where a set of words matched a place anywhere in the snapshot. */
    private enum Reach { NONE, OUT_OF_SCOPE_ONLY, IN_SCOPE }

    /**
     * Whether the words match any place or alias in <em>any</em> window of the snapshot, requested or not, and
     * whether any such place is in the question's scope.
     */
    private Reach reach(List<String> wanted, List<AskSnapshot.LaterWindow> later) {
        Set<Long> seen = new HashSet<>();
        boolean outOfScope = false;
        for (AskSnapshot.LaterWindow lw : later) {
            for (AskSnapshot.LaterTide tide : lw.tides()) {
                if (!seen.add(tide.locationId()) || matchPlace(tide.name(),
                        snapshot.locationAliases().getOrDefault(tide.locationId(), List.of()), wanted) == null) {
                    continue;
                }
                if (scope.contains(tide.region())) {
                    return Reach.IN_SCOPE;
                }
                outOfScope = true;
            }
        }
        for (AskSnapshot.Window w : snapshot.windows()) {
            for (AskSnapshot.Region r : w.regions()) {
                boolean visible = scope.contains(r.name());
                for (AskSnapshot.Slot slot : r.slots()) {
                    if (slot.locationId() == null || slot.name() == null || !seen.add(slot.locationId())
                            || matchPlace(slot.name(), snapshot.locationAliases()
                                    .getOrDefault(slot.locationId(), List.of()), wanted) == null) {
                        continue;
                    }
                    if (visible) {
                        return Reach.IN_SCOPE;
                    }
                    outOfScope = true;
                }
            }
        }
        return outOfScope ? Reach.OUT_OF_SCOPE_ONLY : Reach.NONE;
    }

    /** A result with no place, only a note. */
    private AskToolResult<LookUpPlaceResult> noPlace(String note) {
        return finish(LOOK_UP, new LookUpPlaceResult(List.of(), note), () -> { });
    }

    /** One spelling in the snapshot near the reader's: its distance, and the in-scope places that carry it. */
    private static final class Spelling {
        private final int distance;
        private final Set<String> placeNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        Spelling(int distance) {
            this.distance = distance;
        }
    }

    /**
     * The fallback when the words matched nothing exactly (ask-place-explain plan §5.2, E2): a name the reader
     * misspelt is corrected and looked up again, once, with the corrected words.
     *
     * <p>Runs only when no place or alias anywhere in the snapshot carries the words (X9), so a correct spelling is
     * never "corrected" into a different place. A clear winner is looked up with its words and the result carries
     * {@code closestTo}; a tie returns the in-scope names as {@code suggestions}; an out-of-scope winner returns
     * the scope note and no name.
     */
    private AskToolResult<LookUpPlaceResult> closestSpelling(String given, List<String> wanted,
            List<AskSnapshot.Window> windows, List<AskSnapshot.LaterWindow> later) {
        // A later window's names are seen only when a later window was asked for, so a call without one is unchanged.
        List<AskSnapshot.LaterWindow> seenLater = later.isEmpty() ? List.of() : snapshot.laterWindows();
        Reach exact = reach(wanted, seenLater);
        if (exact != Reach.NONE) {
            return noPlace(exact == Reach.OUT_OF_SCOPE_ONLY ? PLACE_OUT_OF_SCOPE_NOTE : NO_SUCH_PLACE_NOTE);
        }
        int allowed = PlaceNames.allowedEdits(String.join("", wanted).length());
        Map<String, Spelling> spellings = allowed == 0 ? Map.of() : nearSpellings(wanted, allowed, seenLater);
        if (spellings.isEmpty()) {
            return noPlace(NO_SUCH_PLACE_NOTE);
        }
        int least = spellings.values().stream().mapToInt(s -> s.distance).min().orElseThrow();
        // Two edits or more are common enough that a near neighbour is a real doubt; one edit is not.
        int doubtUpTo = least >= 2 ? least + 1 : least;
        List<Map.Entry<String, Spelling>> contenders = spellings.entrySet().stream()
                .filter(e -> e.getValue().distance <= doubtUpTo).toList();
        if (contenders.size() == 1) {
            List<String> corrected = List.of(contenders.getFirst().getKey().split(" "));
            String closestTo = cap(given.strip().replace('"', '\'').replace("\\", ""), CLOSEST_TO_CAP);
            Attempt again = attempt(corrected, windows, later, closestTo);
            if (again.result() != null) {
                return again.result();
            }
            // Found, but not in the windows asked about; a place found only out of scope is never confirmed.
            Reach found = reach(corrected, seenLater);
            if (found == Reach.IN_SCOPE) {
                return finish(LOOK_UP, new LookUpPlaceResult(List.of(), null, NO_SUCH_PLACE_NOTE, null, closestTo,
                        null), () -> { });
            }
            return noPlace(found == Reach.OUT_OF_SCOPE_ONLY ? PLACE_OUT_OF_SCOPE_NOTE : NO_SUCH_PLACE_NOTE);
        }
        Map<String, Integer> nearest = new HashMap<>();
        for (Map.Entry<String, Spelling> contender : contenders) {
            for (String place : contender.getValue().placeNames) {
                nearest.merge(place, contender.getValue().distance, Math::min);
            }
        }
        List<String> suggestions = nearest.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER)))
                .map(Map.Entry::getKey).limit(MAX_SPELLING_SUGGESTIONS).toList();
        if (suggestions.isEmpty()) {
            return noPlace(PLACE_OUT_OF_SCOPE_NOTE);
        }
        return finish(LOOK_UP, new LookUpPlaceResult(List.of(), null, SPELLING_TIE_NOTE, null, null, suggestions),
                () -> { });
    }

    /**
     * Every distinct spelling in the snapshot (place names, aliases, area names and aliases, in scope or not)
     * within {@code allowed} edits of the query, keyed by its text: the matched leading run, joined with spaces.
     */
    private Map<String, Spelling> nearSpellings(List<String> wanted, int allowed,
            List<AskSnapshot.LaterWindow> later) {
        Map<String, Spelling> out = new LinkedHashMap<>();
        Set<Long> seen = new HashSet<>();
        for (AskSnapshot.LaterWindow lw : later) {
            for (AskSnapshot.LaterTide tide : lw.tides()) {
                if (!seen.add(tide.locationId())) {
                    continue;
                }
                String suggestable = scope.contains(tide.region()) ? tide.name() : null;
                addSpelling(out, tide.name(), wanted, allowed, suggestable);
                for (String alias : snapshot.locationAliases().getOrDefault(tide.locationId(), List.of())) {
                    addSpelling(out, alias, wanted, allowed, suggestable);
                }
            }
        }
        for (AskSnapshot.Window w : snapshot.windows()) {
            for (AskSnapshot.Region r : w.regions()) {
                boolean visible = scope.contains(r.name());
                for (AskSnapshot.Slot slot : r.slots()) {
                    if (slot.locationId() == null || slot.name() == null || !seen.add(slot.locationId())) {
                        continue;
                    }
                    String suggestable = visible ? slot.name() : null;
                    addSpelling(out, slot.name(), wanted, allowed, suggestable);
                    for (String alias : snapshot.locationAliases().getOrDefault(slot.locationId(), List.of())) {
                        addSpelling(out, alias, wanted, allowed, suggestable);
                    }
                }
            }
        }
        for (AskSnapshot.Area area : snapshot.areas()) {
            addSpelling(out, area.name(), wanted, allowed, null);
            for (String alias : area.aliases()) {
                addSpelling(out, alias, wanted, allowed, null);
            }
        }
        return out;
    }

    /** Records {@code name}'s closest leading run when it is within {@code allowed} edits of the query. */
    private static void addSpelling(Map<String, Spelling> out, String name, List<String> wanted, int allowed,
            String placeName) {
        List<String> words = placeWords(name);
        PlaceNames.PrefixMatch match = PlaceNames.prefixMatch(words, wanted);
        if (match.distance() > allowed) {
            return;
        }
        Spelling spelling = out.computeIfAbsent(String.join(" ", words.subList(0, match.words())),
                text -> new Spelling(match.distance()));
        if (placeName != null) {
            spelling.placeNames.add(placeName);
        }
    }

    /** The places one name matched: whether any spelling was exact, and each (window, region, slot) it sits in. */
    private static final class PlaceMatch {
        private final String name;
        private boolean exact;
        private String matchedAlias;
        private final List<PlaceRow> rows = new ArrayList<>();
        private final List<LaterRow> laterRows = new ArrayList<>();

        PlaceMatch(String name) {
            this.name = name;
        }
    }

    private record PlaceRow(AskSnapshot.Window window, AskSnapshot.Region region, AskSnapshot.Slot slot) {
    }

    /** One place's tide at one requested later window. */
    private record LaterRow(AskSnapshot.LaterWindow window, AskSnapshot.LaterTide tide) {
    }

    /**
     * The forecast's reason for a row, or null when the row carries none: an unrated or neighbour-outlier row, a
     * row whose summary is the "not forecast" placeholder (it names Claude), or a row with no summary served. A wood
     * keeps its summary only, because its potentials and sky score read the opposite way round.
     */
    private static PlaceDetail detail(AskSnapshot.Slot s, PlaceStatus status) {
        if (status == PlaceStatus.UNRATED || status == PlaceStatus.NEIGHBOUR_OUTLIER
                || ForecastResultHandler.SKY_NOT_FORECAST_SUMMARY.equals(s.summary())) {
            return null;
        }
        // Quotes become apostrophes and backslashes go, so JSON escaping cannot grow the text past its cap.
        String summary = s.summary() == null ? null
                : cap(s.summary().replace('"', '\'').replace("\\", ""), SUMMARY_CAP);
        // The scores explain nothing without the reason they came with, and a row of them costs row budget.
        if (summary == null || summary.isBlank()) {
            return null;
        }
        if (status == PlaceStatus.CANOPY) {
            return new PlaceDetail(summary, null, null, null);
        }
        return new PlaceDetail(summary, s.skyRating(), s.fierySky(), s.goldenHour());
    }

    private PlaceInfo placeInfo(long locationId, PlaceRow row, PlaceStatus status, String matchedAlias,
            PlaceDetail detail, String nearestTide) {
        AskSnapshot.Slot s = row.slot();
        Integer rating = status == PlaceStatus.UNRATED ? null : s.rating();
        return new PlaceInfo(locationId, s.name(), row.region().name(), row.window().id(),
                dayWord(row.window().date()), row.window().targetType().name().toLowerCase(Locale.ROOT),
                rating, s.verdict() == null ? null : s.verdict().name(), cap(s.headline(), HEADLINE_CAP),
                s.tideState(), s.coastal() ? s.tideAligned() : null, s.tideFitPhrase(), status.name(),
                matchedAlias, detail, nearestTide);
    }

    /** One named-area member at one window: what is listed, and the region its reason is read from. */
    private record AreaMember(AreaPlace place, AskSnapshot.Region region) {
    }

    /**
     * The named-area branch of {@code look_up_place}: how the area's places rate in each of (at most four of) the
     * requested windows. An explanation, never evidence: nothing here can become a pick. Only the single
     * longest match is answered; {@code otherAreas} (the rest, in match order) are named in a note, so
     * "Swaledale or Wensleydale?" is not silently answered as Swaledale alone.
     */
    private AskToolResult<LookUpPlaceResult> namedAreaResult(AskSnapshot.Area area,
            List<AskSnapshot.Area> otherAreas, List<AskSnapshot.Window> requested,
            Map<Long, PlaceMatch> partialPlaces, String closestTo) {
        List<AskSnapshot.Window> shown = requested.stream().limit(MAX_AREA_WINDOWS).toList();
        List<AreaWindow> windows = new ArrayList<>();
        Map<String, String> reasons = new LinkedHashMap<>();
        Set<Long> inScopeMembers = new HashSet<>();
        boolean anyOutOfScope = false;
        boolean placesCut = false;
        for (AskSnapshot.Window w : shown) {
            List<AreaMember> members = new ArrayList<>();
            int rated = 0;
            int fourPlus = 0;
            for (AskSnapshot.Region r : w.regions()) {
                boolean visible = scope.contains(r.name());
                for (AskSnapshot.Slot slot : r.slots()) {
                    if (slot.locationId() == null || slot.name() == null
                            || !area.locationIds().contains(slot.locationId())) {
                        continue;
                    }
                    if (!visible) {
                        anyOutOfScope = true;
                        continue;
                    }
                    inScopeMembers.add(slot.locationId());
                    PlaceStatus status = classify(r, slot);
                    members.add(new AreaMember(new AreaPlace(slot.locationId(), slot.name(),
                            status == PlaceStatus.UNRATED ? null : slot.rating(), status.name()), r));
                    if (status != PlaceStatus.UNRATED && status != PlaceStatus.CANOPY) {
                        rated++;
                        fourPlus += slot.rating() >= AskSnapshot.Spread.GAME_ON_FROM ? 1 : 0;
                    }
                }
            }
            if (members.isEmpty()) {
                continue;
            }
            members.sort(Comparator.<AreaMember, Integer>comparing(m -> m.place().rating(),
                            Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(m -> m.place().name(), String.CASE_INSENSITIVE_ORDER)
                    .thenComparingLong(m -> m.place().locationId()));
            placesCut |= members.size() > MAX_AREA_PLACES;
            List<AreaMember> listed = members.stream().limit(MAX_AREA_PLACES).toList();
            for (AreaMember member : listed) {
                String reason = statusReason(PlaceStatus.valueOf(member.place().status()), member.region());
                if (reason != null) {
                    reasons.putIfAbsent(member.place().status(), reason);
                }
            }
            windows.add(new AreaWindow(w.id(), dayWord(w.date()), w.targetType().name().toLowerCase(Locale.ROOT),
                    rated, fourPlus, listed.stream().map(AreaMember::place).toList()));
        }
        String note = null;
        if (requested.size() > MAX_AREA_WINDOWS) {
            note = "Showing " + MAX_AREA_WINDOWS + " of " + requested.size()
                    + " windows; pass windowIds to narrow.";
        }
        if (placesCut) {
            note = joinNotes(note, AREA_PLACES_CUT_NOTE);
        }
        List<String> alsoMatched = partialPlaces.values().stream().map(m -> m.name)
                .sorted(String.CASE_INSENSITIVE_ORDER).limit(MAX_ALSO_MATCHED).toList();
        if (!alsoMatched.isEmpty()) {
            note = joinNotes(note, "Also matched places: " + String.join(", ", alsoMatched)
                    + "; ask about one by name.");
        }
        if (!otherAreas.isEmpty()) {
            note = joinNotes(note, "Also matched areas: "
                    + String.join(", ", otherAreas.stream().map(AskSnapshot.Area::name).limit(MAX_ALSO_AREAS)
                    .toList())
                    + "; this result is for " + area.name() + " only, so call look_up_place once per area.");
        }
        if (windows.isEmpty()) {
            note = joinNotes(note, anyOutOfScope ? AREA_OUT_OF_SCOPE_NOTE : AREA_NO_PLACES_NOTE);
        }
        note = joinNotes(note, AREA_SUGGEST_NOTE);
        AreaInfo info = new AreaInfo(area.name(), inScopeMembers.size(), windows);
        // Deliberately nothing recorded as evidence: a named area is explained, never offered as a pick.
        return finish(LOOK_UP, new LookUpPlaceResult(List.of(), reasons.isEmpty() ? null : reasons, note, info,
                closestTo, null), () -> { });
    }

    /**
     * Why PhotoCast does or does not recommend a slot, decided in the {@link PlaceStatus} order. Ends in a
     * check against {@link AskSnapshot#isPickEligible} and throws when the two disagree, so a pick rule added
     * there and missed here fails a test rather than quietly reading as {@code PICK}.
     *
     * @param region the slot's region
     * @param slot   the slot
     * @return the first status the slot earns
     */
    static PlaceStatus classify(AskSnapshot.Region region, AskSnapshot.Slot slot) {
        if (!RatingValidator.isInRange(slot.rating())) {
            return PlaceStatus.UNRATED;
        }
        if (slot.canopy()) {
            return PlaceStatus.CANOPY;
        }
        if (slot.rating() < AskSnapshot.MIN_PICK_RATING) {
            return PlaceStatus.BELOW_GAME_ON;
        }
        if (slot.neighbourOutlier()) {
            return PlaceStatus.NEIGHBOUR_OUTLIER;
        }
        if (!region.pickEligible()) {
            return PlaceStatus.THIN_SAMPLE;
        }
        if (AskSnapshot.isPickEligible(region, slot)) {
            return PlaceStatus.PICK;
        }
        throw new IllegalStateException("A slot refused by AskSnapshot.isPickEligible has no look_up_place status: "
                + slot);
    }

    private static String statusReason(PlaceStatus status, AskSnapshot.Region region) {
        return switch (status) {
            case UNRATED -> region.coverageReason() == null ? "Not rated for this window."
                    : "Not rated for this window. Its region was "
                            + (region.coverageReason() == CoverageReason.STOOD_DOWN
                                    ? "stood down for the weather." : "not scored.");
            case CANOPY -> "A woodland canopy rating: how good the light under the trees is, not the sky.";
            case NEIGHBOUR_OUTLIER ->
                    "Rated well above the places beside it, so PhotoCast is not suggesting it.";
            case THIN_SAMPLE -> "Too few places in this region were rated to recommend one.";
            case BELOW_GAME_ON, PICK -> null;
        };
    }

    /**
     * How a query matched one place: whether it is exact, and the other name that matched when the place's own
     * name did not.
     *
     * @param exact        whether the query's words equal the name's words or an alias's words
     * @param matchedAlias the alias that matched when the name did not, else null
     */
    record NameMatch(boolean exact, String matchedAlias) {
    }

    /**
     * Matches a query against a place's name and its other names, each as a contiguous run of whole words
     * ({@link #containsRun}). {@code matchedAlias} is set only when the name did not match: the alias whose words
     * equal the query if there is one, else the matching alias with the fewest characters, ties broken
     * case-insensitively alphabetically ({@link AskAreas#ALIAS_ORDER}), never by the order the aliases arrive in.
     *
     * @param name    the place's name
     * @param aliases the place's other names, in any order
     * @param wanted  the query's words
     * @return the match, or null when neither the name nor an alias matches
     */
    static NameMatch matchPlace(String name, List<String> aliases, List<String> wanted) {
        List<String> nameWords = placeWords(name);
        boolean nameMatches = containsRun(nameWords, wanted);
        boolean exact = nameWords.equals(wanted);
        String equalAlias = null;
        String shortest = null;
        for (String alias : aliases) {
            List<String> words = placeWords(alias);
            if (!containsRun(words, wanted)) {
                continue;
            }
            if (words.equals(wanted)) {
                exact = true;
                if (equalAlias == null || AskAreas.ALIAS_ORDER.compare(alias, equalAlias) < 0) {
                    equalAlias = alias;
                }
            }
            if (shortest == null || AskAreas.ALIAS_ORDER.compare(alias, shortest) < 0) {
                shortest = alias;
            }
        }
        if (!nameMatches && shortest == null) {
            return null;
        }
        String matched = nameMatches ? null : (equalAlias != null ? equalAlias : shortest);
        return new NameMatch(exact, matched);
    }

    /**
     * The named area a query names: the query's words contain the area's name or one alias as a contiguous run
     * ({@link #containsRun}, the reverse direction from places, so "along hadrians wall" matches). The longest
     * matched run wins, then name order. A query that is only part of a longer name matches nothing.
     *
     * @param areas  the named areas
     * @param wanted the query's words
     * @return the area, or null
     */
    static AskSnapshot.Area matchArea(List<AskSnapshot.Area> areas, List<String> wanted) {
        List<AskSnapshot.Area> matched = matchingAreas(areas, wanted);
        return matched.isEmpty() ? null : matched.getFirst();
    }

    /** An area and the words of the longest run of its name or aliases that a query contained. */
    private record AreaHit(AskSnapshot.Area area, List<String> run) {
    }

    /**
     * Every named area a query names, best first: the longest matched run, then name order. The first is what
     * {@link #matchArea} returns; the rest are what the "also matched areas" note lists, so a question naming
     * two areas ("Swaledale or Wensleydale") is not answered as the first alone without saying so.
     *
     * <p>An area whose matched words are only a part of the best match's words is not "also" matched: a
     * question for "Hadrian's Wall & Tynedale" also contains the words of the ordinary area "Hadrian's Wall",
     * and listing it would offer a second lookup for the same place.
     *
     * @param areas  the named areas
     * @param wanted the query's words
     * @return the matching areas, best first; empty when none matches
     */
    static List<AskSnapshot.Area> matchingAreas(List<AskSnapshot.Area> areas, List<String> wanted) {
        List<AreaHit> hits = new ArrayList<>();
        for (AskSnapshot.Area area : areas) {
            List<String> run = List.of();
            List<String> nameWords = placeWords(area.name());
            if (containsRun(wanted, nameWords)) {
                run = nameWords;
            }
            for (String alias : area.aliases()) {
                List<String> words = placeWords(alias);
                if (words.size() > run.size() && containsRun(wanted, words)) {
                    run = words;
                }
            }
            if (!run.isEmpty()) {
                hits.add(new AreaHit(area, run));
            }
        }
        hits.sort(Comparator.<AreaHit>comparingInt(hit -> hit.run().size()).reversed()
                .thenComparing(hit -> hit.area().name(), String.CASE_INSENSITIVE_ORDER));
        List<AskSnapshot.Area> matched = new ArrayList<>();
        for (AreaHit hit : hits) {
            if (matched.isEmpty() || !containsRun(hits.getFirst().run(), hit.run())) {
                matched.add(hit.area());
            }
        }
        return matched;
    }

    /**
     * A place name as words: lower case, apostrophes removed, every other non-letter-or-digit a gap. Not
     * {@link AskQuestionSanitiser}'s normaliser, whose filler-word list would drop the "the" of a place name.
     *
     * @param text a place name or a query
     * @return its words, possibly empty
     */
    static List<String> placeWords(String text) {
        return PlaceNames.words(text);
    }

    /**
     * Whether {@code words} holds {@code run} as a contiguous run of whole words.
     *
     * @param words the place's words
     * @param run   the query's words
     * @return true when the run appears
     */
    static boolean containsRun(List<String> words, List<String> run) {
        return PlaceNames.containsRun(words, run);
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
        List<AskSnapshot.Topic> kept = AskSnapshot.onePerKindAndDay(snapshot.hotTopics().stream()
                .filter(t -> types.isEmpty() || types.contains(AskEventType.key(t.type())))
                .filter(t -> t.inScope(scope))
                .toList()).stream()
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
     * or the type of a topic the entry merged in, on a date inside the entry's span) is not
     * repeated. A merged entry is offered as its host's event only.
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
        return new AskEvidence(pairs, events, trace.size(), AskEvidence.ThreadFact.NONE, listedWindows);
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
                w.bestRating(), best ? named : null, also ? named : null,
                AskSnapshot.Spread.of(w, scope));
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
                cap(s.headline(), HEADLINE_CAP), kind.map(Enum::name).orElse(null), minutes,
                user.hasUser() ? s.fierySky() : null, user.hasUser() ? s.goldenHour() : null);
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
     * {@code GeoUtils.distanceMiles}, the measure the retired Close-to-home panel applied to that
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
