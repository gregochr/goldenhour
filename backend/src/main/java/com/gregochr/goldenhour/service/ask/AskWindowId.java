package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.TargetType;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;

/**
 * The codec for a window id: {@code yyyy-MM-dd_sunrise} or {@code yyyy-MM-dd_sunset}.
 *
 * <p>One format, one home. {@code BriefingRollupBuilder} used to build this string inline in two
 * places and Ask needs the identical encoding, because both sides name a window to Claude and
 * read it back. {@link Locale#ROOT} is deliberate: under a Turkish default locale {@code SUNRISE}
 * lower-cases with a dotless i and the keys stop matching (the reason the rollup builder's own
 * comment warns about it).
 *
 * <p>{@link #format} accepts any target type, as the rollup builder always did; {@link #parse}
 * accepts only the two solar events, because a window is a solar event and nothing else.
 */
public final class AskWindowId {

    private static final char SEPARATOR = '_';

    private AskWindowId() {
    }

    /**
     * The parts of a window id.
     *
     * @param date       the date of the solar event
     * @param targetType SUNRISE or SUNSET
     */
    public record Parts(LocalDate date, TargetType targetType) {
    }

    /**
     * Builds the id of a window.
     *
     * @param date       the date of the event
     * @param targetType the event type
     * @return the id, e.g. {@code 2026-10-05_sunset}
     */
    public static String format(LocalDate date, TargetType targetType) {
        return date + String.valueOf(SEPARATOR) + targetType.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Reads a window id back into its parts.
     *
     * <p>Strict: the id must be lower case, the date must be a real ISO date and the event must be
     * {@code sunrise} or {@code sunset}. Anything else is empty rather than an exception, so a
     * model's malformed argument becomes a tool error result and never a crash.
     *
     * @param id the id; may be null
     * @return its parts, or empty when it is not a well-formed solar window id
     */
    public static Optional<Parts> parse(String id) {
        if (id == null) {
            return Optional.empty();
        }
        int split = id.indexOf(SEPARATOR);
        if (split <= 0 || split == id.length() - 1) {
            return Optional.empty();
        }
        TargetType type = switch (id.substring(split + 1)) {
            case "sunrise" -> TargetType.SUNRISE;
            case "sunset" -> TargetType.SUNSET;
            default -> null;
        };
        if (type == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Parts(LocalDate.parse(id.substring(0, split)), type));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
