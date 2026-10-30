package com.gregochr.goldenhour.service.ask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.service.DriveTimeResolver;
import com.gregochr.goldenhour.service.ask.AskAnswerValidator.BestAnchor;
import com.gregochr.goldenhour.service.ask.AskAnswerValidator.RawEvent;
import com.gregochr.goldenhour.service.ask.AskAnswerValidator.RawPick;
import com.gregochr.goldenhour.service.ask.AskTools.ComingUpArgs;
import com.gregochr.goldenhour.service.ask.AskTools.ComingUpInfo;
import com.gregochr.goldenhour.service.ask.AskTools.ComingUpResult;
import com.gregochr.goldenhour.service.ask.AskTools.HotTopicsArgs;
import com.gregochr.goldenhour.service.ask.AskTools.HotTopicsResult;
import com.gregochr.goldenhour.service.ask.AskTools.NightSpotInfo;
import com.gregochr.goldenhour.service.ask.AskTools.RankNightSpotsArgs;
import com.gregochr.goldenhour.service.ask.AskTools.RankNightSpotsResult;
import com.gregochr.goldenhour.service.ask.AskTools.RankSpotsArgs;
import com.gregochr.goldenhour.service.ask.AskTools.RankSpotsResult;
import com.gregochr.goldenhour.service.ask.AskTools.SpotInfo;
import com.gregochr.goldenhour.service.ask.AskTools.TopicInfo;
import com.gregochr.goldenhour.util.DayLabels;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/**
 * The engine that makes <b>no Anthropic call</b>, selected by {@code photocast.ask.stub=true}
 * ({@link AskEngineSelection}). It exists so the whole Ask surface can be built, run and looked at
 * locally without spending anything (plan §2.3, §9).
 *
 * <p>It answers from the same {@link AskTools} the Claude engine's model calls, with templated text
 * and a small deterministic keyword switch:
 * <ul>
 *   <li>a question about events, rare things, aurora, snow, an eclipse or a meteor shower is
 *       answered from {@code get_hot_topics} and {@code get_coming_up};</li>
 *   <li>a question about the night sky (stars, stargazing, the Milky Way, dark skies, clear skies, the
 *       northern lights) is answered from {@code rank_night_spots}: the top three different places whose
 *       figure is pick-eligible, for tonight or, when the question says tomorrow, tomorrow night;</li>
 *   <li>anything else is a where-or-when question: the top three <em>different</em> spots from
 *       {@code rank_spots}, narrowed to coastal spots or to one tide state when the question names
 *       the coast or the tide, and to the context window when there is one;</li>
 *   <li>nothing eligible is an honest answer with no picks, never a made-up one.</li>
 * </ul>
 *
 * <p><b>Its answer is held to the same rules as a real one.</b> The submitted answer goes through
 * {@link AskAnswerValidator#validate} exactly as the Claude engine's does: the stub only ever names
 * a pair or an event a tool returned, but it is not <em>trusted</em> to, so a stub bug that named
 * something else would be discarded and shown as a FAILED run rather than passed through. It
 * honours the question's scope (the tools and the validator share the one {@link AskScope}) and a
 * Ready {@code BEST_*} anchor: pick 1 is on the anchored window, taken from the shared
 * {@link AskAnswerValidator#anchoredWindow}, never a second definition of it.
 *
 * <p>It writes nothing: no job run, no {@code api_call_log} row, no cost, no question count (the
 * daily {@code ASK} run's counters are display-only and Operations should show Claude's spend, not
 * local rehearsals). It does enforce the same options contract as the Claude engine
 * ({@link AskRunOptions#requireConsistentWith}) so a caller that would misbill a real run fails the
 * same way against the stub. It has no pre-filter: that is a separate, earlier step ({@link PhraseAskPreFilter}).
 */
@Service
@Conditional(AskEngineSelection.StubSelected.class)
public class StubAskEngine implements AskEngine {

    /** The most picks the stub offers, which is the most an answer may carry. */
    private static final int PICKS = AskAnswerValidator.MAX_PICKS;

