package com.gregochr.goldenhour.service.ask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.gregochr.goldenhour.service.ask.AskTools.ComingUpArgs;
import com.gregochr.goldenhour.service.ask.AskTools.HotTopicsArgs;
import com.gregochr.goldenhour.service.ask.AskTools.RankSpotsArgs;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the model's tool-use input into the argument records of {@link AskTools}.
 *
 * <p>The input is untrusted. A field of the wrong type is a {@link BadArguments} carrying a
 * sentence the engine feeds back to the model as an error {@code tool_result}, so it can correct
 * itself; nothing here lets an exception escape the engine. A JSON {@code null} reads as absent,
 * unknown fields are ignored (the tool simply does not have them) and a missing or null input reads
 * as an empty object, because every argument of every tool is optional.
 */
final class AskToolArguments {

    private AskToolArguments() {
    }

    /** The model's arguments could not be read. */
    static final class BadArguments extends Exception {

        private static final long serialVersionUID = 1L;

        BadArguments(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * Reads {@code rank_spots} arguments.
     *
     * @param input the tool input
     * @return the arguments
     * @throws BadArguments when a field has the wrong type
     */
    static RankSpotsArgs rankSpots(JsonNode input) throws BadArguments {
        JsonNode in = object(input);
        return new RankSpotsArgs(strings(in, "windowIds"), strings(in, "regionNames"),
                bool(in, "coastalOnly"), text(in, "tideState"), whole(in, "maxDriveMinutes"),
                whole(in, "limit"));
    }

    /**
     * Reads {@code get_hot_topics} arguments.
     *
     * @param input the tool input
     * @return the arguments
     * @throws BadArguments when a field has the wrong type
     */
    static HotTopicsArgs hotTopics(JsonNode input) throws BadArguments {
        JsonNode in = object(input);
        return new HotTopicsArgs(strings(in, "types"), whole(in, "limit"));
    }

    /**
     * Reads {@code get_coming_up} arguments.
     *
     * @param input the tool input
     * @return the arguments
     * @throws BadArguments when a field has the wrong type
     */
    static ComingUpArgs comingUp(JsonNode input) throws BadArguments {
        JsonNode in = object(input);
        return new ComingUpArgs(whole(in, "days"), whole(in, "limit"));
    }

    private static JsonNode object(JsonNode input) throws BadArguments {
        if (input == null || input.isNull() || input.isMissingNode()) {
            return JsonNodeFactory.instance.objectNode();
        }
        if (!input.isObject()) {
            throw new BadArguments("The arguments must be a JSON object.");
        }
        return input;
    }

    private static boolean absent(JsonNode value) {
        return value == null || value.isNull();
    }

    private static Boolean bool(JsonNode in, String field) throws BadArguments {
        JsonNode value = in.get(field);
        if (absent(value)) {
            return null;
        }
        if (!value.isBoolean()) {
            throw new BadArguments("'" + field + "' must be true or false.");
        }
        return value.booleanValue();
    }

    private static String text(JsonNode in, String field) throws BadArguments {
        JsonNode value = in.get(field);
        if (absent(value)) {
            return null;
        }
        if (!value.isTextual()) {
            throw new BadArguments("'" + field + "' must be text.");
        }
        return value.textValue();
    }

    private static Integer whole(JsonNode in, String field) throws BadArguments {
        JsonNode value = in.get(field);
        if (absent(value)) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new BadArguments("'" + field + "' must be a whole number.");
        }
        return value.intValue();
    }

    private static List<String> strings(JsonNode in, String field) throws BadArguments {
        JsonNode value = in.get(field);
        if (absent(value)) {
            return null;
        }
        if (!value.isArray()) {
            throw new BadArguments("'" + field + "' must be a list of text.");
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual()) {
                throw new BadArguments("'" + field + "' must be a list of text.");
            }
            out.add(item.textValue());
        }
        return out;
    }
}
