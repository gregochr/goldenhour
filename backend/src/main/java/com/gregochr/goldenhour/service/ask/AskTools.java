package com.gregochr.goldenhour.service.ask;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.model.BriefingWindow;
import com.gregochr.goldenhour.service.DriveTimeResolver;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    /** The furthest ahead {@code get_coming_up} looks, in days. */
    public static final int MAX_COMING_UP_DAYS = 90;

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

    private static final String LIMIT_REACHED =
            "Tool output limit reached for this conversation. Answer now with what you have.";

    private static final ZoneId LONDON = ZoneId.of("Europe/London");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private static final Set<String> TIDE_STATES = Set.of("HIGH", "MID", "LOW");

    private final AskSnapshot snapshot;
    private final AskUserContext user;
    private final Set<String> scope;
    private final DriveTimeResolver driveTimes;
    private final ObjectMapper mapper;

    private final Set<AskEvidence.Pair> pairs = new LinkedHashSet<>();
    private final Set<AskEvidence.EventFact> events = new LinkedHashSet<>();
    private final List<ToolCall> trace = new ArrayList<>();
    private int charsUsed;
    private boolean personal;
    private Map<Long, Integer> driveMinutes;

    /**
     * Creates the tools for one conversation.
     *
     * @param snapshot   the served snapshot every tool reads
     * @param user       who is asking; a user-less context refuses {@code maxDriveMinutes}
     * @param scopeNames the region names the question is about, matched case-insensitively; null
     *                   or empty means every region
     * @param driveTimes the source of the asker's own drive times
     * @param mapper     serialises results for the model
     */
    public AskTools(AskSnapshot snapshot, AskUserContext user, Set<String> scopeNames,
            DriveTimeResolver driveTimes, ObjectMapper mapper) {
        this.snapshot = snapshot;
        this.user = user;
        this.scope = lowerCased(scopeNames);
        this.driveTimes = driveTimes;
        this.mapper = mapper;
    }

    // -- argument records -------------------------------------------------------------------

    /**
     * Arguments of {@code rank_spots}; every field is optional.
     *
     * @param windowIds       windows to rank within; null or empty means every window
     * @param regionNames     regions to rank within; null or empty means every region in scope
     * @param coastalOnly     true to keep only coastal spots
     * @param tideState       HIGH, MID or LOW: keep only spots whose served tide state matches
     * @param maxDriveMinutes keep only spots within this many minutes of the asker's home
     * @param limit           how many spots, at most {@value AskTools#MAX_SPOTS}
     */
    public record RankSpotsArgs(List<String> windowIds, List<String> regionNames,
            Boolean coastalOnly, String tideState, Integer maxDriveMinutes, Integer limit) {
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
     * @param days  how many days ahead, at most {@value AskTools#MAX_COMING_UP_DAYS}
     * @param limit how many entries, at most {@value AskTools#MAX_EVENTS}
     */
    public record ComingUpArgs(Integer days, Integer limit) {
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
     * @param spots the spots, best first
     * @param note  why the list is empty, or null
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RankSpotsResult(List<SpotInfo> spots, String note) {
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
    public AskToolResult listWindows() {
        List<WindowInfo> windows = snapshot.windows().stream().map(this::windowInfo).toList();
        return finish("list_windows", new ListWindowsResult(windows), () -> { });
    }

    /**
     * {@code rank_spots}: pick-eligible slots, best first (rating, then the forecast's own pick,
     * then tide preference met, then name).
     *
     * @param args the optional filters
     * @return the spots, or an error result for a bad argument
     */
    public AskToolResult rankSpots(RankSpotsArgs args) {
        RankSpotsArgs a = args == null ? new RankSpotsArgs(null, null, null, null, null, null)
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

        Set<String> regionFilter = new HashSet<>();
        if (a.regionNames() != null) {
            for (String name : a.regionNames()) {
                String canonical = canonicalRegion(name);
                if (canonical == null) {
                    return fail("rank_spots", "Unknown region '" + name
                            + "'. Region names appear in the list_windows and rank_spots results.");
                }
                if (!inScope(canonical)) {
                    return fail("rank_spots", "Region '" + canonical
                            + "' is outside the scope of this question.");
                }
                regionFilter.add(canonical.toLowerCase(Locale.ROOT));
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

        List<AskSnapshot.Candidate> pool = new ArrayList<>();
        for (AskSnapshot.Window w : windows) {
            // The same in-scope set the BEST anchor consults (AskSnapshot#candidates with a scope).
            for (AskSnapshot.Candidate c : snapshot.candidates(w, scope)) {
                if (regionFilter.isEmpty()
                        || regionFilter.contains(c.region().name().toLowerCase(Locale.ROOT))) {
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
        List<AskSnapshot.Candidate> kept = pool.stream()
                .filter(c -> !coastalOnly || c.slot().coastal())
                .filter(c -> wantedTide == null || wantedTide.equals(c.slot().tideState()))
                .filter(c -> maxDrive == null || withinDrive(minutes, c, maxDrive))
                .sorted(RANK)
                .limit(limit)
                .toList();

        List<SpotInfo> spots = kept.stream().map(c -> spotInfo(c, maxDrive == null ? null
                : minutes.get(c.slot().locationId()))).toList();
        String note = spots.isEmpty() ? NOTHING_MATCHES_NOTE : null;
        return finish("rank_spots", new RankSpotsResult(spots, note), () -> {
            for (SpotInfo s : spots) {
                pairs.add(new AskEvidence.Pair(s.locationId(), s.windowId()));
            }
        });
    }

    /**
     * {@code get_hot_topics}: the served hot topics, optionally of given types, limited to the
     * question's scope when a topic names regions.
     *
     * @param args the optional filters
     * @return the topics
     */
    public AskToolResult getHotTopics(HotTopicsArgs args) {
        HotTopicsArgs a = args == null ? new HotTopicsArgs(null, null) : args;
        int limit = clamp(a.limit(), DEFAULT_LIMIT, MAX_EVENTS);
        Set<String> types = upperCased(a.types());
        List<AskSnapshot.Topic> kept = snapshot.hotTopics().stream()
                .filter(t -> types.isEmpty() || types.contains(upper(t.type())))
                .filter(t -> t.inScope(scope))
                .limit(limit)
                .toList();
        List<TopicInfo> infos = kept.stream()
                .map(t -> new TopicInfo(t.type(), t.label(), cap(t.detail(), DETAIL_CAP),
                        iso(t.date()), t.regions(), t.safetyNote()))
                .toList();
        return finish("get_hot_topics", new HotTopicsResult(infos), () -> {
            for (AskSnapshot.Topic t : kept) {
                events.add(new AskEvidence.EventFact(upper(t.type()), t.label(), t.date(),
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
    public AskToolResult getComingUp(ComingUpArgs args) {
        ComingUpArgs a = args == null ? new ComingUpArgs(null, null) : args;
        int days = clamp(a.days(), MAX_COMING_UP_DAYS, MAX_COMING_UP_DAYS);
        int limit = clamp(a.limit(), DEFAULT_LIMIT, MAX_EVENTS);
        List<AskSnapshot.ComingUp> kept = timeline(snapshot, scope, days).stream().limit(limit).toList();
        List<ComingUpInfo> infos = kept.stream()
                .map(e -> new ComingUpInfo(e.type(), e.title(), iso(e.startDate()),
                        iso(e.endDate()), cap(e.detail(), DETAIL_CAP), e.safetyNote()))
                .toList();
        return finish("get_coming_up", new ComingUpResult(infos), () -> {
            for (AskSnapshot.ComingUp e : kept) {
                events.add(new AskEvidence.EventFact(upper(e.type()), e.title(), e.startDate(),
                        e.safetyNote()));
            }
        });
    }

    /**
     * The one definition of what {@code get_coming_up} can return, shared with the validator's
     * "was anything offered" test so the two cannot disagree: the almanac entries overlapping the
     * next {@code days} civil dates plus the in-scope hot topics dated inside them that the almanac
     * does not already list, soonest first. A hot topic is stood in the timeline as a one-day entry
     * titled with its label, carrying its served detail and safety note.
     *
     * <p>"Dated inside them" reads the dates a topic COVERS ({@link AskSnapshot.Topic#coversAnyOf}),
     * not its date alone: a {@code NIGHT} topic dated yesterday — the aurora alert for the night
     * still running before dawn — reaches this morning's sunrise, so it is listed. ⚠️ It stands on
     * its OWN date, yesterday, even so: {@code AskReadyFreshness.liveEvent} re-finds an event by the
     * live topic's {@code date}, and {@code get_hot_topics} reports the same topic on that date, so
     * moving the entry onto today would make an answer built from it read as no longer live on
     * the very next serve (a Codex review of #1056). The night is named by its dusk date everywhere.
     *
     * @param snapshot the snapshot the conversation runs against
     * @param scope    the question's region names, matched case-insensitively; empty means every region
     * @param days     how many days ahead, from today
     * @return the timeline, soonest first, unlimited
     */
    static List<AskSnapshot.ComingUp> timeline(AskSnapshot snapshot, Set<String> scope, int days) {
        LocalDate from = snapshot.today();
        // N days is N civil dates from today: AlmanacService.getFeed ends at today + N - 1.
        LocalDate to = from.plusDays(days - 1L);
        List<AskSnapshot.ComingUp> entries = new ArrayList<>(snapshot.comingUp().stream()
                .filter(e -> !e.endDate().isBefore(from) && !e.startDate().isAfter(to))
                .toList());
        List<AskSnapshot.ComingUp> almanac = List.copyOf(entries);
        snapshot.hotTopics().stream()
                .filter(t -> t.coversAnyOf(from, to))
                .filter(t -> t.inScope(scope))
                .filter(t -> almanac.stream().noneMatch(e -> listedBy(e, t)))
                .map(t -> new AskSnapshot.ComingUp(t.type(), t.label(), t.date(), t.date(), t.detail(),
                        t.safetyNote()))
                .forEach(entries::add);
        entries.sort(Comparator.comparing(AskSnapshot.ComingUp::startDate)
                .thenComparing(AskSnapshot.ComingUp::title)
                .thenComparing(AskSnapshot.ComingUp::type)
                .thenComparing(AskSnapshot.ComingUp::endDate));
        return List.copyOf(entries);
    }

    /**
     * Whether an almanac entry already lists a live topic: the same type (the almanac writes
     * {@code lunar-eclipse} where a hot topic writes {@code LUNAR_ECLIPSE}) on a date inside its span.
     */
    private static boolean listedBy(AskSnapshot.ComingUp entry, AskSnapshot.Topic topic) {
        return typeKey(entry.type()).equals(typeKey(topic.type()))
                && !topic.date().isBefore(entry.startDate()) && !topic.date().isAfter(entry.endDate());
    }

    private static String typeKey(String type) {
        return upper(type).replace('-', '_');
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
     * Whether the asker's own drive times were used, which makes the answer personal.
     *
     * @return true once {@code maxDriveMinutes} has been used by a user
     */
    public boolean personal() {
        return personal;
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

    private static final Comparator<AskSnapshot.Candidate> RANK =
            Comparator.<AskSnapshot.Candidate>comparingInt(c -> -c.slot().rating())
                    .thenComparingInt(AskTools::pickOrder)
                    .thenComparingInt(c -> c.slot().tideAligned() ? 0 : 1)
                    .thenComparing(c -> c.slot().name(),
                            Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                    .thenComparing(c -> c.window().date())
                    .thenComparing(c -> c.window().targetType())
                    .thenComparingLong(c -> c.slot().locationId());

    private static int pickOrder(AskSnapshot.Candidate c) {
        BriefingWindow.PickKind kind = pickKind(c);
        if (kind == null) {
            return 2;
        }
        return kind == BriefingWindow.PickKind.BEST ? 0 : 1;
    }

    /** Whether this slot is the window's forecast-wide pick, matched by id then by name. */
    private static BriefingWindow.PickKind pickKind(AskSnapshot.Candidate c) {
        BriefingWindow.Pick pick = c.window().pick();
        if (pick == null) {
            return null;
        }
        boolean same = pick.locationId() != null
                ? pick.locationId().equals(c.slot().locationId())
                : pick.locationName() != null && pick.locationName().equals(c.slot().name());
        return same ? pick.kind() : null;
    }

    private WindowInfo windowInfo(AskSnapshot.Window w) {
        BriefingWindow.Pick pick = w.pick();
        boolean pickInScope = pick != null && inScope(pick.regionName());
        PickInfo named = pickInScope
                ? new PickInfo(pick.regionName(), pick.locationName(), pick.locationId()) : null;
        boolean best = pickInScope && pick.kind() == BriefingWindow.PickKind.BEST;
        boolean also = pickInScope && pick.kind() == BriefingWindow.PickKind.ALSO;
        return new WindowInfo(w.id(), dayWord(w.date()), w.targetType().name().toLowerCase(
                Locale.ROOT), clock(w), w.verdict() == null ? null : w.verdict().name(),
                w.bestRating(), best ? named : null, also ? named : null);
    }

    private SpotInfo spotInfo(AskSnapshot.Candidate c, Integer minutes) {
        AskSnapshot.Slot s = c.slot();
        BriefingWindow.PickKind kind = pickKind(c);
        return new SpotInfo(s.locationId(), s.name(), c.region().name(), c.window().id(),
                s.rating(), s.verdict() == null ? null : s.verdict().name(), s.tideState(),
                s.coastal() ? s.tideAligned() : null, s.tideFitPhrase(),
                cap(s.headline(), HEADLINE_CAP), kind == null ? null : kind.name(), minutes);
    }

    private String dayWord(LocalDate date) {
        LocalDate today = snapshot.today();
        if (date.equals(today)) {
            return "Today";
        }
        if (date.equals(today.plusDays(1))) {
            return "Tomorrow";
        }
        return date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }

    private static String clock(AskSnapshot.Window w) {
        if (w.eventTime() == null) {
            return null;
        }
        return w.eventTime().atZone(ZoneOffset.UTC).withZoneSameInstant(LONDON).format(CLOCK);
    }

    private Map<Long, Integer> driveMinutesByLocation() {
        if (driveMinutes == null) {
            driveMinutes = driveTimes.getAllMinutes(user.userId());
        }
        return driveMinutes;
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
        return names.stream()
                .filter(n -> n.toLowerCase(Locale.ROOT).equals(wanted))
                .findFirst()
                .orElse(null);
    }

    private boolean inScope(String regionName) {
        return scope.isEmpty() || (regionName != null
                && scope.contains(regionName.toLowerCase(Locale.ROOT)));
    }

    private AskToolResult fail(String tool, String message) {
        trace.add(new ToolCall(tool, true, 0));
        return AskToolResult.error(message);
    }

    /**
     * Serialises a payload, applies the conversation's character cap, and on success records the
     * call and runs {@code accepted}. A result the cap refuses records nothing as returned.
     */
    private AskToolResult finish(String tool, Object payload, Runnable accepted) {
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
        return new AskToolResult(false, json, payload);
    }

    private static int clamp(Integer requested, int fallback, int max) {
        int value = requested == null ? fallback : requested;
        return Math.clamp(value, 1, max);
    }

    private static Set<String> lowerCased(Set<String> names) {
        Set<String> out = new HashSet<>();
        if (names != null) {
            names.stream().filter(n -> n != null && !n.isBlank())
                    .forEach(n -> out.add(n.strip().toLowerCase(Locale.ROOT)));
        }
        return out;
    }

    private static Set<String> upperCased(List<String> values) {
        Set<String> out = new HashSet<>();
        if (values != null) {
            values.stream().filter(v -> v != null && !v.isBlank())
                    .forEach(v -> out.add(upper(v)));
        }
        return out;
    }

    private static String upper(String value) {
        return value == null ? null : value.strip().toUpperCase(Locale.ROOT);
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
