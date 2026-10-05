package com.gregochr.goldenhour.service.ask;

import com.fasterxml.jackson.databind.JsonNode;
import com.gregochr.goldenhour.service.ask.AskAnswerValidator.Raw;
import com.gregochr.goldenhour.service.ask.AskAnswerValidator.RawEvent;
import com.gregochr.goldenhour.service.ask.AskAnswerValidator.RawPick;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads the input of the model's {@code submit_answer} call into the validator's
 * {@link Raw} answer.
 *
 * <p>Everything the model returns is untrusted, so this is strict about <em>shape</em> and says
 * nothing about <em>truth</em> (the validator decides that). A non-object input, a missing
 * {@code answerable} or {@code summary}, a value of the wrong type, a pick without a whole-number
 * {@code locationId} or a {@code windowId}, an event without a {@code type}, or a date that is not
 * {@code yyyy-MM-dd} is a {@link Parsed#error() error}, which the engine turns into a FAILED
 * outcome. Nothing here throws. Unknown extra fields are ignored: nothing reads them, so they
 * cannot carry anything anywhere (in particular the model has no field through which to write a
 * safety note or a "try" suggestion).
 */
public final class AskAnswerParser {

    /** Most picks and events read from one reply; anything past it is never looked at. */
    static final int MAX_ITEMS = 10;

    private AskAnswerParser() {
    }

    /**
     * The result of reading a {@code submit_answer} input.
     *
     * @param raw   the answer, or null when the input is malformed
     * @param error why it is malformed, or null when it was read
     */
    public record Parsed(Raw raw, String error) {

        /**
         * Whether the input was read.
         *
         * @return true when {@code raw} is present
         */
        public boolean ok() {
            return raw != null;
        }
    }

    /**
     * Reads a {@code submit_answer} input.
     *
     * @param input the tool input; may be null or any JSON shape
     * @return the answer or the reason it is malformed; never throws
     */
    public static Parsed parse(JsonNode input) {
        try {
            return new Parsed(read(input), null);
        } catch (Malformed e) {
            return new Parsed(null, e.getMessage());
        }
    }

    private static Raw read(JsonNode input) throws Malformed {
        if (input == null || !input.isObject()) {
            throw new Malformed("the input is not a JSON object");
        }
        boolean answerable = requiredBoolean(input, "answerable");
        String summary = requiredText(input, "summary");
        String missing = optionalText(input, "missing");
        List<RawPick> picks = new ArrayList<>();
        for (JsonNode item : optionalArray(input, "picks")) {
            if (picks.size() >= MAX_ITEMS) {
                break;
            }
            picks.add(readPick(item));
        }
        List<RawEvent> events = new ArrayList<>();
        for (JsonNode item : optionalArray(input, "events")) {
            if (events.size() >= MAX_ITEMS) {
                break;
            }
            events.add(readEvent(item));
        }
        return new Raw(answerable, summary, picks, events, missing);
    }

    private static RawPick readPick(JsonNode item) throws Malformed {
        if (!item.isObject()) {
            throw new Malformed("a pick is not an object");
        }
        JsonNode id = item.get("locationId");
        if (id == null || !id.isIntegralNumber() || !id.canConvertToLong()) {
            throw new Malformed("a pick's locationId is not a whole number");
        }
        return new RawPick(id.longValue(), requiredText(item, "windowId"), optionalText(item, "why"));
    }

    private static RawEvent readEvent(JsonNode item) throws Malformed {
        if (!item.isObject()) {
            throw new Malformed("an event is not an object");
        }
        return new RawEvent(requiredText(item, "type"), optionalDate(item, "date"),
                optionalText(item, "why"));
    }

    private static boolean requiredBoolean(JsonNode node, String field) throws Malformed {
        JsonNode value = node.get(field);
        if (value == null || !value.isBoolean()) {
            throw new Malformed("'" + field + "' is missing or not true/false");
        }
        return value.booleanValue();
    }

    private static String requiredText(JsonNode node, String field) throws Malformed {
        return optionalValue(node, field).orElseThrow(
                () -> new Malformed("'" + field + "' is missing"));
    }

    private static String optionalText(JsonNode node, String field) throws Malformed {
        return optionalValue(node, field).orElse(null);
    }

    /** A text field: absent or JSON null reads as empty, anything but a string is malformed. */
    private static Optional<String> optionalValue(JsonNode node, String field) throws Malformed {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (!value.isTextual()) {
            throw new Malformed("'" + field + "' is not text");
        }
        return Optional.of(value.textValue());
    }

    private static Iterable<JsonNode> optionalArray(JsonNode node, String field) throws Malformed {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (!value.isArray()) {
            throw new Malformed("'" + field + "' is not a list");
        }
        return value;
    }

    private static LocalDate optionalDate(JsonNode node, String field) throws Malformed {
        String text = optionalText(node, field);
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(text.strip());
        } catch (DateTimeParseException e) {
            throw new Malformed("'" + field + "' is not a yyyy-MM-dd date");
        }
    }

    /** A shape error, caught in {@link #parse}; never escapes this class. */
    private static final class Malformed extends Exception {

        private static final long serialVersionUID = 1L;

        Malformed(String message) {
            super(message, null, false, false);
        }
    }
}
