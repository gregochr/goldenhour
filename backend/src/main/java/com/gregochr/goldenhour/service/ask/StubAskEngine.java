package com.gregochr.goldenhour.service.ask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gregochr.goldenhour.repository.RegionRepository;
import com.gregochr.goldenhour.service.DriveTimeResolver;
import com.gregochr.goldenhour.service.ask.AskAnswerValidator.RawEvent;
import com.gregochr.goldenhour.service.ask.AskAnswerValidator.RawPick;
import com.gregochr.goldenhour.service.ask.AskAnswerValidator.Result;
import com.gregochr.goldenhour.service.ask.AskTools.ComingUpArgs;
import com.gregochr.goldenhour.service.ask.AskTools.ComingUpInfo;
import com.gregochr.goldenhour.service.ask.AskTools.ComingUpResult;
import com.gregochr.goldenhour.service.ask.AskTools.HotTopicsArgs;
import com.gregochr.goldenhour.service.ask.AskTools.HotTopicsResult;
import com.gregochr.goldenhour.service.ask.AskTools.RankSpotsArgs;
import com.gregochr.goldenhour.service.ask.AskTools.RankSpotsResult;
import com.gregochr.goldenhour.service.ask.AskTools.SpotInfo;
import com.gregochr.goldenhour.service.ask.AskTools.TopicInfo;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

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
 * honours the question's scope (the tools and the validator share the one name set,
 * {@link AskScopes}) and a Ready {@code BEST_*} anchor: pick 1 is on the anchored window, taken
 * from the shared {@link AskAnswerValidator#anchoredWindow}, never a second definition of it.
 *
 * <p>It writes nothing: no job run, no {@code api_call_log} row, no cost, no question count (the
 * daily {@code ASK} run's counters are display-only and Operations should show Claude's spend, not
 * local rehearsals). It does enforce the same options contract as the Claude engine
 * ({@link AskRunOptions#requireConsistentWith}) so a caller that would misbill a real run fails the
 * same way against the stub. It has no pre-filter: that is a separate, earlier step (B5).
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
    private static final Pattern COASTAL_WORDS = Pattern.compile(
            "\\b(?:coast|coastal|tide|tides|tidal|beach|beaches|sea)\\b");
    private static final Pattern HIGH_TIDE = Pattern.compile("\\bhigh (?:tide|water)\\b");
    private static final Pattern LOW_TIDE = Pattern.compile("\\blow (?:tide|water)\\b");
    /** The topical keywords an events question may carry, in the order they are looked for. */
    private static final List<String> TOPICS = List.of("aurora", "snow", "eclipse", "meteor");

    private final AskAnswerValidator validator;
    private final DriveTimeResolver driveTimes;
    private final RegionRepository regionRepository;
    private final ObjectMapper mapper;

    /**
     * Creates the stub engine.
     *
     * @param validator        holds the answer to what the tools returned
     * @param driveTimes       the asker's drive times, for the tools' (unused here) drive filter
     * @param regionRepository resolves the question's region ids to names
     * @param mapper           serialises tool results
     */
    public StubAskEngine(AskAnswerValidator validator, DriveTimeResolver driveTimes,
            RegionRepository regionRepository, ObjectMapper mapper) {
        this.validator = validator;
        this.driveTimes = driveTimes;
        this.regionRepository = regionRepository;
        this.mapper = mapper;
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
        AskRunOptions opts = options == null ? AskRunOptions.none() : options;
        opts.requireConsistentWith(user);
        if (question.sanitised() == null || question.sanitised().isBlank()) {
            return failed("the question is empty", List.of());
        }
        Optional<Set<String>> scope = AskScopes.resolve(regionRepository, question);
        if (scope.isEmpty()) {
            return failed("a region id in the question's scope does not exist", List.of());
        }
        AskTools tools = new AskTools(snapshot, user, scope.get(), driveTimes, mapper);
        String text = question.sanitised().toLowerCase(Locale.ROOT);

        AskAnswerValidator.Raw raw;
        try {
            raw = EVENT_WORDS.matcher(text).find()
                    ? eventsAnswer(text, tools)
                    : spotsAnswer(text, question, snapshot, tools, scope.get(), opts);
        } catch (StubFailure e) {
            return failed(e.getMessage(), tools.trace());
        }
        List<AskTools.ToolCall> trace = new ArrayList<>(tools.trace());
        trace.add(new AskTools.ToolCall(AskToolSchemas.SUBMIT_ANSWER, false, 0));
        Result result = validator.validate(raw, snapshot, tools.evidence(), scope.get(), opts.anchor());
        if (!result.accepted()) {
            return new AskRun(new AskOutcome(AskOutcome.Status.FAILED, null, tools.personal(), 1),
                    trace, "the answer was discarded: " + result.reason());
        }
        AskOutcome.Status status = result.answer().answerable() ? AskOutcome.Status.OK
                : AskOutcome.Status.CANT;
        return new AskRun(new AskOutcome(status, result.answer(), tools.personal(), 1), trace, null);
    }

    // -- where and when -----------------------------------------------------------------------

    private AskAnswerValidator.Raw spotsAnswer(String text, AskQuestion question,
            AskSnapshot snapshot, AskTools tools, Set<String> scope, AskRunOptions opts)
            throws StubFailure {
        // A context window not in the window set is ignored, as it is everywhere else.
        List<String> windowIds = question.windowId() != null
                && snapshot.window(question.windowId()).isPresent()
                ? List.of(question.windowId()) : null;
        Boolean coastal = COASTAL_WORDS.matcher(text).find() ? Boolean.TRUE : null;
        String tide = HIGH_TIDE.matcher(text).find() ? "HIGH"
                : LOW_TIDE.matcher(text).find() ? "LOW" : null;

        List<SpotInfo> lead = new ArrayList<>();
        if (opts.anchor() != null) {
            Optional<AskSnapshot.Window> leadWindow =
                    AskAnswerValidator.anchoredWindow(snapshot, opts.anchor(), scope);
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

        List<SpotInfo> chosen = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (SpotInfo spot : concat(lead, ranked)) {
            if (chosen.size() < PICKS && seen.add(spot.locationId())) {
                chosen.add(spot);
            }
        }
        List<RawPick> picks = chosen.stream()
                .map(s -> new RawPick(s.locationId(), s.windowId(), why(s, snapshot.today())))
                .toList();
        return new AskAnswerValidator.Raw(true, spotsSummary(chosen, snapshot.today()), picks,
                List.of(), null);
    }

    private static String spotsSummary(List<SpotInfo> chosen, LocalDate today) {
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
            String day;
            if (parts.date().equals(today)) {
                day = "today";
            } else if (parts.date().equals(today.plusDays(1))) {
                day = "tomorrow";
            } else {
                day = parts.date().getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
            }
            return day + " " + parts.targetType().name().toLowerCase(Locale.ROOT);
        }).orElse(windowId);
    }

    // -- events -------------------------------------------------------------------------------

    private AskAnswerValidator.Raw eventsAnswer(String text, AskTools tools) throws StubFailure {
        String topic = TOPICS.stream().filter(text::contains).findFirst().orElse(null);
        AskToolResult hot = tools.getHotTopics(new HotTopicsArgs(null, AskTools.MAX_EVENTS));
        AskToolResult coming = tools.getComingUp(
                new ComingUpArgs(AskTools.MAX_COMING_UP_DAYS, AskTools.MAX_EVENTS));
        List<Found> found = new ArrayList<>();
        if (!hot.error()) {
            for (TopicInfo t : ((HotTopicsResult) hot.payload()).topics()) {
                found.add(new Found(t.type(), t.label(), t.detail(), parse(t.date())));
            }
        }
        if (!coming.error()) {
            for (ComingUpInfo e : ((ComingUpResult) coming.payload()).entries()) {
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
            if (events.size() < EVENTS && seen.add(f.type().toUpperCase(Locale.ROOT) + "|" + f.date())) {
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
    private static List<SpotInfo> spots(AskToolResult result) throws StubFailure {
        if (result.error()) {
            throw new StubFailure("rank_spots returned an error: " + result.content());
        }
        return ((RankSpotsResult) result.payload()).spots();
    }

    private static List<SpotInfo> concat(List<SpotInfo> first, List<SpotInfo> second) {
        List<SpotInfo> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    private static AskRun failed(String reason, List<AskTools.ToolCall> trace) {
        return new AskRun(new AskOutcome(AskOutcome.Status.FAILED, null, false, 0), trace, reason);
    }

    /** A tool returned an error the stub's fixed script cannot recover from. */
    private static final class StubFailure extends Exception {

        private static final long serialVersionUID = 1L;

        StubFailure(String message) {
            super(message, null, false, false);
        }
    }
}
