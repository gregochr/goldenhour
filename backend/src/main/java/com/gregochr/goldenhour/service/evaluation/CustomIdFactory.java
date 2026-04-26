package com.gregochr.goldenhour.service.evaluation;

import com.gregochr.goldenhour.entity.AlertLevel;
import com.gregochr.goldenhour.entity.TargetType;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Builds and parses Anthropic Batch API custom IDs used across the four forecast
 * evaluation paths (scheduled, JFDI, force-submit, aurora).
 *
 * <p>Formats:
 * <ul>
 *   <li>Forecast (scheduled): {@code fc-{locationId}-{date}-{targetType}}</li>
 *   <li>JFDI: {@code jfdi-{locationId}-{date}-{targetType}}</li>
 *   <li>Force-submit: {@code force-{sanitisedRegion}-{locationId}-{date}-{targetType}}</li>
 *   <li>Aurora: {@code au-{alertLevel}-{date}}</li>
 * </ul>
 *
 * <p>Parsing dispatches by prefix rather than hyphen count — the previous implementation
 * in {@code BatchResultProcessor} counted parts after {@code split("-")} (fc/jfdi = 6,
 * force = 7), a brittle scheme that would silently misparse any future format with a
 * different hyphen count. Prefix dispatch plus bounded length extraction (date is always
 * 10 chars) removes that fragility.
 *
 * <p>Region sanitisation for force-submit IDs strips every non-ASCII-alphanumeric
 * character — the exact rule previously used at {@code ForceSubmitBatchService.forceSubmit},
 * preserved verbatim because {@link BatchResultProcessor}'s forward-compatible parser
 * relies on the region segment containing zero hyphens.
 */
public final class CustomIdFactory {

    private static final int MAX_LENGTH = 64;
    private static final Pattern ANTHROPIC_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");
    private static final Pattern REGION_STRIP = Pattern.compile("[^a-zA-Z0-9]");

    private static final String PREFIX_FORECAST = "fc-";
    private static final String PREFIX_JFDI = "jfdi-";
    private static final String PREFIX_FORCE = "force-";
    private static final String PREFIX_AURORA = "au-";

    /** Fixed length of the ISO date segment (YYYY-MM-DD). */
    private static final int DATE_LEN = 10;

    private CustomIdFactory() {
    }

    /**
     * Builds a forecast custom ID for the scheduled batch path.
     *
     * @param locationId database ID of the location
     * @param date       forecast date
     * @param targetType SUNRISE, SUNSET, or HOURLY
     * @return an ID of the form {@code "fc-{locationId}-{date}-{targetType}"}
     * @throws IllegalArgumentException if the resulting ID exceeds the Anthropic 64-char
     *                                  limit or contains invalid characters
     */
    public static String forForecast(Long locationId, LocalDate date, TargetType targetType) {
        Objects.requireNonNull(locationId, "locationId");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(targetType, "targetType");
        return validate(PREFIX_FORECAST + locationId + "-" + date + "-" + targetType.name());
    }

    /**
     * Builds a JFDI custom ID.
     *
     * @param locationId database ID of the location
     * @param date       forecast date
     * @param targetType SUNRISE, SUNSET, or HOURLY
     * @return an ID of the form {@code "jfdi-{locationId}-{date}-{targetType}"}
     * @throws IllegalArgumentException if the resulting ID exceeds the Anthropic 64-char
     *                                  limit or contains invalid characters
     */
    public static String forJfdi(Long locationId, LocalDate date, TargetType targetType) {
        Objects.requireNonNull(locationId, "locationId");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(targetType, "targetType");
        return validate(PREFIX_JFDI + locationId + "-" + date + "-" + targetType.name());
    }

    /**
     * Builds a force-submit custom ID.
     *
     * <p>The region name is sanitised by stripping every non-ASCII-alphanumeric character
     * (e.g. {@code "The North York Moors"} → {@code "TheNorthYorkMoors"}). This matches
     * the rule previously inlined in {@code ForceSubmitBatchService.forceSubmit} verbatim
     * and is required for the result-side parser to work: the region segment must contain
     * zero hyphens so the trailing date and target-type segments remain unambiguous.
     *
     * @param regionName region name; will be sanitised
     * @param locationId database ID of the location
     * @param date       forecast date
     * @param targetType SUNRISE, SUNSET, or HOURLY
     * @return an ID of the form {@code "force-{sanitisedRegion}-{locationId}-{date}-{targetType}"}
     * @throws IllegalArgumentException if the resulting ID exceeds the Anthropic 64-char
     *                                  limit or contains invalid characters
     */
    public static String forForceSubmit(String regionName, Long locationId,
            LocalDate date, TargetType targetType) {
        Objects.requireNonNull(regionName, "regionName");
        Objects.requireNonNull(locationId, "locationId");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(targetType, "targetType");
        String sanitised = sanitiseRegionName(regionName);
        if (sanitised.isEmpty()) {
            throw new IllegalArgumentException("Region name has no alphanumeric characters: "
                    + regionName);
        }
        return validate(PREFIX_FORCE + sanitised + "-" + locationId + "-" + date
                + "-" + targetType.name());
    }

    /**
     * Builds an aurora custom ID.
     *
     * @param alertLevel current alert level
     * @param date       forecast date
     * @return an ID of the form {@code "au-{alertLevel}-{date}"}
     * @throws IllegalArgumentException if the resulting ID exceeds the Anthropic 64-char
     *                                  limit or contains invalid characters
     */
    public static String forAurora(AlertLevel alertLevel, LocalDate date) {
        Objects.requireNonNull(alertLevel, "alertLevel");
        Objects.requireNonNull(date, "date");
        return validate(PREFIX_AURORA + alertLevel.name() + "-" + date);
    }

