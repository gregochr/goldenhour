package com.gregochr.goldenhour.service.ask;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tool definitions Claude is given (plan §2.2, §2.3): JSON schemas that match, field for
 * field, the argument records of {@link AskTools} and the reply contract of
 * {@link AskAnswerParser}.
 *
 * <p>{@code submit_answer} is the reply. It has no suggestions field: the server chooses any "try
 * this instead" questions, never the model (plan §4 #17). {@code maxDriveMinutes} and {@code nearHome}
 * are advertised only to a conversation that has a user, because a user-less (Ready) conversation
 * must not be able even to think about home. A follow-up's {@code submit_answer} may carry
 * {@link AskAnswerValidator#MAX_RANKED_PICKS} picks, so a ranking of the reader's earlier options is
 * not cut to three.
 */
public final class AskToolSchemas {

    /** {@code list_windows}. */
    public static final String LIST_WINDOWS = "list_windows";
    /** {@code rank_spots}. */
    public static final String RANK_SPOTS = "rank_spots";
    /** {@code look_up_place}. */
    public static final String LOOK_UP_PLACE = "look_up_place";
    /** {@code rank_night_spots}. */
    public static final String RANK_NIGHT_SPOTS = "rank_night_spots";
    /** {@code get_hot_topics}. */
    public static final String GET_HOT_TOPICS = "get_hot_topics";
    /** {@code get_coming_up}. */
    public static final String GET_COMING_UP = "get_coming_up";
    /** {@code submit_answer}: terminates the loop. */
    public static final String SUBMIT_ANSWER = "submit_answer";

    private AskToolSchemas() {
    }

    /**
     * Every tool of a conversation.
     *
     * @param withUser true when the conversation has a user, so {@code rank_spots} may take
     *                 {@code maxDriveMinutes} and {@code nearHome}
     * @return the tools, {@code submit_answer} last; a fresh question's, with three picks at most
     */
    public static List<Tool> tools(boolean withUser) {
        return tools(withUser, false);
    }

    /**
     * Every tool of a conversation, for a fresh question or a follow-up.
     *
     * @param withUser   true when the conversation has a user, so {@code rank_spots} may take
     *                   {@code maxDriveMinutes} and {@code nearHome}
     * @param withThread true for a follow-up: {@code submit_answer} then allows
     *                   {@link AskAnswerValidator#MAX_RANKED_PICKS} picks, for ranking earlier options
     * @return the tools, {@code submit_answer} last
     */
    public static List<Tool> tools(boolean withUser, boolean withThread) {
        return tools(withUser, withThread, false);
    }

    /**
     * Every tool of a conversation, with or without {@code look_up_place}.
     *
     * @param withUser        true when the conversation has a user, so {@code rank_spots} may take
     *                        {@code maxDriveMinutes} and {@code nearHome}
     * @param withThread      true for a follow-up: {@code submit_answer} then allows
     *                        {@link AskAnswerValidator#MAX_RANKED_PICKS} picks
     * @param withPlaceLookup true for a typed question, which may ask about one named place; false for the
     *                        Ready precompute, whose tool list (and prompt bytes) stay as they were
     * @return the tools, {@code look_up_place} after {@code rank_spots}, {@code submit_answer} last
     */
    public static List<Tool> tools(boolean withUser, boolean withThread, boolean withPlaceLookup) {
        List<Tool> tools = new ArrayList<>();
        tools.add(listWindows());
        tools.add(rankSpots(withUser, withPlaceLookup));
        if (withPlaceLookup) {
            tools.add(lookUpPlace());
        }
        tools.add(rankNightSpots(withUser));
        tools.add(getHotTopics());
        tools.add(getComingUp());
        tools.add(submitAnswer(withThread));
        return List.copyOf(tools);
    }

    private static Tool listWindows() {
        return tool(LIST_WINDOWS,
                "Lists the upcoming sunrise and sunset windows, with the forecast's verdict, best "
                        + "rating, the spread of its ratings (rated count, the count at each star, how "
                        + "many are game on, the regions holding a game-on place, and the regions "
                        + "stood down or not scored) and its own BEST BET / ALSO GOOD pick for each, "
                        + "then the night windows "
                        + "(tonight's and tomorrow night's astro, and aurora where the reader may see it). "
                        + "Call this first for any question about when to go.",
                m(), List.of());
    }

    private static Tool rankSpots(boolean withUser, boolean withNamedAreas) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("windowIds", array(string(), "Only these window ids (from list_windows)."));
        props.put("regionNames", array(string(), "Only these region names."));
        props.put("coastalOnly", m("type", "boolean",
                "description", "True to keep only coastal spots."));
        props.put("tideState", m("type", "string", "enum", List.of("HIGH", "MID", "LOW"),
                "description", "Only spots whose water at the event is in this state."));
        if (withUser) {
            props.put("maxDriveMinutes", m("type", "integer", "minimum", 1,
                    "description", "Only spots within this many minutes' drive of the reader's "
                            + "home. Use when the reader names a number of minutes, or asks for "
                            + "somewhere closer than the places already given."));
            props.put("nearHome", m("type", "boolean",
                    "description", "True to keep only spots within the reader's own saved local "
                            + "radius of home, in straight-line miles. Use for 'close to home', "
                            + "'near', 'nearby' or 'local' with no number. The result says the "
                            + "radius: quote it as 'within N miles of home'."));
        }
        if (withNamedAreas) {
            props.put("areaName", m("type", "string", "description", "Only places in this named area (e.g. a "
                    + "landmark such as Hadrian's Wall), by its name or another name for it."));
        }
        props.put("limit", m("type", "integer", "minimum", 1, "maximum", AskTools.MAX_SPOTS,
                "description", "How many spots, at most " + AskTools.MAX_SPOTS + "."));
        return tool(RANK_SPOTS,
                "The spots worth going to, best first: only places rated 3 stars or better, never a "
                        + "wood. Every pick in your answer must be a locationId and windowId this "
                        + "tool returned.",
                props, List.of());
    }

    private static Tool lookUpPlace() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("name", m("type", "string", "description", "The place's name, or part of it."));
        props.put("windowIds", array(string(), "Only these window ids (from list_windows). Default: every window."));
        return tool(LOOK_UP_PLACE,
                "Looks up one place by name and returns its served rating, verdict, headline, tide fit "
                        + "and whether PhotoCast recommends it (status), for each window, plus the forecast's own "
                        + "reason for the place asked about. Use it when the reader "
                        + "asks about a specific place, e.g. why it is rated as it is. It never makes the place "
                        + "a pick. The name may also be a named area, such as a landmark, or another name for a "
                        + "place; the result then lists the named area's places for each window.",
                props, List.of("name"));
    }

    private static Tool rankNightSpots(boolean withUser) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("night", m("type", "string",
                "description", "tonight (the default), tomorrow, or the date of a night list_windows names."));
        props.put("kind", m("type", "string", "enum", List.of("astro", "aurora", "any"),
                "description", "astro: the clear-sky and stargazing forecast. aurora: the northern lights "
                        + "forecast. any (the default): whichever have a forecast."));
        props.put("regionNames", array(string(), "Only these region names."));
        props.put("minStars", m("type", "integer", "minimum", 1, "maximum", AskTools.MAX_NIGHT_STARS,
                "description", "The least rating a place's figure may have; the default is "
                        + AskTools.DEFAULT_MIN_NIGHT_STARS + "."));
        props.put("maxBortle", m("type", "integer", "minimum", 1, "maximum", AskTools.MAX_BORTLE,
                "description", "Only places at or under this Bortle class (1 is the darkest sky)."));
        if (withUser) {
            props.put("maxDriveMinutes", m("type", "integer", "minimum", 1,
                    "description", "Only places within this many minutes' drive of the reader's home. "
                            + "Use when the reader names a number of minutes."));
            props.put("nearHome", m("type", "boolean",
                    "description", "True to keep only places within the reader's own saved local "
                            + "radius of home, in straight-line miles. Use for 'close to home', "
                            + "'near', 'nearby' or 'local' with no number. The result says the "
                            + "radius: quote it as 'within N miles of home'."));
        }
        props.put("limit", m("type", "integer", "minimum", 1, "maximum", AskTools.MAX_NIGHT_SPOTS,
                "description", "How many places, at most " + AskTools.MAX_NIGHT_SPOTS + "."));
        return tool(RANK_NIGHT_SPOTS,
                "Night questions: clear skies tonight, stargazing, the stars, the Milky Way, dark skies, "
                        + "Bortle, aurora, the northern lights. The places for tonight or tomorrow night, "
                        + "best first, with the clear-sky forecast and the Bortle class, and the aurora "
                        + "forecast where the reader may see it. Pick a place at the astroWindowId or "
                        + "auroraWindowId this tool returned for the figure you cite: a sky pick needs 4 "
                        + "stars or better, an aurora pick 3 or better, never a wood. Every pick in your "
                        + "answer must be a locationId and window id this tool returned.",
                props, List.of());
    }

    private static Tool getHotTopics() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("types", array(string(), "Only these topic types."));
        props.put("limit", m("type", "integer", "minimum", 1, "maximum", AskTools.MAX_EVENTS,
                "description", "How many topics, at most " + AskTools.MAX_EVENTS + "."));
        return tool(GET_HOT_TOPICS,
                "The events flagged on the live forecast for the next few days: aurora, eclipses, king "
                        + "tides, inversions, snow and more. For any question about events or what is "
                        + "coming up, call get_coming_up as well: it covers the longer range.",
                props, List.of());
    }

    private static Tool getComingUp() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("days", m("type", "integer", "minimum", 1,
                "maximum", AskSnapshot.MAX_COMING_UP_DAYS,
                "description", "How many days ahead, at most " + AskSnapshot.MAX_COMING_UP_DAYS + "."));
        props.put("limit", m("type", "integer", "minimum", 1, "maximum", AskTools.MAX_EVENTS,
                "description", "How many entries, at most " + AskTools.MAX_EVENTS + "."));
        return tool(GET_COMING_UP,
                "The timeline of future events over the next weeks and months: meteor showers, "
                        + "supermoons, eclipses, equinoxes and big tides from the almanac, plus the "
                        + "events the live forecast is flagging. For any question about events or what "
                        + "is coming up, call get_hot_topics as well.",
                props, List.of());
    }

    private static Tool submitAnswer(boolean withThread) {
        Map<String, Object> pick = object(m(
                "locationId", m("type", "integer",
                        "description", "The locationId rank_spots or rank_night_spots returned."),
                "windowId", m("type", "string",
                        "description", "The windowId rank_spots or rank_night_spots returned for it."),
                "why", m("type", "string",
                        "description", "Under 22 words: the sky, tide or rating that makes it good.")),
                List.of("locationId", "windowId", "why"));
        Map<String, Object> event = object(m(
                "type", m("type", "string",
                        "description", "The type exactly as get_hot_topics or get_coming_up returned it."),
                "date", m("type", "string", "description", "The date it returned, yyyy-MM-dd."),
                "why", m("type", "string", "description", "Under 22 words.")),
                List.of("type", "why"));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("answerable", m("type", "boolean",
                "description", "False when PhotoCast's forecast cannot answer the question."));
        props.put("summary", m("type", "string",
                "description", "One or two plain sentences that answer the question directly."));
        props.put("picks", withThread
                ? m("type", "array", "items", pick, "maxItems", AskAnswerValidator.MAX_RANKED_PICKS,
                        "description", "Up to three different spots, best first, for where/when "
                                + "questions; fewer is fine. When ranking the reader's earlier "
                                + "options, up to four (a place may appear at two windows).")
                : m("type", "array", "items", pick, "maxItems", AskAnswerValidator.MAX_PICKS,
                        "description", "Up to three different spots, best first, for where/when "
                                + "questions; fewer is fine, and none when nothing is worth going for."));
        props.put("events", m("type", "array", "items", event, "maxItems", AskAnswerValidator.MAX_PICKS,
                "description", "Only events a tool returned that bear on the question."));
        props.put("missing", m("type", "string",
                "description", "When not answerable: a short phrase naming the data PhotoCast lacks."));
        return tool(SUBMIT_ANSWER,
                "Your reply. Call this exactly once, when you are done: it ends the conversation.",
                props, List.of("answerable", "summary"));
    }

    // -- helpers ----------------------------------------------------------------------------

    /**
     * An insertion-ordered map built from alternating keys and values, so the schemas Claude sees are
     * byte-stable from run to run.
     *
     * @param keysAndValues {@code key, value, key, value, ...}; every key a {@link String}
     * @return the map
     * @throws IllegalArgumentException when the list is odd in length or a key is not a String: a
     *         schema definition that is wrong is a programming error, caught the first time the
     *         schemas are built
     */
    static Map<String, Object> m(Object... keysAndValues) {
        if (keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("a schema map needs key/value pairs but got "
                    + keysAndValues.length + " arguments");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            if (!(keysAndValues[i] instanceof String key)) {
                throw new IllegalArgumentException("a schema map key must be a String but argument " + i
                        + " is " + (keysAndValues[i] == null ? "null" : keysAndValues[i].getClass().getName()));
            }
            out.put(key, keysAndValues[i + 1]);
        }
        return out;
    }

    private static Map<String, Object> string() {
        return m("type", "string");
    }

    private static Map<String, Object> array(Map<String, Object> items, String description) {
        return m("type", "array", "items", items, "description", description);
    }

    private static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        return m("type", "object", "properties", properties, "required", required);
    }

    private static Tool tool(String name, String description, Map<String, Object> properties,
            List<String> required) {
        Tool.InputSchema.Properties.Builder props = Tool.InputSchema.Properties.builder();
        properties.forEach((key, schema) -> props.putAdditionalProperty(key, JsonValue.from(schema)));
        Tool.InputSchema.Builder schema = Tool.InputSchema.builder().properties(props.build());
        if (!required.isEmpty()) {
            schema.required(new ArrayList<>(required));
        }
        return Tool.builder().name(name).description(description).inputSchema(schema.build()).build();
    }
}
