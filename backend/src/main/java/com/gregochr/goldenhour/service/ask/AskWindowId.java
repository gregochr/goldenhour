package com.gregochr.goldenhour.service.ask;

import com.gregochr.goldenhour.entity.TargetType;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;

/**
 * The codec for a window id: {@code yyyy-MM-dd_sunrise} or {@code yyyy-MM-dd_sunset} for a solar
 * window, {@code yyyy-MM-dd_astro} or {@code yyyy-MM-dd_aurora} for a night (ask-night plan §2.1).
 *
 * <p>One format, one home. {@code BriefingRollupBuilder} used to build this string inline in two
 * places and Ask needs the identical encoding, because both sides name a window to Claude and
 * read it back. {@link Locale#ROOT} is deliberate: under a Turkish default locale {@code SUNRISE}
 * lower-cases with a dotless i and the keys stop matching (the reason the rollup builder's own
 * comment warns about it).
 *
 * <p>{@link #format(LocalDate, TargetType)} accepts any target type, as the rollup builder always
 * did; {@link #parse} accepts only the four {@link AskWindowKind}s, because a window is a solar event
 * or a night and nothing else. The date of a night is the date whose dusk opens it (the poller's night,
 * {@code AuroraForecastRunService.currentNightDate()}), never the civil date of its small hours.
 */
public final class AskWindowId {

    private static final char SEPARATOR = '_';

    private AskWindowId() {
    }

    /**
     * The parts of a window id.
     *
     * @param date the date of the solar event, or of the dusk that opens the night
     * @param kind the window's kind
     */
    public record Parts(LocalDate date, AskWindowKind kind) {
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
     * Builds the id of a window from its kind: the one codec for a solar window and a night alike.
     *
     * @param date the date of the event, or of the dusk that opens the night
     * @param kind the window's kind
     * @return the id, e.g. {@code 2026-10-11_astro}
     */
    public static String format(LocalDate date, AskWindowKind kind) {
        return date + String.valueOf(SEPARATOR) + kind.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Reads a window id back into its parts.
     *
     * <p>Strict: the id must be lower case, the date must be a real ISO date and the kind must be
     * {@code sunrise}, {@code sunset}, {@code astro} or {@code aurora}. Anything else is empty rather
     * than an exception, so a model's malformed argument becomes a tool error result and never a crash.
     *
     * @param id the id; may be null
     * @return its parts, or empty when it is not a well-formed window id
     */
    public static Optional<Parts> parse(String id) {
        if (id == null) {
            return Optional.empty();
        }
        int split = id.indexOf(SEPARATOR);
        if (split <= 0 || split == id.length() - 1) {
            return Optional.empty();
        }
        AskWindowKind kind = switch (id.substring(split + 1)) {
            case "sunrise" -> AskWindowKind.SUNRISE;
            case "sunset" -> AskWindowKind.SUNSET;
            case "astro" -> AskWindowKind.ASTRO;
            case "aurora" -> AskWindowKind.AURORA;
            default -> null;
        };
        if (kind == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Parts(LocalDate.parse(id.substring(0, split)), kind));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