    /** The most events the stub offers. */
    private static final int EVENTS = 3;

    private static final Pattern EVENT_WORDS = Pattern.compile(
            "\\b(?:rare|events?|aurora|snow|eclipses?|meteors?)\\b");
    /**
     * The words that make a question one about the night sky. A bare "aurora" is deliberately not among them:
     * the stub has always answered it as an event.
     */
    private static final Pattern NIGHT_WORDS = Pattern.compile(
            "\\b(?:stars?|stargazing|milky way|dark skies|dark sky|night sky|clear skies|clear sky|bortle"
                    + "|northern lights)\\b");
    private static final Pattern AURORA_WORDS = Pattern.compile("\\b(?:aurora|northern lights)\\b");
    private static final Pattern TODAY_WORDS = Pattern.compile("\\b(?:today|tonight|this evening|this morning)\\b");
    private static final Pattern TOMORROW_WORD = Pattern.compile("\\btomorrow\\b");
    private static final Pattern WEEKEND_WORD = Pattern.compile("\\bweekend\\b");
    private static final Pattern WEEKDAY_WORDS = Pattern.compile(
            "\\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b");
    private static final Pattern COASTAL_WORDS = Pattern.compile(
            "\\b(?:coast|coastal|tide|tides|tidal|beach|beaches|sea)\\b");
    private static final Pattern HIGH_TIDE = Pattern.compile("\\bhigh (?:tide|water)\\b");
    private static final Pattern LOW_TIDE = Pattern.compile("\\blow (?:tide|water)\\b");
    /** The topical keywords an events question may carry, in the order they are looked for. */
    private static final List<String> TOPICS = List.of("aurora", "snow", "eclipse", "meteor");

    private final AskConversation.Deps frame;

