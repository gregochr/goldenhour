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
 * this instead" questions, never the model (plan §4 #17). {@code maxDriveMinutes} is advertised
 * only to a conversation that has a user, because a user-less (Ready) conversation must not be
 * able even to think about home.
 */
public final class AskToolSchemas {

    /** {@code list_windows}. */
    public static final String LIST_WINDOWS = "list_windows";
    /** {@code rank_spots}. */
    public static final String RANK_SPOTS = "rank_spots";
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
     * @param withDriveLimit true when the conversation has a user, so {@code rank_spots} may take
     *                       {@code maxDriveMinutes}
     * @return the tools, {@code submit_answer} last
     */
    public static List<Tool> tools(boolean withDriveLimit) {
        return List.of(listWindows(), rankSpots(withDriveLimit), getHotTopics(), getComingUp(),
                submitAnswer());
    }

    private static Tool listWindows() {
        return tool(LIST_WINDOWS,
                "Lists the upcoming sunrise and sunset windows, with the forecast's verdict, best "
                        + "rating and its own BEST BET / ALSO GOOD pick for each. Call this first for "
                        + "any question about when to go.",
                m(), List.of());
    }

    private static Tool rankSpots(boolean withDriveLimit) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("windowIds", array(string(), "Only these window ids (from list_windows)."));
        props.put("regionNames", array(string(), "Only these region names."));
        props.put("coastalOnly", m("type", "boolean",
                "description", "True to keep only coastal spots."));
        props.put("tideState", m("type", "string", "enum", List.of("HIGH", "MID", "LOW"),
                "description", "Only spots whose water at the event is in this state."));
        if (withDriveLimit) {
            props.put("maxDriveMinutes", m("type", "integer", "minimum", 1,
                    "description", "Only spots within this many minutes' drive of the reader's "
                            + "home. Use only when the question names a time limit."));
        }
        props.put("limit", m("type", "integer", "minimum", 1, "maximum", AskTools.MAX_SPOTS,
                "description", "How many spots, at most " + AskTools.MAX_SPOTS + "."));
        return tool(RANK_SPOTS,
                "The spots worth going to, best first: only places rated 3 stars or better, never a "
                        + "wood. Every pick in your answer must be a locationId and windowId this "
                        + "tool returned.",
                props, List.of());
    }

    private static Tool getHotTopics() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("types", array(string(), "Only these topic types."));
        props.put("limit", m("type", "integer", "minimum", 1, "maximum", AskTools.MAX_EVENTS,
                "description", "How many topics, at most " + AskTools.MAX_EVENTS + "."));
        return tool(GET_HOT_TOPICS,
                "The sky and weather events the forecast is flagging now: aurora, eclipses, king "
                        + "tides, inversions, snow and more.",
                props, List.of());
    }

    private static Tool getComingUp() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("days", m("type", "integer", "minimum", 1,
                "maximum", AskTools.MAX_COMING_UP_DAYS,
                "description", "How many days ahead, at most " + AskTools.MAX_COMING_UP_DAYS + "."));
        props.put("limit", m("type", "integer", "minimum", 1, "maximum", AskTools.MAX_EVENTS,
                "description", "How many entries, at most " + AskTools.MAX_EVENTS + "."));
        return tool(GET_COMING_UP,
                "Rare events in the next weeks and months: eclipses, meteor showers, supermoons, "
                        + "equinoxes and big tides.",
                props, List.of());
    }

    private static Tool submitAnswer() {
        Map<String, Object> pick = object(m(
                "locationId", m("type", "integer",
                        "description", "The locationId rank_spots returned."),
                "windowId", m("type", "string",
                        "description", "The windowId rank_spots returned for it."),
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
        props.put("picks", m("type", "array", "items", pick, "maxItems", AskAnswerValidator.MAX_PICKS,
                "description", "Two or three different spots, best first, for where/when questions."));
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