    /**
     * Applies the force-submit region sanitisation rule: strip every non-ASCII-alphanumeric
     * character. Exposed package-private for test reuse and explicit documentation.
     */
    static String sanitiseRegionName(String regionName) {
        return REGION_STRIP.matcher(regionName).replaceAll("");
    }

    /**
     * Parses any custom ID produced by this factory, dispatching by prefix.
     *
     * @param customId the ID to parse
     * @return a structured record matching the ID's format
     * @throws IllegalArgumentException if the ID does not match any known prefix or is
     *                                  otherwise malformed
     */
    public static ParsedCustomId parse(String customId) {
        Objects.requireNonNull(customId, "customId");
        if (customId.startsWith(PREFIX_FORECAST)) {
            return parseForecast(customId);
        }
        if (customId.startsWith(PREFIX_JFDI)) {
            return parseJfdi(customId);
        }
        if (customId.startsWith(PREFIX_FORCE)) {
            return parseForceSubmit(customId);
        }
        if (customId.startsWith(PREFIX_AURORA)) {
            return parseAurora(customId);
        }
        throw new IllegalArgumentException("Unknown custom ID prefix: " + customId);
    }

    private static ParsedCustomId.Forecast parseForecast(String customId) {
        TailParts tail = extractDateAndTarget(customId, PREFIX_FORECAST);
        Long locationId = parseLocationId(tail.before(), customId);
        return new ParsedCustomId.Forecast(locationId, tail.date(), tail.targetType());
    }

    private static ParsedCustomId.Jfdi parseJfdi(String customId) {
        TailParts tail = extractDateAndTarget(customId, PREFIX_JFDI);
        Long locationId = parseLocationId(tail.before(), customId);
        return new ParsedCustomId.Jfdi(locationId, tail.date(), tail.targetType());
    }

    private static ParsedCustomId.ForceSubmit parseForceSubmit(String customId) {
        TailParts tail = extractDateAndTarget(customId, PREFIX_FORCE);
        // Before is "{sanitisedRegion}-{locationId}"; split at last hyphen.
        int locationSep = tail.before().lastIndexOf('-');
        if (locationSep < 1 || locationSep >= tail.before().length() - 1) {
            throw new IllegalArgumentException("Malformed force-submit custom ID: " + customId);
        }
        String sanitisedRegion = tail.before().substring(0, locationSep);
        Long locationId = parseLocationId(
                tail.before().substring(locationSep + 1), customId);
        return new ParsedCustomId.ForceSubmit(sanitisedRegion, locationId,
                tail.date(), tail.targetType());
    }

    private static ParsedCustomId.Aurora parseAurora(String customId) {
        String body = customId.substring(PREFIX_AURORA.length());
        if (body.length() < DATE_LEN + 2) {
            throw new IllegalArgumentException("Malformed aurora custom ID: " + customId);
        }
        String dateStr = body.substring(body.length() - DATE_LEN);
        // Expect a separating hyphen before the date.
        if (body.charAt(body.length() - DATE_LEN - 1) != '-') {
            throw new IllegalArgumentException("Malformed aurora custom ID: " + customId);
        }
        String alertName = body.substring(0, body.length() - DATE_LEN - 1);
        try {
            LocalDate date = LocalDate.parse(dateStr);
            AlertLevel alertLevel = AlertLevel.valueOf(alertName);
            return new ParsedCustomId.Aurora(alertLevel, date);
        } catch (DateTimeParseException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Malformed aurora custom ID: " + customId, e);
        }
    }

    /**
     * Extracts the trailing {@code -{date}-{targetType}} suffix from {@code customId}
     * after stripping {@code prefix}, and returns the remaining "before" body.
     */
    private static TailParts extractDateAndTarget(String customId, String prefix) {
        String body = customId.substring(prefix.length());
        int targetSep = body.lastIndexOf('-');
        if (targetSep < DATE_LEN + 1) {
            throw new IllegalArgumentException("Malformed custom ID: " + customId);
        }
        String targetName = body.substring(targetSep + 1);
        String beforeTarget = body.substring(0, targetSep);
        if (beforeTarget.length() < DATE_LEN + 2) {
            throw new IllegalArgumentException("Malformed custom ID: " + customId);
        }
        String dateStr = beforeTarget.substring(beforeTarget.length() - DATE_LEN);
        if (beforeTarget.charAt(beforeTarget.length() - DATE_LEN - 1) != '-') {
            throw new IllegalArgumentException("Malformed custom ID: " + customId);
        }
        String before = beforeTarget.substring(0, beforeTarget.length() - DATE_LEN - 1);
        try {
            LocalDate date = LocalDate.parse(dateStr);
            TargetType target = TargetType.valueOf(targetName);
            return new TailParts(before, date, target);
        } catch (DateTimeParseException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Malformed custom ID: " + customId, e);
        }
    }

    private static Long parseLocationId(String value, String customId) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Malformed custom ID (non-numeric locationId): "
                    + customId, e);
        }
    }

    private static String validate(String id) {
        if (id.length() > MAX_LENGTH || !ANTHROPIC_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "Custom ID exceeds " + MAX_LENGTH + " chars or contains invalid characters: "
                            + id);
        }
        return id;
    }

    private record TailParts(String before, LocalDate date, TargetType targetType) {
    }
}