    /**
     * Creates the stub engine.
     *
     * @param validator        holds the answer to what the tools returned
     * @param driveTimes       the asker's drive times, for the tools' (unused here) drive filter
     * @param mapper           serialises tool results
     */
    public StubAskEngine(AskAnswerValidator validator, DriveTimeResolver driveTimes,
            ObjectMapper mapper) {
        this.frame = new AskConversation.Deps(validator, driveTimes, mapper);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException when the options do not match the conversation, as the
     *         Claude engine does
     */
    @Override
    public AskRun run(AskQuestion question, AskSnapshot snapshot, AskUserContext user,
            AskRunOptions options) {
        return switch (AskConversation.open(question, snapshot, user, options, frame)) {
            case AskConversation.Opening.Refused refused -> refused.run();
            case AskConversation.Opening.Open open -> script(open.conversation(), question, snapshot);
        };
    }

    /** The stub's one turn: pick the script by keyword, then submit what it found. */
    private AskRun script(AskConversation conversation, AskQuestion question, AskSnapshot snapshot) {
        AskTools tools = conversation.tools();
        String text = question.sanitised().toLowerCase(Locale.ROOT);

        AskAnswerValidator.Raw raw;
        if (!EVENT_WORDS.matcher(text).find() && asksAboutEarlierPicks(text, question, snapshot)) {
            // A question about the earlier picks themselves (rank, compare, "those") ranks them with no
            // tool call. Anything that asks for new places (a day, the coast, a tide, a window) falls
            // through to the ordinary script, with the thread still in evidence for the validator.
            Optional<AskAnswerValidator.Raw> earlier = threadAnswer(conversation, snapshot);
            if (earlier.isPresent()) {
                return conversation.submit(earlier.get(), 1, tools.trace());
            }
        }
        try {
            if (NIGHT_WORDS.matcher(text).find()) {
                raw = nightAnswer(text, tools);
            } else {
                raw = EVENT_WORDS.matcher(text).find()
                        ? eventsAnswer(text, tools)
                        : spotsAnswer(text, question, snapshot, conversation);
            }
        } catch (StubFailure e) {
            return conversation.fail(e.getMessage(), 0, tools.trace());
        }
        return conversation.submit(raw, 1, tools.trace());
    }

    // -- the thread ---------------------------------------------------------------------------

    /** Words that point back at the answers already given. */
    private static final Set<String> EARLIER_WORDS = Set.of("rank", "order", "those", "them", "these",
            "either", "both", "closer", "nearer", "earlier", "which");

    /**
     * Whether the question is about the earlier options rather than a request for new places: it
     * points back at them (a deterministic word test, whole words) and names no day, coast, tide or
     * window of its own, since any of those asks the forecast a new question.
     */
    private static boolean asksAboutEarlierPicks(String text, AskQuestion question, AskSnapshot snapshot) {
        List<String> words = PhraseAskPreFilter.words(text);
        if (ReadyIntentRules.turnsDownEarlierPlaces(words)) {
            // "Neither of those", "something else": the reader wants new places, never the earlier ones ranked.
            return false;
        }
        boolean pointsBack = words.stream().anyMatch(EARLIER_WORDS::contains)
                || IntStream.range(0, words.size() - 1)
                        .anyMatch(i -> "the".equals(words.get(i)) && "other".equals(words.get(i + 1)));
        boolean newPlaces = TODAY_WORDS.matcher(text).find() || TOMORROW_WORD.matcher(text).find()
                || WEEKEND_WORD.matcher(text).find() || WEEKDAY_WORDS.matcher(text).find()
                || COASTAL_WORDS.matcher(text).find() || HIGH_TIDE.matcher(text).find()
                || LOW_TIDE.matcher(text).find()
                || question.windowId() != null && snapshot.window(question.windowId()).isPresent();
        return pointsBack && !newPlaces;
    }

    /**
     * The thread's picks that are still in scope and pick-eligible, ranked the way {@code rank_spots}
     * ranks (rating, the forecast's own pick, tide met, name), best three at distinct places. Empty
     * when the conversation has no earlier pick left, which sends the question to the ordinary script.
     */
    private static Optional<AskAnswerValidator.Raw> threadAnswer(AskConversation conversation,
            AskSnapshot snapshot) {
        List<AskSnapshot.Candidate> earlier = conversation.thread().fact().picks().stream()
                .flatMap(pair -> snapshot.candidate(pair.windowId(), pair.locationId()).stream())
                .sorted(AskTools.RANK)
                .toList();
        List<SpotInfo> chosen = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (AskSnapshot.Candidate c : earlier) {
            if (chosen.size() < PICKS && seen.add(c.slot().locationId())) {
                chosen.add(conversation.tools().spotInfo(c, null));
            }
        }
        if (chosen.isEmpty()) {
            return Optional.empty();
        }
        List<RawPick> picks = chosen.stream()
                .map(s -> new RawPick(s.locationId(), s.windowId(), why(s, snapshot.today())))
                .toList();
        StringBuilder summary = new StringBuilder("Of your earlier options, ")
                .append(chosen.getFirst().name()).append(" at ")
                .append(windowWords(chosen.getFirst().windowId(), snapshot.today())).append(" ranks first at ")
                .append(chosen.getFirst().rating()).append('★');
        for (int i = 1; i < chosen.size(); i++) {
            summary.append(i == 1 ? ", then " : i == chosen.size() - 1 ? " and " : ", ")
                    .append(chosen.get(i).name());
        }
        return Optional.of(new AskAnswerValidator.Raw(true, summary.append('.').toString(), picks,
                List.of(), null));
    }

    // -- where and when -----------------------------------------------------------------------

    private AskAnswerValidator.Raw spotsAnswer(String text, AskQuestion question,
            AskSnapshot snapshot, AskConversation conversation) throws StubFailure {
        AskTools tools = conversation.tools();
        BestAnchor anchor = conversation.anchor();
        // A context window not in the window set is ignored, as it is everywhere else. With none, a
        // Ready anchor names the windows the question is about, and failing that a day word in the
        // question ("tonight", "tomorrow", "on Saturday", "this weekend") narrows them, so the
        // catalogue's day-worded Ready questions answer about the right day locally.
        List<String> windowIds = question.windowId() != null
                && snapshot.window(question.windowId()).isPresent()
                ? List.of(question.windowId())
                : anchor != null && !anchor.windowIds().isEmpty()
                ? anchor.windowIds().stream().sorted().toList()
                : dayWindows(text, snapshot).orElse(null);
        Boolean coastal = COASTAL_WORDS.matcher(text).find() ? Boolean.TRUE : null;
        String tide = HIGH_TIDE.matcher(text).find() ? "HIGH"
                : LOW_TIDE.matcher(text).find() ? "LOW" : null;

        List<SpotInfo> lead = new ArrayList<>();
        // A Ready question whose covered windows hold nothing game on is answered with no pick, so there is
        // nothing to lead with: the very set test the validator, the relevance rule and the freshness check
        // consult (ReadyRelevance.mayHaveNoPick).
        AskAnswerValidator.ReadyBasis basis = conversation.options().readyBasis();
        boolean nothingGameOn = basis != null
                && ReadyRelevance.mayHaveNoPick(basis.question(), basis.offer(), snapshot, conversation.scope());
        if (anchor != null && !nothingGameOn) {
            Optional<AskSnapshot.Window> leadWindow =
                    AskAnswerValidator.anchoredWindow(snapshot, anchor, conversation.scope());
            if (leadWindow.isPresent()) {
                // The forecast's own BEST BET leads when it is the window's pick; the validator only
                // requires the window, but the Plan tab names a location and so does the stub.
                lead = new ArrayList<>(spots(tools.rankSpots(new RankSpotsArgs(
                        List.of(leadWindow.get().id()), null, null, null, null, PICKS))));
                lead.sort((a, b) -> Boolean.compare(!"BEST".equals(a.pick()), !"BEST".equals(b.pick())));
            }
        }
        List<SpotInfo> ranked = spots(tools.rankSpots(
                new RankSpotsArgs(windowIds, null, coastal, tide, null, AskTools.MAX_SPOTS)));

        Set<Long> turnedDown = conversation.thread().fact().turnedDown();
        List<SpotInfo> chosen = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        // Picks are the game-on places only (the owner's scale: 3★ is marginal, never filler). The one
        // exception is the forecast's own BEST BET, which an anchored Ready answer must lead with.
        SpotInfo crown = lead.isEmpty() ? null : lead.getFirst();
        for (SpotInfo spot : concat(lead, ranked)) {
            boolean gameOn = spot.rating() >= AskSnapshot.Spread.GAME_ON_FROM;
            if (chosen.size() < PICKS && !turnedDown.contains(spot.locationId())
                    && (gameOn || spot == crown) && seen.add(spot.locationId())) {
                chosen.add(spot);
            }
        }
        List<RawPick> picks = chosen.stream()
                .map(s -> new RawPick(s.locationId(), s.windowId(), why(s, snapshot.today())))
                .toList();
        // The figures quoted are over the windows the question COVERS when it is a Ready one (an all-blue
        // Saturday is part of "this weekend"), else the windows asked about; the carried set is the same.
        List<String> quoted = basis != null ? basis.offer().coveredWindowIds()
                : windowIds != null ? windowIds
                : snapshot.windows().stream().map(AskSnapshot.Window::id).toList();
        List<AskSnapshot.Spread> spreads = quoted.stream()
                .flatMap(id -> snapshot.spread(id, conversation.scope()).stream()).toList();
        return new AskAnswerValidator.Raw(true, spotsSummary(chosen, spreads, snapshot.today()), picks,
                List.of(), null);
    }

    // -- the night ----------------------------------------------------------------------------

    /**
     * A night-sky question: {@code rank_night_spots} for tonight (tomorrow night when the question says
     * tomorrow), the top places whose figure is pick-eligible, at distinct locations. The kind is aurora
     * when the question names the northern lights and any otherwise; the tool withholds aurora from an
     * asker without access, and the summary then says so.
     */
    private AskAnswerValidator.Raw nightAnswer(String text, AskTools tools) throws StubFailure {
        String kind = AURORA_WORDS.matcher(text).find() ? "aurora" : "any";
        String night = TOMORROW_WORD.matcher(text).find() ? "tomorrow" : "tonight";
        AskToolResult<RankNightSpotsResult> result = tools.rankNightSpots(new RankNightSpotsArgs(night, kind,
                null, null, null, null, null, AskTools.MAX_NIGHT_SPOTS));
        if (result.error()) {
            throw new StubFailure("rank_night_spots returned an error: " + result.content());
        }
        RankNightSpotsResult found = result.payload();
        List<RawPick> picks = new ArrayList<>();
        List<String> names = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (NightSpotInfo spot : found.spots()) {
            String windowId = pickWindow(spot, kind);
            if (picks.size() < PICKS && windowId != null && seen.add(spot.locationId())) {
                picks.add(new RawPick(spot.locationId(), windowId, nightWhy(spot, windowId)));
                names.add(spot.name());
            }
        }
        String when = found.when() == null ? night : found.when();
        String summary;
        if (picks.isEmpty()) {
            summary = "Nothing is rated well enough for the night sky " + when + " in the forecast."
                    + (Boolean.FALSE.equals(found.auroraAvailable()) && "aurora".equals(kind)
                            ? " Aurora ratings are a Pro feature." : "");
        } else {
            summary = names.getFirst() + " looks best for the night sky " + when
                    + (names.size() > 1 ? ", followed by " + String.join(" and ", names.subList(1, names.size()))
                            : "") + ".";
        }
        return new AskAnswerValidator.Raw(true, summary, picks, List.of(), null);
    }

    /**
     * The window a night spot may be picked at: the astro window when its sky figure reaches the pick bar,
     * else the aurora window when that does ({@code kind} aurora looks at the aurora one alone); null when
     * neither figure is pickable, so the stub never offers what the validator would drop.
     */
    private static String pickWindow(NightSpotInfo spot, String kind) {
        boolean astro = !"aurora".equals(kind) && spot.astroWindowId() != null && spot.astroStars() != null
                && spot.astroStars() >= AskSnapshot.MIN_ASTRO_PICK_RATING;
        if (astro) {
            return spot.astroWindowId();
        }
        boolean aurora = spot.auroraWindowId() != null && spot.auroraStars() != null
                && spot.auroraStars() >= AskSnapshot.MIN_AURORA_PICK_RATING;
        return aurora ? spot.auroraWindowId() : null;
    }

    private static String nightWhy(NightSpotInfo spot, String windowId) {
        boolean astro = windowId.equals(spot.astroWindowId());
        StringBuilder why = new StringBuilder("Rated ").append(astro ? spot.astroStars() : spot.auroraStars())
                .append("★ for ").append(astro ? "stargazing" : "the northern lights");
        if (astro && spot.clearSky() != null) {
            why.append(": ").append(spot.clearSky());
        }
        return why.append(". Bortle ").append(spot.bortle()).append('.').toString();
    }

    /** The windows a day word in the question names, or empty when it names none that exist. */
    private static Optional<List<String>> dayWindows(String text, AskSnapshot snapshot) {
        Predicate<LocalDate> wanted = null;
        LocalDate today = snapshot.today();
        if (TODAY_WORDS.matcher(text).find()) {
            wanted = today::equals;
        } else if (TOMORROW_WORD.matcher(text).find()) {
            wanted = today.plusDays(1)::equals;
        } else if (WEEKEND_WORD.matcher(text).find()) {
            wanted = d -> d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;
        } else {
            Matcher day = WEEKDAY_WORDS.matcher(text);
            if (day.find()) {
                DayOfWeek weekday = DayOfWeek.valueOf(day.group(1).toUpperCase(Locale.ROOT));
                wanted = d -> d.getDayOfWeek() == weekday;
            }
        }
        if (wanted == null) {
            return Optional.empty();
        }
        Predicate<LocalDate> onDay = wanted;
        List<String> ids = snapshot.windows().stream().filter(w -> onDay.test(w.date()))
                .map(AskSnapshot.Window::id).toList();
        return ids.isEmpty() ? Optional.empty() : Optional.of(ids);
    }

    /**
     * The share of rated places at 4★ or better from which the stub calls a window broadly game on.
     * A stub-only template threshold: the real engine is given the spread and the owner's criteria and
     * judges it; nothing in the server applies a threshold (ask-window-context plan §2.3).
     */
    private static final int STUB_BROADLY_GAME_ON_PCT = 40;

    /**
     * How the asked windows read from their spreads, in the words the criteria use, or empty when the
     * snapshot holds none of them. Summed over the windows asked about; the figures are the served
     * ones, never recomputed from the picks.
     */
    private static Optional<String> framing(List<AskSnapshot.Spread> spreads) {
        if (spreads.isEmpty()) {
            return Optional.empty();
        }
        int rated = spreads.stream().mapToInt(AskSnapshot.Spread::rated).sum();
        int gameOn = spreads.stream().mapToInt(AskSnapshot.Spread::gameOn).sum();
        // "Every region stood down" only when nothing else was in scope: regions that are thin or not scored
        // are missing evidence, never bad weather.
        boolean allStoodDown = spreads.stream().allMatch(sp -> sp.regions().stoodDown() != null
                && sp.regions().counted() == 0 && sp.regions().thin() == null && sp.regions().notScored() == null);
        String over = spreads.getFirst().over();
        String area = AskSnapshot.Spread.EVERY_REGION.equals(over) ? "across every region" : "in " + over;
        if (rated == 0) {
            return Optional.of(allStoodDown
                    ? "Every region is stood down for that window, so it is poor " + area + "."
                    : "That window is mostly not scored yet, so there is nothing to say about it " + area + ".");
        }
        String places = rated + " rated " + (rated == 1 ? "place" : "places");
        if (gameOn == 0) {
            return Optional.of("Nothing worth going out for " + area + ": of " + places
                    + " none is 4★ or better.");
        }
        String verb = gameOn == 1 ? " is" : " are";
        if (100 * gameOn / rated < STUB_BROADLY_GAME_ON_PCT) {
            return Optional.of("Not a window to go out for " + area + ": of " + places + ", " + gameOn + verb
                    + " 4★ or better.");
        }
        return Optional.of("Broadly game on " + area + ": " + gameOn + " of " + places + (gameOn == 1 ? " is" : " are")
                + " 4★ or better.");
    }

    private static String spotsSummary(List<SpotInfo> chosen, List<AskSnapshot.Spread> spreads,
            LocalDate today) {
        Optional<String> framing = framing(spreads);
        if (framing.isPresent()) {
            if (chosen.isEmpty()) {
                return framing.get();
            }
            List<String> names = chosen.stream().filter(s -> s.rating() >= AskSnapshot.Spread.GAME_ON_FROM)
                    .map(SpotInfo::name).toList();
            boolean exceptions = framing.get().startsWith("Not a window") && !names.isEmpty();
            return framing.get() + (exceptions
                    ? " The " + (names.size() == 1 ? "exception is " : "exceptions are ")
                            + String.join(" and ", names) + "."
                    : " " + spotsSummary(chosen, List.of(), today));
        }
        if (chosen.isEmpty()) {
            return "No spot in scope is rated 3★ or better in the current forecast.";
        }
        SpotInfo top = chosen.getFirst();
        StringBuilder summary = new StringBuilder(top.name()).append(" at ")
                .append(windowWords(top.windowId(), today)).append(" looks strongest at ")
                .append(top.rating()).append('★');
        if (chosen.size() > 1) {
            summary.append(", followed by ").append(chosen.get(1).name());
            if (chosen.size() > 2) {
                summary.append(" and ").append(chosen.get(2).name());
            }
        }
        return summary.append('.').toString();
    }

    private static String why(SpotInfo spot, LocalDate today) {
        StringBuilder why = new StringBuilder("Rated ").append(spot.rating()).append("★ for ")
                .append(windowWords(spot.windowId(), today)).append('.');
        if (Boolean.TRUE.equals(spot.tideAligned())) {
            why.append(" The tide suits it.");
        } else if (spot.tideState() != null) {
            why.append(" The tide is ").append(spot.tideState().toLowerCase(Locale.ROOT))
                    .append(" at the event.");
        }
        return why.toString();
    }

    /** {@code today sunrise}, {@code tomorrow sunset}, {@code Thursday sunrise}. */
    private static String windowWords(String windowId, LocalDate today) {
        return AskWindowId.parse(windowId).map(parts -> {
            return DayLabels.relative(parts.date(), today) + " "
                    + parts.kind().name().toLowerCase(Locale.ROOT);
        }).orElse(windowId);
    }

    // -- events -------------------------------------------------------------------------------

    private AskAnswerValidator.Raw eventsAnswer(String text, AskTools tools) throws StubFailure {
        String topic = TOPICS.stream().filter(text::contains).findFirst().orElse(null);
        AskToolResult<HotTopicsResult> hot = tools.getHotTopics(new HotTopicsArgs(null, AskTools.MAX_EVENTS));
        AskToolResult<ComingUpResult> coming = tools.getComingUp(
                new ComingUpArgs(AskSnapshot.MAX_COMING_UP_DAYS, AskTools.MAX_EVENTS));
        List<Found> found = new ArrayList<>();
        if (!hot.error()) {
            for (TopicInfo t : hot.payload().topics()) {
                found.add(new Found(t.type(), t.label(), t.detail(), parse(t.date())));
            }
        }
        if (!coming.error()) {
            for (ComingUpInfo e : coming.payload().entries()) {
                found.add(new Found(e.type(), e.title(), e.detail(), parse(e.start())));
            }
        }
        if (hot.error() && coming.error()) {
            throw new StubFailure("both event tools returned an error: " + hot.content());
        }
        List<Found> matching = found.stream()
                .filter(f -> topic == null || f.mentions(topic))
                .toList();
        List<RawEvent> events = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Found f : matching) {
            if (events.size() < EVENTS && seen.add(AskEventType.offerKey(f.type(), f.date()))) {
                events.add(new RawEvent(f.type(), f.date(), why(f)));
            }
        }
        String summary;
        if (events.isEmpty()) {
            summary = topic == null ? "No rare events are showing in the forecast right now."
                    : "Nothing about " + topic + " is showing in the forecast right now.";
        } else {
            List<String> labels = matching.stream().map(Found::label).distinct().limit(EVENTS).toList();
            summary = "Coming up in the forecast: " + String.join(", ", labels) + ".";
        }
        return new AskAnswerValidator.Raw(true, summary, List.of(), events, null);
    }

    /** The served detail's first sentence, or a plain line when there is none. */
    private static String why(Found event) {
        if (event.detail() == null || event.detail().isBlank()) {
            return "Listed in the forecast.";
        }
        String detail = event.detail().strip();
        int stop = detail.indexOf(". ");
        return stop < 0 ? detail : detail.substring(0, stop + 1);
    }

    /** An event as a tool returned it, in one shape for topics and almanac entries. */
    private record Found(String type, String label, String detail, LocalDate date) {

        boolean mentions(String keyword) {
            return contains(type, keyword) || contains(label, keyword) || contains(detail, keyword);
        }

        private static boolean contains(String text, String keyword) {
            return text != null && text.toLowerCase(Locale.ROOT).contains(keyword);
        }
    }

    private static LocalDate parse(String iso) {
        if (iso == null) {
            return null;
        }
        try {
            return LocalDate.parse(iso);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    // -- helpers ------------------------------------------------------------------------------

    /** The spots of a {@code rank_spots} result; a tool error stops the stub, loudly. */
    private static List<SpotInfo> spots(AskToolResult<RankSpotsResult> result) throws StubFailure {
        if (result.error()) {
            throw new StubFailure("rank_spots returned an error: " + result.content());
        }
        return result.payload().spots();
    }

    private static List<SpotInfo> concat(List<SpotInfo> first, List<SpotInfo> second) {
        List<SpotInfo> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    /** A tool returned an error the stub's fixed script cannot recover from. */
    private static final class StubFailure extends Exception {

        private static final long serialVersionUID = 1L;

        StubFailure(String message) {
            super(message, null, false, false);
        }
    }
}
